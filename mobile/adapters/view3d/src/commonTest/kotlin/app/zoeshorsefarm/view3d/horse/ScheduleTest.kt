package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.createRng
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DT = 1.0 / 60

/** One blink: start time, length (s) and peak closure. */
private data class Blink(
    val start: Double,
    var length: Double,
    var peak: Double,
)

/** Runs a blink scheduler and returns every closure. */
private fun blinks(
    seed: Int,
    seconds: Double,
    alert: Double = 0.0,
): List<Blink> {
    val s = createBlinkScheduler(createRng(seed))
    val list = ArrayList<Blink>()
    var cur: Blink? = null
    var t = 0.0
    while (t < seconds) {
        val c = s.step(DT, alert)
        if (c > 0) {
            val b = cur ?: Blink(t, 0.0, 0.0).also { cur = it }
            b.length += DT
            b.peak = max(b.peak, c)
        } else if (cur != null) {
            list.add(cur)
            cur = null
        }
        t += DT
    }
    return list
}

class BlinkCurveTest {
    @Test
    fun `opens closes fully and opens again closing is faster than opening`() {
        assertEquals(0.0, blinkCurve(0.0))
        assertEquals(0.0, blinkCurve(1.0))
        assertClose(1.0, blinkCurve(0.4), 6)
        assertGreater(blinkCurve(0.2), 0.0)
        assertLess(blinkCurve(0.2), 1.0)
        // closing takes 40 % of the blink, opening 60 %: at 80 % the lid is still more than half open
        assertGreater(blinkCurve(0.1), blinkCurve(0.9) - 1e-9)
        assertGreater(blinkCurve(0.7), 0.5)
    }
}

class BlinkSchedulerTest {
    @Test
    fun `is deterministic for a seed`() {
        assertEquals(blinks(5, 60.0), blinks(5, 60.0))
        assertNotEquals(blinks(5, 60.0), blinks(6, 60.0))
    }

    @Test
    fun `blinks every 3 to 8 s on average each blink lasts about 0 point 12 to 0 point 15 s and closes fully`() {
        val list = blinks(1, 600.0)
        // double blinks are two blinks 0.12-0.3 s apart: count the gaps between "groups"
        val starts = list.map { it.start }
        val groups = starts.filterIndexed { i, s -> i == 0 || s - starts[i - 1] > 0.5 }
        val perMinute = groups.size / 600.0 * 60
        assertGreater(perMinute, 7.0)
        assertLess(perMinute, 21.0)
        for (b in list) {
            assertGreater(b.peak, 0.95)
            assertGreater(b.length, 0.09)
            assertLess(b.length, 0.2)
        }
        for (i in 1 until groups.size) assertGreater(groups[i] - groups[i - 1], 2.9)
    }

    @Test
    fun `sometimes blinks twice in a row`() {
        val list = blinks(2, 900.0)
        val doubles = list.filterIndexed { i, b -> i > 0 && b.start - list[i - 1].start < 0.6 }.size
        assertGreater(doubles.toDouble(), 3.0)
        assertLess(doubles.toDouble(), list.size / 2.0)
    }

    @Test
    fun `blinks less while the horse is alert`() {
        val calm = blinks(3, 900.0, 0.0).size
        val alert = blinks(3, 900.0, 1.0).size
        assertTrue(alert < calm)
    }

    @Test
    fun `never moves the lid by a large step in one frame`() {
        val s = createBlinkScheduler(createRng(8))
        var prev = 0.0
        var max = 0.0
        var t = 0.0
        while (t < 120) {
            val c = s.step(DT)
            max = max(max, abs(c - prev))
            prev = c
            t += DT
        }
        assertLess(max, 0.6)
    }
}

class GestureSchedulerTest {
    private class Event(
        val id: GestureId,
        val start: Double,
        val leg: Int,
        var peak: Double,
    )

    private class Played(
        val events: List<Event>,
        val maxStep: Double,
    )

    private fun play(
        seed: Int,
        seconds: Double,
        allowedAt: (Double) -> Boolean = { true },
    ): Played {
        val g = GestureScheduler(createRng(seed))
        val events = ArrayList<Event>()
        var cur: Event? = null
        var prevW = 0.0
        var maxStep = 0.0
        var t = 0.0
        while (t < seconds) {
            val s = g.step(DT, allowedAt(t))
            maxStep = max(maxStep, abs(s.weight - prevW))
            prevW = s.weight
            val id = s.id
            if (id != null) {
                val e = cur ?: Event(id, t, s.leg, 0.0).also { cur = it }
                e.peak = max(e.peak, s.weight)
            } else if (cur != null) {
                events.add(cur)
                cur = null
            }
            t += DT
        }
        return Played(events, maxStep)
    }

    @Test
    fun `plays every kind of gesture at halt spaced out and fades smoothly`() {
        val played = play(4, 900.0)
        assertEquals(IDLE_GESTURES.map { it.id }.toSet(), played.events.map { it.id }.toSet())
        for (i in 1 until played.events.size) {
            assertGreater(played.events[i].start - played.events[i - 1].start, 5.0)
        }
        for (e in played.events) assertGreater(e.peak, 0.95)
        assertLess(played.maxStep, 0.1)
    }

    @Test
    fun `never starts a gesture while the horse is not allowed to`() {
        assertEquals(0, play(4, 300.0) { false }.events.size)
    }

    @Test
    fun `takes a running gesture back smoothly when the horse has to move and drops it`() {
        val g = GestureScheduler(createRng(11), cfg = GestureConfig(interval = 0.5..0.5))
        var t = 0.0
        while (t < 20 && g.step(DT, true).id == null) t += DT
        while (g.state.t < 0.6) g.step(DT, true)
        assertGreater(g.state.weight, 0.9)
        var prev = g.state.weight
        var frames = 0
        while (g.state.id != null && frames < 600) {
            val w = g.step(DT, false).weight
            assertLess(abs(w - prev), 0.25)
            prev = w
            frames++
        }
        assertNull(g.state.id)
        assertLess(frames.toDouble(), 40.0)
        assertEquals(0.0, g.state.weight)
    }

    @Test
    fun `timer only counts down while allowed`() {
        val g = GestureScheduler(createRng(1), cfg = GestureConfig(interval = 10.0..10.0))
        var t = 0.0
        while (t < 8) {
            g.step(DT, true)
            t += DT
        }
        t = 0.0
        while (t < 30) {
            g.step(DT, false)
            t += DT
        }
        assertNull(g.state.id)
        var started = false
        t = 0.0
        while (t < 10) {
            started = g.step(DT, true).id != null || started
            t += DT
        }
        assertTrue(started)
    }
}

class RandomTimerTest {
    @Test
    fun `fires within its interval and re-arms`() {
        val timer = createRandomTimer(createRng(3), 4.0..10.0)
        val times = ArrayList<Double>()
        var t = 0.0
        while (t < 200) {
            if (timer.step(DT)) times.add(t)
            t += DT
        }
        assertGreater(times.size.toDouble(), 15.0)
        for (i in 1 until times.size) {
            val gap = times[i] - times[i - 1]
            assertGreater(gap, 3.9)
            assertLess(gap, 10.1)
        }
    }
}
