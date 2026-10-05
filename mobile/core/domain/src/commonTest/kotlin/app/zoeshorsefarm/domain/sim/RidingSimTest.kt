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
}
