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

private fun untilQuiet(
    s: RidingSim,
    @Suppress("UNUSED_PARAMETER") ev: List<SimEvent>,
    @Suppress("UNUSED_PARAMETER") t: Double,
) = s.horse.jump == null && s.horse.refusal == null && s.horse.z > 4

private val vertical = ElementKind.VERTICAL
private val cross = ElementKind.CROSS
private val oxer = ElementKind.OXER

class RidingSimTest {
    // ---- Contract ----

    @Test
    fun providesHorseRailsApproachZoneForRebuildRebuildAll() {
        val v = makeElement(vertical, 0.6, id = "v")
        val o = makeElement(oxer, 0.7, id = "o", x = 10.0)
        val sim = makeSim(listOf(v, o))
        sim.reset(0.0, -10.0, 0.0)
        assertEquals(Gait.HALT, sim.horse.gait)
        assertFalse(sim.horse.gallop)
        assertNull(sim.horse.jump)
        assertNull(sim.horse.hop)
        assertNull(sim.horse.refusal)
        assertEquals(0.0, sim.horse.y)
        assertEquals(0.0, sim.horse.turnRate)
        assertContentEquals(booleanArrayOf(true), sim.rails["v"])
        assertContentEquals(booleanArrayOf(true, true), sim.rails["o"])
        val approach = assertNotNull(sim.approach)
        assertEquals("v", approach.elementId)
        assertEquals(1, approach.dir)
        assertCloseTo(10.0, approach.distance, 9)
        val z = assertNotNull(sim.zoneFor("v", 1, 5.8))
        assertEquals(zoneForElement(v, 5.8, TUNING), z)
        assertNull(sim.zoneFor("nope", 1, 5.0))
        assertEquals(emptyList(), sim.step(1.0 / 60, SimInput()))
    }

    @Test
    fun rebuildPutsThePolesOfOneElementUpAgainAndRebuildAllEveryElement() {
        val v = makeElement(vertical, 0.6, id = "v")
        val o = makeElement(oxer, 0.7, id = "o", x = 10.0)
        val sim = makeSim(listOf(v, o))
        sim.rails.getValue("v")[0] = false
        sim.rails.getValue("o")[1] = false
        sim.rebuild("v")
        assertContentEquals(booleanArrayOf(true), sim.rails["v"])
        assertContentEquals(booleanArrayOf(true, false), sim.rails["o"])
        sim.rebuildAll()
        assertContentEquals(booleanArrayOf(true, true), sim.rails["o"])
        sim.rebuild("unknown")
    }

    @Test
    fun approachNullWhenFacingAwayDirMinus1FromTheOtherSide() {
        val v = makeElement(vertical, 0.6, id = "v")
        val sim = makeSim(listOf(v))
        sim.reset(0.0, -5.0, PI)
        assertNull(sim.approach)
        sim.reset(0.0, 5.0, PI)
        val approach = assertNotNull(sim.approach)
        assertEquals("v", approach.elementId)
        assertEquals(-1, approach.dir)
        sim.reset(0.0, -20.0, 0.0)
        assertNull(sim.approach)
    }

    // ---- Jumpability by gait (rule 16) ----

    @Test
    fun walkAtACrossSpaceDoesNothingRefusalAtTheLastTakeoffPoint() {
        val c = makeElement(cross, 0.45)
        val sim = makeSim(listOf(c))
        placeBefore(sim, c, 4.0, speed = 1.5)
        val result = drive(sim, pressAt(NONE, 1.2), maxT = 6.0)
        assertEquals(0, result.events.ofType<SimEvent.Takeoff>().size)
        assertEquals(0, result.events.ofType<SimEvent.Hop>().size)
        assertEquals(listOf(SimEvent.Refusal(c.id, 1, RefusalReason.GAIT)), result.events.ofType<SimEvent.Refusal>())
    }

    @Test
    fun trotAtACrossJumpWithSpaceInTheZoneNoKnockdown() {
        val c = makeElement(cross, 0.45)
        val sim = makeSim(listOf(c))
        placeBefore(sim, c, 6.0, speed = S.trotMedium)
        val result = drive(sim, pressAt(TROT, ::atCenter), until = ::untilQuiet)
        assertEquals(listOf(SimEvent.Takeoff(c.id, 1, false, 0.0)), result.events.ofType<SimEvent.Takeoff>())
        assertEquals(listOf(SimEvent.Landed(c.id, 1, false)), result.events.ofType<SimEvent.Landed>())
    }

    @Test
    fun trotAtAVerticalSpaceDoesNothingRefusalStops() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        val result = drive(sim, pressAt(TROT, ::atCenter), maxT = 5.0)
        assertEquals(0, result.events.ofType<SimEvent.Takeoff>().size)
        assertEquals(0, result.events.ofType<SimEvent.Hop>().size)
        assertEquals(RefusalReason.GAIT, result.events.ofType<SimEvent.Refusal>()[0].reason)
        assertEquals(Gait.HALT, sim.horse.gait)
    }

    @Test
    fun canterVerticalAndOxerAreJumped() {
        for (el in listOf(makeElement(vertical, 0.8), makeElement(oxer, 0.85))) {
            val sim = makeSim(listOf(el))
            placeBefore(sim, el, 8.0, speed = S.canterMedium, gallop = true)
            val result = drive(sim, pressAt(CANTER, ::atCenter), until = ::untilQuiet)
            assertEquals(listOf(SimEvent.Landed(el.id, 1, false)), result.events.ofType<SimEvent.Landed>())
        }
    }

    @Test
    fun bothDirectionsAreJumpable() {
        val o = makeElement(oxer, 0.7, spread = 0.6)
        val sim = makeSim(listOf(o))
        placeBefore(sim, o, 8.0, dir = -1, speed = S.canterMedium, gallop = true)
        val result =
            drive(sim, pressAt(CANTER, ::atCenter), until = { s, _, _ -> s.horse.jump == null && s.horse.z < -4 })
        assertEquals(listOf(SimEvent.Landed(o.id, -1, false)), result.events.ofType<SimEvent.Landed>())
    }

    // ---- Approach angle (rule 17) ----

    @Test
    fun over30DegreesNoJumpEvenWithSpaceRefusalWithRunOutThenTrot() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 8.0, angle = 35 * DEG, speed = S.canterMedium, gallop = true)
        var inside = false
        val result =
            drive(sim, pressAt(CANTER, ::atCenter), maxT = 6.0, onStep = { s, _, _ ->
                if (insideBlock(s, v)) inside = true
            })
        assertEquals(0, result.events.ofType<SimEvent.Takeoff>().size)
        assertEquals(0, result.events.ofType<SimEvent.Hop>().size)
        assertEquals(listOf(SimEvent.Refusal(v.id, 1, RefusalReason.ANGLE)), result.events.ofType<SimEvent.Refusal>())
        assertEquals(
            listOf(SimEvent.GallopEnded(GallopEndReason.REFUSAL)),
            result.events.ofType<SimEvent.GallopEnded>(),
        )
        assertFalse(inside)
        assertNull(sim.horse.refusal)
        assertEquals(Gait.TROT, sim.horse.gait)
        assertFalse(sim.horse.gallop)
        // is past the obstacle
        val p = toLocal(v, sim.horse.x, sim.horse.z)
        assertTrue(p.along > 0)
    }

    @Test
    fun runoutSetsHorseRefusalWithTypeRunout() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 4.0, angle = 40 * DEG, speed = S.canterMedium, gallop = true)
        var seen: RefusalType? = null
        drive(sim, CANTER, maxT = 3.0, onStep = { s, _, _ ->
            val r = s.horse.refusal
            if (r != null && seen == null) seen = r.type
        })
        assertEquals(RefusalType.RUNOUT, seen)
    }

    @Test
    fun upTo30DegreesItIsJumpedBeyondTheToleranceWithRisk() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 8.0, angle = 25 * DEG, speed = S.canterMedium, gallop = true)
        val result = drive(sim, pressAt(CANTER, ::atCenter), maxT = 4.0)
        val take = result.events.ofType<SimEvent.Takeoff>()
        assertEquals(1, take.size)
        assertTrue(take[0].risk > 0)
        assertEquals(1, result.events.ofType<SimEvent.Landed>().size)
    }

    // ---- Safe core and knockdown risk (rules 15, 18, 19) ----

    private class JumpOnce(
        val knocked: Boolean,
        val risk: Double,
    )

    /** One jump with Space at the given distance/speed/angle. */
    private fun jumpOnce(
        el: Element,
        seed: Int,
        speed: Double,
        distance: Double,
        angle: Double = 0.0,
    ): JumpOnce {
        val sim = makeSim(listOf(el), seed = seed)
        val gallop = !(el.kind == cross && speed <= S.trotMax)
        placeBefore(sim, el, distance + 0.5, speed = speed, gallop = gallop, angle = angle)
        val input = if (gallop) SimInput(gallop = true) else SimInput()
        val result = drive(sim, pressAt(input, distance), maxT = 4.0)
        val take = result.events.ofType<SimEvent.Takeoff>()
        val landed = result.events.ofType<SimEvent.Landed>()
        check(take.size == 1 && landed.size == 1) { "no jump" }
        return JumpOnce(landed[0].knocked, take[0].risk)
    }

    private fun knockRate(
        el: Element,
        speed: Double,
        distance: Double,
        angle: Double = 0.0,
        seeds: Int = 300,
    ): Double {
        var n = 0
        for (seed in 1..seeds) if (jumpOnce(el, seed, speed, distance, angle).knocked) n++
        return n.toDouble() / seeds
    }

    @Test
    fun safeCore100PercentWithoutKnockdownOverManySeedsWithoutARandomDraw() {
        val cases =
            listOf(
                makeElement(cross, 0.45) to 3.2,
                makeElement(vertical, 0.6) to 5.8,
                makeElement(oxer, 0.85) to 6.2,
            )
        for ((el, speed) in cases) {
            val z = zoneForElement(el, speed, TUNING)
            for (seed in 1..200) {
                val rng = CountingRng(seed)
                val sim = makeSim(listOf(el), rng = rng)
                val gallop = el.kind != cross
                placeBefore(sim, el, z.far + 1.5, speed = speed, gallop = gallop)
                // Space is pressed in the first frame with distance <= target (up to v * dt closer)
                val target = z.near + speed / 60 + ((z.far - z.near - speed / 60) * seed) / 201
                val result = drive(sim, pressAt(if (gallop) CANTER else TROT, target), maxT = 4.0)
                assertEquals(0.0, result.events.ofType<SimEvent.Takeoff>()[0].risk)
                assertFalse(result.events.ofType<SimEvent.Landed>()[0].knocked)
                assertEquals(0, result.events.ofType<SimEvent.RailDown>().size)
                assertEquals(0, rng.calls)
            }
        }
    }

    @Test
    fun knockdownRateRisesWithTheDistanceDeviationTooEarly() {
        val v = makeElement(vertical, 0.6)
        val z = zoneForElement(v, 5.8, TUNING)
        val rates = listOf(0.4, 1.0, 1.8).map { dd -> knockRate(v, 5.8, z.far + dd) }
        assertTrue(rates[0] > 0)
        assertTrue(rates[1] > rates[0])
        assertTrue(rates[2] > rates[1])
    }

    @Test
    fun knockdownRateRisesWithTheSpeedDeviation() {
        val v = makeElement(vertical, 0.8)
        val band = speedBand(v, TUNING)

        fun at(speed: Double) = knockRate(v, speed, zoneForElement(v, speed, TUNING).center)
        val inBand = at(band.min + 0.2)
        val slight = at(band.max + 0.3)
        val strong = at(S.canterMax)
        assertEquals(0.0, inBand)
        assertTrue(slight > 0)
        assertTrue(strong > slight)
    }

    @Test
    fun knockdownRateRisesWithTheAngle() {
        val v = makeElement(vertical, 0.6)
        val center = zoneForElement(v, 5.8, TUNING).center
        val r = listOf(5, 18, 29).map { a -> knockRate(v, 5.8, center, a * DEG) }
        assertEquals(0.0, r[0])
        assertTrue(r[1] > 0)
        assertTrue(r[2] > r[1])
    }

    @Test
    fun oxer85cmHigherKnockdownRateThanACrossAtTheSameDeviation() {
        val c = makeElement(cross, 0.45)
        val o = makeElement(oxer, 0.85, spread = 0.7)
        val rc = knockRate(c, 6.5, zoneForElement(c, 6.5, TUNING).far + 0.6, seeds = 400)
        val ro = knockRate(o, 6.5, zoneForElement(o, 6.5, TUNING).far + 0.6, seeds = 400)
        assertTrue(rc > 0)
        assertTrue(ro > rc * 1.5)
    }

    @Test
    fun tooCloseBetweenZoneAndLastTakeoffPointIncreasesTheRisk() {
        val o = makeElement(oxer, 0.85, spread = 0.7)
        val z = zoneForElement(o, 5.8, TUNING)
        val jump = jumpOnce(o, 1, 5.8, (z.near + z.lastPoint) / 2)
        assertTrue(jump.risk > 0)
    }

    // ---- Knockdown and poles (rule 23) ----

    @Test
    fun knockdownRailDownWhenCrossingRailsUpdatedLandedKnocked() {
        val c = makeElement(cross, 0.45, id = "c")
        val sim = makeSim(listOf(c), rng = { 0.0 })
        val z = zoneForElement(c, S.trotMax, TUNING)
        placeBefore(sim, c, z.reach - 0.05, speed = S.trotMax)
        var railAt: Double? = null
        var takeoffSeen = false
        val result =
            drive(sim, { _, _ -> SimInput(jump = !takeoffSeen) }, maxT = 4.0, onStep = { s, ev, _ ->
                if (ev.ofType<SimEvent.Takeoff>().isNotEmpty()) takeoffSeen = true
                if (ev.ofType<SimEvent.RailDown>().isNotEmpty()) railAt = toLocal(c, s.horse.x, s.horse.z).along
            })
        assertEquals(listOf(SimEvent.RailDown("c", 0, 1)), result.events.ofType<SimEvent.RailDown>())
        val at = assertNotNull(railAt)
        assertTrue(at >= 0)
        assertTrue(at < 0.2)
        assertContentEquals(booleanArrayOf(false), sim.rails["c"])
        assertEquals(listOf(SimEvent.Landed("c", 1, true)), result.events.ofType<SimEvent.Landed>())

        // a pole already down does not fall again
        placeBefore(sim, c, z.reach - 0.05, speed = S.trotMax)
        takeoffSeen = false
        val again =
            drive(sim, { _, _ -> SimInput(jump = !takeoffSeen) }, maxT = 4.0, onStep = { _, ev, _ ->
                if (ev.ofType<SimEvent.Takeoff>().isNotEmpty()) takeoffSeen = true
            })
        assertEquals(0, again.events.ofType<SimEvent.RailDown>().size)
        assertFalse(again.events.ofType<SimEvent.Landed>()[0].knocked)

        sim.rebuild("c")
        assertContentEquals(booleanArrayOf(true), sim.rails["c"])
    }

    @Test
    fun oxerTooCloseKnocksThePoleCrossedFirstDependingOnDirection() {
        for (dir in listOf(1, -1)) {
            val o = makeElement(oxer, 0.85, id = "o", spread = 0.7)
            val sim = makeSim(listOf(o), rng = { 0.0 })
            val z = zoneForElement(o, 5.8, TUNING)
            placeBefore(sim, o, z.near + 0.3, dir = dir, speed = 5.8, gallop = true)
            val result = drive(sim, pressAt(CANTER, (z.near + z.lastPoint) / 2), maxT = 4.0)
            assertEquals(
                listOf(SimEvent.RailDown("o", if (dir > 0) 0 else 1, dir)),
                result.events.ofType<SimEvent.RailDown>(),
            )
            assertContentEquals(
                if (dir >
                    0
                ) {
                    booleanArrayOf(false, true)
                } else {
                    booleanArrayOf(true, false)
                },
                sim.rails["o"],
            )
            sim.rebuildAll()
            assertContentEquals(booleanArrayOf(true, true), sim.rails["o"])
        }
    }

    // ---- Jump sequence (rule 24) ----

    @Test
    fun phasesTakeoffFlightLandingHeightAboveTheObstacleSteeringLocked() {
        val v = makeElement(vertical, 0.8)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 5.0, speed = 6.0, gallop = true)
        val phases = ArrayList<JumpPhase>()
        var maxY = 0.0
        var headingAtTakeoff: Double? = null
        // straight run-in; full lock is held only once the jump has started (steering is locked)
        val press = pressAt(SimInput(gallop = true), ::atCenter)
        drive(
            sim,
            { s, t -> press(s, t).copy(steer = if (s.horse.jump != null) 1.0 else 0.0) },
            maxT = 3.0,
            onStep = { s, ev, _ ->
                if (ev.ofType<SimEvent.Takeoff>().isNotEmpty()) headingAtTakeoff = s.horse.heading
                val jump = s.horse.jump
                if (jump != null) {
                    if (phases.lastOrNull() != jump.phase) phases.add(jump.phase)
                    assertTrue(jump.progress >= 0)
                    assertTrue(jump.progress <= 1)
                    assertEquals(v.id, jump.elementId)
                    assertEquals(headingAtTakeoff, s.horse.heading)
                    assertEquals(6.0, s.horse.speed)
                    maxY = max(maxY, s.horse.y)
                }
            },
        )
        assertEquals(listOf(JumpPhase.TAKEOFF, JumpPhase.FLIGHT, JumpPhase.LANDING), phases)
        assertTrue(maxY > v.height)
        assertNull(sim.horse.jump)
        assertEquals(0.0, sim.horse.y)
    }

    // ---- Self jump and refusal (rules 20, 22) ----

    @Test
    fun withoutSpaceSelfJumpWithMatchingGaitAngleAndSpeedWithRisk() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 8.0, speed = 5.8, gallop = true)
        val result = drive(sim, CANTER, until = ::untilQuiet)
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
                until = ::untilQuiet,
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
                until = ::untilQuiet,
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

    private fun untilStopped(
        s: RidingSim,
        @Suppress("UNUSED_PARAMETER") ev: List<SimEvent>,
        @Suppress("UNUSED_PARAMETER") t: Double,
    ) = s.horse.refusal == null && s.horse.speed == 0.0

    @Test
    fun approachingAgainWithinTheApproachDistanceEvasionInsteadOfRefusal() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        drive(sim, TROT, maxT = 3.0, until = ::untilStopped)
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
        drive(sim, TROT, maxT = 3.0, until = ::untilStopped)
        turnInPlace(sim, PI)
        drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.z < -9 }, maxT = 20.0)
        brakeToHalt(sim)
        turnInPlace(sim, 0.0)
        sim.step(1.0 / 60, SimInput(gallop = false))
        val result = drive(sim, pressAt(CANTER, ::atCenter), until = ::untilQuiet)
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
        drive(sim, TROT, maxT = 3.0, until = ::untilStopped)
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
        val result = drive(sim, pressAt(CANTER, ::atCenter), until = ::untilQuiet)
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
        drive(sim, TROT, maxT = 3.0, until = ::untilStopped)
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
        drive(sim, TROT, maxT = 8.0, until = ::untilStopped)
        val distance = -toLocal(v, sim.horse.x, sim.horse.z).along
        assertTrue(distance >= 0.35)
        assertTrue(distance < 1)
    }

    @Test
    fun theLockIsReleasedByTheSameDistanceMeasureAsTheApproach() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 6.0, speed = S.trotMedium)
        drive(sim, TROT, maxT = 3.0, until = ::untilStopped)
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

    // ---- Space buffer while landing ----

    private fun center(el: Element): Double {
        val z = zoneForElement(el, 5.8, TUNING)
        return (z.near + z.far) / 2
    }

    /** Jumps `a` at canter and returns the z of the horse at landing (probe run). */
    private fun landingZ(a: Element): Double {
        val probe = makeSim(listOf(a))
        placeBefore(probe, a, center(a), speed = 5.8, gallop = true)
        drive(
            probe,
            pressAt(CANTER, center(a)),
            maxT = 4.0,
            until = { _, ev, _ -> ev.ofType<SimEvent.Landed>().isNotEmpty() },
        )
        return probe.horse.z
    }

    /** Space for `a` in its zone, then once more shortly before landing. */
    private fun pressTwice(a: Element): (RidingSim, Double) -> SimInput {
        var first = false
        var second = false
        return { s, _ ->
            val jump = s.horse.jump
            val ap = s.approach
            if (!first) {
                if (jump == null && ap?.elementId == "a" && ap.distance <= center(a)) {
                    first = true
                    CANTER.copy(jump = true)
                } else {
                    CANTER
                }
            } else if (!second && jump?.phase == JumpPhase.LANDING && jump.progress > 0.5) {
                second = true
                CANTER.copy(jump = true)
            } else {
                CANTER
            }
        }
    }

    private fun pair(): Pair<Element, Element> {
        val a = makeElement(vertical, 0.7, id = "a")
        val b0 = makeElement(vertical, 0.7, id = "b")
        // b lies so that it is in the middle of its takeoff zone at the moment of landing
        val b = b0.copy(z = landingZ(a) + center(b0))
        return a to b
    }

    @Test
    fun aPressShortlyBeforeLandingIsCarriedOverAndJumpsTheNextObstacle() {
        val (a, b) = pair()
        val sim = makeSim(listOf(a, b))
        placeBefore(sim, a, center(a), speed = 5.8, gallop = true)
        val result = drive(sim, pressTwice(a), maxT = 3.0)
        val takes = result.events.ofType<SimEvent.Takeoff>()
        assertEquals(listOf("a", "b"), takes.map { it.elementId })
        assertFalse(takes[1].self)
    }

    @Test
    fun aCarriedPressExpiresWithoutAHopWhenNoObstacleComesWithinReach() {
        val (a, _) = pair()
        val sim = makeSim(listOf(a))
        placeBefore(sim, a, center(a), speed = 5.8, gallop = true)
        val result = drive(sim, pressTwice(a), maxT = 3.0)
        assertEquals(1, result.events.ofType<SimEvent.Takeoff>().size)
        assertEquals(0, result.events.ofType<SimEvent.Hop>().size)
    }

    @Test
    fun aPressFarBeforeLandingIsNotCarriedOver() {
        val (a, b) = pair()
        val sim = makeSim(listOf(a, b))
        placeBefore(sim, a, center(a), speed = 5.8, gallop = true)
        var first = false
        var second = false
        val result =
            drive(sim, { s, _ ->
                val jump = s.horse.jump
                val ap = s.approach
                if (!first && jump == null && ap?.elementId == "a" && ap.distance <= center(a)) {
                    first = true
                    CANTER.copy(jump = true)
                } else if (first && !second && jump?.phase == JumpPhase.TAKEOFF) {
                    second = true
                    CANTER.copy(jump = true)
                } else {
                    CANTER
                }
            }, maxT = 3.0)
        // no buffered jump: b is only jumped by itself (self jump) at its last takeoff point
        val takes = result.events.ofType<SimEvent.Takeoff>()
        assertTrue(takes[1].self)
    }

    // ---- Hop (rule 21) ----

    @Test
    fun trotWithNoObstacleInReachHopWithoutCounting() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0, speed = S.trotMedium)
        val ev = sim.step(1.0 / 60, SimInput(jump = true))
        assertEquals(listOf<SimEvent>(SimEvent.Hop), ev)
        var maxY = 0.0
        val result = drive(sim, NONE, maxT = 1.0, onStep = { s, _, _ -> maxY = max(maxY, s.horse.y) })
        assertTrue(maxY > 0.1)
        assertNull(sim.horse.hop)
        assertEquals(0, result.events.ofType<SimEvent.Landed>().size)
        assertCloseTo(S.trotMedium, sim.horse.speed, 9)
    }

    @Test
    fun canterWithNoObstacleInReachHop() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0, speed = 5.8, gallop = true)
        val ev = sim.step(1.0 / 60, SimInput(gallop = true, jump = true))
        assertEquals(listOf<SimEvent>(SimEvent.Hop), ev)
        assertNotNull(sim.horse.hop)
    }

    @Test
    fun haltAndWalkNothing() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0)
        assertEquals(emptyList(), sim.step(1.0 / 60, SimInput(jump = true)))
        sim.reset(0.0, 0.0, 0.0, speed = 1.5)
        assertEquals(emptyList(), sim.step(1.0 / 60, SimInput(jump = true)))
    }

    @Test
    fun obstacleInReachGaitNotAllowedNoHop() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 2.0, speed = S.trotMedium)
        assertEquals(emptyList(), sim.step(1.0 / 60, SimInput(jump = true)))
    }

    @Test
    fun obstacleInReachAngleNotAllowedNoHop() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 2.5, speed = 5.8, gallop = true, angle = 35 * DEG)
        assertEquals(emptyList(), sim.step(1.0 / 60, SimInput(gallop = true, jump = true)))
    }

    @Test
    fun obstacleStillOutOfReachHop() {
        val v = makeElement(vertical, 0.6)
        val sim = makeSim(listOf(v))
        placeBefore(sim, v, 9.0, speed = 5.8, gallop = true)
        assertEquals(listOf<SimEvent>(SimEvent.Hop), sim.step(1.0 / 60, SimInput(gallop = true, jump = true)))
    }

    // ---- Double combination ----

    private fun doubleCombination(kind: ElementKind) {
        val a = makeElement(kind, 0.8, id = "a", spread = 0.6)
        val b = makeElement(kind, 0.8, id = "b", z = COMBI_DISTANCE, spread = 0.6)
        val sim = makeSim(emptyList(), obstacles = listOf(Obstacle(5, listOf(a, b), true)))
        sim.reset(0.0, -20.0, 0.0, speed = S.canterMedium, gallop = true)
        sim.step(1.0 / 60, SimInput(gallop = true))
        val pressedFor = HashSet<String>()
        val bApproachAfterLanding = ArrayList<SimApproach?>()
        val result =
            drive(
                sim,
                { s, _ ->
                    val ap = s.approach
                    if (ap != null && s.horse.jump == null && !pressedFor.contains(ap.elementId) &&
                        ap.distance <= atCenter(s, ap)
                    ) {
                        pressedFor.add(ap.elementId)
                        SimInput(gallop = true, jump = true)
                    } else {
                        SimInput(gallop = true)
                    }
                },
                until = { s, _, _ -> s.horse.z > COMBI_DISTANCE + 4 },
                onStep = { s, ev, _ ->
                    if (ev.ofType<SimEvent.Landed>().any { it.elementId == "a" }) bApproachAfterLanding.add(s.approach)
                },
            )
        assertEquals(
            listOf(SimEvent.Landed("a", 1, false), SimEvent.Landed("b", 1, false)),
            result.events.ofType<SimEvent.Landed>(),
        )
        assertEquals(listOf(0.0, 0.0), result.events.ofType<SimEvent.Takeoff>().map { it.risk })
        val zoneB = zoneForElement(b, S.canterMedium, TUNING)
        val first = assertNotNull(bApproachAfterLanding[0])
        assertEquals("b", first.elementId)
        assertTrue(first.distance > zoneB.far)
    }

    @Test
    fun verticalAAndBAtCanterWithSpaceEachInTheZone2CountedJumps() = doubleCombination(vertical)

    @Test
    fun oxerAAndBAtCanterWithSpaceEachInTheZone2CountedJumps() = doubleCombination(oxer)

    // ---- Determinism ----

    @Test
    fun sameSeedSameInputsSameEvents() {
        fun run(seed: Int): List<SimEvent> {
            val v = makeElement(vertical, 0.8, id = "v")
            val sim = makeSim(listOf(v), seed = seed)
            placeBefore(sim, v, 8.0, speed = 7.5, gallop = true, angle = 20 * DEG)
            return drive(sim, pressAt(CANTER, 4.0), maxT = 3.0).events
        }
        val runs = (1..5).map { run(it) }
        assertEquals(runs[0], run(1))
        assertEquals(runs[3], run(4))
    }

    @Test
    fun defaultsRunsWithoutRulesRngAndTuning() {
        val v = makeElement(vertical, 0.6)
        val sim = RidingSim(obstacles = obstaclesOf(v))
        placeBefore(sim, v, 8.0, speed = 5.8, gallop = true)
        val result: DriveResult = drive(sim, CANTER, maxT = 3.0)
        assertEquals(1, result.events.ofType<SimEvent.Landed>().size)
    }

    // ---- Robustness ----

    @Test
    fun randomRidingThroughASetupNeverInsideAnObstacleNeverOutsideTheArena() {
        val els =
            listOf(
                makeElement(cross, 0.45, id = "k", x = -10.0, z = -15.0),
                makeElement(vertical, 0.6, id = "s", x = 10.0, z = -15.0, rot = PI / 2),
                makeElement(oxer, 0.85, id = "o", x = -10.0, z = 15.0, rot = 0.4),
            )
        val ca = makeElement(vertical, 0.7, id = "ca", x = 8.0, z = 10.0)
        val cb = makeElement(oxer, 0.8, id = "cb", x = 8.0, z = 10 + COMBI_DISTANCE, spread = 0.6)
        val all = els + listOf(ca, cb)
        val maxX = 20 - TUNING.horse.radius + 1e-9
        val maxZ = 35 - TUNING.horse.radius + 1e-9
        for (seed in listOf(1, 2, 3)) {
            val sim =
                makeSim(
                    emptyList(),
                    seed = seed,
                    obstacles =
                        els.map { Obstacle(null, listOf(it), false) } + Obstacle(null, listOf(ca, cb), false),
                )
            sim.reset(0.0, 0.0, 0.0)
            val rng = createRng(seed * 101)
            var input = SimInput()
            var takeoffs = 0
            var landed = 0
            drive(
                sim,
                { _, t ->
                    if (jsRound(t * 60) % 45 == 0.0) {
                        val steer = if (rng() < 0.5) 0.0 else rng() * 2 - 1
                        val throttle = rng() * 2 - 0.7
                        val gallop = rng() < 0.6
                        input = SimInput(steer = steer, throttle = throttle, gallop = gallop)
                    }
                    input.copy(jump = rng() < 0.03)
                },
                maxT = 120.0,
                onStep = { s, ev, _ ->
                    takeoffs += ev.ofType<SimEvent.Takeoff>().size
                    landed += ev.ofType<SimEvent.Landed>().size
                    assertTrue(abs(s.horse.x) <= maxX)
                    assertTrue(abs(s.horse.z) <= maxZ)
                    for (el in all) {
                        val active = s.horse.jump
                        if (active != null && active.elementId == el.id) continue
                        assertFalse(insideBlock(s, el))
                    }
                    assertTrue(s.horse.heading.isFinite())
                    // the random throttle can also rein the horse back (slowly)
                    assertTrue(s.horse.speed >= -TUNING.reinBack.maxSpeed - 1e-9)
                    assertTrue(s.horse.speed <= S.canterMax)
                },
            )
            assertTrue(landed >= takeoffs - 1)
            assertTrue(landed <= takeoffs)
        }
    }

    // ---- Rein-back (rules 8, 9, 24) ----

    private val r = TUNING.reinBack
    private val back = SimInput(throttle = -1.0)
    private val arenaHalfLength = ARENA.length / 2

    /** Rear point (hindquarters) of the horse. */
    private fun rearOf(h: Horse) =
        Vec2(
            h.x - sin(h.heading) * TUNING.horse.rearLength,
            h.z - cos(h.heading) * TUNING.horse.rearLength,
        )

    private fun noTrouble(events: List<SimEvent>) {
        // takeoff, landed, refusal, swerve, hop, fenceStop, gallopEnded: everything but a fallen rail
        assertEquals(emptyList(), events.filter { it !is SimEvent.RailDown })
    }

    @Test
    fun waitsAShortPauseInHaltThenWalksBackwardsWithGaitBack() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0)
        drive(sim, back, maxT = r.delayS - 0.05)
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
        drive(sim, back, maxT = 0.2)
        assertTrue(sim.horse.speed < 0)
        assertEquals(Gait.BACK, sim.horse.gait)
        assertTrue(sim.horse.z < 0)
        assertCloseTo(0.0, sim.horse.x, 9)
    }

    @Test
    fun backsAlongTheReverseOfTheHeadingSlowerThanTheWalk() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 90 * DEG)
        drive(sim, back, maxT = 4.0)
        assertCloseTo(-r.maxSpeed, sim.horse.speed, 9)
        assertTrue(sim.horse.x < -1)
        assertCloseTo(0.0, sim.horse.z, 6)
        assertTrue(r.maxSpeed < S.walkMax)
    }

    @Test
    fun stopsWhenSIsReleased() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0)
        drive(sim, back, maxT = 2.0)
        assertEquals(Gait.BACK, sim.horse.gait)
        drive(sim, NONE, maxT = 1.0)
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
        val z = sim.horse.z
        drive(sim, NONE, maxT = 1.0)
        assertEquals(z, sim.horse.z)
    }

    @Test
    fun wEndsItAndTheHorseWalksOffForwards() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0)
        drive(sim, back, maxT = 2.0)
        sim.step(DT, SimInput(throttle = 1.0))
        assertTrue(sim.horse.speed >= 0)
        assertTrue(sim.horse.gait != Gait.BACK)
        val zBack = sim.horse.z
        drive(sim, SimInput(throttle = 1.0), maxT = 2.0)
        assertTrue(sim.horse.z > zBack)
    }

    @Test
    fun gallopEndsIt() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0)
        drive(sim, back, maxT = 2.0)
        sim.step(DT, SimInput(throttle = -1.0, gallop = true))
        assertEquals(Gait.CANTER, sim.horse.gait)
        assertTrue(sim.horse.speed >= 0)
    }

    @Test
    fun canBeSteeredWhileBackingTheHorseStillMovesBackwards() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0)
        drive(sim, back, maxT = 1.0)
        val heading = sim.horse.heading
        drive(sim, SimInput(throttle = -1.0, steer = 1.0), maxT = 1.0)
        assertTrue(abs(wrapAngle(sim.horse.heading - heading)) > 0.3)
        assertEquals(Gait.BACK, sim.horse.gait)
        assertTrue(sim.horse.z < 0)
        assertTrue(abs(sim.horse.x) > 0.01)
    }

    @Test
    fun brakingFromAWalkWithSStopsFirstBackingStartsOnlyAfterThePause() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0, speed = 1.2)
        var backedWhileMoving = false
        drive(sim, back, maxT = 2.0, onStep = { s, _, _ ->
            if (s.horse.gait == Gait.BACK && s.horse.z > 0.001 && s.horse.speed > 0) backedWhileMoving = true
        })
        assertFalse(backedWhileMoving)
        assertEquals(Gait.BACK, sim.horse.gait)
    }

    @Test
    fun spaceDoesNotJumpOrHopWhileBacking() {
        val c = makeElement(cross, 0.45, id = "c")
        val sim = makeSim(listOf(c))
        placeBefore(sim, c, 1.5)
        assertTrue(assertNotNull(sim.zoneFor("c", 1, 2.0)).reach > 1.5)
        val result =
            drive(sim, SimInput(throttle = -1.0, jump = true), maxT = 4.0, onStep = { s, _, _ ->
                assertNull(s.horse.jump)
                assertNull(s.horse.hop)
                assertEquals(0.0, s.horse.y)
            })
        assertEquals(Gait.BACK, sim.horse.gait)
        noTrouble(result.events)
    }

    @Test
    fun aSpacePressedJustBeforeTheBackingStartsIsNotCarriedIntoIt() {
        val c = makeElement(cross, 0.45, id = "c")
        val sim = makeSim(listOf(c))
        placeBefore(sim, c, 1.5)
        sim.step(DT, SimInput(throttle = -1.0, jump = true))
        val result = drive(sim, back, maxT = 2.0)
        noTrouble(result.events)
    }

    @Test
    fun backingAwayFromAnObstacleTriggersNoRefusalSwerveOrTakeoff() {
        val c = makeElement(cross, 0.45, id = "c")
        val sim = makeSim(listOf(c))
        // Walk up to it, brake (the obstacle is armed) and keep S held
        placeBefore(sim, c, 3.0, speed = 1.0)
        val result = drive(sim, back, maxT = 6.0)
        noTrouble(result.events)
        assertEquals(Gait.BACK, sim.horse.gait)
        assertNull(sim.approach)
    }

    @Test
    fun backingVeryCloseToAnObstacleFrontCausesNoEventsEither() {
        val c = makeElement(cross, 0.45, id = "c")
        val sim = makeSim(listOf(c))
        placeBefore(sim, c, 0.4)
        val result = drive(sim, back, maxT = 4.0)
        noTrouble(result.events)
        assertTrue(toLocal(c, sim.horse.x, sim.horse.z).along < -0.4)
    }

    @Test
    fun ridingForwardAtTheObstacleWorksAsBeforeAfterBackingOff() {
        val c = makeElement(cross, 0.45, id = "c")
        val sim = makeSim(listOf(c))
        placeBefore(sim, c, 1.0)
        drive(sim, back, maxT = 3.0)
        drive(sim, NONE, maxT = 1.0)
        assertEquals(Gait.HALT, sim.horse.gait)
        val result = drive(sim, SimInput(throttle = 1.0), until = { s, _, _ -> s.horse.refusal != null }, maxT = 10.0)
        // a walking horse is not allowed to jump: a normal refusal, not a stuck state
        assertTrue(result.events.ofType<SimEvent.Refusal>().size + result.events.ofType<SimEvent.Takeoff>().size > 0)
    }

    @Test
    fun theFenceHoldsTheHindquartersNoRearPointOutsideNoFenceStopStaysStopped() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 30.0, PI)
        val result =
            drive(sim, back, maxT = 20.0, onStep = { s, _, _ ->
                assertTrue(rearOf(s.horse).z <= arenaHalfLength - TUNING.horse.rearMargin + 1e-9)
            })
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
        assertTrue(rearOf(sim.horse).z > arenaHalfLength - TUNING.horse.rearMargin - 0.1)
        noTrouble(result.events)
        // S still held: it keeps standing instead of jittering against the fence
        val z = sim.horse.z
        drive(sim, back, maxT = 2.0)
        assertEquals(z, sim.horse.z)
        assertEquals(0.0, sim.horse.speed)
    }

    @Test
    fun canBackAwayFromTheFenceAgainAfterReleasingSAndPressingItAnew() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 30.0, PI)
        drive(sim, back, maxT = 20.0)
        assertEquals(0.0, sim.horse.speed)
        drive(sim, NONE, maxT = 0.1)
        turnInPlace(sim, 0.0)
        val z = sim.horse.z
        drive(sim, back, maxT = 2.0)
        assertTrue(sim.horse.z < z - 0.2)
        assertTrue(rearOf(sim.horse).z <= arenaHalfLength - TUNING.horse.rearMargin + 1e-9)
    }

    @Test
    fun anObstacleBehindTheHorseStopsItBeforeTheHindquartersReachIt() {
        val v = makeElement(vertical, 0.6, id = "v")
        val sim = makeSim(listOf(v))
        // facing -z, obstacle in the back (+z side)
        sim.reset(0.0, -6.0, PI)
        val ext = blockExtents(v, TUNING)
        val result =
            drive(sim, back, maxT = 30.0, onStep = { s, _, _ ->
                val rear = rearOf(s.horse)
                val p = toLocal(v, rear.x, rear.z)
                val inside = abs(p.along) < ext.along - 1e-6 && abs(p.across) < ext.across - 1e-6
                assertFalse(inside)
            })
        assertEquals(0.0, sim.horse.speed)
        assertEquals(Gait.HALT, sim.horse.gait)
        val rear = rearOf(sim.horse)
        assertTrue(abs(toLocal(v, rear.x, rear.z).along) < v.spread / 2 + r.rearClearance + 0.1)
        noTrouble(result.events)
        // and it stays there while S is held
        val z = sim.horse.z
        drive(sim, back, maxT = 2.0)
        assertEquals(z, sim.horse.z)
    }

    @Test
    fun whileBackingTheRearPointKeepsTheRearClearanceFromThePoleTailDoesNotClip() {
        val v = makeElement(vertical, 0.6, id = "v")
        val sim = makeSim(listOf(v))
        sim.reset(0.0, -6.0, PI)
        val minAlong = v.spread / 2 + r.rearClearance
        drive(sim, back, maxT = 30.0, onStep = { s, _, _ ->
            val rear = rearOf(s.horse)
            val p = toLocal(v, rear.x, rear.z)
            val inside = abs(p.along) < minAlong - 1e-6 && abs(p.across) < blockExtents(v, TUNING).across
            assertFalse(inside)
        })
        assertEquals(0.0, sim.horse.speed)
        val rear = rearOf(sim.horse)
        assertTrue(abs(toLocal(v, rear.x, rear.z).along) >= minAlong - 1e-6)
        // the clearance exceeds the front margin: the tail reaches further back than the front
        assertTrue(r.rearClearance > TUNING.horse.frontMargin)
    }

    @Test
    fun anObstacleBehindATurningHorseAlsoHoldsTheHindquartersBack() {
        val v = makeElement(vertical, 0.6, id = "v")
        val sim = makeSim(listOf(v))
        sim.reset(0.0, -4.0, PI)
        val ext = blockExtents(v, TUNING)
        drive(sim, SimInput(throttle = -1.0, steer = 1.0), maxT = 30.0, onStep = { s, _, _ ->
            if (s.horse.speed < 0) {
                val rear = rearOf(s.horse)
                val p = toLocal(v, rear.x, rear.z)
                val inside = abs(p.along) < ext.along - 1e-6 && abs(p.across) < ext.across - 1e-6
                assertFalse(inside)
            }
        })
    }

    @Test
    fun rightAfterALandingTheHindquartersMayStillBeOverTheObstacleNoBackingThroughIt() {
        val v = makeElement(vertical, 0.6, id = "v")
        val sim = makeSim(listOf(v))
        // facing +z, the reference point 1.5 m behind the obstacle: the rear point is inside it
        sim.reset(0.0, TUNING.horse.rearLength, 0.0)
        val result = drive(sim, back, maxT = 2.0)
        assertEquals(0.0, sim.horse.speed)
        assertCloseTo(TUNING.horse.rearLength, sim.horse.z, 6)
        noTrouble(result.events)
        // turned around, backing away from the obstacle works
        sim.reset(0.0, TUNING.horse.rearLength, PI)
        drive(sim, back, maxT = 2.0)
        assertTrue(sim.horse.z > TUNING.horse.rearLength + 0.2)
    }

    @Test
    fun resetIntoANegativeSpeedGivesTheBackGait() {
        val sim = makeSim(emptyList())
        sim.reset(0.0, 0.0, 0.0, speed = -0.3)
        assertEquals(Gait.BACK, sim.horse.gait)
    }

    @Test
    fun neverMovesTheHorseIntoAnObstacleWhileBackingAtRandomStress() {
        val els =
            listOf(
                makeElement(cross, 0.45, id = "k", x = -3.0, z = -3.0),
                makeElement(oxer, 0.85, id = "o", x = 3.0, z = 3.0, rot = 0.6),
            )
        val sim = makeSim(els)
        val rng = createRng(77)
        sim.reset(0.0, 0.0, 0.0)
        var input = back
        drive(
            sim,
            { _, t ->
                if (jsRound(t * 60) % 40 == 0.0) input = SimInput(throttle = -1.0, steer = rng() * 2 - 1)
                input
            },
            maxT = 120.0,
            onStep = { s, _, _ ->
                for (el in els) {
                    assertFalse(insideBlock(s, el))
                    // a standing horse turning on the spot may swing its rear past an obstacle
                    if (s.horse.speed >= 0) continue
                    val rear = rearOf(s.horse)
                    val ext = blockExtents(el, TUNING)
                    val p = toLocal(el, rear.x, rear.z)
                    val rearInside = abs(p.along) < ext.along - 1e-6 && abs(p.across) < ext.across - 1e-6
                    assertFalse(rearInside)
                }
            },
        )
    }
}
