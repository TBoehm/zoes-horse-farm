package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.resources.GpuMemoryModel

enum class TextureWrap { CLAMP, REPEAT, MIRROR }

/**
 * RGBA8 pixels of a texture with how it is sampled: what a renderer neutral `Texture` holds
 * (drawn with `Raster2D`). `srgb` is true for colour maps (decoded to linear when sampled, like
 * three.js `SRGBColorSpace`) and false for data such as normal maps. With `mipmaps` the whole mip
 * chain is generated on the GPU and sampled trilinearly.
 *
 * Row 0 is the top row of the image. The material flips `v` (Filament's default), which makes
 * three.js uvs (`flipY`, v up) show the picture the same way.
 */
class TextureData(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val srgb: Boolean = true,
    val mipmaps: Boolean = true,
    val wrapS: TextureWrap = TextureWrap.REPEAT,
    val wrapT: TextureWrap = TextureWrap.REPEAT,
    val anisotropy: Float = 1f,
) {
    init {
        require(width >= 1 && height >= 1) { "texture size must be positive: ${width}x$height" }
        require(pixels.size == width * height * BYTES_PER_PIXEL) {
            "pixels must be ${width}x$height RGBA8 (${width * height * BYTES_PER_PIXEL} bytes), not ${pixels.size}"
        }
        require(anisotropy >= 1f) { "anisotropy is at least 1" }
    }

    val levels: Int get() = if (mipmaps) GpuMemoryModel.mipLevels(width, height) else 1

    /** GPU bytes with the mip chain. */
    val byteSize: Long get() = GpuMemoryModel.textureBytes(width, height, BYTES_PER_PIXEL, mipmaps)

    private companion object {
        const val BYTES_PER_PIXEL = 4
    }
}
