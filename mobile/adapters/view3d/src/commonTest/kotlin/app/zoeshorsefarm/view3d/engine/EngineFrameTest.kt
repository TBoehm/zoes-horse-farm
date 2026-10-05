package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.view3d.assertClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The frame loop: time steps, drawing, resize, the guard around every step, the render gate.

class EngineFrameTest {
    @Test
    fun doesNothingWithoutAHandler() {
        val rig = EngineRig()
        rig.frames(3)
        assertEquals(0, rig.fake.renderCount)
        assertFalse(rig.engine.running)
    }

    @Test
    fun drawsOnceForEveryFrameAndCallsTheHandler() {
        val rig = EngineRig()
        rig.run()
        rig.frames(5)
        assertEquals(5, rig.fake.renderCount)
        assertEquals(5, rig.calls.size)
    }

    @Test
    fun theFirstFrameAssumesSixtyHertz() {
        val rig = EngineRig()
        rig.run()
        rig.time = 1234.5
        rig.engine.frame(rig.time)
        assertClose(1.0 / 60, rig.calls[0][0], 9)
        assertClose(1.0 / 60, rig.calls[0][1], 9)
    }

    @Test
    fun givesTheSimulationAClampedStepAndTheGovernorTheRealOne() {
        val rig = EngineRig()
        rig.run()
        rig.frames(1)
        rig.frames(1, step = 0.5)
        assertEquals(TUNING.sim.maxDt, rig.calls[1][0])
        assertClose(0.5, rig.calls[1][1], 9)
    }

    @Test
    fun aNewRunStartsWithoutAPredecessorFrame() {
        val rig = EngineRig()
        rig.run()
        rig.frames(3)
        rig.engine.run(null)
        rig.run()
        rig.frames(1, step = 2.0)
        assertClose(1.0 / 60, rig.calls.last()[1], 9)
    }

    @Test
    fun stopsDrawingWhenTheLoopIsStopped() {
        val rig = EngineRig()
        rig.run()
        rig.frames(2)
        rig.engine.run(null)
        rig.frames(2)
        assertEquals(2, rig.fake.renderCount)
        assertFalse(rig.engine.running)
    }

    @Test
    fun fitsTheSurfaceAndTheCameraToTheViewWhenTheLoopStarts() {
        val rig = EngineRig(GraphicsLevel.MEDIUM)
        rig.run()
        val size = rig.fake.getSize(Vec2())
        assertEquals(800.0, size.x)
        assertEquals(400.0, size.y)
        assertClose(2.0, rig.engine.camera.aspect)
    }

    @Test
    fun followsAChangeOfTheViewAtOnce() {
        val rig = EngineRig()
        rig.run()
        rig.engine.setViewSize(300.0, 600.0)
        assertEquals(300.0, rig.fake.getSize(Vec2()).x)
        assertClose(0.5, rig.engine.camera.aspect)
        rig.engine.setViewSize(300.0, 600.0, devicePixelRatio = 3.0)
        assertEquals(3.0, rig.engine.sizing.view.devicePixelRatio)
    }

    @Test
    fun aFailingHandlerIsLoggedOnceAndTheFrameIsStillDrawn() {
        val rig = EngineRig()
        rig.engine.run { _, _ -> error("boom") }
        rig.frames(10)
        assertEquals(10, rig.fake.renderCount)
        assertEquals(1, rig.errors.size) // the same place is logged once per five seconds
        assertTrue(rig.errors[0].contains("frame"))
    }

    @Test
    fun theFirstCompileHoldsTheLoopUntilItIsDone() {
        val rig = EngineRig(deferCompile = true)
        rig.run()
        rig.frames(3)
        assertEquals(0, rig.fake.renderCount)
        assertEquals(0, rig.calls.size) // no hidden time passes
        assertTrue(rig.engine.settling)
        rig.backend.finishCompile()
        rig.frames(1)
        assertEquals(1, rig.fake.renderCount)
        assertEquals(1, rig.calls.size)
    }

    @Test
    fun theHoldEndsAfterTwoAndAHalfSecondsEvenIfTheCompileNeverReports() {
        val rig = EngineRig(deferCompile = true)
        rig.run()
        rig.frames(120) // two seconds
        assertEquals(0, rig.fake.renderCount)
        rig.frames(60)
        assertTrue(rig.fake.renderCount > 0)
        assertFalse(rig.engine.settling)
    }

    @Test
    fun aStageThatCompilesHoldsTheLoopWhileTheLastPictureStays() {
        val rig = EngineRig(GraphicsLevel.LOW)
        rig.run()
        rig.frames(2)
        rig.settings.setGraphicsLevel(GraphicsLevel.MEDIUM)
        rig.backend.deferCompile = true
        var guardFrames = 0
        while (rig.backend.pending.isEmpty() && guardFrames++ < 200) rig.frames(1)
        assertTrue(rig.backend.pending.isNotEmpty())
        val drawn = rig.fake.renderCount
        val called = rig.calls.size
        rig.frames(5)
        assertEquals(drawn, rig.fake.renderCount)
        assertEquals(called, rig.calls.size)
        rig.backend.finishCompile()
        rig.frames(1)
        assertTrue(rig.fake.renderCount > drawn)
    }
}
