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

class RideSessionViewTest {
    // view

    @Test
    fun startsAtTheModeStartPoseWithAllRailsUpAndNothingHighlightedFreeMode() {
        val view = setup().session.view
        assertEquals(FREE_LAYOUT.startPose.x, view.horse.x)
        assertEquals(FREE_LAYOUT.startPose.z, view.horse.z)
        assertEquals(FREE_LAYOUT.startPose.heading, view.horse.heading)
        assertEquals(0.0, view.horse.speed)
        assertContentEquals(booleanArrayOf(true), view.rails["f1"])
        assertNull(view.aid)
        assertNull(view.highlight)
        assertFalse(view.finishMarked)
        assertNull(view.lines)
        assertNull(view.hud)
    }

    @Test
    fun tellsWhileAJumpIsInProgressFromTakeOffThroughFlightToLanding() {
        // rule 4
        val z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING)
        val mode = crossMode(distance = z.near + 0.3, speed = TUNING.speeds.trotMax)
        val session = setup(mode).session
        assertFalse(session.view.jumping)
        val phases = mutableSetOf<JumpPhase>()
        var pressed = false
        var sawJumping = false
        run(
            session,
            done = {
                val view = session.view
                // the flag is exactly "a jump phase is set"
                assertEquals(view.horse.jump != null, view.jumping)
                view.horse.jump?.let { phases.add(it.phase) }
                sawJumping = sawJumping || view.jumping
                sawJumping && !view.jumping
            },
        ) {
            val press = !pressed
            pressed = true
            SimInput(jump = press)
        }
        assertEquals(setOf(JumpPhase.TAKEOFF, JumpPhase.FLIGHT, JumpPhase.LANDING), phases)
        assertFalse(session.view.jumping)
    }

    @Test
    fun tellsWhileAnObstacleIsBeingApproachedAlsoWithoutAJump() {
        // rule 4
        val far = setup(crossMode(distance = TUNING.approachDistance + 5))
        assertFalse(far.session.view.approaching)

        val near = setup(crossMode(distance = TUNING.approachDistance - 2))
        assertTrue(near.session.view.approaching)
        assertFalse(near.session.view.jumping)

        // walking away turns the horse round and ends the approach
        val distance = TUNING.approachDistance - 2
        val away = setup(crossMode(distance = distance, start = { RideStart(Pose(0.0, -distance, PI)) }))
        assertFalse(away.session.view.approaching)
    }

    @Test
    fun isApproachingRightUpToTakeOffAndNoLongerDuringTheJumpItself() {
        val z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING)
        val mode = crossMode(distance = z.near + 0.3, speed = TUNING.speeds.trotMax)
        val session = setup(mode).session
        assertTrue(session.view.approaching)
        assertFalse(session.view.jumping)
        var pressed = false
        run(session, done = { session.view.jumping }) {
            val press = !pressed
            pressed = true
            SimInput(jump = press)
        }
        // busy for the ride screen is jumping || approaching: there is no gap between the two
        assertTrue(session.view.jumping)
    }

    @Test
    fun describesTheModeForTheScreenObstaclesFlagsQuitTarget() {
        val session = setup(CourseMode(2)).session
        assertEquals(RideModeId.COURSE, session.modeId)
        assertTrue(session.flags)
        assertEquals("courseSelect", session.quitScreen)
        assertEquals("pause.toSelect", session.quitLabelKey)
        assertTrue(session.obstacles.isNotEmpty())
    }

    @Test
    fun exposesModeDataOfACourseLinesWithLabelKeysHighlightAndHudModel() {
        val view = setup(CourseMode(1)).session.view
        val lines = assertNotNull(view.lines)
        assertEquals("prestart.legendStart", lines.labelKeys.start)
        assertEquals("prestart.legendFinish", lines.labelKeys.finish)
        assertEquals(1, view.highlight?.number)
        val hud = assertNotNull(view.hud)
        assertEquals(RunPhase.PRESTART, hud.phase)
        assertEquals(0, hud.faults)
        assertFalse(view.finishMarked)
    }

    // view reuse (no per-frame allocation)

    @Test
    fun returnsTheSameViewObjectOnEveryReadWithFreshContent() {
        val session = setup().session
        val first = session.view
        session.step(STEP_DT, SimInput(throttle = 1.0))
        assertSame(first, session.view)
        assertTrue(session.view.horse.speed > 0)
    }

    @Test
    fun reusesOneAidObjectWhileTheAidIsShownAndDropsItWithNullWhenNot() {
        var aidOn = true
        val mode = crossMode(distance = 10.0, aid = { _, _ -> if (aidOn) AidTarget("c", 1) else null })
        val session = setup(mode).session
        val aid = assertNotNull(session.view.aid)
        session.step(STEP_DT, SimInput(throttle = 1.0))
        assertSame(aid, session.view.aid)
        aidOn = false
        assertNull(session.view.aid)
        aidOn = true
        val again = assertNotNull(session.view.aid)
        assertEquals("c", again.elementId)
        assertEquals(1, again.dir)
        assertSame(aid, again)
    }

    @Test
    fun returnsSharedEmptyListsForStepsWithoutCommands() {
        val session = setup().session
        val a = session.step(STEP_DT, SimInput())
        val commands = a.commands
        val events = a.events
        val b = session.step(STEP_DT, SimInput())
        assertEquals(0, commands.size)
        assertSame(commands, b.commands)
        assertSame(events, b.events)
    }

    // step

    @Test
    fun movesTheHorseWithTheInputAndReturnsSimEvents() {
        val session = setup().session
        val z0 = session.view.horse.z
        val out = session.step(STEP_DT, SimInput(throttle = 1.0))
        assertEquals(emptyList(), out.events)
        assertEquals(emptyList(), out.commands)
        run(session, SimInput(throttle = 1.0), maxT = 1.0)
        assertTrue(session.view.horse.z > z0)
    }

    @Test
    fun ignoresAStepWithDtZeroNothingMovesNoEvents() {
        val session = setup().session
        val horse = session.view.horse
        val before = listOf(horse.x, horse.z, horse.heading, horse.speed)
        val out = session.step(0.0, SimInput(throttle = 1.0))
        assertEquals(emptyList(), out.events)
        assertEquals(emptyList(), out.commands)
        assertEquals(before, listOf(horse.x, horse.z, horse.heading, horse.speed))
    }

    // jump aid

    @Test
    fun theAidIsNullWhenTheModeShowsNone() {
        val session = setup(crossMode(distance = 10.0, aid = { _, _ -> null })).session
        assertNull(session.view.aid)
    }

    @Test
    fun combinesTheModeTargetWithTheSimZoneAlsoAtAHalt() {
        val mode = crossMode(distance = 10.0, aid = { _, _ -> AidTarget("c", 1) })
        val session = setup(mode).session
        val aid = assertNotNull(session.view.aid)
        assertEquals("c", aid.elementId)
        assertEquals(1, aid.dir)
        assertEquals(zoneForElement(cross, 0.0, TUNING), aid.zone)
    }

    @Test
    fun usesTheCurrentSpeedForTheZone() {
        val speed = TUNING.speeds.trotMax
        val mode = crossMode(distance = 10.0, speed = speed, aid = { _, _ -> AidTarget("c", 1) })
        val session = setup(mode).session
        assertEquals(
            zoneForElement(cross, speed, TUNING).far,
            session.view.aid
                ?.zone
                ?.far,
        )
    }

    @Test
    fun handsTheApproachedElementAndTheSavedSettingsToTheModeFreeModeRule42() {
        val on = setup(freeNearCross(), settings = Settings(aidFree = true))
        assertEquals(
            "c",
            on.session.view.aid
                ?.elementId,
        )
        assertEquals(
            1,
            on.session.view.aid
                ?.dir,
        )
        val off = setup(freeNearCross(), settings = Settings(aidFree = false))
        assertNull(off.session.view.aid)
    }

    @Test
    fun followsASettingsChangeWithoutARestart() {
        val ctx = setup(freeNearCross(), settings = Settings(aidFree = false))
        assertNull(ctx.session.view.aid)
        ctx.store.update(SettingsSection) { it.copy(aidFree = true) }
        assertNotNull(ctx.session.view.aid)
    }

    // restart

    @Test
    fun putsTheHorseBackResetsTheModeAndAsksToResetTheTouchGallop() {
        var restarts = 0
        val mode = TestMode(restartHandler = { restarts += 1 })
        val session = setup(mode).session
        val startX = session.view.horse.x
        val startZ = session.view.horse.z
        run(session, SimInput(throttle = 1.0), maxT = 2.0)
        assertNotEquals(startZ, session.view.horse.z)
        val before = restarts
        val commands = session.restart()
        assertEquals(listOf(RideCommand.ResetTouchGallop), commands)
        assertEquals(before + 1, restarts)
        assertEquals(startX, session.view.horse.x)
        assertEquals(startZ, session.view.horse.z)
        assertEquals(0.0, session.view.horse.speed)
    }

    @Test
    fun startsAFreshCourseRun() {
        val session = setup(CourseMode(1)).session
        run(session, SimInput(throttle = 1.0), maxT = 3.0)
        session.restart()
        val hud = assertNotNull(session.view.hud)
        assertEquals(RunPhase.PRESTART, hud.phase)
        assertEquals(0, hud.timeCs)
    }

    // settings are cached, not read every frame

    private class CountingStore(
        settings: Settings,
    ) : FakeStore(settings = settings) {
        var settingsReads = 0
        var listeners = 0

        override fun <T : Any> get(section: Section<T>): T {
            if (section.name == SettingsSection.name) settingsReads += 1
            return super.get(section)
        }

        override fun <T : Any> onChange(
            section: Section<T>,
            listener: (T) -> Unit,
        ): () -> Unit {
            listeners += 1
            val off = super.onChange(section, listener)
            return {
                listeners -= 1
                off()
            }
        }
    }

    private fun sessionOn(store: Store) = RideSession(freeNearCross(), store, FixedClock(FIXED_ISO), seededRng(1))

    @Test
    fun doesNotReadTheSettingsOnEveryViewAccess() {
        val store = CountingStore(Settings(aidFree = true))
        val session = sessionOn(store)
        val before = store.settingsReads
        repeat(100) { session.view }
        assertEquals(before, store.settingsReads)
    }

    @Test
    fun followsAChangeThroughTheStoreNotification() {
        val store = CountingStore(Settings(aidFree = false))
        val session = sessionOn(store)
        assertNull(session.view.aid)
        store.update(SettingsSection) { it.copy(aidFree = true) }
        assertNotNull(session.view.aid)
    }

    @Test
    fun disposeStopsListening() {
        val store = CountingStore(Settings(aidFree = false))
        val session = sessionOn(store)
        assertEquals(1, store.listeners)
        session.dispose()
        assertEquals(0, store.listeners)
    }
}
