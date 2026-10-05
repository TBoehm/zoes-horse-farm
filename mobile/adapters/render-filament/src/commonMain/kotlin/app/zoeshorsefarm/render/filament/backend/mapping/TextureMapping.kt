package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.mesh.TextureData
import app.zoeshorsefarm.render.filament.mesh.TextureWrap
import app.zoeshorsefarm.scene.texture.Filter
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.scene.texture.TextureColorSpace
import app.zoeshorsefarm.scene.texture.Wrap
import kotlin.math.max
import kotlin.math.min

/**
 * A scene [Texture] as the [TextureData] of a GPU upload. The pixels are copied: Filament reads the
 * array after the upload call returns, while the scene's `Raster2D` is drawn into again and again.
 * Sampling is always linear; a mip chain is built when the texture asks for mipmaps and filters with
 * them (`LINEAR_MIPMAP_LINEAR` is the default), and `anisotropy` is cut to the device limit.
 */
object TextureMapping {
    fun toData(
        texture: Texture,
        maxAnisotropy: Int,
    ): TextureData {
        val image = texture.image
        return TextureData(
            width = image.width,
            height = image.height,
            pixels = image.pixels.copyOf(),
            srgb = texture.colorSpace == TextureColorSpace.SRGB,
            mipmaps = texture.generateMipmaps && usesMipmaps(texture.minFilter),
            wrapS = wrapOf(texture.wrapS),
            wrapT = wrapOf(texture.wrapT),
            anisotropy = max(1, min(texture.anisotropy, max(1, maxAnisotropy))).toFloat(),
        )
    }

    fun wrapOf(wrap: Wrap): TextureWrap =
        when (wrap) {
            Wrap.CLAMP_TO_EDGE -> TextureWrap.CLAMP
            Wrap.REPEAT -> TextureWrap.REPEAT
            Wrap.MIRRORED_REPEAT -> TextureWrap.MIRROR
        }

    private fun usesMipmaps(filter: Filter): Boolean =
        when (filter) {
            Filter.NEAREST, Filter.LINEAR -> false
            else -> true
        }
}
