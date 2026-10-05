package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.mesh.TextureWrap
import app.zoeshorsefarm.scene.texture.Filter
import app.zoeshorsefarm.scene.texture.RgbaImage
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.scene.texture.TextureColorSpace
import app.zoeshorsefarm.scene.texture.Wrap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class TextureMappingTest {
    private fun texture() = Texture(RgbaImage(4, 2, ByteArray(32) { it.toByte() }))

    @Test
    fun `the pixels are copied`() {
        val texture = texture()
        val data = TextureMapping.toData(texture, 4)
        assertContentEquals(texture.image.pixels, data.pixels)
        assertNotSame(texture.image.pixels, data.pixels)
        assertEquals(4, data.width)
        assertEquals(2, data.height)
    }

    @Test
    fun `the colour space decides srgb`() {
        val texture = texture()
        assertFalse(TextureMapping.toData(texture, 4).srgb)
        texture.colorSpace = TextureColorSpace.SRGB
        assertTrue(TextureMapping.toData(texture, 4).srgb)
    }

    @Test
    fun `wrap modes are mapped`() {
        val texture = texture()
        texture.wrapS = Wrap.REPEAT
        texture.wrapT = Wrap.MIRRORED_REPEAT
        val data = TextureMapping.toData(texture, 4)
        assertEquals(TextureWrap.REPEAT, data.wrapS)
        assertEquals(TextureWrap.MIRROR, data.wrapT)
        assertEquals(TextureWrap.CLAMP, TextureMapping.wrapOf(Wrap.CLAMP_TO_EDGE))
    }

    @Test
    fun `mipmaps need the flag and a mipmap filter`() {
        val texture = texture()
        assertTrue(TextureMapping.toData(texture, 4).mipmaps)
        texture.minFilter = Filter.LINEAR
        assertFalse(TextureMapping.toData(texture, 4).mipmaps)
        texture.minFilter = Filter.LINEAR_MIPMAP_LINEAR
        texture.generateMipmaps = false
        assertFalse(TextureMapping.toData(texture, 4).mipmaps)
    }

    @Test
    fun `anisotropy is limited by the device`() {
        val texture = texture()
        texture.anisotropy = 16
        assertEquals(4f, TextureMapping.toData(texture, 4).anisotropy)
        texture.anisotropy = 2
        assertEquals(2f, TextureMapping.toData(texture, 4).anisotropy)
        texture.anisotropy = 0
        assertEquals(1f, TextureMapping.toData(texture, 4).anisotropy)
    }
}
