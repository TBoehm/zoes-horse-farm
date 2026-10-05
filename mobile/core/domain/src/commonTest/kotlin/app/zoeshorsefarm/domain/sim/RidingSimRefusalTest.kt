package app.zoeshorsefarm.domain.sim

import app.zoeshorsefarm.domain.testing.CountingRng
import app.zoeshorsefarm.domain.testing.DEG
import app.zoeshorsefarm.domain.testing.DT
import app.zoeshorsefarm.domain.testing.DriveResult
import app.zoeshorsefarm.domain.testing.assertCloseTo
import app.zoeshorsefarm.domain.testing.brakeToHalt
import app.zoeshorsefarm.domain.testing.drive
import app.zoeshorsefarm.domain.testing.insideBlock
import app.zoeshorsefarm.domain.testing.jsRound
import app.zoeshorsefarm.domain.testing.makeElement
import app.zoeshorsefarm.domain.testing.makeSim
import app.zoeshorsefarm.domain.testing.obstaclesOf
import app.zoeshorsefarm.domain.testing.ofType
import app.zoeshorsefarm.domain.testing.placeBefore
import app.zoeshorsefarm.domain.testing.pressAt
import app.zoeshorsefarm.domain.testing.turnInPlace
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val S = TUNING.speeds
private val TROT = SimInput(throttle = 0.0)
private val CANTER = SimInput(gallop = true)
private val NONE = SimInput()

/** Space in the zone center for the current speed. */
private fun atCenter(
    sim: RidingSim,
    a: SimApproach,
): Double {
    val zone = assertNotNull(sim.zoneFor(a.elementId, a.dir, sim.horse.speed))
    return zone.far * 0.5 + zone.near * 0.5
}

private val untilQuiet: (RidingSim, List<SimEvent>, Double) -> Boolean =
    { s, _, _ -> s.horse.jump == null && s.horse.refusal == null && s.horse.z > 4 }

private val vertical = ElementKind.VERTICAL
private val cross = ElementKind.CROSS
private val oxer = ElementKind.OXER

class RidingSimRefusalTest {
    // ---- Self jump and refusal (rules 20, 22) ----

    @Test
    fun withoutSpaceSelfJumpWithMatchingGaitAngleAndSpeedWithRisk() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 8.0, speed = 5.8, gallop = true)
        val result = drive(sim, CANTER, until = untilQuiet)
        val take = result.events.ofType<SimEvent.Takeoff>()
        assertEquals(1, take.size)
        assertTrue(take[0].self)
        assertTrue(take[0].risk >= 0.3)
        assertEquals(1, result.events.ofType<SimEvent.Landed>().size)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
    }

    @Test
    fun refusalOnlyAtTheLastTakeoffPoint() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 8.0, speed = S.trotMedium)
        var prevDistance = Double.POSITIVE_INFINITY
        var atPrev: Double? = null
        var atNow: Double? = null
        drive(sim, TROT, maxT = 4.0, onStep = { s, ev, _ ->
            val info = approachInfo(v, s.horse, TUNING.approachDistance)
            if (ev.ofType<SimEvent.Refusal>().isNotEmpty()) {
                atPrev = prevDistance
                atNow = assertNotNull(info).distance
            }
            if (atPrev == null && info != null) prevDistance = info.distance
        })
        val lp = zoneForElement(v, S.trotMedium, TUNING).lastPoint
        assertTrue(assertNotNull(atPrev) > lp)
        assertTrue(assertNotNull(atNow) <= lp + 1e-9)
    }

    @Test
    fun strikingOffIntoCanterBeforehandNoRefusal() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 10.0, speed = S.trotMedium)
        sim.step(1.0 / 60, SimInput())
        val result =
            drive(
                sim,
                { s, _ -> SimInput(gallop = (sim.approach?.distance ?: 0.0) < 8 || s.horse.gallop) },
                until = untilQuiet,
            )
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(1, result.events.ofType<SimEvent.Landed>().size)
    }

    @Test
    fun aModerateSteeringCorrectionInTheLast5mStillJumpsWithoutARefusal() {
        val v = makeElement(vertical, 0.8)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 8.0, speed = 5.8, gallop = true)
        val press = pressAt(SimInput(gallop = true), ::atCenter)
        var correctingSince: Double? = null
        var maxHeading = 0.0
        val result =
            drive(
                sim,
                { s, t ->
                    // the correction (steer 0.5) starts 5 m before the obstacle and lasts 0.3 s
                    val a = s.approach
                    if (correctingSince == null && a != null && a.distance <= 5) correctingSince = t
                    val since = correctingSince
                    val correcting = since != null && t - since < 0.3
                    press(s, t).copy(steer = if (correcting) 0.5 else 0.0)
                },
                until = untilQuiet,
                maxT = 6.0,
                onStep = { s, _, _ -> maxHeading = max(maxHeading, abs(s.horse.heading)) },
            )
        assertNotNull(correctingSince)
        assertTrue(maxHeading > 0.05) // the correction really turned the horse
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(0, result.events.ofType<SimEvent.Swerve>().size)
        assertEquals(1, result.events.ofType<SimEvent.Takeoff>().size)
        assertEquals(1, result.events.ofType<SimEvent.Landed>().size)
    }

    @Test
    fun turningAwayBeforehandNoRefusal() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 8.0, speed = S.trotMedium)
        // from 6 m before the obstacle turn right until the course clearly misses it
        var turning = false
        val result =
            drive(sim, { s, _ ->
                val a = s.approach
                if (a != null && a.distance < 6) turning = true
                if (s.horse.heading < -1.2) turning = false
                SimInput(steer = if (turning) 1.0 else 0.0)
            }, maxT = 6.0)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(0, result.events.ofType<SimEvent.Swerve>().size)
    }

    @Test
    fun gaitStopsBeforeTheObstacleHaltGallopOff() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMax)
        var seenStop = false
        var inside = false
        val result =
            drive(sim, TROT, maxT = 4.0, onStep = { s, _, _ ->
                if (s.horse.refusal?.type == RefusalType.STOP) seenStop = true
                if (insideBlock(s, v)) inside = true
            })
        assertTrue(seenStop)
        assertFalse(inside)
        assertEquals(RefusalReason.GAIT, result.events.ofType<SimEvent.Refusal>()[0].reason)
        assertEquals(
            listOf(SimEvent.GallopEnded(GallopEndReason.REFUSAL)),
            result.events.ofType<SimEvent.GallopEnded>(),
        )
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
        assertNull(sim.horse.refusal)
        val p = toLocal(v, sim.horse.x, sim.horse.z)
        assertTrue(p.along < 0)
        assertTrue(p.along > -1)
    }

    @Test
    fun aRefusalAfterTheGallopEndedInTheStrikeOffStaysAHaltNoTrotFallBack() {
        val c = makeElement(cross, 0.45)
        val sim = makeSim(listOf(c))
        placeBefore(sim, c, 1.2, speed = 0.6, gallop = true)
        // the gallop is released while still below trotMin, then the horse refuses the cross
        sim.step(1.0 / 60, SimInput(gallop = false))
        val result = drive(sim, NONE, maxT = 4.0)
        assertEquals(1, result.events.ofType<SimEvent.Refusal>().size)
        drive(sim, NONE, maxT = 3.0)
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
    }

    @Test
    fun tooLittleSpeedRefusalSpeedStops() {
        val o = makeElement(oxer, 0.85, spread = 0.7)
        val sim = makeSim(listOf(o))
        placeBefore(sim, o, 8.0, speed = S.canterMin, gallop = true)
        val result = drive(sim, CANTER, maxT = 4.0)
        assertEquals(RefusalReason.SPEED, result.events.ofType<SimEvent.Refusal>()[0].reason)
        assertEquals(Gait.HALT, sim.horse.gait)
        assertFalse(sim.horse.gallop)

        val c = makeElement(cross, 0.45)
        val sim2 = makeSim(listOf(c))
        placeBefore(sim2, c, 6.0, speed = 2.0)
        val r2 = drive(sim2, TROT, maxT = 5.0)
        assertEquals(Gait.HALT, sim2.horse.gait)
        assertEquals(RefusalReason.SPEED, r2.events.ofType<SimEvent.Refusal>()[0].reason)
    }

    @Test
    fun shiftHeldAfterTheRefusalCanterOnlyAfterAFreshKeyPress() {
        val o = makeElement(oxer, 0.85, spread = 0.7)
        val sim = makeSim(listOf(o))
        placeBefore(sim, o, 6.0, speed = S.canterMin, gallop = true)
        drive(sim, CANTER, maxT = 4.0)
        assertFalse(sim.horse.gallop)
        sim.step(1.0 / 60, SimInput(gallop = false))
        sim.step(1.0 / 60, SimInput(gallop = true))
        assertTrue(sim.horse.gallop)
    }

    @Test
    fun gaitAndAngleTogetherGaitBehaviourStops() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium, angle = 40 * DEG)
        val result = drive(sim, TROT, maxT = 4.0)
        assertEquals(listOf(SimEvent.Refusal(v.id, 1, RefusalReason.GAIT)), result.events.ofType<SimEvent.Refusal>())
        assertEquals(Gait.HALT, sim.horse.gait)
    }

    @Test
    fun volteNextToTheObstacleNoRefusalNoEvasion() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        sim.reset(5.0, -2.0, 0.0, speed = S.trotMedium)
        val result = drive(sim, SimInput(steer = -0.6), maxT = 20.0)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(0, result.events.ofType<SimEvent.Swerve>().size)
    }

    @Test
    fun coursePastTheObstacleOutsideTheStandsNoRefusal() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        sim.reset(3.5, -8.0, 0.0, speed = S.trotMedium)
        val result = drive(sim, TROT, maxT = 4.0)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertTrue(sim.horse.z > 2)
    }

    // ---- Lock after refusal (rule 22) ----

    private val untilStopped: (RidingSim, List<SimEvent>, Double) -> Boolean =
        { s, _, _ -> s.horse.refusal == null && s.horse.speed == 0.0 }

    @Test
    fun approachingAgainWithinTheApproachDistanceEvasionInsteadOfRefusal() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        drive(sim, TROT, maxT = 3.0, until = untilStopped)
        turnInPlace(sim, PI)
        drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.z < -7 }, maxT = 20.0)
        brakeToHalt(sim)
        turnInPlace(sim, 0.0)
        // press gallop again and approach at canter without Space
        sim.step(1.0 / 60, SimInput(gallop = false))
        var inside = false
        var speedAtSwerve: Double? = null
        val result =
            drive(sim, CANTER, maxT = 6.0, onStep = { s, ev, _ ->
                if (insideBlock(s, v)) inside = true
                if (ev.ofType<SimEvent.Swerve>().isNotEmpty()) speedAtSwerve = s.horse.speed
            })
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(0, result.events.ofType<SimEvent.Takeoff>().size)
        assertEquals(listOf(SimEvent.Swerve(v.id)), result.events.ofType<SimEvent.Swerve>())
        assertEquals(0, result.events.ofType<SimEvent.GallopEnded>().size)
        assertFalse(inside)
        assertTrue(sim.horse.gallop)
        assertEquals(Gait.CANTER, sim.horse.gait)
        assertEquals(speedAtSwerve, sim.horse.speed)
        assertTrue(toLocal(v, sim.horse.x, sim.horse.z).along > 0)
    }

    @Test
    fun whileLockedTheHorseJumpsNormallyOnSpace() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        drive(sim, TROT, maxT = 3.0, until = untilStopped)
        turnInPlace(sim, PI)
        drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.z < -9 }, maxT = 20.0)
        brakeToHalt(sim)
        turnInPlace(sim, 0.0)
        sim.step(1.0 / 60, SimInput(gallop = false))
        val result = drive(sim, pressAt(CANTER, ::atCenter), until = untilQuiet)
        val take = result.events.ofType<SimEvent.Takeoff>()
        assertEquals(1, take.size)
        assertFalse(take[0].self)
        assertEquals(1, result.events.ofType<SimEvent.Landed>().size)
    }

    @Test
    fun afterMovingBeyondTheApproachDistanceARefusalIsPossibleAgain() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        drive(sim, TROT, maxT = 3.0, until = untilStopped)
        turnInPlace(sim, PI)
        drive(
            sim,
            SimInput(throttle = 1.0),
            until = { s, _, _ -> s.horse.z < -TUNING.approachDistance - 1 },
            maxT = 20.0,
        )
        brakeToHalt(sim)
        turnInPlace(sim, 0.0)
        drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.speed >= S.trotMedium }, maxT = 5.0)
        val result = drive(sim, TROT, maxT = 8.0)
        assertEquals(1, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(0, result.events.ofType<SimEvent.Swerve>().size)
    }

    // ---- Obstacles without refusal (rules.canRefuse = false) ----

    @Test
    fun withoutSpaceEvadeSidewaysGaitSpeedAndGallopAreKept() {
        val v = makeElement(vertical, 0.6, id = "v")
        val calls = ArrayList<Pair<String, Int>>()
        val sim =
            makeSim(listOf(v), canRefuse = { id, dir ->
                calls.add(id to dir)
                false
            })
        placeBefore(sim, v, 8.0, speed = 5.8, gallop = true)
        var inside = false
        val result =
            drive(sim, CANTER, maxT = 4.0, onStep = { s, _, _ ->
                if (insideBlock(s, v)) inside = true
                assertEquals(Gait.CANTER, s.horse.gait)
                assertEquals(5.8, s.horse.speed)
            })
        assertEquals(listOf("v" to 1), calls)
        assertEquals(listOf(SimEvent.Swerve("v")), result.events.ofType<SimEvent.Swerve>())
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(0, result.events.ofType<SimEvent.Takeoff>().size)
        assertEquals(0, result.events.ofType<SimEvent.GallopEnded>().size)
        assertFalse(inside)
        assertTrue(sim.horse.gallop)
        assertTrue(toLocal(v, sim.horse.x, sim.horse.z).along > 0)
    }

    @Test
    fun evenWithADisallowedGaitEvadeWithoutStopping() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v), canRefuse = { _, _ -> false })
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        val result = drive(sim, TROT, maxT = 4.0)
        assertEquals(1, result.events.ofType<SimEvent.Swerve>().size)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(S.trotMedium, sim.horse.speed)
        assertEquals(Gait.TROT, sim.horse.gait)
    }

    @Test
    fun withSpaceNormalJump() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v), canRefuse = { _, _ -> false })
        placeBefore(sim, v, 8.0, speed = 5.8, gallop = true)
        val result = drive(sim, pressAt(CANTER, ::atCenter), until = untilQuiet)
        val take = result.events.ofType<SimEvent.Takeoff>()[0]
        assertFalse(take.self)
        assertEquals(0.0, take.risk)
        assertEquals(1, result.events.ofType<SimEvent.Landed>().size)
    }

    // ---- Stands (rule 22) ----

    @Test
    fun bodyHitsTheStandEvadeInsteadOfRunningThrough() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        sim.reset(2.0, -8.0, 0.0, speed = 5.8, gallop = true)
        var inside = false
        val result =
            drive(sim, CANTER, maxT = 4.0, onStep = { s, _, _ ->
                if (insideBlock(s, v)) inside = true
            })
        assertEquals(1, result.events.ofType<SimEvent.Swerve>().size)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertFalse(inside)
        assertTrue(sim.horse.z > 1)
    }

    @Test
    fun sidewaysIntoTheObstacleTheHorseNeverRunsThrough() {
        val v = makeElement(oxer, 0.7, spread = 0.6)
        val sim = makeSim(listOf(v))
        sim.reset(-10.0, 0.0, PI / 2, speed = S.trotMedium)
        var inside = false
        val result =
            drive(sim, TROT, maxT = 8.0, onStep = { s, _, _ ->
                if (insideBlock(s, v)) inside = true
            })
        assertFalse(inside)
        assertEquals(1, result.events.ofType<SimEvent.Swerve>().size)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertTrue(sim.horse.x > 3)
    }

    @Test
    fun walkingAgainstTheStandEvadesLikeAtTrotTheHorseNeverTreadsOnTheSpot() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v), canRefuse = { _, _ -> false })
        sim.reset(-10.0, 0.0, PI / 2, speed = 1.2)
        var inside = false
        val result =
            drive(
                sim,
                { s, _ -> SimInput(throttle = if (s.horse.speed < 1.2) 1.0 else 0.0) },
                maxT = 16.0,
                onStep = { s, _, _ ->
                    if (insideBlock(s, v)) inside = true
                    assertTrue(s.horse.gait != Gait.TROT)
                },
            )
        assertFalse(inside)
        assertEquals(1, result.events.ofType<SimEvent.Swerve>().size)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        // it got past the obstacle instead of standing against it
        assertTrue(sim.horse.x > 3)
    }

    @Test
    fun afterARefusalStopWalkingIntoTheLockedElementEvadesInsteadOfPinning() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        drive(sim, TROT, maxT = 3.0, until = untilStopped)
        assertEquals(0.0, sim.horse.speed)
        var inside = false
        val result =
            drive(
                sim,
                { s, _ -> SimInput(throttle = if (s.horse.speed < 1.2) 1.0 else 0.0) },
                maxT = 12.0,
                onStep = { s, _, _ ->
                    if (insideBlock(s, v)) inside = true
                },
            )
        assertFalse(inside)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(1, result.events.ofType<SimEvent.Swerve>().size)
        assertTrue(toLocal(v, sim.horse.x, sim.horse.z).along > 1)
    }

    @Test
    fun standingAtTheObstacleHaltDoesNotTriggerAnEvasion() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v), canRefuse = { _, _ -> false })
        placeBefore(sim, v, 0.2, speed = 0.0)
        val result = drive(sim, NONE, maxT = 2.0)
        assertEquals(0, result.events.ofType<SimEvent.Swerve>().size)
    }

    // ---- Values from tuning ----

    @Test
    fun keepsTheFormerlyHardCodedValuesInTheTuningTable() {
        assertEquals(4.0, TUNING.refusal.stopDecelMin)
        assertCloseTo(sin(10 * DEG), TUNING.refusal.driftSide, 12)
        assertEquals(0.5, TUNING.jump.railChoice.firstProbability)
    }

    @Test
    fun theRefusalStopDeceleratesAtLeastWithRefusalStopDecelMin() {
        fun stopAlong(stopDecelMin: Double): Double {
            val v = makeElement(vertical, 0.6)
            val sim =
                makeSim(
                    listOf(v),
                    tuning = TUNING.copy(refusal = TUNING.refusal.copy(stopDecelMin = stopDecelMin)),
                )
            placeBefore(sim, v, 6.0, speed = 1.2)
            drive(sim, SimInput(throttle = 0.0), maxT = 8.0, until = { s, _, _ -> s.horse.speed == 0.0 })
            return toLocal(v, sim.horse.x, sim.horse.z).along
        }
        // a harder floor brakes earlier, so the horse stands further in front of the obstacle
        assertTrue(stopAlong(40.0) < stopAlong(4.0))
    }

    @Test
    fun refusalDriftSideDecidesWhenTheCourseDirectionPicksTheEvasionSide() {
        fun sideAfterEvading(driftSide: Double): Double {
            val v = makeElement(vertical, 0.6)
            val sim =
                makeSim(
                    listOf(v),
                    canRefuse = { _, _ -> false },
                    tuning = TUNING.copy(refusal = TUNING.refusal.copy(driftSide = driftSide)),
                )
            // course drifts toward +t (15 degrees) but meets the obstacle left of the center (-1 m)
            placeBefore(sim, v, 8.0, speed = S.trotMedium, angle = 15 * DEG, crossing = -1.0)
            drive(sim, TROT, maxT = 5.0)
            return kotlin.math.sign(toLocal(v, sim.horse.x, sim.horse.z).across)
        }
        assertEquals(1.0, sideAfterEvading(TUNING.refusal.driftSide))
        assertEquals(-1.0, sideAfterEvading(1.0))
    }

    @Test
    fun jumpRailChoiceFirstProbabilityPicksTheRailInTheMiddleOfTheOxerZone() {
        val speed = 4.8 // below the target range: risk > 0 although the horse is in the zone

        fun railFor(
            firstProbability: Double,
            draw: Double,
        ): Int? {
            val o = makeElement(oxer, 0.85, id = "o", spread = 0.7)
            var calls = 0
            // first draw: the knockdown (always), second draw: the rail choice
            val rng = { if (calls++ == 0) 0.0 else draw }
            val sim =
                makeSim(
                    listOf(o),
                    rng = rng,
                    tuning =
                        TUNING.copy(
                            jump = TUNING.jump.copy(railChoice = RailChoiceTuning(firstProbability)),
                        ),
                )
            val z = zoneForElement(o, speed, TUNING)
            val aim = (z.near + z.far) / 2
            placeBefore(sim, o, aim + 0.5, speed = speed, gallop = true)
            val result = drive(sim, pressAt(CANTER, aim), maxT = 4.0)
            return result.events
                .ofType<SimEvent.RailDown>()
                .firstOrNull()
                ?.rail
        }
        assertEquals(0, railFor(0.5, 0.4))
        assertEquals(1, railFor(0.5, 0.6))
        assertEquals(0, railFor(0.9, 0.6))
        assertEquals(1, railFor(0.1, 0.6))
    }

    // ---- Refusal stop and lock details ----

    @Test
    fun theRefusalStopLeavesTheHorseAVisibleDistanceInFrontOfThePole() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = 1.2)
        drive(sim, TROT, maxT = 8.0, until = untilStopped)
        val distance = -toLocal(v, sim.horse.x, sim.horse.z).along
        assertTrue(distance >= 0.35)
        assertTrue(distance < 1)
    }

    @Test
    fun theLockIsReleasedByTheSameDistanceMeasureAsTheApproach() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        drive(sim, TROT, maxT = 3.0, until = untilStopped)
        // back off 5 m, then ride sideways far beyond 12 m from the center, but along the
        // approach axis always closer than the approach distance
        turnInPlace(sim, PI)
        drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.z < -5 }, maxT = 20.0)
        brakeToHalt(sim)
        turnInPlace(sim, PI / 2)
        drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.x > 13 }, maxT = 20.0)
        brakeToHalt(sim)
        turnInPlace(sim, -PI / 2)
        drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.x < 0.2 }, maxT = 20.0)
        brakeToHalt(sim)
        turnInPlace(sim, 0.0)
        drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.speed >= S.trotMedium }, maxT = 5.0)
        val result = drive(sim, TROT, maxT = 8.0)
        assertEquals(0, result.events.ofType<SimEvent.Refusal>().size)
        assertEquals(1, result.events.ofType<SimEvent.Swerve>().size)
    }
}
