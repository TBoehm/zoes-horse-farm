package app.zoeshorsefarm.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioLogicTest {
    @Test
    fun volumeMappingIsExactAtTheEdgesAndQuadraticInBetween() {
        assertEquals(0.0, volumeToGain(0.0))
        assertEquals(1.0, volumeToGain(1.0))
        assertEquals(0.25, volumeToGain(0.5), 1e-9)
    }

    @Test
    fun volumeMappingIsMonotonicallyIncreasing() {
        var last = -1.0
        for (i in 0..20) {
            val g = volumeToGain(i / 20.0)
            assertTrue(g > last)
            last = g
        }
    }

    @Test
    fun volumeMappingClampsAndSanitizesInvalidValues() {
        assertEquals(0.0, volumeToGain(-3.0))
        assertEquals(1.0, volumeToGain(7.0))
        assertEquals(0.5, clamp01(Double.NaN))
        assertEquals(0.5, clamp01(null))
        assertEquals(0.2, clamp01(null, 0.2))
    }

    @Test
    fun mutingSetsTheChannelToZeroWithoutChangingTheVolume() {
        assertEquals(0.0, channelGain(0.8, true))
        assertEquals(0.64, channelGain(0.8, false), 1e-9)
    }

    @Test
    fun normalizeSettingsSetsMediumVolumeAndBothChannelsOn() {
        assertEquals(
            AudioSettings(musicVolume = 0.5, musicMuted = false, sfxVolume = 0.5, sfxMuted = false),
            normalizeSettings(),
        )
        assertEquals(
            AudioSettings(musicVolume = 1.0, musicMuted = false, sfxVolume = 0.5, sfxMuted = true),
            normalizeSettings(musicVolume = 2.0, sfxMuted = true),
        )
    }

    @Test
    fun musicRunsOnlyWhenWantedVisibleAndNotMuted() {
        assertTrue(shouldMusicRun(wanted = true, hidden = false, muted = false))
        assertFalse(shouldMusicRun(wanted = false, hidden = false, muted = false))
        assertFalse(shouldMusicRun(wanted = true, hidden = true, muted = false))
        assertFalse(shouldMusicRun(wanted = true, hidden = false, muted = true))
    }

    @Test
    fun midiToFreqMapsA4To440AndAnOctaveDoubles() {
        assertEquals(440.0, midiToFreq(69), 1e-9)
        assertEquals(880.0, midiToFreq(81), 1e-9)
        assertEquals(261.63, midiToFreq(60), 0.05)
    }

    private fun plan(
        now: Double,
        nextTime: Double,
        step: Int,
    ): StepPlan =
        planSteps(
            now = now,
            nextTime = nextTime,
            step = step,
            lookahead = 0.15,
            stepDuration = 0.3,
            loopSteps = 4,
        )

    @Test
    fun planStepsSchedulesAllStepsInTheWindowAndRemembersTheNextOne() {
        val p = plan(now = 0.0, nextTime = 0.05, step = 0)
        assertEquals(listOf(PlannedStep(0, 0.05)), p.toList())
        assertEquals(1, p.step)
        assertEquals(0.35, p.nextTime, 1e-9)
        // window without a step
        val q = plan(now = 0.1, nextTime = p.nextTime, step = p.step)
        assertEquals(emptyList(), q.toList())
        assertEquals(0.35, q.nextTime, 1e-9)
    }

    @Test
    fun planStepsRunsWithoutDriftAndWrapsAroundOverManyCalls() {
        var nextTime = 0.1
        var step = 0
        val times = mutableListOf<Double>()
        val steps = mutableListOf<Int>()
        var now = 0.0
        while (now < 6) {
            val p = plan(now, nextTime, step)
            for (e in p.toList()) {
                times += e.time
                steps += e.step
            }
            nextTime = p.nextTime
            step = p.step
            now += 0.03
        }
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3, 0), steps.take(9))
        for (i in 1 until times.size) assertEquals(0.3, times[i] - times[i - 1], 1e-6)
    }

    @Test
    fun planStepsRestartsAfterALongPauseInsteadOfCatchingUpOnABacklog() {
        val p = plan(now = 100.0, nextTime = 3.0, step = 2)
        val events = p.toList()
        assertEquals(1, events.size)
        assertEquals(2, events[0].step)
        assertTrue(events[0].time >= 100.0)
        assertTrue(events[0].time < 100.1)
    }

    @Test
    fun planStepsNeverSchedulesIntoThePast() {
        val p = plan(now = 10.0, nextTime = 9.9, step = 0)
        assertEquals(10.0, p.toList()[0].time)
    }

    @Test
    fun planStepsReusesTheGivenPlanWithoutKeepingOldEvents() {
        val reuse = StepPlan()
        planSteps(0.0, 0.05, 0, 0.15, 0.3, 4, into = reuse)
        assertEquals(1, reuse.count)
        planSteps(0.1, 0.35, 1, 0.15, 0.3, 4, into = reuse)
        assertEquals(0, reuse.count)
    }

    @Test
    fun planStepsGrowsTheEventListWhenTheWindowHoldsManySteps() {
        val p = planSteps(0.0, 0.0, 0, lookahead = 9.95, stepDuration = 0.1, loopSteps = 7)
        assertEquals(100, p.count)
        assertEquals(99 % 7, p.toList().last().step)
    }

    @Test
    fun mulberry32IsDeterministicAndLiesInZeroToOne() {
        val a = Mulberry32(5)
        val b = Mulberry32(5)
        repeat(100) {
            val x = a.next()
            assertEquals(b.next(), x)
            assertTrue(x >= 0.0)
            assertTrue(x < 1.0)
        }
    }

    @Test
    fun mulberry32MatchesTheWebImplementation() {
        val r = Mulberry32(5)
        assertEquals(0.6897749109193683, r.next(), 1e-15)
        assertEquals(0.7727432732935995, r.next(), 1e-15)
        assertEquals(0.21976301027461886, r.next(), 1e-15)
        val noise = Mulberry32(1337)
        assertEquals(0.1844118325971067, noise.next(), 1e-15)
        assertEquals(0.18998925131745636, noise.next(), 1e-15)
    }

    @Test
    fun generateImpulseReturnsTwoDistinctBoundedDecayingChannels() {
        val (l, r) = generateImpulse(sampleRate = 8000, seconds = 1.0)
        assertEquals(8000, l.size)
        assertEquals(8000, r.size)
        assertFalse(l.contentEquals(r))

        fun peak(
            d: FloatArray,
            from: Int,
            to: Int,
        ) = (from until to).maxOf { abs(d[it]) }
        assertTrue(peak(l, 0, 8000) <= 1f)
        assertTrue(peak(l, 0, 2000) > peak(l, 6000, 8000))
        assertTrue(abs(l[7999]) < 0.01f)
        assertTrue(l.all { it.isFinite() })
    }

    @Test
    fun generateImpulseIsReproducibleWithTheSameRandomGenerator() {
        val a = generateImpulse(sampleRate = 1000, seconds = 0.5, rng = Mulberry32(3))
        val b = generateImpulse(sampleRate = 1000, seconds = 0.5, rng = Mulberry32(3))
        assertTrue(a[0].contentEquals(b[0]))
    }

    @Test
    fun generateImpulseMatchesTheWebImplementation() {
        val (l, r) = generateImpulse(sampleRate = 8000, seconds = 0.01)
        assertEquals(80, l.size)
        val expectedLeft = floatArrayOf(0f, -0.019137270748615265f, 0.004602310247719288f, 0.01862652413547039f)
        for (i in expectedLeft.indices) assertEquals(expectedLeft[i], l[i], 1e-7f)
        assertEquals(0.004681223072111607f, r[1], 1e-7f)
        assertEquals(0.018272612243890762f, r[2], 1e-7f)
    }
}
