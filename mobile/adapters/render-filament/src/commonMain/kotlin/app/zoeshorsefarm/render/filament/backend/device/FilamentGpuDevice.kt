package app.zoeshorsefarm.render.filament.backend.device

import app.zoeshorsefarm.render.filament.context.FilamentContext
import app.zoeshorsefarm.render.filament.material.FilamentMaterial
import app.zoeshorsefarm.render.filament.material.MarkingRegions
import app.zoeshorsefarm.render.filament.material.MaterialBuildException
import app.zoeshorsefarm.render.filament.material.MaterialLibrary
import app.zoeshorsefarm.render.filament.material.MaterialPackageCache
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.material.filamentMaterialLibrary
import app.zoeshorsefarm.render.filament.mesh.Aabb
import app.zoeshorsefarm.render.filament.mesh.FilamentRenderable
import app.zoeshorsefarm.render.filament.mesh.GpuMesh
import app.zoeshorsefarm.render.filament.mesh.GpuTexture
import app.zoeshorsefarm.render.filament.mesh.InstanceTexture
import app.zoeshorsefarm.render.filament.mesh.MeshData
import app.zoeshorsefarm.render.filament.mesh.PackOptions
import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.render.filament.mesh.RenderableOptions
import app.zoeshorsefarm.render.filament.mesh.SpriteBatch
import app.zoeshorsefarm.render.filament.mesh.TextureData
import app.zoeshorsefarm.render.filament.mesh.TextureWrap
import app.zoeshorsefarm.render.filament.mesh.UploadRing
import app.zoeshorsefarm.render.filament.mesh.VertexSemantic
import io.github.erkko68.filament.Material
import io.github.erkko68.filament.MaterialInstance

/**
 * The [GpuDevice] on Filament: every handle wraps one of the classes of the `mesh` and `material`
 * packages. Compile-checked only (no GPU in the unit tests); the logic that decides what to create
 * and when is in `SceneSync`, which the tests run against a fake device.
 */
class FilamentGpuDevice(
    private val context: FilamentContext,
    cache: MaterialPackageCache? = null,
    markings: MarkingRegions = MarkingRegions.WEB,
    override val maxTextureSize: Int = DEFAULT_MAX_TEXTURE_SIZE,
    override val maxAnisotropy: Int = DEFAULT_MAX_ANISOTROPY,
) : GpuDevice {
    private val engine = context.engine
    private val tracker = context.tracker
    private val library: MaterialLibrary<FilamentMaterial> =
        filamentMaterialLibrary(engine, tracker, cache, markings)

    private var placeholderCreated = false

    override val placeholderTexture: TextureHandle by lazy {
        val white =
            TextureData(
                1,
                1,
                byteArrayOf(-1, -1, -1, -1),
                srgb = false,
                mipmaps = false,
                wrapS = TextureWrap.CLAMP,
                wrapT = TextureWrap.CLAMP,
            )
        placeholderCreated = true
        createTexture("placeholder", white)
    }

    /** Materials built and the shader programs they stand for, for the debug box. */
    val materialCount: Int get() = library.materialCount
    val estimatedPrograms: Int get() = library.estimatedPrograms

    override fun createMesh(
        label: String,
        data: MeshData,
        options: PackOptions,
    ): MeshHandle = FilamentMesh(GpuMesh.upload(engine, tracker, label, data, options))

    override fun createTexture(
        label: String,
        data: TextureData,
    ): TextureHandle = FilamentTexture(GpuTexture.upload(engine, tracker, label, data))

    override fun createInstanceData(
        label: String,
        capacity: Int,
    ): InstanceDataHandle = FilamentInstanceData(InstanceTexture(engine, tracker, label, capacity))

    override fun createSpriteBatch(
        label: String,
        capacity: Int,
    ): SpriteBatchHandle = FilamentSpriteBatch(SpriteBatch(engine, tracker, label, capacity))

    override fun createMaterialInstance(
        spec: MaterialSpec,
        label: String,
    ): MaterialInstanceHandle {
        val material: Material = library.get(spec).material
        return FilamentMaterialInstance(engine, material.createInstance(label))
    }

    override fun prepareMaterial(spec: MaterialSpec): Boolean =
        try {
            library.get(spec).material.compile(Material.CompilerPriorityQueue.HIGH)
            true
        } catch (
            @Suppress("SwallowedException") e: MaterialBuildException,
        ) {
            false
        }

    override fun releaseMaterial(spec: MaterialSpec) {
        library.release(spec)
    }

    override fun createRenderable(
        mesh: MeshHandle,
        materials: List<MaterialInstanceHandle>,
        ranges: List<PrimitiveRange>?,
        options: RenderableOptions,
    ): RenderableHandle {
        val gpuMesh = (mesh as FilamentMesh).mesh
        val instances = materials.map { (it as FilamentMaterialInstance).instance }
        return FilamentRenderableHandle(FilamentRenderable.create(context, gpuMesh, instances, ranges, options))
    }

    override fun flush() {
        engine.flush()
    }

    override fun dispose() {
        library.clear()
        if (placeholderCreated) placeholderTexture.destroy()
    }

    private class FilamentMesh(
        val mesh: GpuMesh,
    ) : MeshHandle {
        private val rings = arrayOfNulls<UploadRing>(VertexSemantic.entries.size)
        private var normalRing: UploadRing? = null

        override val label: String get() = mesh.label
        override val vertexCount: Int get() = mesh.vertexCount
        override val indexCount: Int get() = mesh.indexCount
        override val semantics: Set<VertexSemantic> = mesh.layout.attributes.mapTo(LinkedHashSet()) { it.semantic }
        override val bounds: Aabb get() = mesh.bounds
        override val byteSize: Long get() = mesh.byteSize
        override val uploadOverflows: Int
            get() = rings.sumOf { it?.overflowCount ?: 0 } + (normalRing?.overflowCount ?: 0)

        override fun updateFloats(
            semantic: VertexSemantic,
            values: FloatArray,
            count: Int,
        ) {
            val ring = rings[semantic.ordinal] ?: UploadRing(RING_SLOTS).also { rings[semantic.ordinal] = it }
            mesh.updateFloats(semantic, values, count, ring)
        }

        override fun updateNormals(normals: FloatArray) {
            val ring = normalRing ?: UploadRing(RING_SLOTS).also { normalRing = it }
            mesh.updateNormals(normals, ring)
        }

        override fun destroy() = mesh.destroy()
    }

    private class FilamentTexture(
        val texture: GpuTexture,
    ) : TextureHandle {
        override val label: String get() = texture.label
        override val byteSize: Long get() = texture.byteSize

        override fun destroy() = texture.destroy()
    }

    private class FilamentInstanceData(
        val texture: InstanceTexture,
    ) : InstanceDataHandle {
        override val capacity: Int get() = texture.capacity
        override val uploadOverflows: Int get() = texture.overflowCount

        override fun update(
            matrices: FloatArray,
            colors: FloatArray?,
            from: Int,
            to: Int,
        ) = texture.update(matrices, colors, COLOR_COMPONENTS, from, to)

        override fun destroy() = texture.destroy()
    }

    private class FilamentSpriteBatch(
        val batch: SpriteBatch,
    ) : SpriteBatchHandle {
        override val capacity: Int get() = batch.capacity
        override val mesh: MeshHandle = FilamentMesh(batch.mesh)
        override val uploadOverflows: Int get() = batch.overflowCount

        override fun update(
            centers: FloatArray,
            sizes: FloatArray,
            opacities: FloatArray,
            count: Int,
        ) = batch.update(centers, sizes, opacities, count)

        override fun destroy() = batch.destroy()
    }

    private class FilamentMaterialInstance(
        private val engine: io.github.erkko68.filament.Engine,
        val instance: MaterialInstance,
    ) : MaterialInstanceHandle {
        override fun setFloat(
            name: String,
            x: Float,
        ) = instance.setParameter(name, x)

        override fun setFloat3(
            name: String,
            x: Float,
            y: Float,
            z: Float,
        ) = instance.setParameter(name, x, y, z)

        override fun setFloat4(
            name: String,
            x: Float,
            y: Float,
            z: Float,
            w: Float,
        ) = instance.setParameter(name, x, y, z, w)

        override fun setTexture(
            name: String,
            texture: TextureHandle,
        ) {
            val gpu = (texture as FilamentTexture).texture
            instance.setParameter(name, gpu.texture, gpu.sampler)
        }

        override fun setInstanceData(data: InstanceDataHandle) {
            (data as FilamentInstanceData).texture.bind(instance)
        }

        override fun setPolygonOffset(
            factor: Float,
            units: Float,
        ) = instance.setPolygonOffset(factor, units)

        override fun setDepthTest(enabled: Boolean) {
            instance.isDepthCullingEnabled = enabled
        }

        override fun destroy() {
            engine.destroy(instance)
        }
    }

    private class FilamentRenderableHandle(
        private val renderable: FilamentRenderable,
    ) : RenderableHandle {
        override fun setTransform(matrix: FloatArray) = renderable.setTransform(matrix)

        override fun setVisible(visible: Boolean) {
            renderable.visible = visible
        }

        override fun setCastShadows(enabled: Boolean) = renderable.setCastShadows(enabled)

        override fun setReceiveShadows(enabled: Boolean) = renderable.setReceiveShadows(enabled)

        override fun setCulling(enabled: Boolean) = renderable.setCulling(enabled)

        override fun setFog(enabled: Boolean) = renderable.setFog(enabled)

        override fun setPriority(priority: Int) = renderable.setPriority(priority)

        override fun setBounds(bounds: Aabb) = renderable.setBounds(bounds)

        override fun setBones(palette: FloatArray) = renderable.setBones(palette)

        override fun setRanges(ranges: List<PrimitiveRange>) = renderable.setRanges(ranges)

        override fun setMaterial(
            primitive: Int,
            material: MaterialInstanceHandle,
        ) = renderable.setMaterialInstanceAt(primitive, (material as FilamentMaterialInstance).instance)

        override fun destroy() = renderable.destroy()
    }

    private companion object {
        const val DEFAULT_MAX_TEXTURE_SIZE = 4096
        const val DEFAULT_MAX_ANISOTROPY = 4
        const val RING_SLOTS = 4
        const val COLOR_COMPONENTS = 3
    }
}
