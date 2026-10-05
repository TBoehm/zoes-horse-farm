package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.view3d.assertClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The battery and thermal levers of the frame loop: a paused ride draws one frame and then stops
// drawing; the optional 30 fps cap (default off).

class EnginePowerTest {
    @Test
    fun aPausedRideDrawsOneFrameAndThenStopsDrawing() {
        val rig = EngineRig()
        rig.run()
        rig.frames(3)
        rig.engine.setPaused(true)
        rig.frames(10)
        assertEquals(4, rig.fake.renderCount) // three running frames and the one of the pause menu
        assertEquals(13, rig.calls.size) // the screen's own frame still runs (cheap)
    }

    @Test
    fun drawingStartsAgainWhenTheRideContinues() {
        val rig = EngineRig()
        rig.run()
        rig.frames(2)
        rig.engine.setPaused(true)
        rig.frames(5)
        val drawn = rig.fake.renderCount
        rig.engine.setPaused(false)
        rig.frames(3)
        assertEquals(drawn + 3, rig.fake.renderCount)
    }

    @Test
    fun theFirstFrameAfterAPauseHasNoHiddenTime() {
        val rig = EngineRig()
        rig.run()
        rig.frames(2)
        rig.engine.setPaused(true)
        rig.frames(2)
        rig.time += 600.0 // the host stopped its display link for ten minutes
        rig.engine.setPaused(false)
        rig.frames(1)
        assertClose(1.0 / 60, rig.calls.last()[0], 9)
        assertClose(1.0 / 60, rig.calls.last()[1], 9)
    }

    @Test
    fun wantsNoFramesWhileThePauseIsDrawnAndTellsTheHostWhenItChanges() {
        val rig = EngineRig()
        val told = ArrayList<Boolean>()
        rig.engine.demandListener = DemandListener { told.add(it) }
        assertFalse(rig.engine.wantsFrames) // nothing runs
        rig.run()
        assertTrue(rig.engine.wantsFrames)
        rig.frames(2)
        rig.engine.setPaused(true)
        assertTrue(rig.engine.wantsFrames) // the frame of the pause menu is still to be drawn
        rig.frames(1)
        assertFalse(rig.engine.wantsFrames)
        rig.engine.setPaused(false)
        assertTrue(rig.engine.wantsFrames)
        rig.engine.run(null)
        assertFalse(rig.engine.wantsFrames)
        assertEquals(listOf(true, false, true, false), told)
    }

    @Test
    fun aVisibleChangeWhilePausedDrawsOnceMore() {
        val rig = EngineRig()
        rig.run()
        rig.frames(2)
        rig.engine.setPaused(true)
        rig.frames(3)
        val drawn = rig.fake.renderCount
        rig.engine.setViewSize(400.0, 800.0) // rotation behind the pause menu
        rig.frames(3)
        assertEquals(drawn + 1, rig.fake.renderCount)
        rig.engine.requestRedraw()
        rig.frames(3)
        assertEquals(drawn + 2, rig.fake.renderCount)
    }

    @Test
    fun aLevelChangeWhilePausedKeepsDrawingUntilItIsApplied() {
        val rig = EngineRig(GraphicsLevel.HIGH)
        rig.run()
        rig.frames(3)
        rig.engine.setPaused(true)
        rig.frames(3)
        val drawn = rig.fake.renderCount
        rig.settings.setGraphicsLevel(GraphicsLevel.LOW)
        assertTrue(rig.engine.wantsFrames)
        var guardFrames = 0
        while (rig.engine.settling && guardFrames++ < 600) rig.frames(1)
        assertTrue(rig.fake.renderCount > drawn)
        rig.frames(5)
        val settled = rig.fake.renderCount
        rig.frames(5)
        assertEquals(settled, rig.fake.renderCount)
        assertFalse(rig.engine.wantsFrames)
    }

    @Test
    fun pausingTwiceChangesNothing() {
        val rig = EngineRig()
        rig.run()
        rig.frames(2)
        rig.engine.setPaused(true)
        rig.frames(2)
        val drawn = rig.fake.renderCount
        rig.engine.setPaused(true)
        rig.frames(2)
        assertEquals(drawn, rig.fake.renderCount)
    }

    @Test
    fun drawsEveryFrameByDefault() {
        val rig = EngineRig()
        rig.run()
        rig.frames(60)
        assertEquals(60, rig.fake.renderCount)
        assertFalse(rig.engine.capTo30Fps)
    }

    @Test
    fun theThirtyFpsCapDrawsEverySecondFrameOfASixtyHertzDisplay() {
        val rig = EngineRig(capTo30Fps = true)
        rig.run()
        rig.frames(60)
        assertEquals(30, rig.fake.renderCount)
        assertEquals(30, rig.calls.size)
        for (i in 1 until rig.calls.size) assertClose(1.0 / 30, rig.calls[i][1], 6)
    }

    @Test
    fun theThirtyFpsCapAlsoWorksOnFastDisplays() {
        for (hz in listOf(90, 120, 144)) {
            val rig = EngineRig(capTo30Fps = true)
            rig.run()
            rig.frames(hz * 2, step = 1.0 / hz)
            assertTrue(rig.fake.renderCount in 55..62, "$hz Hz drew ${rig.fake.renderCount} frames in two seconds")
        }
    }

    @Test
    fun theCapCanBeSwitchedOnAndOffWhileRiding() {
        val rig = EngineRig()
        rig.run()
        rig.frames(10)
        rig.engine.setCapTo30Fps(true)
        rig.frames(10)
        assertEquals(15, rig.fake.renderCount)
        rig.engine.setCapTo30Fps(false)
        rig.frames(10)
        assertEquals(25, rig.fake.renderCount)
    }

    @Test
    fun cappedFramesStillAdvanceTheEnginesTimer() {
        val rig = EngineRig(capTo30Fps = true, deferCompile = true)
        rig.run()
        rig.frames(60 * 3)
        assertTrue(rig.fake.renderCount > 0) // the compile hold ended after 2.5 s of real time
    }
}
