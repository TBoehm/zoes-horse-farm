package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.RefusalType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The web app has no test for sequence-helper.js (it is test support used by the horse view
// tests); these cover the Kotlin port so the view tests can rely on it.
class SequenceHelperTest {
    @Test
    fun aRampIsLinearAndAConstantStaysConstant() {
        val state = stateAt(ScriptStep(1.0, Gait.WALK, speed = Ramp(0.0, 1.5), turn = Ramp.constant(0.7)), 0.5)
        assertEquals(Gait.WALK, state.gait)
        assertEquals(0.75, state.speed)
        assertEquals(0.7, state.turnRate)
        assertEquals(0.0, state.y)
        assertNull(state.jump)
        assertNull(state.hop)
        assertNull(state.refusal)
    }

    @Test
    fun aStepWithoutSpeedOrTurnHasZero() {
        val state = stateAt(ScriptStep(1.0), 0.3)
        assertEquals(Gait.HALT, state.gait)
        assertEquals(0.0, state.speed)
        assertEquals(0.0, state.turnRate)
    }

    @Test
    fun jumpHopAndRefusalAdvanceTheirOwnProgress() {
        val state =
            stateAt(
                ScriptStep(1.0, Gait.CANTER, jump = JumpPhase.FLIGHT, hop = true, refusal = RefusalType.RUNOUT),
                0.25,
            )
        assertEquals(JumpPhase.FLIGHT, assertNotNull(state.jump).phase)
        assertEquals(0.25, state.jump?.progress)
        assertEquals(0.25, state.hop?.progress)
        assertEquals(RefusalType.RUNOUT, state.refusal?.type)
        assertEquals(0.25, state.refusal?.progress)
    }

    @Test
    fun runScriptAdvancesEveryFrameWithTheMidFrameProgress() {
        val script = listOf(ScriptStep(0.1, Gait.WALK, speed = Ramp(0.0, 6.0)), ScriptStep(0.05, Gait.TROT))
        val speeds = ArrayList<Double>()
        val indices = ArrayList<Int>()
        var advanced = 0
        val frames =
            runScript(
                script,
                advance = { dt, _ ->
                    assertEquals(FRAME, dt)
                    advanced++
                },
                onFrame = { state, index, _ ->
                    speeds.add(state.speed)
                    indices.add(index)
                },
            )
        // 0.1 s = 6 frames, 0.05 s = 3 frames
        assertEquals(9, frames)
        assertEquals(9, advanced)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 1, 1, 1), indices)
        assertEquals(0.5, speeds.first(), 1e-12)
        assertEquals(5.5, speeds[5], 1e-12)
    }

    @Test
    fun aVeryShortStepStillRunsOneFrame() {
        assertEquals(1, runScript(listOf(ScriptStep(0.001)), { _, _ -> }, { _, _, _ -> }))
    }

    @Test
    fun theDeltaTrackerKeepsTheLargestFrameToFrameChangePerChannel() {
        val tracker = DeltaTracker()
        tracker.sample("a", 0.0, 0.0)
        tracker.sample("a", 1.0, 1.0 / 60, "step1")
        tracker.sample("a", 1.2, 2.0 / 60, "step2")
        tracker.sample("b", 5.0, 0.0)
        tracker.sample("b", 5.0, 0.5)
        assertTrue(abs(tracker.max.getValue("a") - 1.0) < 1e-12)
        assertEquals("step1@0.02s", tracker.where["a"])
        assertEquals(0.0, tracker.max.getValue("b"))
        val worst = tracker.worst()
        assertEquals("a", worst.name)
        assertEquals("step1@0.02s", worst.where)
        assertEquals("", tracker.worst { it == "zzz" }.name)
    }

    @Test
    fun theWebSequencesArePresentWithTheirDurations() {
        assertEquals(
            setOf("gaitLadder", "leadChange", "jump", "jumpTrot", "refusalStop", "fenceStop", "runout", "spot"),
            Sequences.all.keys,
        )
        assertEquals(13, Sequences.gaitLadder.size)
        assertCloseTo(19.0, Sequences.gaitLadder.sumOf { it.durationS }, 9)
        assertCloseTo(10.2, Sequences.leadChange.sumOf { it.durationS }, 9)
        assertEquals(
            listOf(JumpPhase.TAKEOFF, JumpPhase.FLIGHT, JumpPhase.LANDING),
            Sequences.jump.mapNotNull { it.jump },
        )
        assertEquals(RefusalType.STOP, Sequences.refusalStop[1].refusal)
        assertEquals(Gait.BACK, Sequences.spot[3].gait)
    }
}
