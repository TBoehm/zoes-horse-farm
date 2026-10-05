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

class RidingSimReinBackTest {
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
