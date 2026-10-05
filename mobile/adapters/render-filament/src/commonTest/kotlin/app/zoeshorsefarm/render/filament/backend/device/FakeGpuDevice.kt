package app.zoeshorsefarm.render.filament.backend.device

import app.zoeshorsefarm.render.filament.material.MaterialBuildException
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.mesh.Aabb
import app.zoeshorsefarm.render.filament.mesh.MeshData
import app.zoeshorsefarm.render.filament.mesh.MeshPacker
import app.zoeshorsefarm.render.filament.mesh.PackOptions
import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.render.filament.mesh.RenderableOptions
import app.zoeshorsefarm.render.filament.mesh.TextureData
import app.zoeshorsefarm.render.filament.mesh.VertexSemantic

/**
 * A [GpuDevice] made of plain objects. It also checks what Filament would punish: a mesh, material
 * instance or texture that is destroyed while something still uses it, a material that is released
 * while instances of it are alive, a handle that is used after it was destroyed.
 */
class FakeGpuDevice(
    override val maxTextureSize: Int = 4096,
    override val maxAnisotropy: Int = 4,
) : GpuDevice {
    val meshes = ArrayList<FakeMesh>()
    val textures = ArrayList<FakeTexture>()
    val instanceData = ArrayList<FakeInstanceData>()
    val spriteBatches = ArrayList<FakeSpriteBatch>()
    val materialInstances = ArrayList<FakeMaterialInstance>()
    val renderables = ArrayList<FakeRenderable>()

    /** Specs whose material is built, with the number of live instances. */
    val builtMaterials = LinkedHashMap<MaterialSpec, Int>()
    val preparedMaterials = ArrayList<MaterialSpec>()
    var materialBuilds = 0
        private set
    var flushes = 0
        private set
    var disposed = false
        private set

    /** Specs for which the compile fails. */
    val failingSpecs = HashSet<MaterialSpec>()

    val liveMeshes: List<FakeMesh> get() = meshes.filter { !it.destroyed }
    val liveTextures: List<FakeTexture> get() = textures.filter { !it.destroyed }
    val liveInstanceData: List<FakeInstanceData> get() = instanceData.filter { !it.destroyed }
    val liveSpriteBatches: List<FakeSpriteBatch> get() = spriteBatches.filter { !it.destroyed }
    val liveMaterialInstances: List<FakeMaterialInstance> get() = materialInstances.filter { !it.destroyed }
    val liveRenderables: List<FakeRenderable> get() = renderables.filter { !it.destroyed }

    private val placeholder = FakeTexture(this, "placeholder", 4L)
    override val placeholderTexture: TextureHandle get() = placeholder

    override fun createMesh(
        label: String,
        data: MeshData,
        options: PackOptions,
    ): MeshHandle {
        val packed = MeshPacker.pack(data, options)
        return FakeMesh(
            this,
            label,
            data,
            packed.indices?.format?.byteSize ?: 0,
            options,
            packed.bounds,
            packed.byteSize,
        ).also { meshes += it }
    }

    override fun createTexture(
        label: String,
        data: TextureData,
    ): TextureHandle = FakeTexture(this, label, data.byteSize, data).also { textures += it }

    override fun createInstanceData(
        label: String,
        capacity: Int,
    ): InstanceDataHandle = FakeInstanceData(this, label, capacity).also { instanceData += it }

    override fun createSpriteBatch(
        label: String,
        capacity: Int,
    ): SpriteBatchHandle {
        val mesh = FakeMesh(this, "$label quads", null, 0, PackOptions(), Aabb(), 0L).also { meshes += it }
        return FakeSpriteBatch(label, capacity, mesh).also { spriteBatches += it }
    }

    override fun createMaterialInstance(
        spec: MaterialSpec,
        label: String,
    ): MaterialInstanceHandle {
        if (spec in failingSpecs) throw MaterialBuildException("material ${spec.key} failed to compile")
        if (spec !in builtMaterials) {
            materialBuilds++
            builtMaterials[spec] = 0
        }
        builtMaterials[spec] = builtMaterials.getValue(spec) + 1
        return FakeMaterialInstance(this, spec, label).also { materialInstances += it }
    }

    override fun prepareMaterial(spec: MaterialSpec): Boolean {
        if (spec in failingSpecs) return false
        if (spec !in builtMaterials) {
            materialBuilds++
            builtMaterials[spec] = 0
        }
        preparedMaterials += spec
        return true
    }

    override fun releaseMaterial(spec: MaterialSpec) {
        val instances = builtMaterials[spec] ?: return
        check(instances == 0) { "material ${spec.key} released with $instances live instances" }
        builtMaterials.remove(spec)
    }

    override fun createRenderable(
        mesh: MeshHandle,
        materials: List<MaterialInstanceHandle>,
        ranges: List<PrimitiveRange>?,
        options: RenderableOptions,
    ): RenderableHandle {
        val fakeMesh = mesh as FakeMesh
        check(!fakeMesh.destroyed) { "renderable made of destroyed mesh ${fakeMesh.label}" }
        val instances = materials.map { it as FakeMaterialInstance }
        for (instance in instances) check(!instance.destroyed) { "renderable made of destroyed material instance" }
        check(ranges == null || ranges.size == materials.size) { "one material per range" }
        return FakeRenderable(this, fakeMesh, instances.toMutableList(), ranges?.toMutableList(), options).also {
            renderables += it
        }
    }

    override fun flush() {
        flushes++
    }

    override fun dispose() {
        disposed = true
    }

    internal fun checkMeshUnused(mesh: FakeMesh) {
        for (renderable in liveRenderables) {
            check(renderable.mesh !== mesh) { "mesh ${mesh.label} destroyed while a renderable draws it" }
        }
    }

    internal fun checkInstanceUnused(instance: FakeMaterialInstance) {
        for (renderable in liveRenderables) {
            check(instance !in renderable.materials) { "material instance destroyed while a renderable draws with it" }
        }
        builtMaterials[instance.spec] = builtMaterials.getValue(instance.spec) - 1
    }

    internal fun checkTextureUnused(texture: FakeTexture) {
        for (instance in liveMaterialInstances) {
            check(instance.textures.values.none { it === texture }) {
                "texture ${texture.label} destroyed while bound to a material instance"
            }
        }
    }
}

class FakeMesh(
    private val device: FakeGpuDevice,
    override val label: String,
    val data: MeshData?,
    val indexBytes: Int,
    val options: PackOptions,
    override val bounds: Aabb,
    override val byteSize: Long,
) : MeshHandle {
    var destroyed = false
        private set
    val floatUpdates = ArrayList<Pair<VertexSemantic, Int>>()
    var normalUpdates = 0
        private set

    override val vertexCount: Int get() = (data?.vertexCount ?: 0)
    override val indexCount: Int get() = data?.indices?.size ?: 0
    override val semantics: Set<VertexSemantic> get() = data?.semantics ?: setOf(VertexSemantic.POSITION)
    override val uploadOverflows: Int get() = 0

    override fun updateFloats(
        semantic: VertexSemantic,
        values: FloatArray,
        count: Int,
    ) {
        check(!destroyed) { "mesh $label is destroyed" }
        floatUpdates += semantic to count
    }

    override fun updateNormals(normals: FloatArray) {
        check(!destroyed) { "mesh $label is destroyed" }
        normalUpdates++
    }

    override fun destroy() {
        check(!destroyed) { "mesh $label destroyed twice" }
        device.checkMeshUnused(this)
        destroyed = true
    }
}

class FakeTexture(
    private val device: FakeGpuDevice,
    override val label: String,
    override val byteSize: Long,
    val data: TextureData? = null,
) : TextureHandle {
    var destroyed = false
        private set

    override fun destroy() {
        if (label == "placeholder") return
        check(!destroyed) { "texture $label destroyed twice" }
        device.checkTextureUnused(this)
        destroyed = true
    }
}

class FakeInstanceData(
    private val device: FakeGpuDevice,
    val label: String,
    override val capacity: Int,
) : InstanceDataHandle {
    var destroyed = false
        private set
    val updates = ArrayList<IntRange>()
    var lastColors: FloatArray? = null
        private set
    var lastMatrices: FloatArray = FloatArray(0)
        private set
    override val uploadOverflows: Int get() = 0

    override fun update(
        matrices: FloatArray,
        colors: FloatArray?,
        from: Int,
        to: Int,
    ) {
        check(!destroyed) { "instance data $label is destroyed" }
        updates += from until to
        lastMatrices = matrices.copyOf()
        lastColors = colors?.copyOf()
    }

    override fun destroy() {
        check(!destroyed) { "instance data destroyed twice" }
        check(device.liveMaterialInstances.none { it.instanceData === this }) {
            "instance data destroyed while bound to a material instance"
        }
        destroyed = true
    }
}

class FakeSpriteBatch(
    val label: String,
    override val capacity: Int,
    private val fakeMesh: FakeMesh,
) : SpriteBatchHandle {
    var destroyed = false
        private set
    var updates = 0
        private set
    var lastCenters: FloatArray = FloatArray(0)
        private set
    var lastSizes: FloatArray = FloatArray(0)
        private set
    var lastOpacities: FloatArray = FloatArray(0)
        private set
    override val mesh: MeshHandle get() = fakeMesh
    override val uploadOverflows: Int get() = 0

    override fun update(
        centers: FloatArray,
        sizes: FloatArray,
        opacities: FloatArray,
        count: Int,
    ) {
        check(!destroyed) { "sprite batch $label is destroyed" }
        updates++
        lastCenters = centers.copyOf(count * 3)
        lastSizes = sizes.copyOf(count)
        lastOpacities = opacities.copyOf(count)
    }

    override fun destroy() {
        check(!destroyed) { "sprite batch destroyed twice" }
        fakeMesh.destroy()
        destroyed = true
    }
}

class FakeMaterialInstance(
    private val device: FakeGpuDevice,
    val spec: MaterialSpec,
    val label: String,
) : MaterialInstanceHandle {
    var destroyed = false
        private set
    val floats = LinkedHashMap<String, FloatArray>()
    val textures = LinkedHashMap<String, FakeTexture>()
    var instanceData: FakeInstanceData? = null
        private set
    var polygonOffset: Pair<Float, Float> = 0f to 0f
        private set
    var depthTest = true
        private set

    /** Number of calls that changed a value, to see what the steady path leaves alone. */
    var setCalls = 0
        private set

    override fun setFloat(
        name: String,
        x: Float,
    ) {
        check(!destroyed) { "material instance $label is destroyed" }
        setCalls++
        floats[name] = floatArrayOf(x)
    }

    override fun setFloat3(
        name: String,
        x: Float,
        y: Float,
        z: Float,
    ) {
        check(!destroyed) { "material instance $label is destroyed" }
        setCalls++
        floats[name] = floatArrayOf(x, y, z)
    }

    override fun setFloat4(
        name: String,
        x: Float,
        y: Float,
        z: Float,
        w: Float,
    ) {
        check(!destroyed) { "material instance $label is destroyed" }
        setCalls++
        floats[name] = floatArrayOf(x, y, z, w)
    }

    override fun setTexture(
        name: String,
        texture: TextureHandle,
    ) {
        check(!destroyed) { "material instance $label is destroyed" }
        val fake = texture as FakeTexture
        check(!fake.destroyed) { "destroyed texture bound to $label" }
        setCalls++
        textures[name] = fake
    }

    override fun setInstanceData(data: InstanceDataHandle) {
        check(!destroyed) { "material instance $label is destroyed" }
        setCalls++
        instanceData = data as FakeInstanceData
    }

    override fun setPolygonOffset(
        factor: Float,
        units: Float,
    ) {
        setCalls++
        polygonOffset = factor to units
    }

    override fun setDepthTest(enabled: Boolean) {
        setCalls++
        depthTest = enabled
    }

    override fun destroy() {
        check(!destroyed) { "material instance $label destroyed twice" }
        device.checkInstanceUnused(this)
        destroyed = true
    }
}

class FakeRenderable(
    private val device: FakeGpuDevice,
    val mesh: FakeMesh,
    val materials: MutableList<FakeMaterialInstance>,
    initialRanges: MutableList<PrimitiveRange>?,
    val options: RenderableOptions,
) : RenderableHandle {
    var drawRanges: List<PrimitiveRange>? = initialRanges
        private set
    var destroyed = false
        private set
    var visible = true
        private set
    var transform = FloatArray(0)
        private set
    var transformCalls = 0
        private set
    var castShadows = options.castShadows
        private set
    var receiveShadows = options.receiveShadows
        private set
    var culling = options.culling
        private set
    var fog = options.fog
        private set
    var priority = options.priority
        private set
    var blendOrder = options.blendOrder
        private set
    var bounds: Aabb = options.bounds ?: mesh.bounds
        private set
    var boundsCalls = 0
        private set
    var bones = FloatArray(0)
        private set
    var boneCalls = 0
        private set
    var rangeCalls = 0
        private set
    var visibilityCalls = 0
        private set

    private fun live() = check(!destroyed) { "renderable used after destroy" }

    override fun setTransform(matrix: FloatArray) {
        live()
        transformCalls++
        transform = matrix.copyOf()
    }

    override fun setVisible(visible: Boolean) {
        live()
        visibilityCalls++
        this.visible = visible
    }

    override fun setCastShadows(enabled: Boolean) {
        live()
        castShadows = enabled
    }

    override fun setReceiveShadows(enabled: Boolean) {
        live()
        receiveShadows = enabled
    }

    override fun setCulling(enabled: Boolean) {
        live()
        culling = enabled
    }

    override fun setFog(enabled: Boolean) {
        live()
        fog = enabled
    }

    override fun setDrawOrder(
        priority: Int,
        blendOrder: Int,
    ) {
        live()
        this.priority = priority
        this.blendOrder = blendOrder
    }

    override fun setBounds(bounds: Aabb) {
        live()
        boundsCalls++
        this.bounds = Aabb().also { it.copyFrom(bounds) }
    }

    override fun setBones(palette: FloatArray) {
        live()
        boneCalls++
        bones = palette.copyOf()
    }

    override fun setRanges(ranges: List<PrimitiveRange>) {
        live()
        rangeCalls++
        drawRanges = ranges.toList()
    }

    override fun setMaterial(
        primitive: Int,
        material: MaterialInstanceHandle,
    ) {
        live()
        val fake = material as FakeMaterialInstance
        check(!fake.destroyed) { "destroyed material instance set" }
        materials[primitive] = fake
    }

    override fun destroy() {
        check(!destroyed) { "renderable destroyed twice" }
        destroyed = true
    }
}
