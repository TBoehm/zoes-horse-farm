package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.domain.testing.DEG
import app.zoeshorsefarm.domain.testing.DT
import app.zoeshorsefarm.domain.testing.assertCloseTo
import app.zoeshorsefarm.domain.testing.drive
import app.zoeshorsefarm.domain.testing.jsRound
import app.zoeshorsefarm.domain.testing.makeSim
import app.zoeshorsefarm.domain.testing.ofType
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MovementTest {
    private val s = TUNING.speeds
    private val noElements = emptyList<Element>()

    /** Turn radius (m) at full steering lock. */
    private fun turnRadius(
        speed: Double,
        tuning: Tuning,
    ) = speed / maxTurnRate(speed, tuning)

    // ---- Gait from speed (rule 9) ----

    @Test
    fun mapsHaltWalkAndTrotBySpeedGallopIsAlwaysCanter() {
        assertEquals(Gait.HALT, gaitForSpeed(0.0, false, s))
        assertEquals(Gait.HALT, gaitForSpeed(0.1, false, s))
        assertEquals(Gait.WALK, gaitForSpeed(1.0, false, s))
        assertEquals(Gait.WALK, gaitForSpeed(s.walkMax, false, s))
        assertEquals(Gait.TROT, gaitForSpeed(s.walkMax + 0.1, false, s))
        assertEquals(Gait.TROT, gaitForSpeed(s.trotMax, false, s))
        assertEquals(Gait.CANTER, gaitForSpeed(0.0, true, s))
    }

    @Test
    fun negativeSpeedIsTheReinBackGaitRule9() {
        assertEquals(Gait.BACK, gaitForSpeed(-0.01, false, s))
        assertEquals(Gait.BACK, gaitForSpeed(-TUNING.reinBack.maxSpeed, false, s))
    }

    // ---- Speed (rules 8-10) ----

    @Test
    fun wIncreasesSpeedContinuouslyThroughWalkUpToTrotAndNeverBeyondTrotMax() {
        val sim = makeSim(noElements)
        val gaits = LinkedHashSet<Gait>()
        drive(sim, SimInput(throttle = 1.0), maxT = 6.0, onStep = { sm, _, _ -> gaits.add(sm.horse.gait) })
        assertEquals(listOf(Gait.HALT, Gait.WALK, Gait.TROT), gaits.toList())
        assertCloseTo(s.trotMax, sim.horse.speed, 6)
    }

    @Test
    fun sBrakesToAHaltSpeed0() {
        val sim = makeSim(noElements)
        sim.reset(0.0, 0.0, 0.0, speed = 3.0)
        // S held on: it brakes to a halt first (the rein-back only follows after its pause)
        drive(sim, SimInput(throttle = -1.0), maxT = 3.0, until = { sm, _, _ -> sm.horse.speed == 0.0 })
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
    }

    @Test
    fun speedIsKeptWithoutInput() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -20.0, 0.0, speed = 2.5)
        drive(sim, SimInput(), maxT = 2.0)
        assertCloseTo(2.5, sim.horse.speed, 9)
        assertEquals(Gait.TROT, sim.horse.gait)
    }

    @Test
    fun rateIsProportionalToTheDeflectionJoystick() {
        val a = makeSim(noElements)
        val b = makeSim(noElements)
        drive(a, SimInput(throttle = 1.0), maxT = 0.5)
        drive(b, SimInput(throttle = 0.5), maxT = 0.5)
        assertCloseTo(0.5, b.horse.speed / a.horse.speed, 2)
    }

    @Test
    fun gallopGaitIsCanterImmediatelyGentleAccelerationToAtLeastCanterMin() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -30.0, 0.0, speed = 2.0)
        sim.step(1.0 / 60, SimInput(gallop = false))
        sim.step(1.0 / 60, SimInput(gallop = true))
        assertEquals(Gait.CANTER, sim.horse.gait)
        assertTrue(sim.horse.gallop)
        assertTrue(sim.horse.speed < 2.2)
        drive(sim, SimInput(gallop = true), maxT = 1.5)
        assertCloseTo(s.canterMin, sim.horse.speed, 6)
    }

    @Test
    fun wsControlTheCanterSpeedWithinCanterMinCanterMax() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -34.0, 0.0, speed = s.canterMin, gallop = true)
        drive(sim, SimInput(gallop = true, throttle = 1.0), maxT = 2.0)
        assertCloseTo(s.canterMax, sim.horse.speed, 6)
        drive(sim, SimInput(gallop = true, throttle = -1.0), maxT = 3.0)
        assertCloseTo(s.canterMin, sim.horse.speed, 6)
        assertEquals(Gait.CANTER, sim.horse.gait)
    }

    @Test
    fun gallopOffTrotSpeedDropsGentlyToWorkingTrot() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -34.0, 0.0, speed = 7.0, gallop = true)
        sim.step(1.0 / 60, SimInput(gallop = false))
        assertFalse(sim.horse.gallop)
        assertEquals(Gait.TROT, sim.horse.gait)
        assertTrue(sim.horse.speed > 6.5)
        drive(sim, SimInput(), maxT = 3.0)
        assertCloseTo(s.trotMedium, sim.horse.speed, 6)
        assertEquals(Gait.TROT, sim.horse.gait)
    }

    @Test
    fun gallopEndedDuringTheStrikeOffBelowTrotMinBackToTrotNotToAWalk() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -30.0, 0.0)
        sim.step(DT, SimInput(gallop = false))
        drive(sim, SimInput(gallop = true), maxT = 0.3)
        assertTrue(sim.horse.gallop)
        assertTrue(sim.horse.speed < s.trotMin)
        sim.step(DT, SimInput(gallop = false))
        assertFalse(sim.horse.gallop)
        drive(sim, SimInput(), maxT = 1.0)
        assertEquals(Gait.TROT, sim.horse.gait)
        assertCloseTo(s.trotMin, sim.horse.speed, 6)
    }

    @Test
    fun easesUpToTrotAtTheTuningRateNoJumpInSpeed() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -30.0, 0.0)
        sim.step(DT, SimInput(gallop = false))
        drive(sim, SimInput(gallop = true), maxT = 0.2)
        val v0 = sim.horse.speed
        sim.step(DT, SimInput(gallop = false))
        assertTrue(sim.horse.speed - v0 <= TUNING.control.gallopEndTrotUp * DT + 1e-9)
        assertTrue(sim.horse.speed > v0)
    }

    @Test
    fun endingTheGallopFromAHaltAfterAVeryShortPressStillReachesTrot() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -30.0, 0.0)
        sim.step(DT, SimInput(gallop = false))
        sim.step(DT, SimInput(gallop = true))
        sim.step(DT, SimInput(gallop = false))
        drive(sim, SimInput(), maxT = 2.0)
        assertEquals(Gait.TROT, sim.horse.gait)
    }

    @Test
    fun sAfterTheStrikeOffGallopEndsBrakesToAHaltInsteadOfTrottingOn() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -30.0, 0.0)
        sim.step(DT, SimInput(gallop = false))
        drive(sim, SimInput(gallop = true), maxT = 0.3)
        // S held on: it brakes to a halt first (the rein-back only follows after its pause)
        drive(sim, SimInput(throttle = -1.0), maxT = 2.0, until = { sm, _, _ -> sm.horse.speed == 0.0 })
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
    }

    @Test
    fun aWalkingHorseWithoutGallopStaysAtItsPaceNoAutomaticTrot() {
        val sim = makeSim(noElements)
        sim.reset(0.0, -30.0, 0.0, speed = 1.0)
        drive(sim, SimInput(), maxT = 2.0)
        assertCloseTo(1.0, sim.horse.speed, 9)
        assertEquals(Gait.WALK, sim.horse.gait)
    }

    @Test
    fun afterResetTheHorseOnlyGallopsAfterAFreshKeyPress() {
        val sim = makeSim(noElements)
        sim.reset(0.0, 0.0, 0.0)
        sim.step(1.0 / 60, SimInput(gallop = true))
        assertFalse(sim.horse.gallop)
        sim.step(1.0 / 60, SimInput(gallop = false))
        sim.step(1.0 / 60, SimInput(gallop = true))
        assertTrue(sim.horse.gallop)
    }

    // ---- Steering (rules 8, 10, 22) ----

    @Test
    fun turnsOnTheSpotAtHaltRightTurnsToTheRight() {
        val sim = makeSim(noElements)
        sim.reset(1.0, 2.0, 0.0)
        val right = Vec2(-1.0, 0.0)
        drive(sim, SimInput(steer = 1.0), maxT = 0.5)
        assertEquals(1.0, sim.horse.x)
        assertEquals(2.0, sim.horse.z)
        assertTrue(sim.horse.turnRate > 0)
        val f = forwardOf(sim.horse.heading)
        assertTrue(f.x * right.x + f.z * right.z > 0.3)
        assertEquals(Gait.HALT, sim.horse.gait)
    }

    @Test
    fun steeringStrengthIsProportionalToAbsSteer() {
        val a = makeSim(noElements)
        val b = makeSim(noElements)
        drive(a, SimInput(steer = -1.0), maxT = 1.0)
        drive(b, SimInput(steer = -0.5), maxT = 1.0)
        assertCloseTo(0.5, b.horse.turnRate / a.horse.turnRate, 3)
        assertTrue(a.horse.turnRate < 0)
    }

    @Test
    fun theTurnRadiusGrowsWithSpeed() {
        val radii = listOf(1.0, 3.0, 5.0, 8.0).map { turnRadius(it, TUNING) }
        for (i in 1 until radii.size) assertTrue(radii[i] > radii[i - 1])
        assertEquals(TUNING.control.turnInPlace, maxTurnRate(0.0, TUNING))
    }

    @Test
    fun measuredTurnAtTrotIsTighterThanAtCanter() {
        fun measure(
            speed: Double,
            gallop: Boolean,
        ): Double {
            val sim = makeSim(noElements)
            sim.reset(0.0, 0.0, 0.0, speed = speed, gallop = gallop)
            drive(sim, SimInput(steer = 1.0, gallop = gallop), maxT = 1.0)
            return speed / abs(sim.horse.turnRate)
        }
        assertTrue(measure(3.2, false) < measure(6.0, true))
    }

    // ---- Turn agility (rule 10: direct steering, SRT-009 child feedback) ----

    // Real horses: 10 m volte (r = 5 m) at trot/walk, 20 m circle (r = 10 m) at canter, jump-off
    // turns at jumping canter about r = 6-8 m. The child asked for "about 50 % better" turning,
    // so the game is deliberately far more agile than reality: radius = 1/1.5 of the values
    // before SRT-009 (walk 1.0 m, trot 2.7 m, jumping canter 6.3 m, full gallop 10.4 m).
    private val beforeTurnInPlace = 1.8
    private val beforeTurnSpeedRef = 6.0

    private fun radiusBefore(v: Double) = (v * (1 + v / beforeTurnSpeedRef)) / beforeTurnInPlace

    @Test
    fun walkVeryTightTurnAtMost08mRadiusTurnOnTheHaunches() {
        assertTrue(turnRadius(1.5, TUNING) <= 0.8)
    }

    @Test
    fun workingTrotRadiusAbout18mBetween16And20m() {
        val r = turnRadius(s.trotMedium, TUNING)
        assertTrue(r >= 1.6)
        assertTrue(r <= 2.0)
    }

    @Test
    fun jumpingCanterJumpOffTurnRadiusAbout42mBetween38And46m() {
        val r = turnRadius(s.canterMedium, TUNING)
        assertTrue(r >= 3.8)
        assertTrue(r <= 4.6)
    }

    @Test
    fun everyGaitTurnsAbout15TimesTighterThanBeforeSrt009Ratio14To16() {
        for (v in listOf(1.5, s.trotMedium, s.canterMedium, s.canterMax)) {
            val ratio = radiusBefore(v) / turnRadius(v, TUNING)
            assertTrue(ratio >= 1.4)
            assertTrue(ratio <= 1.6)
        }
    }

    @Test
    fun onTheSpotTurnRateAllowsAHalfTurnInAbout12sAtLeast24RadPerS() {
        assertTrue(maxTurnRate(0.0, TUNING) >= 2.4)
    }

    // Game-feel bound, not a realism bound: reality is ~2.5 m/s^2 on a 20 m canter circle and
    // 6-8 m/s^2 in tight turns; the child-friendly arcade steering reaches up to ~9.3 m/s^2 at full
    // gallop (v * omega). It must stay below 1 g (9.81 m/s^2) so the horse never feels like it snaps.
    @Test
    fun gameFeelBoundLateralAccelerationVOmegaStaysBelow1gAtEverySpeed() {
        var v = 0.5
        while (v <= s.canterMax) {
            assertTrue(v * maxTurnRate(v, TUNING) < 9.81)
            v += 0.5
        }
    }

    @Test
    fun theTurnRateFollowsTheStickQuickly90PercentOfTheTargetWithin013s() {
        val sim = makeSim(noElements)
        drive(sim, SimInput(steer = 1.0), maxT = 0.13)
        assertTrue(abs(sim.horse.turnRate) >= 0.9 * maxTurnRate(0.0, TUNING))
    }

    @Test
    fun releasingTheStickStopsTheTurnCalmlyMonotoneDecayNoOvershootDoneIn03s() {
        val sim = makeSim(noElements)
        drive(sim, SimInput(steer = 1.0), maxT = 1.0)
        var last = abs(sim.horse.turnRate)
        var t = 0.0
        while (t < 0.3) {
            sim.step(DT, SimInput(steer = 0.0))
            val now = sim.horse.turnRate
            assertTrue(now >= 0)
            assertTrue(now <= last + 1e-12)
            last = now
            t += DT
        }
        assertTrue(last < 0.02 * maxTurnRate(0.0, TUNING))
    }

    @Test
    fun fullLockAtCanterReallyRidesTheSmallCircleMeasuredRadiusAbout42m() {
        val sim = makeSim(noElements)
        sim.reset(0.0, 0.0, 0.0, speed = s.canterMedium, gallop = true)
        drive(sim, SimInput(steer = 1.0, gallop = true), maxT = 1.0)
        val r = s.canterMedium / abs(sim.horse.turnRate)
        assertTrue(r >= 3.8)
        assertTrue(r <= 4.6)
    }

    // ---- Fencing (rule 24) ----

    @Test
    fun frontalStopHaltGallopOffWithEvents() {
        val sim = makeSim(noElements)
        sim.reset(10.0, 0.0, 90 * DEG, speed = 6.0, gallop = true)
        val result = drive(sim, SimInput(gallop = true), maxT = 3.0)
        assertEquals(1, result.events.ofType<SimEvent.FenceStop>().size)
        assertEquals(listOf(SimEvent.GallopEnded(GallopEndReason.FENCE)), result.events.ofType<SimEvent.GallopEnded>())
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
        assertFalse(sim.horse.gallop)
        assertTrue(sim.horse.x <= ARENA.width / 2 - TUNING.horse.radius + 1e-9)
    }

    @Test
    fun aFenceStopDuringTheStrikeOffStaysAHaltNoTrotFallBack() {
        val sim = makeSim(noElements)
        sim.reset(0.0, ARENA.length / 2 - TUNING.horse.radius - 0.1, 0.0)
        sim.step(DT, SimInput(gallop = false))
        drive(sim, SimInput(gallop = true), maxT = 0.5)
        assertEquals(0.0, sim.horse.speed)
        drive(sim, SimInput(), maxT = 1.0)
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
    }

    @Test
    fun afterAFenceStopItOnlyGallopsAfterAFreshKeyPress() {
        val sim = makeSim(noElements)
        sim.reset(15.0, 0.0, 90 * DEG, speed = 5.0, gallop = true)
        drive(sim, SimInput(gallop = true), maxT = 2.0)
        assertFalse(sim.horse.gallop)
        // Shift stays held while turning on the spot: no gallop
        drive(sim, SimInput(gallop = true, steer = 1.0), maxT = 2.0)
        assertFalse(sim.horse.gallop)
        sim.step(1.0 / 60, SimInput(gallop = false))
        sim.step(1.0 / 60, SimInput(gallop = true))
        assertTrue(sim.horse.gallop)
    }

    @Test
    fun frontalWith30DegreesDeviationStillCountsAsFrontal() {
        val sim = makeSim(noElements)
        sim.reset(15.0, 0.0, 60 * DEG, speed = 3.0)
        val result = drive(sim, SimInput(), maxT = 3.0)
        assertEquals(1, result.events.ofType<SimEvent.FenceStop>().size)
    }

    @Test
    fun continuingToPushAgainstTheFenceCreatesNoEventFlood() {
        val sim = makeSim(noElements)
        sim.reset(15.0, 0.0, 90 * DEG, speed = 3.0)
        val result = drive(sim, SimInput(throttle = 1.0), maxT = 4.0)
        assertEquals(1, result.events.ofType<SimEvent.FenceStop>().size)
        assertTrue(sim.horse.x <= ARENA.width / 2 - TUNING.horse.radius + 1e-9)
    }

    @Test
    fun obliqueSlidesAlongTheWallInParallelWithUnchangedSpeed() {
        val sim = makeSim(noElements)
        sim.reset(15.0, 0.0, 40 * DEG, speed = 5.0, gallop = true)
        val result = drive(sim, SimInput(gallop = true), maxT = 2.0)
        assertEquals(0, result.events.ofType<SimEvent.FenceStop>().size)
        assertEquals(0, result.events.ofType<SimEvent.GallopEnded>().size)
        assertCloseTo(5.0, sim.horse.speed, 9)
        assertTrue(sim.horse.gallop)
        assertCloseTo(0.0, wrapAngle(sim.horse.heading), 9)
        assertCloseTo(ARENA.width / 2 - TUNING.horse.radius, sim.horse.x, 9)
    }

    @Test
    fun obliqueTheHeadingEasesParallelToTheWallInsteadOfSnapping() {
        val sim = makeSim(noElements)
        sim.reset(18.0, 0.0, 40 * DEG, speed = 5.0, gallop = true)
        val headings = ArrayList<Double>()
        drive(sim, SimInput(gallop = true), maxT = 2.0, onStep = { sm, _, _ -> headings.add(sm.horse.heading) })
        // never turns more than the slide turn rate allows within one step
        var previous = 40 * DEG
        for (h in headings) {
            assertTrue(abs(wrapAngle(h - previous)) <= TUNING.fence.slideTurnRate * DT + 1e-9)
            previous = h
        }
        // it really took several steps (a snap would jump from 40 degrees to 0 at once)
        assertTrue(headings.count { it > 1 * DEG && it < 39 * DEG } > 3)
        assertCloseTo(0.0, wrapAngle(sim.horse.heading), 9)
    }

    @Test
    fun theHindquartersStayInsideTheArenaWhenTheHorseTurnsAroundAtTheWall() {
        val sim = makeSim(noElements)
        // halt at the east wall facing it, then turn on the spot until it faces away
        sim.reset(15.0, 0.0, 90 * DEG, speed = 5.0, gallop = true)
        drive(sim, SimInput(gallop = true), maxT = 2.0)
        sim.step(DT, SimInput(gallop = false))
        val limit = ARENA.width / 2 - TUNING.horse.rearMargin + 1e-9
        drive(
            sim,
            SimInput(steer = 1.0),
            maxT = 10.0,
            // stop as soon as it faces away (independent of the turn rate tuning)
            until = { sm, _, _ -> abs(wrapAngle(sm.horse.heading - 270 * DEG)) < 0.1 },
            onStep = { sm, _, _ ->
                val rear = sm.horse.x - sin(sm.horse.heading) * TUNING.horse.rearLength
                assertTrue(rear <= limit)
            },
        )
        assertTrue(abs(wrapAngle(sim.horse.heading - 270 * DEG)) < 0.5)
    }

    @Test
    fun theHindquartersStayInsideInAllFourCornersWhateverTheHeading() {
        val limitX = ARENA.width / 2 - TUNING.horse.rearMargin + 1e-9
        val limitZ = ARENA.length / 2 - TUNING.horse.rearMargin + 1e-9
        val sim = makeSim(noElements)
        for ((cx, cz) in listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
            sim.reset(cx * 19.0, cz * 34.0, 0.0)
            drive(
                sim,
                SimInput(steer = 1.0),
                maxT = 8.0,
                onStep = { sm, _, _ ->
                    val f = forwardOf(sm.horse.heading)
                    assertTrue(abs(sm.horse.x - f.x * TUNING.horse.rearLength) <= limitX)
                    assertTrue(abs(sm.horse.z - f.z * TUNING.horse.rearLength) <= limitZ)
                },
            )
        }
    }

    @Test
    fun theHorseNeverLeavesTheArenaRandomRiding() {
        val sim = makeSim(noElements)
        val rng = createRng(3)
        var steer = 0.0
        var throttle = 1.0
        val maxX = ARENA.width / 2 - TUNING.horse.radius + 1e-9
        val maxZ = ARENA.length / 2 - TUNING.horse.radius + 1e-9
        val rearMaxX = ARENA.width / 2 - TUNING.horse.rearMargin + 1e-9
        val rearMaxZ = ARENA.length / 2 - TUNING.horse.rearMargin + 1e-9
        drive(
            sim,
            { _, t ->
                if (jsRound(t * 60) % 60 == 0.0) {
                    steer = rng() * 2 - 1
                    throttle = rng() * 2 - 0.6
                }
                SimInput(steer = steer, throttle = throttle, gallop = rng() < 0.98)
            },
            maxT = 120.0,
            onStep = { sm, _, _ ->
                assertTrue(abs(sm.horse.x) <= maxX)
                assertTrue(abs(sm.horse.z) <= maxZ)
                val f = forwardOf(sm.horse.heading)
                val rear = TUNING.horse.rearLength
                assertTrue(abs(sm.horse.x - f.x * rear) <= rearMaxX)
                assertTrue(abs(sm.horse.z - f.z * rear) <= rearMaxZ)
            },
        )
    }

    // ---- not in the web tests: the sim keeps working with NaN input ----

    @Test
    fun nonFiniteInputCountsAsNoInput() {
        val sim = makeSim(noElements)
        sim.reset(0.0, 0.0, 0.0, speed = 2.5)
        sim.step(DT, SimInput(steer = Double.NaN, throttle = Double.POSITIVE_INFINITY))
        assertEquals(0.0, sim.horse.turnRate)
        assertCloseTo(2.5, sim.horse.speed, 9)
        assertNotEquals(Double.NaN, sim.horse.z)
    }
}
