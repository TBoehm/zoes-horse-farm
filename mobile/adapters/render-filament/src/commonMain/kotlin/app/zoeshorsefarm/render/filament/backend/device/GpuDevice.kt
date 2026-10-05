package app.zoeshorsefarm.render.filament.backend.device

import app.zoeshorsefarm.render.filament.backend.mapping.ParamSink
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.mesh.Aabb
import app.zoeshorsefarm.render.filament.mesh.MeshData
import app.zoeshorsefarm.render.filament.mesh.PackOptions
import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.render.filament.mesh.RenderableOptions
import app.zoeshorsefarm.render.filament.mesh.TextureData
import app.zoeshorsefarm.render.filament.mesh.VertexSemantic

/*
 * The part of Filament that the scene synchronisation needs, as small interfaces. `FilamentGpuDevice`
 * implements them with the wrapper classes of this module; the tests implement them with plain
 * objects, which makes the synchronisation (what is uploaded when, what is freed, what is rebuilt)
 * testable on the JVM without a GPU.
 */

/** A mesh on the GPU. */
interface MeshHandle {
    val label: String
    val vertexCount: Int
    val indexCount: Int
    val semantics: Set<VertexSemantic>
    val bounds: Aabb
    val byteSize: Long

    /** Rewrites the first `count` floats of a float attribute (positions, uvs, custom attributes). */
    fun updateFloats(
        semantic: VertexSemantic,
        values: FloatArray,
        count: Int,
    )

    /** Rewrites the tangent frames from new normals (xyz per vertex). */
    fun updateNormals(normals: FloatArray)

    /** Uploads that fell back to a one shot array because the driver still held every slot. */
    val uploadOverflows: Int

    fun destroy()
}

/** A texture on the GPU, ready to be bound to a material parameter. */
interface TextureHandle {
    val label: String
    val byteSize: Long

    fun destroy()
}

/** The transform and colour data texture of one instanced mesh. */
interface InstanceDataHandle {
    val capacity: Int
    val uploadOverflows: Int

    /** Writes the instances `from until to`: matrices (16 floats each) and colours (rgb, or null for white). */
    fun update(
        matrices: FloatArray,
        colors: FloatArray?,
        from: Int,
        to: Int,
    )

    fun destroy()
}

/** The particle quads of a `Points` object. */
interface SpriteBatchHandle {
    val capacity: Int
    val mesh: MeshHandle
    val uploadOverflows: Int

    fun update(
        centers: FloatArray,
        sizes: FloatArray,
        opacities: FloatArray,
        count: Int,
    )

    fun destroy()
}

/** One material instance: the values and textures of a material variant. */
interface MaterialInstanceHandle : ParamSink {
    fun setTexture(
        name: String,
        texture: TextureHandle,
    )

    fun setInstanceData(data: InstanceDataHandle)

    /** `glPolygonOffset(factor, units)`; (0, 0) switches it off. */
    fun setPolygonOffset(
        factor: Float,
        units: Float,
    )

    fun setDepthTest(enabled: Boolean)

    fun destroy()
}

/** One drawable on the GPU (a Filament entity). */
interface RenderableHandle {
    fun setTransform(matrix: FloatArray)

    /** In or out of the Filament scene, without freeing anything. */
    fun setVisible(visible: Boolean)

    fun setCastShadows(enabled: Boolean)

    fun setReceiveShadows(enabled: Boolean)

    fun setCulling(enabled: Boolean)

    fun setFog(enabled: Boolean)

    /** The coarse draw order and the blend order among transparent primitives. */
    fun setDrawOrder(
        priority: Int,
        blendOrder: Int,
    )

    fun setBounds(bounds: Aabb)

    /** Bone matrices (16 floats per bone) of a skinned mesh. */
    fun setBones(palette: FloatArray)

    /** Other ranges for the same number of primitives. */
    fun setRanges(ranges: List<PrimitiveRange>)

    fun setMaterial(
        primitive: Int,
        material: MaterialInstanceHandle,
    )

    fun destroy()
}

/** Creates and frees the GPU objects. All calls come from the render thread. */
interface GpuDevice {
    /** The largest texture and anisotropy the device supports. */
    val maxTextureSize: Int
    val maxAnisotropy: Int

    /** A 1x1 white texture that stands in for a texture that was freed and not uploaded again yet. */
    val placeholderTexture: TextureHandle

    fun createMesh(
        label: String,
        data: MeshData,
        options: PackOptions,
    ): MeshHandle

    fun createTexture(
        label: String,
        data: TextureData,
    ): TextureHandle

    fun createInstanceData(
        label: String,
        capacity: Int,
    ): InstanceDataHandle

    fun createSpriteBatch(
        label: String,
        capacity: Int,
    ): SpriteBatchHandle

    /**
     * A material instance of `spec`; the material is compiled on first use.
     * Throws `MaterialBuildException` if the shader does not compile.
     */
    fun createMaterialInstance(
        spec: MaterialSpec,
        label: String,
    ): MaterialInstanceHandle

    /** Compiles the material of `spec` without making an instance; false if it does not compile. */
    fun prepareMaterial(spec: MaterialSpec): Boolean

    /** Frees the material of `spec` once none of its instances is left. */
    fun releaseMaterial(spec: MaterialSpec)

    /** `materials[i]` draws `ranges[i]`; without ranges the whole mesh is drawn with `materials[0]`. */
    fun createRenderable(
        mesh: MeshHandle,
        materials: List<MaterialInstanceHandle>,
        ranges: List<PrimitiveRange>?,
        options: RenderableOptions,
    ): RenderableHandle

    /** Submits pending work (after a batch of material compiles); a frame submits its own. */
    fun flush()

    /** Frees what the device itself holds (the placeholder texture, materials left over). */
    fun dispose()
}
