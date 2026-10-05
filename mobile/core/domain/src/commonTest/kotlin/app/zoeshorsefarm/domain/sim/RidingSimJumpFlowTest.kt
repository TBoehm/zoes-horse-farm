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

private val vertical = ElementKind.VERTICAL
private val cross = ElementKind.CROSS
private val oxer = ElementKind.OXER

class RidingSimJumpFlowTest {
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
                val inZoneOfA = ap != null && ap.elementId == "a" && ap.distance <= center(a)
                if (!first && jump == null && inZoneOfA) {
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
                    val due = ap != null && !pressedFor.contains(ap.elementId) && ap.distance <= atCenter(s, ap)
                    if (due && s.horse.jump == null) {
                        pressedFor.add(checkNotNull(ap).elementId)
                        SimInput(gallop = true, jump = true)
                    } else {
                        SimInput(gallop = true)
                    }
                },
                until = { s, _, _ -> s.horse.z > COMBI_DISTANCE + 4 },
                onStep = { s, ev, _ ->
                    if (ev.ofType<SimEvent.Landed>().any { it.elementId == "a" }) {
                        bApproachAfterLanding.add(
                            s.approach?.copy(),
                        )
                    }
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
}
