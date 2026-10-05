package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.context.FilamentContext
import io.github.erkko68.filament.Box
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Entity
import io.github.erkko68.filament.MaterialInstance
import io.github.erkko68.filament.RenderableManager
import io.github.erkko68.filament.Scene

/** What a renderable is drawn like; the defaults are those of a three.js mesh. */
class RenderableOptions(
    val castShadows: Boolean = false,
    val receiveShadows: Boolean = false,
    /** Frustum culling (`frustumCulled`); off for the sky and for instanced meshes with a loose box. */
    val culling: Boolean = true,
    /** Draw order among renderables of the same kind: 0 first, 7 last (the sky uses 0). */
    val priority: Int = DEFAULT_PRIORITY,
    /** Whether the global fog applies; the sky and the clouds have it off. */
    val fog: Boolean = true,
    /** The box used for culling; null takes the bounds of the mesh. */
    val bounds: Aabb? = null,
    /** Draw instances (see [InstanceTexture]); 0 draws the mesh once. */
    val instanceCount: Int = 0,
    /** Bones of a skinned mesh (see [SkinPalette]); 0 for none. */
    val boneCount: Int = 0,
) {
    init {
        require(priority in 0..MAX_PRIORITY) { "priority is 0 to $MAX_PRIORITY" }
        require(instanceCount >= 0 && boneCount >= 0) { "counts cannot be negative" }
        require(boneCount <= SkinPalette.MAX_BONES) { "at most ${SkinPalette.MAX_BONES} bones" }
        require(instanceCount == 0 || boneCount == 0) { "a skinned mesh cannot be instanced" }
    }

    companion object {
        const val DEFAULT_PRIORITY = 4
        const val MAX_PRIORITY = 7
    }
}

/**
 * One drawable: a Filament entity with a transform and a renderable component, made from a [GpuMesh]
 * and a material instance. It joins the scene when created; [visible] takes it out of the scene and
 * puts it back (three.js `visible`), without freeing anything.
 *
 * Instanced renderables must keep an identity transform: their instance matrices are world matrices.
 */
class FilamentRenderable private constructor(
    private val engine: Engine,
    private val scene: Scene,
    val entity: Entity,
    val mesh: GpuMesh,
    private val boneCount: Int,
) {
    private var inScene = true
    private var destroyed = false
    private val box = Box()

    var visible: Boolean
        get() = inScene
        set(value) {
            if (value == inScene || destroyed) return
            inScene = value
            if (value) scene.addEntity(entity) else scene.remove(entity)
        }

    /**
     * The world transform, column-major (three.js `matrixWorld.elements`; 16 floats). Filament has
     * no parent links here, so the caller passes the world matrix of the node.
     */
    fun setTransform(matrix: FloatArray) {
        require(matrix.size >= MATRIX_SIZE) { "a transform is a 4x4 matrix" }
        val transforms = engine.transformManager
        transforms.setTransform(transforms.getInstance(entity), matrix)
    }

    /** The bone matrices of a skinned mesh, 16 floats per bone (see [SkinPalette.compute]). */
    fun setBones(palette: FloatArray) {
        check(boneCount > 0) { "this renderable has no skeleton" }
        require(palette.size >= boneCount * MATRIX_SIZE) { "the palette must hold $boneCount matrices" }
        val renderables = engine.renderableManager
        renderables.setBones(renderables.getInstance(entity), palette, boneCount)
    }

    fun setMaterialInstance(material: MaterialInstance) {
        val renderables = engine.renderableManager
        renderables.setMaterialInstanceAt(renderables.getInstance(entity), 0, material)
    }

    fun setBounds(bounds: Aabb) {
        applyBounds(bounds)
        val renderables = engine.renderableManager
        renderables.setAxisAlignedBoundingBox(renderables.getInstance(entity), box)
    }

    fun setCastShadows(enabled: Boolean) {
        val renderables = engine.renderableManager
        renderables.setCastShadows(renderables.getInstance(entity), enabled)
    }

    fun setReceiveShadows(enabled: Boolean) {
        val renderables = engine.renderableManager
        renderables.setReceiveShadows(renderables.getInstance(entity), enabled)
    }

    private fun applyBounds(bounds: Aabb) {
        box.center = bounds.center(FloatArray(3))
        box.halfExtent = bounds.halfExtent(FloatArray(3))
    }

    /** Takes the entity out of the scene and frees it. The mesh and material are not freed. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        if (inScene) scene.remove(entity)
        engine.destroy(entity)
        engine.transformManager.destroy(entity)
        engine.entityManager.destroy(entity)
    }

    companion object {
        /** Builds the renderable and adds it to the scene of the context. */
        fun create(
            context: FilamentContext,
            mesh: GpuMesh,
            material: MaterialInstance,
            options: RenderableOptions = RenderableOptions(),
        ): FilamentRenderable {
            val engine = context.engine
            val entity = engine.entityManager.create()
            val renderable = FilamentRenderable(engine, context.scene, entity, mesh, options.boneCount)
            renderable.applyBounds(options.bounds ?: mesh.bounds)
            val builder =
                RenderableManager
                    .Builder(1)
                    .boundingBox(renderable.box)
                    .material(0, material)
                    .castShadows(options.castShadows)
                    .receiveShadows(options.receiveShadows)
                    .culling(options.culling)
                    .priority(options.priority)
                    .fog(options.fog)
            val indices = mesh.indexBuffer
            if (indices != null) {
                builder.geometry(
                    0,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    mesh.vertexBuffer,
                    indices,
                    0,
                    mesh.indexCount,
                )
            } else {
                builder.geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, 0, mesh.vertexCount)
            }
            if (options.boneCount > 0) builder.skinning(options.boneCount)
            if (options.instanceCount > 0) builder.instances(options.instanceCount)
            val result = builder.build(engine, entity)
            check(
                result == RenderableManager.Builder.Result.Success,
            ) { "Filament refused the renderable of ${mesh.label}" }
            engine.transformManager.create(entity)
            context.scene.addEntity(entity)
            return renderable
        }

        private const val MATRIX_SIZE = 16
    }
}
