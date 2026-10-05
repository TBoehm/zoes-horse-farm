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

class RideSessionFinishTest {
    // finishing a course ride

    @Test
    fun savesTheRideAndEmitsAFinishedCommandWithTheResultsParams() {
        val ctx = setup(finishingMode())
        val out = ctx.session.step(STEP_DT, SimInput())
        assertEquals(1, ctx.store.progress.finishedRides)
        assertEquals(2, ctx.store.progress.unlocked)
        val finished = out.commands.ofType<RideCommand.Finished>()
        assertEquals(1, finished.size)
        assertEquals("results", finished[0].screen)
        val params = finished[0].params
        assertEquals(1, params.courseId)
        assertEquals(result, params.result)
        assertTrue(params.isNewBest)
        assertEquals(2, params.unlockedCourse)
        assertContains(params.awarded, "clean")
        assertEquals(FIXED_ISO, ctx.store.progress.badges["clean"])
    }

    @Test
    fun finishesOnlyOnceFurtherStepsDoNothing() {
        val ctx = setup(finishingMode())
        ctx.session.step(STEP_DT, SimInput())
        val out = ctx.session.step(STEP_DT, SimInput())
        assertEquals(emptyList(), out.events)
        assertEquals(emptyList(), out.commands)
        assertEquals(1, ctx.store.progress.finishedRides)
    }

    @Test
    fun canRideAgainAfterARestart() {
        val ctx = setup(finishingMode())
        ctx.session.step(STEP_DT, SimInput())
        ctx.session.restart()
        assertEquals(
            1,
            ctx.session
                .step(STEP_DT, SimInput())
                .commands
                .ofType<RideCommand.Finished>()
                .size,
        )
        assertEquals(2, ctx.store.progress.finishedRides)
    }

    @Test
    fun doesNotFinishOrSaveAnythingOnTheWayWithTheRealCourseMode() {
        val ctx = setup(CourseMode(1))
        val out = run(ctx.session, SimInput(throttle = 1.0), maxT = 3.0)
        assertEquals(emptyList(), out.commands.ofType<RideCommand.Finished>())
        assertEquals(0, ctx.store.progress.finishedRides)
    }

    // finish signal sound

    @Test
    fun theFinishSignalIsCommandedRightBeforeTheFinishedCommandOnARealFinish() {
        val session = setup(finishingMode()).session
        val commands = session.step(STEP_DT, SimInput()).commands
        assertTrue(commands.last() is RideCommand.Finished)
        assertEquals(RideCommand.Sound(RideSound.FINISH_SIGNAL), commands[commands.size - 2])
        assertEquals(1, commands.count { it == RideCommand.Sound(RideSound.FINISH_SIGNAL) })
    }

    @Test
    fun theFinishSignalIsNotCommandedWhenTheFinishLineIsCrossedTooEarlyObstaclesMissing() {
        val course = courseById(1)
        val finish = course.finish
        val start = course.start
        val mid = Vec2((finish.a.x + finish.b.x) / 2, (finish.a.z + finish.b.z) / 2)
        val base = CourseMode(1)
        val mode =
            TestMode(
                base = base,
                start = {
                    RideStart(
                        Pose(mid.x - finish.dir.x * 2, mid.z - finish.dir.z * 2, atan2(finish.dir.x, finish.dir.z)),
                        speed = TUNING.speeds.trotMedium,
                    )
                },
            )
        val session = setup(mode).session
        // the ride is on, but no obstacle was jumped yet
        val sm = Vec2((start.a.x + start.b.x) / 2, (start.a.z + start.b.z) / 2)
        val frame =
            RideFrame(
                Pose(sm.x + start.dir.x, sm.z + start.dir.z, Double.NaN),
                sm.x - start.dir.x,
                sm.z - start.dir.z,
            )
        mode.update(0.0, frame, FakeHost())
        assertEquals(RunPhase.RIDING, session.view.hud?.phase)
        val out = run(session, SimInput(throttle = 0.0), maxT = 2.0)
        assertEquals(1, session.view.hud?.missingHint)
        assertEquals(emptyList(), out.commands.ofType<RideCommand.Finished>())
        assertEquals(emptyList(), out.sounds().filter { it == RideSound.FINISH_SIGNAL })
    }

    // abort without credit (rules 40, 49)

    private class Ride(
        val rider: SessionCourseRider,
        val commands: List<RideCommand>,
    )

    /** Rides course 1 with the autopilot until the start line is crossed and a jump counted. */
    private fun rideUntilFirstJump(store: FakeStore): Ride {
        val rider = SessionCourseRider(1, store = store)
        val commands = mutableListOf<RideCommand>()
        var started = false
        var t = 0.0
        while (t < 120 && store.progress.jumps < 1) {
            commands.addAll(rider.step().commands)
            started = started || rider.session.view.hud
                ?.phase == RunPhase.RIDING
            t += STEP_DT
        }
        assertTrue(started)
        assertTrue(store.progress.jumps >= 1)
        return Ride(rider, commands)
    }

    @Test
    fun givesNoCreditForARideThatWasLeftAfterTheStartAndAJump() {
        val store = FakeStore()
        val ride = rideUntilFirstJump(store)
        val jumps = store.progress.jumps
        ride.rider.session.restart()
        assertEquals(emptyList(), ride.commands.ofType<RideCommand.Finished>())
        assertEquals(1, store.progress.unlocked)
        assertEquals(emptyMap(), store.progress.courses)
        assertEquals(0, store.progress.finishedRides)
        assertEquals(jumps, store.progress.jumps)
        assertTrue(jumps > 0)
        assertEquals(
            RunPhase.PRESTART,
            ride.rider.session.view.hud
                ?.phase,
        )
    }

    @Test
    fun awardsNoRideEndBadgesOnAbortEvenWhenEveryConditionWouldHold() {
        val allThreeStars = (1..5).associate { it.toString() to CourseBest(faults = 0, timeCs = 5000, stars = 3) }
        val store = FakeStore(progress = Progress(finishedRides = 10, unlocked = 5, courses = allThreeStars))
        val ride = rideUntilFirstJump(store)
        val more = ride.rider.session.restart()
        val all = ride.commands + more
        assertEquals(emptyList(), all.ofType<RideCommand.Finished>())
        assertEquals(10, store.progress.finishedRides)
        for (id in listOf("clean", "oxerPro", "comboPro", "allOpen", "starRider", "busy")) {
            assertFalse(id in store.progress.badges, id)
        }
        // only instant badges may come up during an aborted ride
        for (command in all.ofType<RideCommand.Badges>()) {
            for (id in command.ids) assertContains(listOf("firstJump", "jumpMouse"), id)
        }
    }
}
