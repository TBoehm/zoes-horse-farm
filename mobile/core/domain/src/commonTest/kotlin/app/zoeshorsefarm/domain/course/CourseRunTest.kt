package app.zoeshorsefarm.domain.course

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Vec2
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseRunTest {
    private fun el(
        id: String,
        kind: ElementKind,
        x: Double,
        z: Double,
    ) = Element(id, kind, 0.7, if (kind == ElementKind.OXER) 0.8 else 0.0, x, z, 0.0)

    private val vertical = ElementKind.VERTICAL
    private val oxer = ElementKind.OXER

    // Test course: 1 vertical, 2 oxer, 3 combination (vertical / oxer), all jump direction +z
    private fun testCourse(obstacles: List<Obstacle>? = null) =
        Course(
            id = 9,
            allowedTimeS = 30,
            start = Line(Vec2(-3.0, -25.0), Vec2(3.0, -25.0), Vec2(0.0, 1.0)),
            finish = Line(Vec2(7.0, -20.0), Vec2(13.0, -20.0), Vec2(0.0, -1.0)),
            obstacles =
                obstacles
                    ?: listOf(
                        Obstacle(1, listOf(el("v1", vertical, 0.0, -10.0)), true),
                        Obstacle(2, listOf(el("o2", oxer, 0.0, 5.0)), true),
                        Obstacle(
                            3,
                            listOf(el("k3a", vertical, 0.0, 15.0), el("k3b", oxer, 0.0, 22.3)),
                            true,
                        ),
                    ),
        )

    private val startPrev = Vec2(0.0, -26.0)
    private val startNext = Vec2(0.0, -24.0)
    private val finishPrev = Vec2(10.0, -19.0)
    private val finishNext = Vec2(10.0, -21.0)

    private fun CourseRun.cross(
        prev: Vec2,
        next: Vec2,
        t: Double,
        backwards: Boolean = false,
    ) = onLineCross(prev.x, prev.z, next.x, next.z, t, backwards)

    private fun crossStart(
        run: CourseRun,
        t: Double = 1000.0,
    ) = run.cross(startPrev, startNext, t)

    private fun crossFinish(
        run: CourseRun,
        t: Double,
    ) = run.cross(finishPrev, finishNext, t)

    /** Crosses a line at its middle in its direction. */
    private fun crossLine(
        run: CourseRun,
        line: Line,
        t: Double,
    ): LineCross? {
        val mx = (line.a.x + line.b.x) / 2
        val mz = (line.a.z + line.b.z) / 2
        val dx = line.dir.x
        val dz = line.dir.z
        return run.onLineCross(mx - dx, mz - dz, mx + dx, mz + dz, t)
    }

    private fun riding(
        course: Course = testCourse(),
        t: Double = 1000.0,
    ): CourseRun {
        val run = CourseRun(course)
        crossStart(run, t)
        return run
    }

    /** Jumps obstacles 1 and 2 cleanly. */
    private fun toCombination(run: CourseRun): CourseRun {
        run.onLanded("v1", 1, false)
        run.onLanded("o2", 1, false)
        return run
    }

    private fun cleanRide(run: CourseRun): CourseRun {
        toCombination(run)
        run.onLanded("k3a", 1, false)
        run.onLanded("k3b", 1, false)
        return run
    }

    // Horse states around part b (at z = 22.3)
    private val horseApproachingB = Pose(0.0, 17.0, 0.0)
    private val horseAwayFromB = Pose(8.0, 8.0, PI)

    private val noFaults = Faults(knockdowns = 0, refusals = 0, timeFaults = 0, total = 0)

    // ---- Pre-start (rule 26) ----

    @Test
    fun beginsInPreStartWithoutTimeAndFaultsObstacle1Highlighted() {
        val run = CourseRun(testCourse())
        assertEquals(RunPhase.PRESTART, run.phase)
        assertEquals(0.0, run.timeMs)
        assertEquals(noFaults, run.faults)
        assertEquals(Highlight("v1", 1), run.highlight)
        assertEquals(CurrentTarget(0, 0, "v1"), run.current)
        assertEquals("1", run.nextLabel)
        assertFalse(run.finishMarked)
        assertNull(run.missingHint)
        assertNull(run.result)
    }

    @Test
    fun highlightsPartAWhenACombinationIsTheFirstObstacle() {
        val course = testCourse()
        val first = listOf(course.obstacles[2].copy(number = 1))
        assertEquals(Highlight("k3a", 1), CourseRun(testCourse(first)).highlight)
    }

    @Test
    fun timeDoesNotRunInPreStart() {
        val run = CourseRun(testCourse())
        run.update(Pose(0.0, -30.0, 0.0), 50000.0)
        assertEquals(0.0, run.timeMs)
        assertEquals(0, run.faults.timeFaults)
    }

    @Test
    fun noRefusalInPreStartNotEvenAtObstacle1() {
        val run = CourseRun(testCourse())
        assertFalse(run.rules.canRefuse("v1", 1))
        assertFalse(run.rules.canRefuse("o2", 1))
    }

    @Test
    fun jumpsInPreStartDoNotCountRebuildAFallenPoleAfterAbout3s() {
        val run = CourseRun(testCourse())
        assertEquals(Landing(scored = false, rebuildAfterS = 3.0), run.onLanded("v1", 1, true))
        assertEquals(Landing(scored = false, rebuildAfterS = null), run.onLanded("v1", 1, false))
        assertEquals(0, run.faults.total)
        assertEquals("v1", run.current?.elementId)
    }

    @Test
    fun refusalReportsInPreStartAreIgnored() {
        val run = CourseRun(testCourse())
        run.onRefusal("v1", 1)
        assertEquals(0, run.faults.refusals)
    }

    @Test
    fun theFinishLineDoesNothingInPreStart() {
        val run = CourseRun(testCourse())
        assertNull(crossFinish(run, 500.0))
        assertEquals(RunPhase.PRESTART, run.phase)
        assertNull(run.missingHint)
    }

    // ---- Start and finish line (rules 26, 27) ----

    @Test
    fun startLineInRidingDirectionStartsTheRideAndTheTime() {
        val run = CourseRun(testCourse())
        assertEquals(LineCross.START, crossStart(run, 2000.0))
        assertEquals(RunPhase.RIDING, run.phase)
        assertEquals(0.0, run.timeMs)
        run.update(Pose(0.0, -20.0, 0.0), 3234.0)
        assertEquals(1234.0, run.timeMs)
    }

    @Test
    fun crossingTheStartLineBackwardsReinBackDoesNotCount() {
        val run = CourseRun(testCourse())
        assertNull(run.cross(startPrev, startNext, 1000.0, backwards = true))
        assertEquals(RunPhase.PRESTART, run.phase)
        // the next forward crossing still starts the ride
        assertEquals(LineCross.START, run.cross(startPrev, startNext, 2000.0, backwards = false))
    }

    @Test
    fun crossingTheFinishLineBackwardsReinBackDoesNotCount() {
        val run = CourseRun(testCourse(emptyList()))
        crossStart(run, 1000.0)
        assertNull(run.cross(finishPrev, finishNext, 5000.0, backwards = true))
        assertEquals(RunPhase.RIDING, run.phase)
        assertNull(run.missingHint)
    }

    @Test
    fun startLineAgainstTheRidingDirectionDoesNotCount() {
        val run = CourseRun(testCourse())
        assertNull(run.cross(startNext, startPrev, 1000.0))
        assertEquals(RunPhase.PRESTART, run.phase)
    }

    @Test
    fun passingBesideTheLineDoesNotCount() {
        val run = CourseRun(testCourse())
        assertNull(run.cross(Vec2(4.0, -26.0), Vec2(4.0, -24.0), 1000.0))
        assertEquals(RunPhase.PRESTART, run.phase)
    }

    @Test
    fun movementWithoutCrossingDoesNotCount() {
        val run = CourseRun(testCourse())
        assertNull(run.cross(Vec2(0.0, -27.0), Vec2(0.0, -25.5), 1000.0))
        assertEquals(RunPhase.PRESTART, run.phase)
    }

    @Test
    fun startLineDuringTheRideDoesNothing() {
        val run = riding(testCourse(), 1000.0)
        assertNull(crossStart(run, 5000.0))
        run.update(Pose(0.0, -20.0, 0.0), 6000.0)
        assertEquals(5000.0, run.timeMs)
    }

    @Test
    fun finishLineAgainstTheRidingDirectionDoesNothing() {
        val run = cleanRide(riding())
        assertNull(run.cross(finishNext, finishPrev, 9000.0))
        assertEquals(RunPhase.RIDING, run.phase)
    }

    @Test
    fun finishLineAfterAllObstaclesEndsTheRideAndStopsTheTime() {
        val run = cleanRide(riding(testCourse(), 1000.0))
        assertEquals(LineCross.FINISH, crossFinish(run, 26271.0))
        assertEquals(RunPhase.FINISHED, run.phase)
        assertEquals(25271.0, run.timeMs)
        run.update(Pose(10.0, -25.0, PI), 90000.0)
        assertEquals(25271.0, run.timeMs)
        assertEquals(2527, assertNotNull(run.result).timeCs)
    }

    // ---- Order, highlighting, jump direction (rules 27-29) ----

    @Test
    fun aScoredJumpOverTheCurrentObstacleAdvancesToTheNext() {
        val run = riding()
        assertEquals(Landing(scored = true, rebuildAfterS = null), run.onLanded("v1", 1, false))
        assertEquals(Highlight("o2", 2), run.highlight)
        assertEquals("2", run.nextLabel)
    }

    @Test
    fun wrongObstacleNoScoringCorrectOneStaysHighlightedRebuildAfter3s() {
        val run = riding()
        assertEquals(Landing(scored = false, rebuildAfterS = 3.0), run.onLanded("o2", 1, true))
        assertEquals(Landing(scored = false, rebuildAfterS = null), run.onLanded("o2", 1, false))
        assertEquals(Highlight("v1", 1), run.highlight)
        assertEquals(0, run.faults.total)
    }

    @Test
    fun correctObstacleAgainstTheJumpDirectionCountsAsAWrongObstacle() {
        val run = riding()
        assertEquals(Landing(scored = false, rebuildAfterS = 3.0), run.onLanded("v1", -1, true))
        assertEquals("v1", run.current?.elementId)
        assertEquals(0, run.faults.total)
    }

    @Test
    fun unknownElementsAreIgnored() {
        val run = riding()
        assertEquals(Landing(scored = false, rebuildAfterS = 3.0), run.onLanded("zzz", 1, true))
        assertEquals("v1", run.current?.elementId)
    }

    @Test
    fun refusalOnlyAtTheCurrentObstacleInJumpDirectionDuringTheRide() {
        val run = riding()
        assertTrue(run.rules.canRefuse("v1", 1))
        assertFalse(run.rules.canRefuse("v1", -1))
        assertFalse(run.rules.canRefuse("o2", 1))
        assertFalse(run.rules.canRefuse("k3b", 1))
    }

    @Test
    fun afterTheLastObstacleNoHighlightFinishMarkedLabelFinish() {
        val run = cleanRide(riding())
        assertNull(run.current)
        assertNull(run.highlight)
        assertTrue(run.finishMarked)
        assertEquals(NEXT_LABEL_FINISH, run.nextLabel)
        assertEquals("finish", run.nextLabel)
        assertFalse(run.rules.canRefuse("k3b", 1))
    }

    // ---- Fault points (rule 32) ----

    @Test
    fun knockdownAtTheCurrentObstacle4FaultsCountsAsJumpedPoleStaysDown() {
        val run = riding()
        assertEquals(Landing(scored = true, rebuildAfterS = null), run.onLanded("v1", 1, true))
        assertEquals(Faults(knockdowns = 1, refusals = 0, timeFaults = 0, total = 4), run.faults)
        assertEquals("o2", run.current?.elementId)
        assertEquals(emptyList(), run.drainRebuilds())
    }

    @Test
    fun everyRefusalCounts4AlsoTheSecondAndFurtherOnesNoElimination() {
        val run = riding()
        run.onRefusal("v1", 1)
        run.onRefusal("v1", 1)
        run.onRefusal("v1", 1)
        assertEquals(Faults(knockdowns = 0, refusals = 3, timeFaults = 0, total = 12), run.faults)
        assertEquals(RunPhase.RIDING, run.phase)
        assertEquals("v1", run.current?.elementId)
        // a jump shortly after the refusal is scored normally
        assertTrue(run.onLanded("v1", 1, false).scored)
    }

    @Test
    fun refusalReportsAtOtherObstaclesAreIgnored() {
        val run = riding()
        run.onRefusal("o2", 1)
        run.onRefusal("v1", -1)
        assertEquals(0, run.faults.total)
    }

    @Test
    fun aPoleDownFromAScoredKnockdownIsNotRebuiltAfterAnotherJump() {
        val run = riding()
        run.onLanded("v1", 1, true)
        assertEquals(Landing(scored = false, rebuildAfterS = null), run.onLanded("v1", -1, true))
    }

    @Test
    fun theRebuildDelayOfUnscoredKnockdownsComesFromTheTuningValue() {
        val run = CourseRun(testCourse(), TUNING.copy(rebuildDelayS = 7.0))
        crossStart(run)
        assertEquals(Landing(scored = false, rebuildAfterS = 7.0), run.onLanded("v1", -1, true))
    }

    @Test
    fun overTimeFollowsTheTruncatedHundredthsLikeTheTimeFaults() {
        val run = riding(testCourse(), 0.0)
        assertFalse(run.overTime)
        run.update(Pose(0.0, 0.0, 0.0), 30000.0)
        assertFalse(run.overTime)
        run.update(Pose(0.0, 0.0, 0.0), 30009.0)
        assertFalse(run.overTime)
        run.update(Pose(0.0, 0.0, 0.0), 30010.0)
        assertTrue(run.overTime)
        assertEquals(1, run.faults.timeFaults)
    }

    @Test
    fun overTimeIsFalseBeforeTheStart() {
        assertFalse(CourseRun(testCourse()).overTime)
    }

    @Test
    fun timeFaultsRunAlongDuringTheRideAndGoIntoTheTotal() {
        val run = riding(testCourse(), 0.0)
        run.onLanded("v1", 1, true)
        run.update(Pose(0.0, 0.0, 0.0), 30000.0)
        assertEquals(0, run.faults.timeFaults)
        run.update(Pose(0.0, 0.0, 0.0), 30010.0)
        assertEquals(Faults(knockdowns = 1, refusals = 0, timeFaults = 1, total = 5), run.faults)
    }

    // ---- Finish before all obstacles (rule 30) ----

    @Test
    fun rideContinuesHintNamesTheMissingObstacle() {
        val run = riding()
        run.onLanded("v1", 1, false)
        assertEquals(LineCross.MISSING, crossFinish(run, 8000.0))
        assertEquals(RunPhase.RIDING, run.phase)
        assertEquals(2, run.missingHint)
    }

    @Test
    fun hintDisappearsAfterAFewSeconds() {
        val run = riding()
        crossFinish(run, 8000.0)
        val away = Pose(10.0, -22.0, PI)
        run.update(away, 8000.0 + TUNING.missingHintS * 1000 - 1)
        assertEquals(1, run.missingHint)
        run.update(away, 8000.0 + TUNING.missingHintS * 1000)
        assertNull(run.missingHint)
    }

    @Test
    fun hintDurationComesFromTheTuningValue() {
        val run = CourseRun(testCourse(), TUNING.copy(missingHintS = 1.0))
        run.cross(Vec2(0.0, -26.0), Vec2(0.0, -24.0), 0.0)
        run.onLanded("v1", 1, false)
        crossFinish(run, 8000.0)
        val away = Pose(10.0, -22.0, PI)
        run.update(away, 8999.0)
        assertEquals(2, run.missingHint)
        run.update(away, 9000.0)
        assertNull(run.missingHint)
    }

    @Test
    fun hintDisappearsAfterTheNextScoredJump() {
        val run = riding()
        crossFinish(run, 8000.0)
        run.onLanded("v1", 1, false)
        assertNull(run.missingHint)
    }

    @Test
    fun namesTheNumberOfTheCombination() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, false)
        crossFinish(run, 9000.0)
        assertEquals(3, run.missingHint)
    }

    // ---- Double combination (rule 31) ----

    @Test
    fun bOnlyComesAfterABAloneCountsAsAWrongObstacle() {
        val run = toCombination(riding())
        assertEquals(Highlight("k3a", 3), run.highlight)
        assertFalse(run.rules.canRefuse("k3b", 1))
        assertEquals(Landing(scored = false, rebuildAfterS = 3.0), run.onLanded("k3b", 1, true))
        assertEquals(0, run.faults.total)
        assertEquals(CurrentTarget(2, 0, "k3a"), run.current)
    }

    @Test
    fun afterAItIsTheTurnOfBSameNumber() {
        val run = toCombination(riding())
        assertTrue(run.onLanded("k3a", 1, false).scored)
        assertEquals(CurrentTarget(2, 1, "k3b"), run.current)
        assertEquals(Highlight("k3b", 3), run.highlight)
        assertEquals("3b", run.nextLabel)
        assertTrue(run.rules.canRefuse("k3b", 1))
        assertFalse(run.rules.canRefuse("k3a", 1))
    }

    @Test
    fun knockdownAt4FaultsContinueWithB() {
        val run = toCombination(riding())
        assertEquals(Landing(scored = true, rebuildAfterS = null), run.onLanded("k3a", 1, true))
        assertEquals("k3b", run.current?.elementId)
        assertEquals(1, run.faults.knockdowns)
    }

    @Test
    fun refusalAtB4FaultsBackToARebuildAAndBImmediately() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, true)
        run.onRefusal("k3b", 1)
        assertEquals(Faults(knockdowns = 1, refusals = 1, timeFaults = 0, total = 8), run.faults)
        assertEquals(CurrentTarget(2, 0, "k3a"), run.current)
        assertEquals(listOf("k3a", "k3b"), run.drainRebuilds().sorted())
        assertEquals(emptyList(), run.drainRebuilds())
    }

    @Test
    fun refusalAtA4FaultsStaysOnARebuildBoth() {
        val run = toCombination(riding())
        run.onRefusal("k3a", 1)
        assertEquals(1, run.faults.refusals)
        assertEquals("k3a", run.current?.elementId)
        assertEquals(listOf("k3a", "k3b"), run.drainRebuilds().sorted())
    }

    @Test
    fun knockdownsAtAAndBCountIndividuallyAcrossAllAttempts() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, true)
        run.onRefusal("k3b", 1)
        run.drainRebuilds()
        run.onLanded("k3a", 1, true)
        run.onLanded("k3b", 1, true)
        assertEquals(Faults(knockdowns = 3, refusals = 1, timeFaults = 0, total = 16), run.faults)
        assertNull(run.current)
    }

    @Test
    fun turningAwayAfterABackToAWithoutFaultsRebuildAAndB() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, true)
        run.update(horseAwayFromB, 9000.0)
        assertEquals(CurrentTarget(2, 0, "k3a"), run.current)
        assertEquals(Faults(knockdowns = 1, refusals = 0, timeFaults = 0, total = 4), run.faults)
        assertEquals(listOf("k3a", "k3b"), run.drainRebuilds().sorted())
    }

    @Test
    fun noTurningAwayWhileTheHorseApproachesBOrIsCloserThanTheApproachDistance() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, false)
        run.update(horseApproachingB, 9000.0)
        assertEquals("k3b", run.current?.elementId)
        run.update(Pose(3.0, 19.0, PI / 2), 9100.0)
        assertEquals("k3b", run.current?.elementId)
        // far away, but heading toward b: not within the approach distance -> counts as turned away
        run.update(Pose(0.0, 5.0, 0.0), 9200.0)
        assertEquals("k3a", run.current?.elementId)
    }

    @Test
    fun afterTurningAwayAIsScoredAgain() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, false)
        run.update(horseAwayFromB, 9000.0)
        assertTrue(run.onLanded("k3a", 1, false).scored)
        assertEquals("k3b", run.current?.elementId)
    }

    // ---- Result ----

    @Test
    fun returnsTimeItemizedFaultsAndStars() {
        val run = riding(testCourse(), 0.0)
        run.onLanded("v1", 1, true)
        run.onLanded("o2", 1, false)
        run.onLanded("k3a", 1, false)
        run.onLanded("k3b", 1, false)
        crossFinish(run, 34010.0)
        assertEquals(
            RideResult(
                courseId = 9,
                timeCs = 3401,
                faults = Faults(knockdowns = 1, refusals = 0, timeFaults = 2, total = 6),
                stars = 1,
                cleanOxer = true,
                cleanCombination = true,
            ),
            run.result,
        )
        assertEquals(Faults(knockdowns = 1, refusals = 0, timeFaults = 2, total = 6), run.faults)
    }

    @Test
    fun cleanRide3Stars() {
        val run = cleanRide(riding(testCourse(), 0.0))
        crossFinish(run, 20000.0)
        assertEquals(3, assertNotNull(run.result).stars)
        assertEquals(0, assertNotNull(run.result).faults.total)
    }

    @Test
    fun nothingIsScoredAfterTheFinish() {
        val run = cleanRide(riding(testCourse(), 0.0))
        crossFinish(run, 20000.0)
        assertEquals(Landing(scored = false, rebuildAfterS = 3.0), run.onLanded("v1", 1, true))
        run.onRefusal("v1", 1)
        assertEquals(0, assertNotNull(run.result).faults.total)
        assertNull(crossFinish(run, 25000.0))
    }

    // cleanOxer (rule 49)

    private fun finishOxer(run: CourseRun): Boolean {
        crossFinish(run, 20000.0)
        return assertNotNull(run.result).cleanOxer
    }

    @Test
    fun cleanOxerTrueForAScoredOxerWithoutRefusalAndWithoutKnockdown() {
        val run = riding()
        run.onLanded("v1", 1, true)
        run.onLanded("o2", 1, false)
        run.onLanded("k3a", 1, false)
        run.onLanded("k3b", 1, true)
        assertTrue(finishOxer(run))
    }

    @Test
    fun cleanOxerFalseIfTheOxerWasRefusedEvenIfCleanAfterwards() {
        val run = riding()
        run.onLanded("v1", 1, false)
        run.onRefusal("o2", 1)
        run.onLanded("o2", 1, false)
        run.onLanded("k3a", 1, false)
        run.onLanded("k3b", 1, true)
        assertFalse(finishOxer(run))
    }

    @Test
    fun cleanOxerAnOxerAsPartOfTheCombinationCounts() {
        val run = riding()
        run.onLanded("v1", 1, false)
        run.onLanded("o2", 1, true)
        run.onLanded("k3a", 1, true)
        run.onLanded("k3b", 1, false)
        assertTrue(finishOxer(run))
    }

    @Test
    fun cleanOxerUnscoredJumpsOverAnOxerDoNotCount() {
        val run = riding()
        run.onLanded("o2", 1, false)
        run.onLanded("v1", 1, false)
        run.onLanded("o2", 1, true)
        run.onLanded("k3a", 1, false)
        run.onRefusal("k3b", 1)
        run.onLanded("k3a", 1, false)
        run.onLanded("k3b", 1, false)
        assertFalse(finishOxer(run))
    }

    @Test
    fun cleanOxerFalseWithoutAnOxerInTheCourse() {
        val run = CourseRun(COURSES[0])
        crossLine(run, COURSES[0].start, 0.0)
        for (o in COURSES[0].obstacles) run.onLanded(o.elements[0].id, 1, false)
        crossLine(run, COURSES[0].finish, 30000.0)
        assertEquals(RunPhase.FINISHED, run.phase)
        assertFalse(assertNotNull(run.result).cleanOxer)
        assertFalse(assertNotNull(run.result).cleanCombination)
    }

    // cleanCombination (rule 49)

    private fun finishCombination(run: CourseRun): Boolean {
        crossFinish(run, 20000.0)
        return assertNotNull(run.result).cleanCombination
    }

    @Test
    fun cleanCombinationTrueForAAndBWithoutRefusalAndKnockdown() {
        assertTrue(finishCombination(cleanRide(riding())))
    }

    @Test
    fun cleanCombinationTrueAfterAFaultFreeTurnAwayAndACleanNewAttempt() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, false)
        run.update(horseAwayFromB, 9000.0)
        run.onLanded("k3a", 1, false)
        run.onLanded("k3b", 1, false)
        assertTrue(finishCombination(run))
    }

    @Test
    fun cleanCombinationFalseAfterARefusalAtTheCombination() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, false)
        run.onRefusal("k3b", 1)
        run.onLanded("k3a", 1, false)
        run.onLanded("k3b", 1, false)
        assertFalse(finishCombination(run))
    }

    @Test
    fun cleanCombinationFalseAfterAKnockdownAtA() {
        val run = toCombination(riding())
        run.onLanded("k3a", 1, true)
        run.onLanded("k3b", 1, false)
        assertFalse(finishCombination(run))
    }

    // ---- Courses from COURSES ----

    @Test
    fun everyCourseCanBeFinishedWithoutFaults() {
        for (c in COURSES) {
            val run = CourseRun(c)
            assertEquals(LineCross.START, crossLine(run, c.start, 0.0), "course ${c.id}")
            for (o in c.obstacles) {
                for (e in o.elements) assertTrue(run.onLanded(e.id, 1, false).scored, "course ${c.id} ${e.id}")
            }
            assertEquals(LineCross.FINISH, crossLine(run, c.finish, 30000.0), "course ${c.id}")
            assertEquals(RunPhase.FINISHED, run.phase)
            val result = assertNotNull(run.result)
            assertEquals(c.id, result.courseId)
            assertEquals(3, result.stars)
            assertEquals(c.id == 5, result.cleanCombination)
            assertEquals(c.id >= 3, result.cleanOxer)
        }
    }
}
