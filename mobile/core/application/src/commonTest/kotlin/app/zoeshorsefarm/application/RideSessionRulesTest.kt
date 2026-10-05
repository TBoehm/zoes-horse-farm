package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.modes.AidTarget
import app.zoeshorsefarm.application.modes.CourseMode
import app.zoeshorsefarm.application.modes.FreeMode
import app.zoeshorsefarm.application.modes.ModeHost
import app.zoeshorsefarm.application.modes.RideFinish
import app.zoeshorsefarm.application.modes.RideFrame
import app.zoeshorsefarm.application.modes.RideMode
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.application.modes.RideStart
import app.zoeshorsefarm.application.testing.FIXED_ISO
import app.zoeshorsefarm.application.testing.FakeHost
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.FixedClock
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.domain.course.FREE_LAYOUT
import app.zoeshorsefarm.domain.course.Faults
import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.course.RunPhase
import app.zoeshorsefarm.domain.course.courseById
import app.zoeshorsefarm.domain.progress.CourseBest
import app.zoeshorsefarm.domain.progress.Progress
import app.zoeshorsefarm.domain.sim.COMBI_DISTANCE
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.GallopEndReason
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.RefusalReason
import app.zoeshorsefarm.domain.sim.SimApproach
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.sim.Zone
import app.zoeshorsefarm.domain.sim.approachInfo
import app.zoeshorsefarm.domain.sim.zoneForElement
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RideSessionRulesTest {
    // jump counting and instant badges (rules 40, 45, 49)

    @Test
    fun countsAJumpAtOnceSavesItAndAnnouncesTheFirstJumpBadge() {
        val jumped = jumpTheCross()
        assertEquals(1, jumped.ctx.store.progress.jumps)
        assertEquals(FIXED_ISO, jumped.ctx.store.progress.badges["firstJump"])
        assertEquals(listOf(RideCommand.Badges(listOf("firstJump"))), jumped.out.commands.ofType<RideCommand.Badges>())
    }

    @Test
    fun announcesABadgeOnlyOnceAndCountsEveryFurtherJump() {
        val jumped = jumpTheCross()
        jumped.ctx.session.restart()
        val second = pressOnce(jumped.ctx.session, landedIn)
        assertEquals(2, jumped.ctx.store.progress.jumps)
        assertEquals(emptyList(), second.commands.ofType<RideCommand.Badges>())
    }

    @Test
    fun announcesTheJumpMouseWithThe100thJump() {
        val jumped = jumpTheCross(progress = Progress(jumps = 99, badges = mapOf("firstJump" to FIXED_ISO)))
        assertEquals(listOf(RideCommand.Badges(listOf("jumpMouse"))), jumped.out.commands.ofType<RideCommand.Badges>())
    }

    @Test
    fun asksForTakeoffAndLandingSounds() {
        val sounds = jumpTheCross().out.sounds()
        assertContains(sounds, RideSound.TAKEOFF)
        assertContains(sounds, RideSound.LANDING)
    }

    // knockdown, feedback and rebuild timers

    @Test
    fun playsTheRailDownSoundAndGivesKnockdownFeedback() {
        val session = knockSetup().session
        val out = pressOnce(session, landedIn)
        assertContains(out.sounds(), RideSound.RAIL_DOWN)
        assertEquals(listOf(RideCommand.Feedback("feedback.knockdown")), out.commands.ofType<RideCommand.Feedback>())
    }

    @Test
    fun rebuildsTheRailsAfterTheDelayTheModeAskedFor() {
        val session = knockSetup().session
        pressOnce(session, railDownIn)
        assertContentEquals(booleanArrayOf(false), session.view.rails["c"])
        run(session, SimInput(), maxT = TUNING.rebuildDelayS - 0.5)
        assertContentEquals(booleanArrayOf(false), session.view.rails["c"])
        run(session, SimInput(), maxT = 1.0)
        assertContentEquals(booleanArrayOf(true), session.view.rails["c"])
    }

    @Test
    fun forgetsPendingRebuildsOnRestartAndPutsAllRailsUp() {
        val session = knockSetup().session
        pressOnce(session, railDownIn)
        session.restart()
        assertContentEquals(booleanArrayOf(true), session.view.rails["c"])
    }

    // mode-requested immediate rebuild

    @Test
    fun rebuildsAtOnceAndCancelsAPendingDelayedRebuildOfTheSameElement() {
        val z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING)
        var requested = false
        val mode =
            crossMode(
                distance = z.reach - 0.05,
                speed = TUNING.speeds.trotMax,
                onEvents = { events, host ->
                    for (e in events) {
                        if (e is SimEvent.RailDown) host.rebuildIn(e.elementId, 100.0)
                        if (e is SimEvent.Landed) {
                            requested = true
                            host.rebuildNow(e.elementId)
                        }
                    }
                },
            )
        val session = setup(mode, rng = { 0.0 }).session
        pressOnce(session) { requested }
        assertContentEquals(booleanArrayOf(true), session.view.rails["c"])
    }

    // mode-requested cancel of a pending rebuild

    @Test
    fun keepsTheRailsDownWhenTheModeCancelsTheDelayedRebuildOfAnElement() {
        val z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING)
        val mode =
            crossMode(
                distance = z.reach - 0.05,
                speed = TUNING.speeds.trotMax,
                onEvents = { events, host ->
                    for (e in events) {
                        if (e is SimEvent.RailDown) host.rebuildIn(e.elementId, 1.0)
                        if (e is SimEvent.Landed) host.cancelRebuild(e.elementId)
                    }
                },
            )
        val session = setup(mode, rng = { 0.0 }).session
        pressOnce(session, railDownIn)
        run(session, SimInput(), maxT = 3.0)
        assertContentEquals(booleanArrayOf(false), session.view.rails["c"])
    }

    @Test
    fun ignoresACancelForAnElementWithoutAPendingRebuild() {
        val mode = crossMode(distance = 10.0, onEvents = { _, host -> host.cancelRebuild("unknown") })
        val session = setup(mode).session
        session.step(STEP_DT, SimInput(throttle = 1.0)) // does not throw
    }

    // end of a gallop

    @Test
    fun asksTheInputToEndTheGallopWhenTheHorseIsStoppedAtTheFence() {
        val z = zoneForElement(cross, TUNING.speeds.canterMin, TUNING)
        val mode = crossMode(distance = z.far + 2, speed = TUNING.speeds.canterMin, gallop = true)
        val session = setup(mode).session
        val out =
            run(
                session,
                SimInput(gallop = true, throttle = 1.0),
                maxT = 20.0,
                done = { o -> o.commands.any { it == RideCommand.EndGallop } },
            )
        assertTrue(out.events.ofType<SimEvent.GallopEnded>().isNotEmpty())
        assertEquals(listOf(RideCommand.EndGallop), out.commands.filter { it == RideCommand.EndGallop })
    }

    @Test
    fun refusesACanterThatIsTooSlowForTheOxerEndGallopCommandAndNoGallop() {
        val oxer = Element("o", ElementKind.OXER, height = 0.85, spread = 0.7, x = 0.0, z = 0.0, rot = 0.0)
        val mode =
            crossMode(
                distance = 9.0,
                speed = TUNING.speeds.canterMin,
                gallop = true,
                obstacles = listOf(Obstacle(null, listOf(oxer), directed = false)),
            )
        val session = setup(mode).session
        // Shift stays held and the throttle is released: the canter stays at its minimum speed
        val out = run(session, SimInput(gallop = true, throttle = 0.0), maxT = 8.0, done = refusalIn)
        val refusals = out.events.ofType<SimEvent.Refusal>()
        assertEquals(1, refusals.size)
        assertEquals("o", refusals[0].elementId)
        assertEquals(RefusalReason.SPEED, refusals[0].reason)
        assertEquals(listOf(RideCommand.EndGallop), out.commands.filter { it == RideCommand.EndGallop })
        assertFalse(session.view.horse.gallop)
    }

    // refusal feedback

    @Test
    fun givesFeedbackWhenTheHorseRefusesWalkingAtTheCrossFreeMode() {
        val session = setup(crossMode(distance = 4.0, speed = 1.2)).session
        val out = run(session, SimInput(throttle = 0.0), maxT = 8.0, done = refusalIn)
        assertTrue(out.events.ofType<SimEvent.Refusal>().isNotEmpty())
        assertEquals(listOf(RideCommand.Feedback("feedback.refusal")), out.commands.ofType<RideCommand.Feedback>())
    }

    // rein-back at session level (rule 9)

    @Test
    fun showsTheBackGaitInTheViewAndPlaysNoSoundForIt() {
        val session = setup(crossMode(distance = 8.0)).session
        val out = run(session, SimInput(throttle = -1.0), maxT = 2.0)
        assertEquals(Gait.BACK, session.view.horse.gait)
        assertTrue(session.view.horse.speed < 0)
        assertEquals(emptyList(), out.sounds())
        assertNull(session.view.aid)
    }

    @Test
    fun spaceDoesNotJumpABackingHorseAndNoJumpIsCounted() {
        val ctx = setup(crossMode(distance = 1.5))
        val out = run(ctx.session, SimInput(throttle = -1.0, jump = true), maxT = 3.0)
        assertEquals(emptyList(), out.events.ofType<SimEvent.Takeoff>())
        assertEquals(0, ctx.store.progress.jumps)
    }

    @Test
    fun backingOverTheStartLineDoesNotStartACourseRide() {
        val course = courseById(1)
        val session = setup(CourseMode(1)).session
        val horse = session.view.horse
        val dx = course.start.dir.x
        val dz = course.start.dir.z

        fun faces() = sin(horse.heading) * dx + cos(horse.heading) * dz

        // turn around on the spot (facing away from the line), then back toward and over it
        run(session, SimInput(steer = 1.0), maxT = 10.0, done = { faces() < -0.99 })
        assertTrue(faces() < -0.9)
        val lineA = course.start.a

        fun along() = (horse.x - lineA.x) * dx + (horse.z - lineA.z) * dz

        assertTrue(along() < 0)
        run(session, SimInput(throttle = -1.0), maxT = 60.0, done = { along() > 0.5 })
        assertTrue(along() > 0.5)
        assertEquals(RunPhase.PRESTART, session.view.hud?.phase)
    }

    @Test
    fun countsAKnockedJumpToo() {
        val z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING)
        val mode = crossMode(distance = z.reach - 0.05, speed = TUNING.speeds.trotMax)
        val ctx = setup(mode, rng = { 0.0 })
        val out = pressOnce(ctx.session, landedIn)
        assertTrue(out.events.ofType<SimEvent.Landed>()[0].knocked)
        assertEquals(1, ctx.store.progress.jumps)
    }

    @Test
    fun doesNotCountARefusal() {
        val ctx = setup(crossMode(distance = 4.0, speed = 1.2))
        val out = run(ctx.session, SimInput(throttle = 0.0), maxT = 8.0, done = refusalIn)
        assertEquals(1, out.events.ofType<SimEvent.Refusal>().size)
        run(ctx.session, SimInput(throttle = 0.0), maxT = 2.0)
        assertEquals(0, ctx.store.progress.jumps)
    }

    @Test
    fun asksForTheEndOfTheGallopAfterARefusalWhileWalking() {
        // walking at the cross: refuse at the last point (the canter variant is above)
        val session = setup(crossMode(distance = 4.0, speed = 1.2)).session
        val out = run(session, SimInput(throttle = 0.0), maxT = 8.0, done = refusalIn)
        assertEquals(
            listOf(SimEvent.GallopEnded(GallopEndReason.REFUSAL)),
            out.events.ofType<SimEvent.GallopEnded>(),
        )
        assertEquals(listOf(RideCommand.EndGallop), out.commands.filter { it == RideCommand.EndGallop })
    }

    @Test
    fun doesNotCountAHop() {
        val ctx = setup(crossMode(distance = 25.0, speed = TUNING.speeds.trotMedium))
        val out = pressOnce(ctx.session) { o -> o.events.any { it is SimEvent.Hop } }
        assertEquals(1, out.events.ofType<SimEvent.Hop>().size)
        run(ctx.session, SimInput(), maxT = 1.0)
        assertEquals(0, ctx.store.progress.jumps)
    }

    @Test
    fun countsBothPartsOfACombinationTwoJumps() {
        val ctx = setup(comboMode())
        val out =
            run(ctx.session, maxT = 8.0, done = { ctx.store.progress.jumps >= 2 }, input = pressInTurn(listOf(a, b)))
        assertEquals(listOf("a", "b"), out.events.ofType<SimEvent.Landed>().map { it.elementId })
        assertEquals(2, ctx.store.progress.jumps)
    }

    @Test
    fun partACanBeKnockedWhilePartBStaysCleanAndBothCount() {
        // only the first knockdown draw falls: a is taken beyond the far end of its zone (risk > 0)
        var draws = 0
        val rng: Rng = { if (draws++ == 0) 0.0 else 0.99 }
        val ctx = setup(comboMode(), rng = rng)
        val out =
            run(
                ctx.session,
                maxT = 8.0,
                done = { ctx.store.progress.jumps >= 2 },
                input = pressInTurn(listOf(a, b)) { it.far + 0.4 },
            )
        val landed = out.events.ofType<SimEvent.Landed>()
        assertEquals(listOf("a" to true, "b" to false), landed.map { it.elementId to it.knocked })
        assertEquals(listOf("a"), out.events.ofType<SimEvent.RailDown>().map { it.elementId })
        assertContentEquals(booleanArrayOf(false), ctx.session.view.rails["a"])
        assertContentEquals(booleanArrayOf(true), ctx.session.view.rails["b"])
        assertEquals(2, ctx.store.progress.jumps)
    }

    // fall direction for the 3D view

    @Test
    fun fallDirectionsAreEmptyAtTheStart() {
        val session = setup(crossMode(distance = 10.0)).session
        assertEquals(0, session.view.fallDirs.size)
    }

    @Test
    fun remembersTheJumpDirectionOfTheLastFallPerElement() {
        assertEquals(1, knockFrom(1).view.fallDirs["c"])
        assertEquals(-1, knockFrom(-1).view.fallDirs["c"])
    }

    @Test
    fun forgetsTheFallDirectionsOnRestart() {
        val session = knockFrom(1)
        session.restart()
        assertEquals(0, session.view.fallDirs.size)
    }

    @Test
    fun keepsTheSameMapObjectSoTheViewCanHoldOnToIt() {
        val session = knockFrom(1)
        assertSame(session.view.fallDirs, session.view.fallDirs)
    }
}
