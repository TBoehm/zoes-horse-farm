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
    /** Order among transparent primitives (see `RenderOrder`); 0 keeps Filament's sorting by distance. */
    val blendOrder: Int = 0,
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
 * and one material instance per primitive (a primitive is a range of the mesh drawn with one
 * material, see [PrimitiveRange]). It joins the scene when created; [visible] takes it out of the
 * scene and puts it back (three.js `visible`), without freeing anything.
 *
 * Instanced renderables must keep an identity transform: their instance matrices are world matrices.
 */
class FilamentRenderable private constructor(
    private val engine: Engine,
    private val scene: Scene,
    val entity: Entity,
    val mesh: GpuMesh,
    private val boneCount: Int,
    private val primitiveCount: Int,
) {
    private var inScene = true
    private var destroyed = false
    private val box = Box()
    private val boxCenter = FloatArray(3)
    private val boxHalfExtent = FloatArray(3)

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

    /** Replaces the material instance of the first primitive. */
    fun setMaterialInstance(material: MaterialInstance) = setMaterialInstanceAt(0, material)

    fun setMaterialInstanceAt(
        primitive: Int,
        material: MaterialInstance,
    ) {
        val renderables = engine.renderableManager
        renderables.setMaterialInstanceAt(renderables.getInstance(entity), primitive, material)
    }

    /** Draws other index ranges; the number of ranges must stay the one the renderable was built with. */
    fun setRanges(ranges: List<PrimitiveRange>) {
        val renderables = engine.renderableManager
        val instance = renderables.getInstance(entity)
        val indices = mesh.indexBuffer
        for (i in ranges.indices) {
            val range = ranges[i]
            if (indices != null) {
                renderables.setGeometryAt(
                    instance,
                    i,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    mesh.vertexBuffer,
                    indices,
                    range.start,
                    range.count,
                )
            } else {
                renderables.setGeometryAt(
                    instance,
                    i,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    mesh.vertexBuffer,
                    range.start,
                    range.count,
                )
            }
        }
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

    fun setCulling(enabled: Boolean) {
        val renderables = engine.renderableManager
        renderables.setCulling(renderables.getInstance(entity), enabled)
    }

    fun setFog(enabled: Boolean) {
        val renderables = engine.renderableManager
        renderables.setFogEnabled(renderables.getInstance(entity), enabled)
    }

    /** The coarse draw order (0..7) and the order among transparent primitives (see `RenderOrder`). */
    fun setDrawOrder(
        priority: Int,
        blendOrder: Int,
    ) {
        val renderables = engine.renderableManager
        val instance = renderables.getInstance(entity)
        renderables.setPriority(instance, priority)
        for (i in 0 until primitiveCount) {
            renderables.setBlendOrderAt(instance, i, blendOrder)
            renderables.setGlobalBlendOrderEnabledAt(instance, i, blendOrder != 0)
        }
    }

    private fun applyBounds(bounds: Aabb) {
        box.center = bounds.center(boxCenter)
        box.halfExtent = bounds.halfExtent(boxHalfExtent)
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
        /** Builds a renderable with one primitive that draws the whole mesh, and adds it to the scene. */
        fun create(
            context: FilamentContext,
            mesh: GpuMesh,
            material: MaterialInstance,
            options: RenderableOptions = RenderableOptions(),
        ): FilamentRenderable = create(context, mesh, listOf(material), null, options)

        /**
         * Builds the renderable and adds it to the scene of the context. `materials[i]` draws
         * `ranges[i]`; without `ranges` there is one primitive that draws the whole mesh.
         */
        fun create(
            context: FilamentContext,
            mesh: GpuMesh,
            materials: List<MaterialInstance>,
            ranges: List<PrimitiveRange>?,
            options: RenderableOptions = RenderableOptions(),
        ): FilamentRenderable {
            require(materials.isNotEmpty()) { "a renderable needs a material" }
            require(ranges == null || ranges.size == materials.size) { "one material per range" }
            val engine = context.engine
            val entity = engine.entityManager.create()
            val renderable = FilamentRenderable(engine, context.scene, entity, mesh, options.boneCount, materials.size)
            renderable.applyBounds(options.bounds ?: mesh.bounds)
            val builder =
                RenderableManager
                    .Builder(materials.size)
                    .boundingBox(renderable.box)
                    .castShadows(options.castShadows)
                    .receiveShadows(options.receiveShadows)
                    .culling(options.culling)
                    .priority(options.priority)
                    .fog(options.fog)
            for (i in materials.indices) {
                builder.material(i, materials[i])
                addGeometry(builder, i, mesh, ranges?.get(i))
                if (options.blendOrder != 0) {
                    builder.blendOrder(i, options.blendOrder)
                    builder.globalBlendOrderEnabled(i, true)
                }
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

        private fun addGeometry(
            builder: RenderableManager.Builder,
            primitive: Int,
            mesh: GpuMesh,
            range: PrimitiveRange?,
        ) {
            val indices = mesh.indexBuffer
            val triangles = RenderableManager.PrimitiveType.TRIANGLES
            when {
                indices != null && range != null -> {
                    builder.geometry(primitive, triangles, mesh.vertexBuffer, indices, range.start, range.count)
                }

                indices != null -> {
                    builder.geometry(primitive, triangles, mesh.vertexBuffer, indices, 0, mesh.indexCount)
                }

                range != null -> {
                    builder.geometry(primitive, triangles, mesh.vertexBuffer, range.start, range.count)
                }

                else -> {
                    builder.geometry(primitive, triangles, mesh.vertexBuffer, 0, mesh.vertexCount)
                }
            }
        }

        private const val MATRIX_SIZE = 16
    }
}
