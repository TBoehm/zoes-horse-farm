package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.domain.testing.DEG
import app.zoeshorsefarm.domain.testing.assertCloseTo
import app.zoeshorsefarm.domain.testing.makeElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JumpTest {
    private val cross = makeElement(ElementKind.CROSS, 0.45)
    private val vertical60 = makeElement(ElementKind.VERTICAL, 0.6)
    private val vertical80 = makeElement(ElementKind.VERTICAL, 0.8)
    private val oxer70 = makeElement(ElementKind.OXER, 0.7, spread = 0.6)
    private val oxer85 = makeElement(ElementKind.OXER, 0.85, spread = 0.7)
    private val all = listOf(cross, vertical60, vertical80, oxer70, oxer85)

    private fun core(
        el: Element,
        speed: Double,
    ): TakeoffState {
        val zone = zoneForElement(el, speed, TUNING)
        return TakeoffState(gait = Gait.CANTER, speed = speed, distance = zone.center, angle = 0.0)
    }

    // ---- Takeoff zone, reach, last takeoff point ----

    @Test
    fun reachGreaterFarGreaterNearGreaterLastPointGreater025mForAllKindsAndSpeeds() {
        for (el in all) {
            for (v in listOf(0.0, 2.6, 3.2, 4.5, 5.8, 8.0)) {
                val z = zoneForElement(el, v, TUNING)
                assertTrue(z.reach > z.far)
                assertTrue(z.far > z.near)
                assertTrue(z.near > z.lastPoint)
                assertTrue(z.lastPoint > 0.25)
                assertTrue(z.reach < TUNING.approachDistance)
            }
        }
    }

    @Test
    fun zoneCenterIsRealisticallyAbout13To18mWorkingPace() {
        assertTrue(zoneForElement(cross, 3.2, TUNING).center > 1.2)
        for (el in listOf(vertical60, vertical80, oxer70, oxer85)) {
            val c = zoneForElement(el, 5.8, TUNING).center
            assertTrue(c >= 1.3)
            assertTrue(c <= 1.85)
        }
    }

    @Test
    fun anOxerIsApproachedSlightlyCloserThanAVerticalOfTheSameHeight() {
        val v = makeElement(ElementKind.VERTICAL, 0.85)
        assertTrue(zoneForElement(oxer85, 5.8, TUNING).center < zoneForElement(v, 5.8, TUNING).center)
    }

    @Test
    fun reachStartsWellBeforeTheZone() {
        val z = zoneForElement(vertical60, 5.8, TUNING)
        assertTrue(z.reach - z.far >= 0.35 * 5.8 - 1e-9)
    }

    @Test
    fun timeWindowGenerousForACrossNarrowerWhenHigherWider() {
        assertCloseTo(0.22, zoneWindow(cross, TUNING), 9)
        assertTrue(zoneWindow(vertical80, TUNING) < zoneWindow(vertical60, TUNING))
        assertTrue(zoneWindow(oxer85, TUNING) < zoneWindow(vertical80, TUNING))
        assertTrue(zoneWindow(oxer85, TUNING) > 0.09)
        assertTrue(zoneWindow(oxer85, TUNING) < 0.12)
    }

    @Test
    fun theWindowNarrowsOnlyAboveTheTuningHeightReference() {
        val raised =
            TUNING.copy(
                jump = TUNING.jump.copy(window = TUNING.jump.window.copy(heightRef = 0.6)),
            )
        assertCloseTo(TUNING.jump.window.base, zoneWindow(vertical60, raised), 9)
        assertTrue(zoneWindow(vertical80, raised) > zoneWindow(vertical80, TUNING))
    }

    @Test
    fun theLastTakeoffPointIsCappedByTheTuningShareOfTheNearEdge() {
        val strict =
            TUNING.copy(
                jump = TUNING.jump.copy(lastPoint = LastPointTuning(lead = 0.0, min = 0.1, maxShareOfNear = 0.5)),
            )
        val z = zoneForElement(vertical80, 5.8, strict)
        assertCloseTo(z.near * 0.5, z.lastPoint, 9)
    }

    @Test
    fun theZoneAdaptsToSpeedFartherAwayAndDeeperAtHigherSpeed() {
        val slow = zoneForElement(vertical80, 4.5, TUNING)
        val fast = zoneForElement(vertical80, 7.0, TUNING)
        assertTrue(fast.far > slow.far)
        assertTrue(fast.center > slow.center)
        assertTrue(fast.far - fast.near > slow.far - slow.near)
    }

    // ---- Jumpability and target ranges ----

    @Test
    fun gaitHaltWalkNeverTrotOnlyCrossesCanterEverythingRule16() {
        for (el in all) {
            assertFalse(gaitAllows(el, Gait.HALT))
            assertFalse(gaitAllows(el, Gait.WALK))
            assertTrue(gaitAllows(el, Gait.CANTER))
            assertEquals(el.kind == ElementKind.CROSS, gaitAllows(el, Gait.TROT))
        }
    }

    @Test
    fun targetSpeedRangeRisesWithHeightAndSpread() {
        assertTrue(speedBand(cross, TUNING).min <= 2.6)
        assertTrue(speedBand(cross, TUNING).min < TUNING.speeds.trotMedium)
        assertTrue(speedBand(vertical60, TUNING).min >= 4.6)
        assertTrue(speedBand(vertical80, TUNING).min > speedBand(vertical60, TUNING).min)
        assertTrue(speedBand(oxer85, TUNING).min >= 5.55)
        for (el in all) {
            val b = speedBand(el, TUNING)
            assertTrue(b.max > b.min)
            assertTrue(b.max <= TUNING.speeds.canterMax)
            // medium canter speed is always within the safe core
            assertTrue(TUNING.speeds.canterMedium >= b.min)
            assertTrue(TUNING.speeds.canterMedium <= b.max)
        }
    }

    @Test
    fun selfJumpMinimumSpeedIsBelowTheTargetRange() {
        for (el in all) assertTrue(selfMinSpeed(el, TUNING) < speedBand(el, TUNING).min)
        assertTrue(selfMinSpeed(oxer85, TUNING) > TUNING.speeds.canterMin)
    }

    @Test
    fun angleTolerance10To12DegreesNarrowerForHeavyObstacles() {
        assertTrue(safeAngle(cross, TUNING) / DEG <= 12)
        assertTrue(safeAngle(oxer85, TUNING) / DEG >= 10)
        assertTrue(safeAngle(oxer85, TUNING) < safeAngle(cross, TUNING))
        assertTrue(difficultyOf(oxer85, TUNING) > difficultyOf(cross, TUNING))
    }

    // ---- Knockdown risk (rules 15, 18, 19, 20) ----

    @Test
    fun safeCoreExactly0AcrossTheWholeZoneTargetRangeAndAngleTolerance() {
        for (el in all) {
            val band = speedBand(el, TUNING)
            for (v in listOf(band.min, (band.min + band.max) / 2, band.max)) {
                val z = zoneForElement(el, v, TUNING)
                for (d in listOf(z.near, z.center, z.far)) {
                    for (a in listOf(0.0, safeAngle(el, TUNING))) {
                        val gait =
                            if (v <= TUNING.speeds.trotMax && el.kind == ElementKind.CROSS) Gait.TROT else Gait.CANTER
                        assertEquals(0.0, takeoffRisk(el, TakeoffState(gait, v, d, a), TUNING))
                    }
                }
            }
        }
    }

    @Test
    fun risesMonotonicallyWithTheSpeedDeviation() {
        val band = speedBand(vertical60, TUNING)
        var prev = 0.0
        for (dv in listOf(0.2, 0.5, 1.0)) {
            val speed = band.max + dv
            val s =
                core(vertical60, speed).copy(
                    speed = speed,
                    distance = zoneForElement(vertical60, speed, TUNING).center,
                )
            val r = takeoffRisk(vertical60, s, TUNING)
            assertTrue(r > prev)
            prev = r
        }
    }

    @Test
    fun risesMonotonicallyWithTheDistanceDeviationTooEarlyAndTooClose() {
        val z = zoneForElement(vertical60, 5.8, TUNING)
        var prevEarly = 0.0
        for (dd in listOf(0.2, 0.6, 1.2)) {
            val r = takeoffRisk(vertical60, core(vertical60, 5.8).copy(distance = z.far + dd), TUNING)
            assertTrue(r > prevEarly)
            prevEarly = r
        }
        val tooClose = takeoffRisk(vertical60, core(vertical60, 5.8).copy(distance = z.lastPoint), TUNING)
        assertTrue(tooClose > 0)
    }

    @Test
    fun risesMonotonicallyWithTheAngleBeyondTheTolerance() {
        var prev = 0.0
        for (a in listOf(15, 22, 30)) {
            val r = takeoffRisk(vertical60, core(vertical60, 5.8).copy(angle = a * DEG), TUNING)
            assertTrue(r > prev)
            prev = r
        }
    }

    @Test
    fun a85cmOxerIsRiskierThanACrossAtTheSameDeviation() {
        val v = 6.5
        val cases =
            listOf<(Element) -> TakeoffState>(
                { el ->
                    TakeoffState(Gait.CANTER, v, zoneForElement(el, v, TUNING).far + 0.5, 0.0)
                },
                { el ->
                    TakeoffState(Gait.CANTER, v, zoneForElement(el, v, TUNING).center, 20 * DEG)
                },
                { el ->
                    val speed = speedBand(el, TUNING).max + 0.4
                    TakeoffState(Gait.CANTER, speed, zoneForElement(el, speed, TUNING).center, 0.0)
                },
            )
        for (mk in cases) {
            val rc = takeoffRisk(cross, mk(cross), TUNING)
            val ro = takeoffRisk(oxer85, mk(oxer85), TUNING)
            assertTrue(rc > 0)
            assertTrue(ro > rc)
        }
    }

    @Test
    fun aSelfJumpAlwaysCarriesClearlyIncreasedRisk() {
        for (el in all) {
            val s = core(el, speedBand(el, TUNING).min + 0.3)
            val self = takeoffRisk(el, s.copy(self = true), TUNING)
            assertTrue(self >= 0.3)
        }
    }

    @Test
    fun isCapped() {
        val r =
            takeoffRisk(
                oxer85,
                TakeoffState(Gait.CANTER, speed = 4.5, distance = 6.0, angle = 29 * DEG, self = true),
                TUNING,
            )
        assertTrue(r <= TUNING.jump.risk.max)
        assertTrue(r > 0.8)
    }

    // ---- rails, block extents, landing (used by the sim; no separate JS test) ----

    @Test
    fun anOxerHasTwoRailsAtMinusAndPlusHalfTheSpreadOtherKindsOneAtZero() {
        assertEquals(listOf(RailPosition(0, -0.3), RailPosition(1, 0.3)), railLayout(oxer70))
        assertEquals(listOf(RailPosition(0, 0.0)), railLayout(vertical60))
    }

    @Test
    fun blockExtentsCoverThePolesStandsAndTheHorseWidth() {
        val e = blockExtents(oxer70, TUNING)
        assertCloseTo(0.3 + TUNING.horse.frontMargin, e.along, 9)
        assertCloseTo(POLE_LENGTH / 2 + STAND_WIDTH + TUNING.horse.halfWidth, e.across, 9)
        assertCloseTo(0.3 + 0.35, blockExtents(oxer70, TUNING, 0.35).along, 9)
    }

    @Test
    fun landingDistanceIsClampedToTheTuningRange() {
        val f = TUNING.jump.flight
        assertEquals(f.landMin, landingDistance(cross.copy(height = 0.0), 0.0, TUNING))
        assertEquals(f.landMax, landingDistance(oxer85, 30.0, TUNING))
        // in between it is the linear formula
        val el = vertical60
        val expected = f.landBase + f.landPerTakeoff * 1.5 + f.landPerHeight * el.height
        assertCloseTo(expected, landingDistance(el, 1.5, TUNING), 9)
    }
}
