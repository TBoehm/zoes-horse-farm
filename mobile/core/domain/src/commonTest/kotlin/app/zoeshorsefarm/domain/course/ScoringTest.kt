package app.zoeshorsefarm.domain.course

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.testing.assertCloseTo
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoringTest {
    private fun el(
        id: String,
        x: Double,
        z: Double,
    ) = Element(id, ElementKind.CROSS, 0.4, 0.0, x, z, 0.0)

    // Start center (0,0) -> (0,30) -> (40,30) -> finish center (40,0): 30 + 40 + 30 = 100 m
    private val square =
        Course(
            id = 2,
            pace = Pace.CANTER,
            start = Line(Vec2(-3.0, 0.0), Vec2(3.0, 0.0), Vec2(0.0, 1.0)),
            finish = Line(Vec2(37.0, 0.0), Vec2(43.0, 0.0), Vec2(0.0, -1.0)),
            obstacles =
                listOf(
                    Obstacle(1, listOf(el("x1", 0.0, 30.0)), true),
                    Obstacle(2, listOf(el("x2a", 40.0, 30.0), el("x2b", 40.0, 20.0)), true),
                ),
        )

    // ---- Ideal line ----

    @Test
    fun connectsStartCenterAllElementCentersIncludingAAndBAndFinishCenter() {
        assertCloseTo(30.0 + 40.0 + 10.0 + 20.0, idealLineLength(square), 6)
    }

    @Test
    fun includesTheTurnWaypointsTrackPerLeg() {
        val withTrack = square.copy(track = listOf(emptyList(), listOf(Vec2(20.0, 40.0)), emptyList()))
        val detour = 2 * hypot(20.0, 10.0)
        assertCloseTo(30.0 + detour + 10.0 + 20.0, idealLineLength(withTrack), 6)
        assertEquals(Vec2(20.0, 40.0), idealLine(withTrack)[2])
    }

    // ---- allowed time (rule 33) ----

    @Test
    fun isIdealLineOverMediumCanterSpeedTimes15RoundedUp() {
        val len = idealLineLength(square)
        assertEquals(ceil((len / TUNING.speeds.canterMedium) * 1.5).toInt(), allowedTime(square))
    }

    @Test
    fun usesTheMediumTrotSpeedForCoursesWithPaceTrotCourse1() {
        val trot = square.copy(pace = Pace.TROT)
        val len = idealLineLength(trot)
        assertEquals(ceil((len / TUNING.speeds.trotMedium) * 1.5).toInt(), allowedTime(trot))
    }

    @Test
    fun doesNotRoundRoundValuesUpToTheNextSecond() {
        // 100 m / 6 m/s * 1.5 = 25 s
        assertEquals(25, allowedTime(square, 6.0))
        assertEquals(26, allowedTime(square, 5.9))
    }

    @Test
    fun isComputedInCoursesNotHardCodedCourse1AtTrot() {
        for (c in COURSES) assertEquals(allowedTime(c), c.allowedTimeS)
        assertEquals(Pace.TROT, COURSES[0].pace)
        assertTrue(COURSES.drop(1).all { it.pace == Pace.CANTER })
        val p1 = COURSES[0]
        assertEquals(ceil((idealLineLength(p1) / TUNING.speeds.trotMedium) * 1.5).toInt(), p1.allowedTimeS)
    }

    // ---- Time faults (rule 33) ----

    @Test
    fun timeFaultsPerStartedFourSecondsOverTheAllowedTime() {
        val cases =
            listOf(
                -5000.0 to 0,
                0.0 to 0,
                10.0 to 1,
                3990.0 to 1,
                4000.0 to 1,
                4010.0 to 2,
                8000.0 to 2,
                8010.0 to 3,
                60000.0 to 15,
            )
        for ((overMs, faults) in cases) assertEquals(faults, timeFaults(overMs), "$overMs ms over")
    }

    @Test
    fun computesInHundredthsRoundingNoiseDoesNotCount() {
        assertEquals(1, timeFaults(4000.0000001))
        assertEquals(0, timeFaults(0.4))
    }

    // ---- Hundredths ----

    @Test
    fun truncatesLikeAStopwatchWithoutRoundingNoise() {
        assertEquals(4827, toCentiseconds(48279.0))
        assertEquals(4827, toCentiseconds(48269.99999999))
        assertEquals(0, toCentiseconds(0.0))
    }

    // ---- Stars (rule 36) ----

    @Test
    fun starsByFaults() {
        val cases = listOf(0 to 3, 1 to 2, 4 to 2, 5 to 1, 8 to 1, 40 to 1)
        for ((faults, stars) in cases) assertEquals(stars, starsFor(faults), "$faults faults")
    }

    // ---- Best result (glossary) ----

    @Test
    fun firstFinishedRideIsAlwaysABestResult() {
        assertTrue(isBetterResult(Rating(12, 9999), null))
    }

    @Test
    fun fewerFaultsWinsEvenWithALongerTime() {
        assertTrue(isBetterResult(Rating(0, 9000), Rating(4, 4000)))
        assertFalse(isBetterResult(Rating(8, 3000), Rating(4, 4000)))
    }

    @Test
    fun withEqualFaultsTheTimeDecidesToTheHundredth() {
        assertTrue(isBetterResult(Rating(4, 4826), Rating(4, 4827)))
        assertFalse(isBetterResult(Rating(4, 4827), Rating(4, 4827)))
        assertFalse(isBetterResult(Rating(4, 4828), Rating(4, 4827)))
    }

    @Test
    fun alsoUnderstandsRideResultsWithItemizedFaults() {
        val result =
            RideResult(
                courseId = 1,
                timeCs = 5000,
                faults = Faults(knockdowns = 0, refusals = 0, timeFaults = 1, total = 1),
                stars = 2,
                cleanOxer = false,
                cleanCombination = false,
            )
        assertTrue(isBetterResult(result, Rating(4, 3000)))
        assertFalse(isBetterResult(Rating(4, 3000), result))
    }
}
