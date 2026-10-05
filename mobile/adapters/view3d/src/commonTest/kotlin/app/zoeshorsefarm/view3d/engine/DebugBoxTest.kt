package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The refresh rhythm of the debug box (web: `createDebugBox` with its own fps meter): about twice per
// second, also for slow frames, never for invalid times.

private val translate: Translate = { key, params -> "$key${params?.values?.joinToString(",", "(", ")").orEmpty()}" }

class DebugBoxTest {
    private val info = EngineDiagnostics().also { it.level = GraphicsLevel.LOW }
    private var errors = emptyList<DebugError>()
    private val box = DebugBox({ info }, { errors }, translate)

    private fun feed(
        seconds: Double,
        frameS: Double,
    ): Int {
        var refreshed = 0
        var t = 0.0
        while (t < seconds - 1e-9) {
            if (box.frame(frameS)) refreshed++
            t += frameS
        }
        return refreshed
    }

    @Test
    fun hasTheTextRightAfterCreation() {
        assertTrue(box.text.startsWith("debug.gpu"))
    }

    @Test
    fun refreshesAboutTwicePerSecond() {
        assertEquals(4, feed(2.0, 1.0 / 60))
    }

    @Test
    fun doesNotRefreshBeforeTheIntervalIsOver() {
        assertEquals(0, feed(0.4, 1.0 / 60))
    }

    @Test
    fun showsNewValuesAfterARefreshButNotBefore() {
        val before = box.text
        info.level = GraphicsLevel.HIGH
        feed(0.3, 1.0 / 60)
        assertEquals(before, box.text)
        feed(0.3, 1.0 / 60)
        assertTrue(box.text.contains("graphics.high"))
    }

    @Test
    fun countsAFrameLongerThanASecondLikeAnyOther() {
        // a suspended app is the ride screen's business: the box refreshes at the next chance
        assertTrue(box.frame(5.0))
    }

    @Test
    fun ignoresInvalidFrameTimes() {
        for (bad in listOf(Double.NaN, -1.0, 0.0, Double.POSITIVE_INFINITY)) assertFalse(box.frame(bad))
        assertEquals(1, feed(0.5, 1.0 / 40))
    }

    @Test
    fun redrawsAtOnceAfterALanguageChange() {
        errors = listOf(DebugError(1.0, "boom"))
        box.renderTexts()
        assertTrue(box.text.contains("debug.error"))
    }
}
