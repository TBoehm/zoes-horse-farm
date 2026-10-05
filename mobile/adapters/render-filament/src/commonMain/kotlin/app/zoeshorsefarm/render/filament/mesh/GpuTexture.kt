package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.resources.GpuResourceTracker
import app.zoeshorsefarm.render.filament.resources.ResourceKind
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Texture
import io.github.erkko68.filament.TextureSampler

/**
 * A 2D texture on the GPU with the sampler to bind it with (`MaterialInstance.setParameter(name,
 * texture, sampler)`). Destroy it after the material instances that use it.
 */
class GpuTexture private constructor(
    private val engine: Engine,
    private val tracker: GpuResourceTracker,
    private val trackerId: Int,
    val label: String,
    val texture: Texture,
    val sampler: TextureSampler,
    val byteSize: Long,
) {
    private var destroyed = false

    fun destroy() {
        if (destroyed) return
        destroyed = true
        engine.destroy(texture)
        tracker.release(trackerId)
    }

    companion object {
        fun upload(
            engine: Engine,
            tracker: GpuResourceTracker,
            label: String,
            data: TextureData,
        ): GpuTexture {
            val format = if (data.srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8
            var usage = Texture.Usage.DEFAULT
            if (data.mipmaps) usage = usage or Texture.Usage.GEN_MIPMAPPABLE
            val texture =
                Texture
                    .Builder()
                    .width(data.width)
                    .height(data.height)
                    .levels(data.levels)
                    .sampler(Texture.Sampler.SAMPLER_2D)
                    .format(format)
                    .usage(usage)
                    .build(engine)
            // the pixel array is read by the render thread later: it is never written again
            texture.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(data.pixels, data.pixels.size, Texture.Format.RGBA, Texture.Type.UBYTE),
            )
            if (data.mipmaps) texture.generateMipmaps(engine)
            val minFilter =
                if (data.mipmaps) TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR else TextureSampler.MinFilter.LINEAR
            val sampler =
                TextureSampler(
                    minFilter,
                    TextureSampler.MagFilter.LINEAR,
                    data.wrapS.toFilament(),
                    data.wrapT.toFilament(),
                    TextureSampler.WrapMode.CLAMP_TO_EDGE,
                )
            sampler.anisotropy = data.anisotropy
            val id = tracker.register(ResourceKind.TEXTURE, label, data.byteSize)
            return GpuTexture(engine, tracker, id, label, texture, sampler, data.byteSize)
        }

        private fun TextureWrap.toFilament(): TextureSampler.WrapMode =
            when (this) {
                TextureWrap.CLAMP -> TextureSampler.WrapMode.CLAMP_TO_EDGE
                TextureWrap.REPEAT -> TextureSampler.WrapMode.REPEAT
                TextureWrap.MIRROR -> TextureSampler.WrapMode.MIRRORED_REPEAT
            }
    }
}
