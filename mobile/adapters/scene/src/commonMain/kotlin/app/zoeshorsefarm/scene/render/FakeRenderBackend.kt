package app.zoeshorsefarm.scene.render

import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.texture.Texture

/**
 * A [RenderBackend] without a GPU for tests. `render` does what three.js does before drawing (updates
 * matrices, skeletons and `onBeforeRender` hooks), counts draw calls and triangles like [SceneStats]
 * and keeps the books of programs, geometries and textures like [GpuTracker], so `info` is
 * meaningful. Context loss can be simulated.
 */
class FakeRenderBackend(
    override val capabilities: RenderCapabilities = RenderCapabilities(),
    override var gpuDescription: String = "fake gpu",
) : RenderBackend {
    override val info: RenderInfo = RenderInfo()
    override var pixelRatio: Double = 1.0
        private set
    override var toneMapping: ToneMapping = ToneMapping.NONE
    override var toneMappingExposure: Double = 1.0
    override var shadowsEnabled: Boolean = false
    override var shadowType: ShadowType = ShadowType.BASIC
    override var shadowAutoUpdate: Boolean = true

    private var width = 0
    private var height = 0
    private val listeners = ArrayList<ContextListener>()
    private val textures = LinkedHashSet<Texture>()
    private val textureListeners = HashMap<Texture, DisposeListener>()
    private var tracker: GpuTracker? = null
    private var trackedScene: Scene? = null

    /** Number of frames drawn (not counting frames skipped while the context is lost). */
    var renderCount: Int = 0
        private set

    /** Number of [compile] calls. */
    var compileCount: Int = 0
        private set

    /** Statistics of the last frame, including the shadow pass. */
    var lastStats: SceneStats = SceneStats(0, 0, 0, PassStats(0, 0))
        private set

    /** True between [simulateContextLoss] and [simulateContextRestore]; nothing is drawn then. */
    var contextLost: Boolean = false
        private set

    /** The books of the fake GPU for the scene that was rendered last (null before the first frame). */
    val gpu: GpuTracker? get() = tracker

    override fun setPixelRatio(ratio: Double) {
        pixelRatio = ratio
    }

    override fun setSize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
    }

    override fun getSize(target: Vec2): Vec2 = target.set(width.toDouble(), height.toDouble())

    override fun getDrawingBufferSize(target: Vec2): Vec2 =
        target.set(kotlin.math.floor(width * pixelRatio), kotlin.math.floor(height * pixelRatio))

    override fun render(
        scene: Scene,
        camera: Camera,
    ) {
        if (contextLost) return
        scene.updateMatrixWorld()
        if (camera.parent == null) camera.updateMatrixWorld()
        val visible =
            object : Traversable {
                override fun traverse(callback: (Node) -> Unit) = scene.traverseVisible(callback)

                override fun traverseVisible(callback: (Node) -> Unit) = scene.traverseVisible(callback)
            }
        visible.traverse { node ->
            if (node is SkinnedMesh) node.skeleton?.update()
            node.onBeforeRender?.invoke(this, scene, camera)
        }
        upload(scene, visible)
        val stats = SceneStats.of(scene)
        lastStats = stats
        info.drawCalls = stats.calls + if (shadowsEnabled) stats.shadowPass.calls else 0
        info.triangles = stats.triangles + if (shadowsEnabled) stats.shadowPass.triangles else 0
        renderCount++
    }

    override fun compile(
        root: Traversable,
        camera: Camera,
        scene: Scene,
        onComplete: () -> Unit,
    ) {
        compileCount++
        if (!contextLost) upload(scene, root)
        onComplete()
    }

    private fun upload(
        scene: Scene,
        root: Traversable,
    ) {
        var current = tracker
        if (current == null || trackedScene !== scene) {
            current = GpuTracker(scene) { shadowsEnabled }
            tracker = current
            trackedScene = scene
        }
        current.compile(root)
        root.traverse { node ->
            if (node is Mesh) {
                node.forEachMaterial { m -> m.textures().forEach(::trackTexture) }
            } else if (node is Points) {
                node.material.textures().forEach(::trackTexture)
            }
        }
        val counts = current.snapshot()
        info.programs = counts.programs
        info.geometries = counts.geometries
        info.textures = textures.size
    }

    private fun trackTexture(texture: Texture) {
        if (!textures.add(texture)) return
        val listener =
            DisposeListener {
                textures.remove(texture)
                textureListeners.remove(texture)?.let { texture.removeDisposeListener(it) }
                info.textures = textures.size
            }
        textureListeners[texture] = listener
        texture.addDisposeListener(listener)
    }

    /** The device is lost: drawing stops and everything on the GPU is gone. */
    fun simulateContextLoss() {
        contextLost = true
        gpuDescription = ""
        for (listener in listeners.toList()) listener.onContextLost()
    }

    /** The device is back: it holds nothing yet, objects are uploaded again by the next frame. */
    fun simulateContextRestore() {
        contextLost = false
        gpuDescription = "fake gpu"
        tracker = null
        trackedScene = null
        textures.clear()
        info.programs = 0
        info.geometries = 0
        info.textures = 0
        for (listener in listeners.toList()) listener.onContextRestored()
    }

    override fun addContextListener(listener: ContextListener) {
        listeners.add(listener)
    }

    override fun removeContextListener(listener: ContextListener) {
        listeners.remove(listener)
    }

    override fun dispose() {
        tracker = null
        trackedScene = null
        textures.clear()
        listeners.clear()
    }
}
