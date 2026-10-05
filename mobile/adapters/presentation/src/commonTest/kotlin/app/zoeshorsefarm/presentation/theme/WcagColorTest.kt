package app.zoeshorsefarm.presentation.theme

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun assertClose(
    expected: Double,
    actual: Double,
    tolerance: Double,
) = assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")

class WcagColorTest {
    @Test
    fun parseHexParsesSixDigitAndThreeDigitHexColors() {
        assertContentEquals(intArrayOf(46, 125, 50), parseHex("#2E7D32"))
        assertContentEquals(intArrayOf(255, 255, 255), parseHex("#fff"))
        assertContentEquals(intArrayOf(0, 0, 0), parseHex("  #000000 "))
    }

    @Test
    fun parseHexReturnsNullForAnythingElse() {
        assertNull(parseHex("red"))
        assertNull(parseHex("#12"))
        assertNull(parseHex("#12345g"))
        assertNull(parseHex(null))
    }

    @Test
    fun relativeLuminanceIsZeroForBlackAndOneForWhite() {
        assertEquals(0.0, relativeLuminance(intArrayOf(0, 0, 0)))
        assertClose(1.0, relativeLuminance(intArrayOf(255, 255, 255)), 1e-10)
    }

    @Test
    fun contrastRatioIs21ForBlackOnWhiteInEitherOrder() {
        assertClose(21.0, contrastRatio("#000000", "#ffffff"), 1e-5)
        assertClose(21.0, contrastRatio("#ffffff", "#000000"), 1e-5)
    }

    @Test
    fun contrastRatioIs1ForIdenticalColors() {
        assertClose(1.0, contrastRatio("#c8502e", "#c8502e"), 1e-10)
    }

    @Test
    fun contrastRatioMatchesKnownWcagValues() {
        assertClose(5.13, contrastRatio("#2E7D32", "#ffffff"), 0.05)
        assertClose(4.54, contrastRatio("#767676", "#ffffff"), 0.05)
    }

    @Test
    fun contrastRatioThrowsForAColorThatCannotBeParsed() {
        val error = assertFailsWith<IllegalArgumentException> { contrastRatio("nope", "#fff") }
        assertTrue(error.message.orEmpty().contains("color", ignoreCase = true))
    }

    @Test
    fun contrastRatioOfArgbColorsIgnoresTheAlphaChannel() {
        assertClose(21.0, contrastRatio(Argb.rgb(0x000000), Argb.rgb(0xffffff)), 1e-5)
        assertClose(21.0, contrastRatio(Argb(alpha = 10, red = 0, green = 0, blue = 0), Argb.rgb(0xffffff)), 1e-5)
    }

    @Test
    fun argbKeepsItsChannelsAndPacksThemIntoAnInt() {
        val color = Argb.rgb(0x2e7d32)
        assertEquals(255, color.alpha)
        assertEquals(46, color.red)
        assertEquals(125, color.green)
        assertEquals(50, color.blue)
        assertEquals(0xFF2E7D32.toInt(), color.toArgbInt())
        assertEquals("#2e7d32", color.toHex())
    }

    @Test
    fun argbWithAnAlphaFractionRoundsToTheNearestChannelValue() {
        val panel = Argb.rgba(0xfffbf3, 0.94)
        assertEquals(240, panel.alpha)
        assertEquals(0xF0FFFBF3.toInt(), panel.toArgbInt())
    }
}
