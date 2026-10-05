package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.GpuDevice
import app.zoeshorsefarm.render.filament.backend.device.MeshHandle
import app.zoeshorsefarm.render.filament.backend.mapping.PrimitiveRanges
import app.zoeshorsefarm.render.filament.backend.mapping.SpriteBillboard
import app.zoeshorsefarm.render.filament.mesh.PackOptions
import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.render.ProgramState
import app.zoeshorsefarm.scene.render.programKey

/**
 * What the draw entries share during a frame: the registries, the frame number, the state of the
 * scene that decides the shader programs, the camera, and the counters of the frame.
 */
internal class SyncContext(
    val device: GpuDevice,
    val geometries: GeometryRegistry,
    val textures: TextureRegistry,
    val materials: MaterialRegistry,
    val programs: ProgramBook,
    val disposeListener: DisposeListener,
    val log: SyncLog,
) {
    var frame = 0

    /** Scene state that is part of the program key (see `ProgramState`). */
    var fog = false
    var environment = false
    var shadows = false

    /** The camera's world matrix as floats (sprites face it). */
    val cameraWorld =
        FloatArray(MATRIX_SIZE).also {
            it[0] = 1f
            it[5] = 1f
            it[10] = 1f
            it[15] = 1f
        }

    /** The first wind found in the frame; the shaders read one wind. */
    var wind: Wind? = null
        private set

    var drawCalls = 0
        private set
    var triangles = 0
        private set
    var shadowCalls = 0
        private set
    var shadowTriangles = 0
        private set

    private var quad: MeshHandle? = null

    /** The unit quad all sprites are drawn with. */
    val spriteQuad: MeshHandle
        get() = quad ?: device.createMesh("sprite quad", SpriteBillboard.quad(), PackOptions()).also { quad = it }

    fun beginFrame() {
        frame++
        wind = null
        drawCalls = 0
        triangles = 0
        shadowCalls = 0
        shadowTriangles = 0
    }

    fun noteWind(candidate: Wind?) {
        if (wind == null) wind = candidate
    }

    /** Counts a draw of `ranges` `copies` times (instances), and again in the shadow pass if it casts shadows. */
    fun countDraw(
        ranges: List<PrimitiveRange>,
        copies: Int,
        castsShadow: Boolean,
    ) {
        val tris = PrimitiveRanges.triangles(ranges) * copies
        drawCalls += ranges.size
        triangles += tris
        if (castsShadow) {
            shadowCalls += ranges.size
            shadowTriangles += tris
        }
    }

    /** One camera facing quad (a sprite): a draw call and two triangles, like `SceneStats`. */
    fun countQuad() {
        drawCalls++
        triangles += 2
    }

    /** One draw call without triangles (the particle points, like `SceneStats`). */
    fun countPoints() {
        drawCalls++
    }

    /** Records the programs `material` is drawn with by `node` under the scene state of this frame. */
    fun bookProgram(
        node: Node,
        material: Material,
    ) {
        programs.add(material, programKey(node, material, ProgramState(fog, environment, shadows)))
    }

    /** The state bits of the scene that change program keys, to compare cheaply between frames. */
    fun programStateBits(receiveShadow: Boolean): Int =
        (if (fog) 1 else 0) or (if (environment) 2 else 0) or (if (shadows) 4 else 0) or (if (receiveShadow) 8 else 0)

    fun releaseQuad() {
        quad?.destroy()
        quad = null
    }

    private companion object {
        const val MATRIX_SIZE = 16
    }
}
