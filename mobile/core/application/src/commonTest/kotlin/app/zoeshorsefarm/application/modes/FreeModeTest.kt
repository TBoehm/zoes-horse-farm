package app.zoeshorsefarm.application.modes

import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.application.testing.FakeHost
import app.zoeshorsefarm.domain.course.FREE_LAYOUT
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.RefusalReason
import app.zoeshorsefarm.domain.sim.SimApproach
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.TUNING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeModeTest {
    @Test
    fun isDomFreeDataNoHudNoLinesQuitsToTheMenu() {
        val mode = FreeMode()
        assertNull(mode.hudModel())
        assertNull(mode.lines)
        assertEquals("menu", mode.quitScreen)
        assertEquals("pause.toMenu", mode.quitLabelKey)
        assertNull(mode.highlight)
        assertFalse(mode.finishMarked)
        assertFalse(mode.flags)
        assertEquals(RideModeId.FREE, mode.id)
        assertEquals(FREE_LAYOUT.obstacles, mode.obstacles)
        assertEquals(RideStart(FREE_LAYOUT.startPose), mode.startPose())
    }

    @Test
    fun allowsRefusalsAtEveryElementInBothDirections() {
        val mode = FreeMode()
        assertTrue(mode.rules.canRefuse("f1", 1))
        assertTrue(mode.rules.canRefuse("f1", -1))
    }

    @Test
    fun takesTheRebuildDelayFromTheTuningValue() {
        val mode = FreeMode(TUNING.copy(rebuildDelayS = 9.0))
        val host = FakeHost()
        mode.onEvents(listOf(SimEvent.RailDown("f2", rail = 0, dir = 1)), host)
        assertEquals(listOf("f2" to 9.0), host.rebuildIn)
    }

    @Test
    fun rebuildsFallenRailsAfterTheDelayAndGivesFeedback() {
        val mode = FreeMode()
        val host = FakeHost()
        mode.onEvents(
            listOf(
                SimEvent.RailDown("f2", rail = 0, dir = 1),
                SimEvent.Refusal("f2", dir = 1, reason = RefusalReason.GAIT),
                SimEvent.Landed("f1", dir = 1, knocked = true),
                SimEvent.Landed("f3", dir = 1, knocked = false),
            ),
            host,
        )
        assertEquals(listOf("f2" to TUNING.rebuildDelayS), host.rebuildIn)
        assertEquals(listOf("feedback.refusal", "feedback.knockdown"), host.feedback)
    }

    @Test
    fun neverEndsTheRideByItself() {
        val mode = FreeMode()
        assertNull(mode.update(0.1, RideFrame(Pose(0.0, 0.0, 0.0)), FakeHost()))
    }

    @Test
    fun showsTheJumpAidAtTheApproachedElementOnlyWhenEnabled() {
        val mode = FreeMode()
        val approach = SimApproach("f4", dir = -1, distance = 5.0, angle = 0.0)
        assertEquals(AidTarget("f4", -1), mode.aidTarget(approach, Settings(aidFree = true)))
        assertNull(mode.aidTarget(approach, Settings(aidFree = false)))
        assertNull(mode.aidTarget(null, Settings(aidFree = true)))
    }
}
