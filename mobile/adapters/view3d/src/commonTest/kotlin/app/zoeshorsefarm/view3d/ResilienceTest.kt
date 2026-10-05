package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.GpuResource
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Tests of resilience.test.js. Not ported: `watchContextLoss prevents the default of the loss event`
// (the browser's canvas event does not exist here, the backend reports loss and restore itself)
// and the three `collectGpuObjects` cases (`collectGpuObjects` lives in the scene module, where
// FrustumCullingTest covers them).

private class Disposable : GpuResource()

class WatchContextLossTest {
    @Test
    fun `reports loss and restore and tracks the state`() {
        val backend = FakeRenderBackend()
        val calls = ArrayList<String>()
        val watch = watchContextLoss(backend, onLost = { calls.add("lost") }, onRestored = { calls.add("restored") })
        assertFalse(watch.lost)
        backend.simulateContextLoss()
        assertTrue(watch.lost)
        backend.simulateContextRestore()
        assertFalse(watch.lost)
        assertEquals(listOf("lost", "restored"), calls)
    }

    @Test
    fun `keeps the state right when a callback throws and tells the log`() {
        val backend = FakeRenderBackend()
        val logged = ArrayList<String>()
        val watch =
            watchContextLoss(
                backend,
                onLost = { error("boom") },
                log = { message, _ -> logged.add(message) },
            )
        backend.simulateContextLoss()
        assertTrue(watch.lost)
        assertEquals(listOf("Context loss handler failed"), logged)
    }

    @Test
    fun `a throwing callback does not break the other listeners`() {
        val backend = FakeRenderBackend()
        var second = 0
        watchContextLoss(backend, onLost = { error("boom") }, log = { _, _ -> })
        watchContextLoss(backend, onLost = { second++ })
        backend.simulateContextLoss()
        assertEquals(1, second)
    }

    @Test
    fun `stops listening on request`() {
        val backend = FakeRenderBackend()
        var lost = 0
        val watch = watchContextLoss(backend, onLost = { lost++ })
        watch.stop()
        backend.simulateContextLoss()
        assertEquals(0, lost)
    }

    @Test
    fun `works without callbacks`() {
        val backend = FakeRenderBackend()
        val watch = watchContextLoss(backend)
        backend.simulateContextLoss()
        assertTrue(watch.lost)
    }
}

class RenderGateTest {
    private val scheduler = TickScheduler()

    @Test
    fun `is open at first`() {
        assertFalse(RenderGate(scheduler).blocked)
    }

    @Test
    fun `blocks until the work is done`() {
        val gate = RenderGate(scheduler)
        var finish: () -> Unit = {}
        gate.hold(1000) { done -> finish = done }
        assertTrue(gate.blocked)
        finish()
        assertFalse(gate.blocked)
        // the time limit is gone with it
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `opens when the work fails`() {
        val gate = RenderGate(scheduler)
        assertFailsWith<IllegalStateException> { gate.hold(1000) { error("compile failed") } }
        assertFalse(gate.blocked)
    }

    @Test
    fun `opens at the latest after the time limit`() {
        val gate = RenderGate(scheduler)
        gate.hold(2500) { }
        scheduler.advance(2499)
        assertTrue(gate.blocked)
        scheduler.advance(2)
        assertFalse(gate.blocked)
    }

    @Test
    fun `a newer hold replaces an older one - the old work does not open the gate`() {
        val gate = RenderGate(scheduler)
        var finishOld: () -> Unit = {}
        gate.hold(1000) { done -> finishOld = done }
        gate.hold(1000) { }
        finishOld()
        assertTrue(gate.blocked)
        // and the old time limit does not open it either
        scheduler.advance(999)
        assertTrue(gate.blocked)
        scheduler.advance(2)
        assertFalse(gate.blocked)
    }

    @Test
    fun `stays open without work`() {
        val gate = RenderGate(scheduler)
        gate.hold(1000, null)
        assertFalse(gate.blocked)
    }

    @Test
    fun `opens at once when the work is done before hold returns`() {
        val gate = RenderGate(scheduler)
        gate.hold(1000) { done -> done() }
        assertFalse(gate.blocked)
    }
}

class RestoreWatchdogTest {
    private val scheduler = TickScheduler()

    @Test
    fun `fires once when the restore does not arrive in time`() {
        var fired = 0
        val watchdog = RestoreWatchdog(scheduler, timeoutMs = 8000, onTimeout = { fired++ })
        watchdog.start()
        scheduler.advance(7999)
        assertEquals(0, fired)
        scheduler.advance(2)
        assertEquals(1, fired)
        scheduler.advance(60_000)
        assertEquals(1, fired)
    }

    @Test
    fun `does not fire after cancel - the context came back`() {
        var fired = 0
        val watchdog = RestoreWatchdog(scheduler, timeoutMs = 8000, onTimeout = { fired++ })
        watchdog.start()
        scheduler.advance(5000)
        watchdog.cancel()
        scheduler.advance(60_000)
        assertEquals(0, fired)
    }

    @Test
    fun `a second start restarts the countdown instead of stacking timers`() {
        var fired = 0
        val watchdog = RestoreWatchdog(scheduler, timeoutMs = 8000, onTimeout = { fired++ })
        watchdog.start()
        scheduler.advance(6000)
        watchdog.start()
        scheduler.advance(6000)
        assertEquals(0, fired)
        scheduler.advance(2001)
        assertEquals(1, fired)
    }

    @Test
    fun `waits about 8 s by default`() {
        var fired = 0
        RestoreWatchdog(scheduler, onTimeout = { fired++ }).start()
        scheduler.advance(7999)
        assertEquals(0, fired)
        scheduler.advance(2)
        assertEquals(1, fired)
    }
}

class ErrorReporterTest {
    private class Entry(
        val message: String,
        val error: Throwable,
    )

    @Test
    fun `logs the first error at once`() {
        val entries = ArrayList<Entry>()
        val report = ErrorReporter(log = { m, e -> entries.add(Entry(m, e)) }, now = { 0 })
        val error = IllegalStateException("x")
        report("frame", error)
        assertEquals(1, entries.size)
        assertTrue(entries[0].message.contains("frame"))
        assertSame(error, entries[0].error)
    }

    @Test
    fun `does not flood the console when the same error repeats every frame`() {
        var logged = 0
        var ms = 0L
        val report = ErrorReporter(log = { _, _ -> logged++ }, intervalMs = 5000, now = { ms })
        repeat(300) {
            ms += 16
            report("frame", IllegalStateException("x"))
        }
        assertEquals(1, logged)
        ms += 5000
        report("frame", IllegalStateException("x"))
        assertEquals(2, logged)
    }

    @Test
    fun `logs a different place at once`() {
        var logged = 0
        val report = ErrorReporter(log = { _, _ -> logged++ }, now = { 0 })
        report("frame", IllegalStateException("x"))
        report("render", IllegalStateException("y"))
        assertEquals(2, logged)
    }

    @Test
    fun `reports with the clock of the platform by default`() {
        var logged = 0
        val report = ErrorReporter(log = { _, _ -> logged++ })
        report("frame", IllegalStateException("x"))
        report("frame", IllegalStateException("x"))
        assertEquals(1, logged)
    }
}

class ReleaseNowTest {
    @Test
    fun `disposes the object and tolerates a missing one`() {
        val obj = Disposable()
        releaseNow(obj)
        assertEquals(1, obj.disposeCount)
        releaseNow(null)
    }
}

class GpuEpochTest {
    @Test
    fun `disposes objects as usual as long as no context was ever lost`() {
        val gpu = GpuEpoch()
        val obj = Disposable()
        gpu.release(obj)
        assertEquals(1, obj.disposeCount)
    }

    @Test
    fun `only forgets objects that lived through a context loss - their handles are stale`() {
        val gpu = GpuEpoch()
        val old = Disposable()
        gpu.contextLost(listOf(old))
        gpu.contextRestored()
        gpu.release(old)
        assertEquals(0, old.disposeCount)
        assertTrue(gpu.isStale(old))
    }

    @Test
    fun `disposes objects that were built after the loss normally once the context is back`() {
        val gpu = GpuEpoch()
        val old = Disposable()
        gpu.contextLost(listOf(old))
        gpu.contextRestored()
        val fresh = Disposable()
        gpu.release(fresh)
        assertEquals(1, fresh.disposeCount)
        assertFalse(gpu.isStale(fresh))
    }

    @Test
    fun `makes no GPU calls at all while the context is lost`() {
        val gpu = GpuEpoch()
        gpu.contextLost()
        val built = Disposable()
        gpu.release(built)
        assertEquals(0, built.disposeCount)
        assertTrue(gpu.lost)
        gpu.contextRestored()
        assertFalse(gpu.lost)
    }

    @Test
    fun `marks objects again at a second loss - they may have been uploaded again`() {
        val gpu = GpuEpoch()
        val obj = Disposable()
        gpu.contextLost()
        gpu.contextRestored()
        gpu.contextLost(listOf(obj))
        gpu.contextRestored()
        gpu.release(obj)
        assertEquals(0, obj.disposeCount)
    }

    @Test
    fun `tolerates a missing object`() {
        val gpu = GpuEpoch()
        gpu.release(null)
        gpu.contextLost(listOf(null))
        gpu.contextRestored()
        gpu.release(null)
        assertFalse(gpu.lost)
    }
}

class TickSchedulerTest {
    @Test
    fun `runs tasks when their time has come and not before`() {
        val scheduler = TickScheduler()
        val ran = ArrayList<String>()
        scheduler.schedule(100) { ran.add("a") }
        scheduler.schedule(50) { ran.add("b") }
        scheduler.advance(49)
        assertEquals(emptyList(), ran)
        scheduler.advance(1)
        assertEquals(listOf("b"), ran)
        scheduler.advance(50)
        assertEquals(listOf("b", "a"), ran)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `a cancelled task does not run`() {
        val scheduler = TickScheduler()
        var ran = 0
        val task = scheduler.schedule(10) { ran++ }
        task.cancel()
        scheduler.advance(100)
        assertEquals(0, ran)
    }

    @Test
    fun `a task may schedule another one`() {
        val scheduler = TickScheduler()
        var ran = 0
        scheduler.schedule(10) { scheduler.schedule(10) { ran++ } }
        scheduler.advance(10)
        assertEquals(0, ran)
        scheduler.advance(10)
        assertEquals(1, ran)
    }

    @Test
    fun `tasks due in one step run in due order and ties in scheduling order`() {
        val scheduler = TickScheduler()
        val ran = ArrayList<String>()
        scheduler.schedule(30) { ran.add("c") }
        scheduler.schedule(10) { ran.add("a1") }
        scheduler.schedule(10) { ran.add("a2") }
        scheduler.schedule(20) { ran.add("b") }
        scheduler.advance(100)
        assertEquals(listOf("a1", "a2", "b", "c"), ran)
    }
}
