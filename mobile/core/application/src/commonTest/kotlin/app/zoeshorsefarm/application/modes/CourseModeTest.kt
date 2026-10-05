package app.zoeshorsefarm.application.modes

import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.application.testing.FakeHost
import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.course.Course
import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.course.REFUSAL_FAULTS
import app.zoeshorsefarm.domain.course.RunPhase
import app.zoeshorsefarm.domain.course.toCentiseconds
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.RefusalReason
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CourseModeTest {
    private val course = COURSES[0]

    private class Setup(
        val mode: CourseMode,
        val host: FakeHost,
    )

    private fun setup() = Setup(CourseMode(course.id), FakeHost())

    private class Crossing(
        val prev: Vec2,
        val next: Vec2,
    )

    /** Crosses a line in its direction through the middle; returns prev/next horse positions. */
    private fun across(line: Line): Crossing {
        val mx = (line.a.x + line.b.x) / 2
        val mz = (line.a.z + line.b.z) / 2
        return Crossing(Vec2(mx - line.dir.x, mz - line.dir.z), Vec2(mx + line.dir.x, mz + line.dir.z))
    }

    /** A frame without a known heading (a crossing then never counts as backwards). */
    private fun frame(
        horse: Vec2,
        prev: Vec2,
        heading: Double = Double.NaN,
    ) = RideFrame(Pose(horse.x, horse.z, heading), prev.x, prev.z)

    /** Crosses the start line through the mode, like the ride session does; the ride is on after it. */
    private fun startRide(
        mode: CourseMode,
        host: FakeHost = FakeHost(),
        ridden: Course = course,
    ) {
        val c = across(ridden.start)
        mode.update(0.0, frame(c.next, c.prev), host)
    }

    private fun landed(
        id: String,
        dir: Int = 1,
        knocked: Boolean = false,
    ) = listOf(SimEvent.Landed(id, dir, knocked))

    private val firstId = course.obstacles[0].elements[0].id

    @Test
    fun exposesTheCourseAsDataLinesWithLabelKeysQuitTarget() {
        val s = setup()
        assertEquals(RideModeId.COURSE, s.mode.id)
        assertSame(course.obstacles, s.mode.obstacles)
        assertEquals(
            CourseLines(
                course.start,
                course.finish,
                LineLabelKeys(start = "prestart.legendStart", finish = "prestart.legendFinish"),
            ),
            s.mode.lines,
        )
        assertEquals("courseSelect", s.mode.quitScreen)
        assertEquals("pause.toSelect", s.mode.quitLabelKey)
        assertTrue(s.mode.flags)
        assertEquals(RideStart(course.startPose), s.mode.startPose())
    }

    @Test
    fun backingOverTheStartLineReinBackDoesNotStartTheRideRidingOverItDoes() {
        val s = setup()
        val c = across(course.start)
        // facing against the riding direction, so the crossing in riding direction is backwards
        val heading = atan2(-course.start.dir.x, -course.start.dir.z)
        s.mode.update(0.1, frame(c.next, c.prev, heading), s.host)
        assertEquals(RunPhase.PRESTART, s.mode.hudModel().phase)
        s.mode.update(0.1, frame(c.next, c.prev, heading + PI), s.host)
        assertEquals(RunPhase.RIDING, s.mode.hudModel().phase)
    }

    @Test
    fun backingOverTheFinishLineDoesNotEndTheRide() {
        val s = setup()
        startRide(s.mode, s.host)
        val c = across(course.finish)
        val heading = atan2(-course.finish.dir.x, -course.finish.dir.z)
        // all obstacles are still open, so a forward crossing would only show the missing hint;
        // a backward one must not even do that
        s.mode.update(0.1, frame(c.next, c.prev, heading), s.host)
        assertNull(s.mode.hudModel().missingHint)
    }

    @Test
    fun hasAPlainDataHudModelBeforeTheStart() {
        val hud = assertNotNull(setup().mode.hudModel())
        assertEquals(RunPhase.PRESTART, hud.phase)
        assertEquals(0, hud.timeCs)
        assertEquals(course.allowedTimeS, hud.allowedS)
        assertEquals(0, hud.faults)
        assertFalse(hud.overTime)
        assertEquals("1", hud.nextLabel)
        assertNull(hud.missingHint)
    }

    @Test
    fun theHudModelIsOneReusedObject() {
        val mode = setup().mode
        assertSame(mode.hudModel(), mode.hudModel())
    }

    @Test
    fun flagsTheHudWhenTheAllowedTimeIsExceeded() {
        val s = setup()
        val start = across(course.start)
        s.mode.update(0.1, frame(start.next, start.prev), s.host)
        s.mode.update(course.allowedTimeS - 1.0, frame(start.next, start.next), s.host)
        assertFalse(s.mode.hudModel().overTime)
        s.mode.update(2.0, frame(start.next, start.next), s.host)
        assertTrue(s.mode.hudModel().overTime)
    }

    @Test
    fun providesTheTimeInHundredthsTruncatedAndFlagsOverTimeByTheSameRule() {
        val s = setup()
        val start = across(course.start)
        s.mode.update(0.1, frame(start.next, start.prev), s.host)
        s.mode.update(1.239, frame(start.next, start.next), s.host)
        assertEquals(toCentiseconds(1239.0), s.mode.hudModel().timeCs)
        assertEquals(123, s.mode.hudModel().timeCs)
        // exactly the allowed time is not over; one hundredth later is
        s.mode.update(course.allowedTimeS - 1.239, frame(start.next, start.next), s.host)
        assertEquals(false, s.mode.hudModel().overTime)
        s.mode.update(0.0101, frame(start.next, start.next), s.host)
        assertEquals(true, s.mode.hudModel().overTime)
    }

    @Test
    fun updatesTheHudModelDuringTheRideAndResetsItOnRestart() {
        val s = setup()
        val start = across(course.start)
        s.mode.update(0.5, frame(start.next, start.prev), s.host)
        assertEquals(RunPhase.RIDING, s.mode.hudModel().phase)
        s.mode.update(0.5, frame(start.next, start.next), s.host)
        assertEquals(50, s.mode.hudModel().timeCs)

        s.mode.onRestart()
        assertEquals(RunPhase.PRESTART, s.mode.hudModel().phase)
        assertEquals(0, s.mode.hudModel().timeCs)
    }

    @Test
    fun highlightsTheObstacleThatIsDueAndMarksTheFinishAfterTheLastOne() {
        val s = setup()
        val highlight = assertNotNull(s.mode.highlight)
        assertEquals(firstId, highlight.elementId)
        assertEquals(course.obstacles[0].number, highlight.number)
        assertFalse(s.mode.finishMarked)
        startRide(s.mode)
        for (o in course.obstacles) for (el in o.elements) s.mode.onEvents(landed(el.id), s.host)
        assertNull(s.mode.highlight)
        assertTrue(s.mode.finishMarked)
    }

    @Test
    fun showsTheNextObstacleNumberWithBForTheSecondPartOfACombination() {
        val withCombination = COURSES.first { c -> c.obstacles.any { it.elements.size > 1 } }
        val mode = CourseMode(withCombination.id)
        val host = FakeHost()
        val combo = withCombination.obstacles.first { it.elements.size > 1 }
        startRide(mode, host, withCombination)
        for (o in withCombination.obstacles) {
            if (o === combo) break
            for (el in o.elements) mode.onEvents(landed(el.id), host)
        }
        assertEquals("${combo.number}", mode.hudModel().nextLabel)
        mode.onEvents(landed(combo.elements[0].id), host)
        assertEquals("${combo.number}b", mode.hudModel().nextLabel)
    }

    @Test
    fun countsAKnockdownAsAFaultAndAsksForFeedback() {
        val s = setup()
        startRide(s.mode)
        s.mode.onEvents(landed(firstId, knocked = true), s.host)
        assertTrue("feedback.knockdown" in s.host.feedback)
        assertTrue(s.mode.hudModel().faults > 0)
    }

    @Test
    fun givesKnockdownFeedbackOnlyForScoredKnockdowns() {
        val s = setup()
        startRide(s.mode)
        // second obstacle is not due yet: this landing is not scored
        val wrong = course.obstacles[1].elements[0].id
        s.mode.onEvents(landed(wrong, knocked = true), s.host)
        assertFalse("feedback.knockdown" in s.host.feedback)
        assertEquals(0, s.mode.hudModel().faults)
    }

    @Test
    fun saysWrongObstacleForAnUnscoredLandingDuringTheRide() {
        val s = setup()
        startRide(s.mode)
        val wrong = course.obstacles[1].elements[0].id
        s.mode.onEvents(landed(wrong), s.host)
        assertEquals(listOf("feedback.wrongObstacle"), s.host.feedback)
        // the right obstacle in the wrong direction is not scored either
        s.mode.onEvents(landed(firstId, dir = -1), s.host)
        assertEquals(listOf("feedback.wrongObstacle", "feedback.wrongObstacle"), s.host.feedback)
    }

    @Test
    fun staysQuietForACleanScoredJumpAndForJumpsBeforeTheStart() {
        val quiet = setup()
        quiet.mode.onEvents(landed(course.obstacles[1].elements[0].id), quiet.host)
        assertEquals(emptyList(), quiet.host.feedback)
        val s = setup()
        startRide(s.mode)
        s.mode.onEvents(landed(firstId), s.host)
        assertEquals(emptyList(), s.host.feedback)
    }

    @Test
    fun asksForADelayedRebuildOfKnockedRailsThatAreNotScored() {
        val s = setup()
        // ride not started yet: the knockdown is not scored, rails come back after a delay
        s.mode.onEvents(landed(firstId, knocked = true), s.host)
        assertEquals(1, s.host.rebuildIn.size)
        assertEquals(firstId, s.host.rebuildIn[0].first)
        assertEquals(emptyList(), s.host.feedback)
    }

    @Test
    fun cancelsAPendingUnscoredRebuildWhenTheSameElementIsKnockedInAScoredJump() {
        val s = setup()
        // before the start the knockdown is unscored: a rebuild is scheduled
        s.mode.onEvents(landed(firstId, knocked = true), s.host)
        assertEquals(1, s.host.rebuildIn.size)
        startRide(s.mode)
        s.mode.onEvents(landed(firstId, knocked = true), s.host)
        assertEquals(listOf(firstId), s.host.cancelRebuild)
        assertTrue("feedback.knockdown" in s.host.feedback)
    }

    @Test
    fun doesNotCancelRebuildsForCleanOrUnscoredLandings() {
        val s = setup()
        startRide(s.mode)
        val second = course.obstacles[1].elements[0].id
        s.mode.onEvents(landed(firstId), s.host)
        s.mode.onEvents(landed(second, dir = -1, knocked = true), s.host)
        assertEquals(emptyList(), s.host.cancelRebuild)
    }

    @Test
    fun countsARefusalAndGivesFeedback() {
        val s = setup()
        startRide(s.mode)
        s.mode.onEvents(listOf(SimEvent.Refusal(firstId, 1, RefusalReason.GAIT)), s.host)
        assertEquals(listOf("feedback.refusal"), s.host.feedback)
        assertEquals(REFUSAL_FAULTS, s.mode.hudModel().faults)
    }

    @Test
    fun reportsTheFinishedRideWithTheResultAndTheResultsScreenWithoutSaving() {
        val s = setup()
        startRide(s.mode)
        for (o in course.obstacles) s.mode.onEvents(landed(o.elements[0].id), s.host)
        val finish = across(course.finish)
        val out = assertNotNull(s.mode.update(0.1, frame(finish.next, finish.prev), s.host))
        assertEquals("results", out.screen)
        assertEquals(course.id, out.result.courseId)
        assertEquals(0, out.result.faults.knockdowns)
        assertEquals(0, out.result.faults.refusals)
        assertEquals(0, out.result.faults.timeFaults)
        assertEquals(0, out.result.faults.total)
        assertEquals(course.id, out.courseId)
    }

    @Test
    fun doesNotReportAFinishWhileRiding() {
        val s = setup()
        val start = across(course.start)
        assertNull(s.mode.update(0.1, frame(start.next, start.prev), s.host))
    }

    @Test
    fun showsTheJumpAidAtTheCurrentElementOnlyWhenEnabled() {
        val s = setup()
        assertNull(s.mode.aidTarget(null, Settings(aidCourse = false)))
        startRide(s.mode)
        assertEquals(AidTarget(firstId, 1), s.mode.aidTarget(null, Settings(aidCourse = true)))
        assertNull(s.mode.aidTarget(null, Settings(aidCourse = false)))
    }

    @Test
    fun anUnknownCourseFallsBackToTheFirstCourse() {
        assertSame(COURSES[0].obstacles, CourseMode(null).obstacles)
        assertSame(COURSES[0].obstacles, CourseMode(99).obstacles)
    }
}
