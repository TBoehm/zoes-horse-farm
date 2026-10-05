package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A host that behaves as the power levers allow: a 60 Hz display link that is started and stopped by
// the engine's demandListener and delivers frames ONLY while it is on. Everything that has to work
// "while the display link is off" is tested through it.

private class HostModel(
    private val rig: EngineRig,
) {
    var linkOn = false
    var delivered = 0

    init {
        rig.engine.demandListener = DemandListener { linkOn = it }
    }

    /** [seconds] of display refreshes at 60 Hz; the link delivers only while it is on. */
    fun advance(seconds: Double) {
        repeat((seconds * 60).toInt()) {
            rig.time += DT
            if (linkOn) {
                delivered++
                rig.engine.frame(rig.time)
            }
        }
    }
}

class EngineHostTest {
    @Test
    fun theRestoreWatchdogFiresEvenThoughThePausedRideStoppedTheDisplayLink() {
        val rig = EngineRig()
        val host = HostModel(rig)
        rig.run()
        host.advance(1.0)
        rig.engine.setPaused(true)
        rig.fake.simulateContextLoss()
        var timeouts = 0
        rig.engine.startRestoreWatchdog { timeouts++ }
        host.advance(7.0)
        assertEquals(0, timeouts)
        assertTrue(host.linkOn) // the countdown needs frames
        host.advance(2.0)
        assertEquals(1, timeouts)
        host.advance(1.0)
        assertFalse(host.linkOn) // nothing left to wait for: the link stops again
    }

    @Test
    fun aCancelledWatchdogLetsThePausedRideStopTheLinkAgain() {
        val rig = EngineRig()
        val host = HostModel(rig)
        rig.run()
        host.advance(0.5)
        rig.engine.setPaused(true)
        rig.engine.startRestoreWatchdog { }
        host.advance(0.5)
        assertTrue(host.linkOn)
        rig.engine.cancelRestoreWatchdog()
        host.advance(0.5)
        assertFalse(host.linkOn)
    }

    @Test
    fun aResizeWhilePausedWakesTheStoppedLinkAndIsDrawn() {
        val rig = EngineRig()
        val host = HostModel(rig)
        rig.run()
        host.advance(0.5)
        rig.engine.setPaused(true)
        host.advance(0.5)
        assertFalse(host.linkOn)
        val drawn = rig.fake.renderCount
        rig.engine.setViewSize(400.0, 800.0)
        assertTrue(host.linkOn)
        host.advance(0.5)
        assertEquals(drawn + 1, rig.fake.renderCount)
        assertFalse(host.linkOn)
    }

    @Test
    fun aPauseDoesNotCarryOverIntoTheNextRide() {
        val rig = EngineRig()
        val host = HostModel(rig)
        rig.run()
        host.advance(0.5)
        rig.engine.setPaused(true)
        host.advance(0.5)
        assertFalse(host.linkOn)
        rig.engine.run(null)
        assertFalse(rig.engine.paused)
        rig.run() // the next ride
        assertTrue(host.linkOn)
        val drawn = rig.fake.renderCount
        host.advance(1.0)
        assertEquals(drawn + 60, rig.fake.renderCount)
    }

    @Test
    fun startingARunEndsAPauseWithoutStoppingFirst() {
        val rig = EngineRig()
        val host = HostModel(rig)
        rig.run()
        rig.engine.setPaused(true)
        rig.run() // replaced without run(null)
        assertFalse(rig.engine.paused)
        assertTrue(host.linkOn)
    }

    @Test
    fun aLevelChangeWhilePausedFinishesThoughTheLinkWasStopped() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        val host = HostModel(rig)
        rig.run()
        host.advance(1.0)
        rig.engine.setPaused(true)
        host.advance(1.0)
        assertFalse(host.linkOn)
        rig.settings.setGraphicsLevel(GraphicsLevel.LOW)
        assertTrue(host.linkOn)
        host.advance(5.0)
        assertFalse(rig.engine.settling)
        assertFalse(rig.fake.shadowsEnabled)
        assertFalse(host.linkOn)
    }

    @Test
    fun aCompileHoldKeepsTheLinkRunningUntilItsTimerEndsIt() {
        val rig = EngineRig(deferCompile = true)
        val host = HostModel(rig)
        rig.run()
        rig.engine.setPaused(true)
        host.advance(1.0)
        assertTrue(host.linkOn)
        host.advance(3.0) // the 2.5 s hold ends by itself
        assertFalse(rig.engine.settling)
        assertFalse(host.linkOn)
    }

    @Test
    fun aRestoredDeviceWhilePausedIsDrawnOnceMore() {
        val rig = EngineRig()
        val host = HostModel(rig)
        rig.run()
        host.advance(0.5)
        rig.engine.setPaused(true)
        host.advance(0.5)
        rig.fake.simulateContextLoss()
        rig.fake.simulateContextRestore()
        val drawn = rig.fake.renderCount
        host.advance(1.0)
        assertTrue(rig.fake.renderCount > drawn)
    }
}
