package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.GpuDevice
import app.zoeshorsefarm.render.filament.backend.mapping.MaterialMapping
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.DirectionalLight
import app.zoeshorsefarm.scene.graph.HemisphereLight
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Skeleton
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.graph.Sprite
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.scene.texture.Texture

/** What a frame of [SceneSync] counted. */
class FrameCounts(
    val drawCalls: Int,
    val triangles: Int,
    val shadowCalls: Int,
    val shadowTriangles: Int,
)

/** Numbers of the device objects the synchronisation holds, for `RenderInfo` and the debug box. */
class SyncStats(
    val programs: Int,
    val textures: Int,
    val geometries: Int,
    val materialInstances: Int,
    val uploadOverflows: Int,
)

/**
 * Keeps the Filament objects in step with the scene graph. Each frame [sync] walks the node graph
 * (indexed loops and no lambdas: nothing is allocated when nothing changed), makes an entry for every
 * mesh, points object and sprite it meets, lets the entries update their entities, and removes the
 * entries of nodes that left the graph. Lights are collected on the way for the stage.
 *
 * Disposing a scene object (geometry, material, texture, instanced mesh) frees its device copy at once
 * and the next use uploads it again, as `GpuObject` promises; the registries and the entries that
 * hold the object drop their renderables first, so Filament never sees a destroyed buffer in use.
 *
 * All calls come from the render thread.
 */
class SceneSync(
    device: GpuDevice,
    log: SyncLog = SyncLog.SILENT,
) {
    private val disposeListener = DisposeListener { handleDispose(it) }
    private val preparedSpecs = HashSet<MaterialSpec>()
    private val skeletonFrames = HashMap<Skeleton, IntArray>()
    private val textures = TextureRegistry(device, disposeListener)
    private val geometries = GeometryRegistry(device, disposeListener, log)
    private val materials = MaterialRegistry(device, textures, disposeListener, log) { preparedSpecs.remove(it) }
    private val programs = ProgramBook()
    private val ctx = SyncContext(device, geometries, textures, materials, programs, disposeListener, log)
    private val entries = HashMap<Node, DrawEntry>()
    private val entryList = ArrayList<DrawEntry>()

    /** The lights of the last frame. */
    val lights = SceneLights()

    /** The wind the last frame found on a material (the shaders read one wind), or null. */
    val wind: Wind? get() = ctx.wind

    private lateinit var backend: RenderBackend
    private lateinit var scene: Scene
    private lateinit var camera: Camera

    /** Draw calls of the last [sync]. */
    val drawCalls: Int get() = ctx.drawCalls

    /** Triangles of the last [sync]. */
    val triangles: Int get() = ctx.triangles

    /** Draw calls the objects that cast shadows add in the shadow pass. */
    val shadowCalls: Int get() = ctx.shadowCalls

    val shadowTriangles: Int get() = ctx.shadowTriangles

    /** Shader programs by the rules of the scene model (see [ProgramBook]). */
    val programCount: Int get() = programs.count

    /** Textures on the GPU. */
    val textureCount: Int get() = textures.count

    /** Geometries on the GPU: meshes, and the particle batches of points objects. */
    val geometryCount: Int get() = geometries.count + pointGeometryCount()

    /** Counts of the last [sync]. */
    fun counts(): FrameCounts = FrameCounts(ctx.drawCalls, ctx.triangles, ctx.shadowCalls, ctx.shadowTriangles)

    /** The numbers of the device objects (allocates; for the debug box and tests). */
    fun stats(): SyncStats =
        SyncStats(
            programs.count,
            textures.count,
            geometryCount,
            materials.bindingCount,
            uploadOverflows(),
        )

    /** The shader programs of the books, by key (for messages and tests). */
    fun programKeys(): List<String> = programs.keys()

    /**
     * Walks `scene` and updates the device objects. The matrices of the scene and the camera must be
     * up to date, skeletons are updated here. `shadowsEnabled` is part of the program keys.
     */
    fun sync(
        owner: RenderBackend,
        scene: Scene,
        camera: Camera,
        shadowsEnabled: Boolean,
    ) {
        backend = owner
        this.scene = scene
        this.camera = camera
        ctx.beginFrame()
        ctx.fog = scene.fog != null
        ctx.environment = scene.environment != null
        ctx.shadows = shadowsEnabled
        camera.matrixWorld.toFloatArray(ctx.cameraWorld)
        lights.clear()
        walk(scene, true)
        sweep()
    }

    /**
     * Builds what `root` yields before the first frame: geometries, material instances (which compiles
     * the shaders), textures, and the program keys. Nothing is drawn.
     */
    fun compile(
        root: Traversable,
        scene: Scene,
        shadowsEnabled: Boolean,
    ) {
        ctx.fog = scene.fog != null
        ctx.environment = scene.environment != null
        ctx.shadows = shadowsEnabled
        root.traverse { node -> prepare(node) }
        // Filament starts the shader compiles it was asked for only when the command queue is flushed
        ctx.device.flush()
    }

    /** Destroys every entity and every device object (the engine is shut down or rebuilt). */
    fun clear() {
        for (entry in entryList) entry.destroy(ctx)
        entryList.clear()
        entries.clear()
        materials.clear()
        textures.clear()
        geometries.clear()
        ctx.releaseQuad()
        programs.clear()
        preparedSpecs.clear()
        skeletonFrames.clear()
        lights.clear()
    }

    // ---- walk ----------------------------------------------------------------------------------

    private fun walk(
        node: Node,
        parentVisible: Boolean,
    ) {
        val visible = parentVisible && node.visible
        if (visible) beforeRender(node)
        when (node) {
            is Mesh -> update(node, visible) { MeshEntry(node) }
            is Points -> update(node, visible) { PointsEntry(node) }
            is Sprite -> update(node, visible) { SpriteEntry(node) }
            is DirectionalLight -> if (visible) lights.add(node)
            is HemisphereLight -> if (visible) lights.add(node)
            else -> Unit
        }
        val children = node.children
        for (i in children.indices) walk(children[i], visible)
    }

    /** Body and tack of the horse share a skeleton: it is updated once per frame. */
    private fun updateSkeleton(node: SkinnedMesh) {
        val skeleton = node.skeleton ?: return
        var frame = skeletonFrames[skeleton]
        if (frame == null) {
            // the skeletons of removed meshes are forgotten now and then; the next frame updates the rest again
            if (skeletonFrames.size > MAX_TRACKED_SKELETONS) skeletonFrames.clear()
            frame = IntArray(1) { -1 }
            skeletonFrames[skeleton] = frame
        }
        // a holder instead of a boxed number: nothing is allocated per frame
        if (frame[0] == ctx.frame) return
        frame[0] = ctx.frame
        skeleton.update()
    }

    private fun beforeRender(node: Node) {
        if (node is SkinnedMesh) updateSkeleton(node)
        val hook = node.onBeforeRender ?: return
        hook(backend, scene, camera)
    }

    private inline fun update(
        node: Node,
        visible: Boolean,
        create: () -> DrawEntry,
    ) {
        var entry = entries[node]
        if (entry == null) {
            entry = create()
            entries[node] = entry
            entryList += entry
        }
        entry.seenFrame = ctx.frame
        entry.update(ctx, visible)
    }

    private fun sweep() {
        var i = entryList.size - 1
        while (i >= 0) {
            val entry = entryList[i]
            if (entry.seenFrame != ctx.frame) {
                entry.destroy(ctx)
                entries.remove(entry.node)
                val last = entryList.removeAt(entryList.size - 1)
                if (last !== entry) entryList[i] = last
            }
            i--
        }
    }

    // ---- dispose -------------------------------------------------------------------------------

    private fun handleDispose(resource: GpuObject) {
        if (resource is Geometry) {
            geometries.free(resource)
        } else if (resource is Material) {
            materials.free(resource)
            programs.release(resource)
        } else if (resource is Texture) {
            textures.free(resource)
        }
        for (i in entryList.indices) entryList[i].onDisposed(ctx, resource)
    }

    private fun uploadOverflows(): Int {
        var total = geometries.uploadOverflows
        for (i in entryList.indices) total += entryList[i].uploadOverflows
        return total
    }

    private fun pointGeometryCount(): Int {
        var count = 0
        for (i in entryList.indices) if (entryList[i] is PointsEntry) count++
        return count
    }

    // ---- compile -------------------------------------------------------------------------------

    private fun prepare(node: Node) {
        when (node) {
            is Mesh -> prepareMesh(node)
            is Points -> prepareBinding(node, node.material, book = true)
            is Sprite -> prepareBinding(node, node.material, book = false)
            else -> Unit
        }
    }

    private fun prepareMesh(mesh: Mesh) {
        val geometry = mesh.geometry
        var lit = false
        var uvTangents = false
        mesh.forEachMaterial { material ->
            val standard = material as? StandardMaterial
            if (standard != null || material is LambertMaterial) lit = true
            if (standard?.normalMap != null) uvTangents = true
        }
        ctx.geometries.acquire(geometry, uvTangents, lit)
        mesh.forEachMaterial { material -> prepareBinding(mesh, material, book = true) }
    }

    private fun prepareBinding(
        node: Node,
        material: Material,
        book: Boolean,
    ) {
        val spec = MaterialMapping.specFor(node, material, (node as? Mesh)?.geometry) ?: return
        val binding = ctx.materials.bind(material, spec, if (spec.usesInstanceData) node else null) ?: return
        if (preparedSpecs.add(spec)) ctx.device.prepareMaterial(spec)
        binding.sync(ctx.frame)
        if (book) ctx.bookProgram(node, material)
    }

    private companion object {
        const val MAX_TRACKED_SKELETONS = 32
    }
}
