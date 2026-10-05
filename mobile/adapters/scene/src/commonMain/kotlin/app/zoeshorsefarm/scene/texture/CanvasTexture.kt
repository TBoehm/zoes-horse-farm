package app.zoeshorsefarm.scene.texture

/**
 * A texture that shares the pixels of a [Raster2D] (three.js `CanvasTexture`): draw into the raster
 * again, then set `needsUpdate` to upload it anew. Defaults are those of the web app's
 * `canvasTexture` helper: repeat wrapping, sRGB colour, mipmaps with trilinear filtering.
 */
fun canvasTexture(
    raster: Raster2D,
    repeat: Boolean = true,
    srgb: Boolean = true,
    anisotropy: Int = 1,
): Texture {
    val texture = Texture(raster)
    if (repeat) texture.setWrap(Wrap.REPEAT)
    texture.colorSpace = if (srgb) TextureColorSpace.SRGB else TextureColorSpace.NONE
    texture.anisotropy = anisotropy
    texture.generateMipmaps = true
    texture.minFilter = Filter.LINEAR_MIPMAP_LINEAR
    texture.needsUpdate = true
    return texture
}
