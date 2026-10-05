package app.zoeshorsefarm.scene.texture

import app.zoeshorsefarm.scene.GpuResource

/** Texture addressing outside 0..1 (three.js `ClampToEdgeWrapping`, `RepeatWrapping`, `MirroredRepeatWrapping`). */
enum class Wrap { CLAMP_TO_EDGE, REPEAT, MIRRORED_REPEAT }

/** Texture filtering (three.js `NearestFilter` ... `LinearMipmapLinearFilter`). */
enum class Filter {
    NEAREST,
    LINEAR,
    NEAREST_MIPMAP_NEAREST,
    LINEAR_MIPMAP_NEAREST,
    NEAREST_MIPMAP_LINEAR,
    LINEAR_MIPMAP_LINEAR,
}

/** How the texel values are encoded (three.js `SRGBColorSpace` / `NoColorSpace`). */
enum class TextureColorSpace { NONE, SRGB }

/** Pixel data of a texture: RGBA8, straight (not premultiplied) alpha, row 0 at the top. */
interface ImageSource {
    val width: Int
    val height: Int
    val pixels: ByteArray
}

/** Plain RGBA8 image (used for generated data such as normal maps). */
class RgbaImage(
    override val width: Int,
    override val height: Int,
    override val pixels: ByteArray = ByteArray(width * height * 4),
) : ImageSource {
    init {
        require(pixels.size == width * height * 4) { "pixel buffer must hold width * height RGBA texels" }
    }
}

/**
 * A 2D texture (three.js `Texture` / `CanvasTexture`). The image is read by the backend on upload;
 * after drawing into it again set [needsUpdate] so it is uploaded again ([version] counts that).
 */
class Texture(
    var image: ImageSource,
) : GpuResource() {
    var name: String = ""
    var wrapS: Wrap = Wrap.CLAMP_TO_EDGE
    var wrapT: Wrap = Wrap.CLAMP_TO_EDGE
    var magFilter: Filter = Filter.LINEAR
    var minFilter: Filter = Filter.LINEAR_MIPMAP_LINEAR
    var generateMipmaps: Boolean = true
    var anisotropy: Int = 1
    var colorSpace: TextureColorSpace = TextureColorSpace.NONE

    /** Incremented every time [needsUpdate] is set to true. A new texture starts at 0 (not uploaded yet). */
    var version: Int = 0
        private set

    var needsUpdate: Boolean = false
        set(value) {
            field = value
            if (value) version++
        }

    val width: Int get() = image.width
    val height: Int get() = image.height

    /** Sets both wrap modes (three.js: `tex.wrapS = tex.wrapT = RepeatWrapping`). */
    fun setWrap(wrap: Wrap): Texture {
        wrapS = wrap
        wrapT = wrap
        return this
    }
}
