package app.zoeshorsefarm.render.filament.material

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GlslNumberTest {
    @Test
    fun `numbers have three decimals like toFixed 3`() {
        assertEquals("0.250", GlslNumber.format(0.25f))
        assertEquals("9.000", GlslNumber.format(9f))
        assertEquals("1.400", GlslNumber.format(1.4f))
        assertEquals("0.000", GlslNumber.format(0f))
        assertEquals("24.000", GlslNumber.format(24f))
    }

    @Test
    fun `negative numbers keep their sign`() {
        assertEquals("-0.500", GlslNumber.format(-0.5f))
        assertEquals("-12.345", GlslNumber.format(-12.345f))
    }

    @Test
    fun `a value that rounds to zero has no sign`() {
        assertEquals("0.000", GlslNumber.format(-0.0001f))
    }

    @Test
    fun `values round to the nearest thousandth`() {
        assertEquals("0.123", GlslNumber.format(0.1234f))
        assertEquals("0.124", GlslNumber.format(0.1236f))
    }

    @Test
    fun `non finite numbers are rejected`() {
        assertFailsWith<IllegalArgumentException> { GlslNumber.format(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { GlslNumber.format(Float.POSITIVE_INFINITY) }
    }

    @Test
    fun `key parts use only letters and digits`() {
        assertEquals("9p500", GlslNumber.keyPart(9.5f))
        assertEquals("m0p250", GlslNumber.keyPart(-0.25f))
        assertEquals("0p000", GlslNumber.keyPart(0f))
    }
}
