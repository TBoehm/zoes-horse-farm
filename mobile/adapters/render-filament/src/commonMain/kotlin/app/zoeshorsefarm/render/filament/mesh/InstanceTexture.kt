package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.material.MaterialSources
import app.zoeshorsefarm.render.filament.resources.GpuResourceTracker
import app.zoeshorsefarm.render.filament.resources.ResourceKind
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.MaterialInstance
import io.github.erkko68.filament.Texture
import io.github.erkko68.filament.TextureSampler

/**
 * The instance data texture (see [InstanceData]) of one instanced mesh: transforms and colours of up
 * to `capacity` instances, read by the vertex shader with `getInstanceIndex()`. [update] writes a
 * range of instances; [bind] hands the texture to a material instance of an instanced material.
 *
 * The renderable that draws the instances needs `instances(count)` and a bounding box that covers
 * all of them (`Aabb.ofInstances`): Filament culls the whole draw call with one box.
 */
class InstanceTexture(
    private val engine: Engine,
    private val tracker: GpuResourceTracker,
    val label: String,
    val capacity: Int,
) {
    private val floats = FloatArray(InstanceData.floatCount(capacity))
    private val ring = UploadRing()
    private val sampler = TextureSampler(TextureSampler.MinFilter.NEAREST, TextureSampler.MagFilter.NEAREST)
    private var destroyed = false

    val texture: Texture =
        Texture
            .Builder()
            .width(InstanceData.TEXTURE_WIDTH)
            .height(InstanceData.rowsFor(capacity))
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA32F)
            .build(engine)

    val byteSize: Long = InstanceData.byteSize(capacity)

    private val trackerId = tracker.register(ResourceKind.TEXTURE, label, byteSize)

    /**
     * Writes the instances `from until to`: matrices column-major (16 floats each, indexed by the
     * absolute instance number), colours with `colorComponents` floats per instance or null for
     * white. Only the texture rows that hold them are uploaded.
     */
    fun update(
        matrices: FloatArray,
        colors: FloatArray?,
        colorComponents: Int,
        from: Int,
        to: Int,
    ) {
        check(!destroyed) { "instance texture $label is destroyed" }
        require(from in 0..to && to <= capacity) { "instances $from until $to are outside 0..$capacity" }
        if (from == to) return
        InstanceData.pack(floats, matrices, colors, colorComponents, from, to)
        val rows = InstanceData.rowRange(from, to)
        val byteCount = InstanceData.rowByteCount(rows)
        val slot = ring.acquire(byteCount)
        InstanceData.rowBytes(floats, rows, slot.bytes)
        texture.setImage(
            engine,
            0,
            0,
            rows.first,
            InstanceData.TEXTURE_WIDTH,
            rows.last - rows.first + 1,
            Texture.PixelBufferDescriptor(
                slot.bytes,
                byteCount,
                Texture.Format.RGBA,
                Texture.Type.FLOAT,
                callback = slot.onConsumed,
            ),
        )
    }

    /** Binds the texture to the `instanceData` sampler of a material instance. */
    fun bind(materialInstance: MaterialInstance) {
        materialInstance.setParameter(MaterialSources.INSTANCE_DATA, texture, sampler)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        engine.destroy(texture)
        tracker.release(trackerId)
    }
}
