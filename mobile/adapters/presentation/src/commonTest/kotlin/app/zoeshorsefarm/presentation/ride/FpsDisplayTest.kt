package app.zoeshorsefarm.presentation.ride

import app.zoeshorsefarm.application.GraphicsLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feeds [seconds] of frames with a constant frame time; returns every reported value. */
private fun feed(
    meter: FpsMeter,
    seconds: Double,
    frameS: Double,
): List<Int> {
    val reported = mutableListOf<Int>()
    var t = 0.0
    while (t < seconds - 1e-9) {
        meter.frame(frameS)?.let { reported += it }
        t += frameS
    }
    return reported
}

class FpsMeterTest {
    @Test
    fun reportsNothingBeforeTheIntervalIsOver() {
        val meter = FpsMeter(intervalS = 0.5)
        assertEquals(emptyList(), feed(meter, 0.4, 1.0 / 60))
    }

    @Test
    fun reportsTheAverageAboutTwicePerSecond() {
        val meter = FpsMeter(intervalS = 0.5)
        val reported = feed(meter, 2.0, 1.0 / 50)
        assertEquals(4, reported.size)
        for (fps in reported) assertEquals(50, fps)
    }

    @Test
    fun roundsToWholeFps() {
        val meter = FpsMeter(intervalS = 0.5)
        val reported = feed(meter, 1.0, 1.0 / 58.4)
        assertEquals(58, reported[0])
    }

    @Test
    fun followsAChangeOfTheFrameRateInTheNextInterval() {
        val meter = FpsMeter(intervalS = 0.5)
        assertEquals(listOf(60), feed(meter, 0.5, 1.0 / 60))
        assertEquals(listOf(20), feed(meter, 0.5, 1.0 / 20))
    }

    @Test
    fun ignoresInvalidFrameTimes() {
        val meter = FpsMeter(intervalS = 0.5)
        for (bad in listOf(Double.NaN, -1.0, 0.0, Double.POSITIVE_INFINITY)) assertNull(meter.frame(bad))
        assertEquals(listOf(40), feed(meter, 0.5, 1.0 / 40))
    }

    @Test
    fun treatsAFrameLongerThanMaxFrameSAsAnInterruptionAndStartsOver() {
        val meter = FpsMeter(intervalS = 0.5, maxFrameS = 1.0)
        feed(meter, 0.3, 1.0 / 30)
        assertNull(meter.frame(5.0)) // a suspended app: not a slow game
        assertEquals(listOf(60), feed(meter, 0.5, 1.0 / 60))
    }

    @Test
    fun countsAFrameOfExactlyMaxFrameSAsASlowFrame() {
        val meter = FpsMeter(intervalS = 0.5, maxFrameS = 1.0)
        assertEquals(1, meter.frame(1.0))
    }

    @Test
    fun startsAFreshIntervalAfterReset() {
        val meter = FpsMeter(intervalS = 0.5)
        feed(meter, 0.3, 1.0 / 30)
        meter.reset()
        assertEquals(listOf(60), feed(meter, 0.5, 1.0 / 60))
    }
}

class FormatFpsTextTest {
    private val texts =
        mapOf(
            "ride.fps" to "{fps} fps",
            "ride.fpsLevel" to "{fps} fps | {level}",
            "ride.fpsLevelAuto" to "{fps} fps | {level} (auto)",
            "ride.fpsNone" to "?",
            "graphics.low" to "Low",
            "graphics.medium" to "Medium",
            "graphics.high" to "High",
        )

    // minimal translate function with {param} substitution, like the real one
    private val t: (String, Map<String, Any?>?) -> String = { key, params ->
        Regex("""\{(\w+)\}""").replace(texts.getValue(key)) { params?.get(it.groupValues[1]).toString() }
    }

    @Test
    fun showsFpsAndTheLevelOfAManualChoice() {
        assertEquals("58 fps | Medium", formatFpsText(58, GraphicsLevel.MEDIUM, auto = false, t))
    }

    @Test
    fun marksAnAutomaticallyChosenLevel() {
        assertEquals("41 fps | Low (auto)", formatFpsText(41, GraphicsLevel.LOW, auto = true, t))
    }

    @Test
    fun showsThePlaceholderTextUntilTheFirstMeasurementIsThere() {
        assertEquals("? fps | High", formatFpsText(null, GraphicsLevel.HIGH, auto = false, t))
        assertEquals("? fps", formatFpsText(null, null, auto = false, t))
    }

    @Test
    fun leavesOutTheLevelWhenItIsNotKnown() {
        assertEquals("60 fps", formatFpsText(60, null, auto = true, t))
    }

    @Test
    fun buildsNoTextOfItsOwnEveryVisibleCharacterComesFromATranslation() {
        val marked: (String, Map<String, Any?>?) -> String = { key, params -> "[$key$params]" }
        val text = formatFpsText(58, GraphicsLevel.LOW, auto = true, marked)
        assertTrue(text.startsWith("[ride.fpsLevelAuto"))
        assertTrue("fps=58" in text)
        assertTrue("level=[graphics.low" in text)
    }
}
