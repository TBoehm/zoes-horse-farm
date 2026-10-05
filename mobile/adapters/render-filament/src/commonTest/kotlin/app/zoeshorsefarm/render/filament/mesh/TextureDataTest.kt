package app.zoeshorsefarm.render.filament.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextureDataTest {
    private fun data(
        width: Int = 4,
        height: Int = 4,
        mipmaps: Boolean = true,
        srgb: Boolean = true,
    ) = TextureData(width, height, ByteArray(width * height * 4), srgb = srgb, mipmaps = mipmaps)

    @Test
    fun `the pixels must be rgba8`() {
        assertFailsWith<IllegalArgumentException> { TextureData(4, 4, ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { TextureData(0, 4, ByteArray(0)) }
    }

    @Test
    fun `a mipmapped texture has a full chain of levels`() {
        assertEquals(10, data(512, 512).levels)
        assertEquals(3, data(4, 4).levels)
    }

    @Test
    fun `without mipmaps there is one level`() {
        assertEquals(1, data(512, 512, mipmaps = false).levels)
    }

    @Test
    fun `the gpu size includes the mip chain`() {
        val plain = data(512, 512, mipmaps = false).byteSize
        val chain = data(512, 512, mipmaps = true).byteSize
        assertEquals(512L * 512 * 4, plain)
        assertTrue(chain > plain && chain < plain * 2)
    }

    @Test
    fun `the colour map is stored as sRGB and data maps are linear`() {
        assertTrue(data(srgb = true).srgb)
        assertFalse(data(srgb = false).srgb)
    }

    @Test
    fun `defaults repeat the texture and filter linearly with anisotropy one`() {
        val d = data()
        assertEquals(TextureWrap.REPEAT, d.wrapS)
        assertEquals(TextureWrap.REPEAT, d.wrapT)
        assertEquals(1f, d.anisotropy)
    }

    @Test
    fun `an anisotropy below one is rejected`() {
        assertFailsWith<IllegalArgumentException> { TextureData(4, 4, ByteArray(64), anisotropy = 0.5f) }
    }

    @Test
    fun `attribute formats tell whether they are plain floats`() {
        assertTrue(AttributeFormat.FLOAT1.isFloat && AttributeFormat.FLOAT4.isFloat)
        assertFalse(AttributeFormat.SHORT4_SNORM.isFloat)
        assertFalse(AttributeFormat.UBYTE4_NORM.isFloat)
        assertFalse(AttributeFormat.USHORT4.isFloat)
    }
}
