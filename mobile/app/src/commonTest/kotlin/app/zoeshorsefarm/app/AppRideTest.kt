package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.CrashGuardSection
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.domain.testing.rideCourse
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.platform.GameKey
import app.zoeshorsefarm.presentation.courses.ResultsModel
import app.zoeshorsefarm.presentation.menu.MainMenuModel
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.ride.PauseAction
import app.zoeshorsefarm.presentation.ride.RideScreenModel
import app.zoeshorsefarm.presentation.settings.SettingsBlock
import app.zoeshorsefarm.presentation.settings.SettingsScreenModel
import app.zoeshorsefarm.scene.math.Vec2
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MAX_RIDE_FRAMES = 60 * 400
private const val PAUSE_SETTLE_FRAMES = 3

class AppRideTest {
    private fun startedRig(
        level: GraphicsLevel? = null,
        quiet: Boolean = false,
        withSurface: Boolean = true,
    ): AppRig {
        val rig = AppRig(quiet = quiet, withSurface = withSurface)
        if (level != null) rig.app.settings.setGraphicsLevel(level)
        rig.app.start()
        return rig
    }

    private fun AppRig.rendered() = backends.last.fake.renderCount

    private fun AppRig.rendering() = app.store.get(CrashGuardSection).rendering

    // ---- a free ride ----

    @Test
    fun aFreeRideBuildsTheEngineOnTheSurfaceAndDrawsEveryFrame() {
        val rig = startedRig()
        assertEquals(0, rig.backends.created.size) // the 3D side is built by the first ride

        rig.app.navigator.go(Route.Ride())
        rig.frames(10)

        assertEquals(1, rig.backends.created.size)
        assertEquals(1, rig.backends.last.attachCount)
        assertNotNull(rig.backends.antialias)
        assertTrue(rig.rendered() >= 10)
        assertFalse(rig.app.model<RideScreenModel>().paused)
        assertTrue(rig.rendering())
    }

    @Test
    fun theRideKeepsTheEngineForTheNextRideAndLeavingStopsTheCrashGuardMark() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        rig.frames(5)

        rig.app.navigator.go(Route.Menu)
        rig.frames(5)
        assertFalse(rig.rendering())
        val drawn = rig.rendered()
        rig.frames(5)
        assertEquals(drawn, rig.rendered()) // no ride, nothing is drawn

        rig.app.navigator.go(Route.Ride())
        rig.frames(5)

        assertEquals(1, rig.backends.created.size)
        assertTrue(rig.rendered() > drawn)
        assertTrue(rig.rendering())
    }

    @Test
    fun theEngineDressesTheHorseAndRunsTheRideFromTheStartPose() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        val startX = ride.view.horse.x
        ride.input.touch.moveStick(1.0, PI / 2)

        rig.frames(120)

        assertTrue(ride.view.horse.z != 0.0 || ride.view.horse.x != startX)
        assertTrue(rig.backends.last.fake.lastStats.calls > 0)
    }

    @Test
    fun theHoofBeatOfTheHorsePlaysTheSoundUnlessPaused() {
        val rig = startedRig()
        rig.app.onTouch()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        ride.input.touch.moveStick(1.0, PI / 2)

        rig.frames(180)
        val heard =
            rig.app.audio
                .getState()
                .sfxCounts.hoof
        assertTrue(heard > 0, "hoof beats: $heard")

        ride.onFocusLost()
        rig.frames(60)
        assertEquals(
            heard,
            rig.app.audio
                .getState()
                .sfxCounts.hoof,
        )
    }

    // ---- a course ride from the start to the results ----

    @Test
    fun aCourseRideWithTheAutopilotEndsOnTheResultsScreenWithSavedProgress() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Prestart(1))
        rig.app.model<app.zoeshorsefarm.presentation.courses.PrestartModel>().go()
        val ride = rig.app.model<RideScreenModel>()
        val rider = ScriptedRider(1)
        // the start signal of the course: "Go" starts the ride, the first frames let the horse set off
        var frames = 0
        while (rig.app.navigator.current is Route.Ride && frames++ < MAX_RIDE_FRAMES) {
            rider.steer(ride)
            rig.frame()
        }

        val finished = (rig.app.navigator.current as? Route.Results)?.finished
        assertNotNull(finished, "the course was not finished after $frames frames")
        val results = rig.app.model<ResultsModel>()
        assertEquals(1, results.courseId)
        val saved = rig.app.store.get(ProgressSection)
        val best = saved.courses.getValue("1")
        assertEquals(finished.result.timeCs, best.timeCs)
        assertEquals(finished.result.faults.total, best.faults)
        assertEquals(1, saved.finishedRides)
        assertTrue(saved.jumps > 0)
        // the same course ridden by the domain's rider has the same faults
        val reference = rideCourse(1)
        assertEquals(reference.result?.faults?.total, finished.result.faults.total)
        assertFalse(rig.rendering()) // the results screen does not draw the 3D scene
    }

    // ---- pause and resume ----

    @Test
    fun aPausedRideDrawsOnePictureAndTheContinueButtonDrawsAgain() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(10)

        rig.app.onFocusLost()
        assertTrue(ride.paused)
        rig.frames(PAUSE_SETTLE_FRAMES)
        val drawn = rig.rendered()
        rig.frames(30)
        assertEquals(drawn, rig.rendered())

        ride.onPauseAction(PauseAction.RESUME)
        rig.frames(5)

        assertFalse(ride.paused)
        assertTrue(rig.rendered() >= drawn + 5)
    }

    @Test
    fun theEscapeKeyPausesAndContinuesTheRide() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(5)

        rig.app.onKey(GameKey.ESCAPE, down = true)
        rig.frame()
        assertTrue(ride.paused)
        rig.app.onKey(GameKey.ESCAPE, down = false)

        assertTrue(rig.app.onKey(GameKey.ESCAPE, down = true))
        assertFalse(ride.paused)
        // a key that is not a game key is not used
        assertFalse(rig.app.onKey(null, down = true))
    }

    @Test
    fun theKeyboardSteersTheRide() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        val startZ = ride.view.horse.z

        assertTrue(rig.app.onKey(GameKey.ARROW_UP, down = true))
        rig.frames(120)
        rig.app.onKey(GameKey.ARROW_UP, down = false)

        assertTrue(ride.view.horse.z != startZ)
    }

    @Test
    fun theSettingsOnTopOfThePauseMenuKeepTheRideAndTheEngine() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.app.onFocusLost()

        ride.onPauseAction(PauseAction.SETTINGS)
        rig.frames(10)
        assertTrue(rig.rendering()) // the ride is still the drawing screen
        rig.app.navigator.pop()

        assertTrue(rig.app.navigator.currentModel === ride)
        ride.onPauseAction(PauseAction.RESUME)
        val drawn = rig.rendered()
        rig.frames(5)
        assertTrue(rig.rendered() > drawn)
        assertEquals(1, rig.backends.created.size)
    }

    @Test
    fun startAgainFromThePauseMenuPutsTheHorseOnTheStartPoseAgain() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        val start = ride.view.horse.let { it.x to it.z }
        ride.input.touch.moveStick(1.0, PI / 2)
        rig.frames(120)
        assertTrue(ride.view.horse.let { it.x to it.z } != start)
        rig.app.onFocusLost()

        ride.onPauseAction(PauseAction.RESTART)

        assertEquals(start, ride.view.horse.let { it.x to it.z })
        assertFalse(ride.paused)
    }

    // ---- the graphics device ----

    @Test
    fun aLostDevicePausesTheRideAndTheRestoreLetsItContinue() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(10)

        rig.backends.last.fake
            .simulateContextLoss()
        rig.frames(5)
        assertTrue(ride.paused)
        assertNotNull(ride.lostNote)
        assertFalse(ride.pauseButtons.first { it.action == PauseAction.RESUME }.enabled)
        val drawn = rig.rendered()
        rig.frames(5)
        assertEquals(drawn, rig.rendered())

        rig.backends.last.fake
            .simulateContextRestore()
        assertNull(ride.lostNote)
        ride.onPauseAction(PauseAction.RESUME)
        rig.frames(5)

        assertFalse(ride.paused)
        assertTrue(rig.rendered() > drawn)
    }

    @Test
    fun aDeviceThatDoesNotComeBackOffersTheReloadWhichBuildsTheEngineAgain() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(10)
        rig.backends.last.fake
            .simulateContextLoss()
        assertTrue(ride.pauseButtons.none { it.action == PauseAction.RELOAD })

        rig.frames(60 * 9) // the watchdog counts 8 seconds on the UI timer
        assertTrue(ride.pauseButtons.any { it.action == PauseAction.RELOAD })

        ride.onPauseAction(PauseAction.RELOAD)

        assertEquals(2, rig.backends.created.size)
        assertNull(ride.lostNote)
        ride.onPauseAction(PauseAction.RESUME)
        val drawn = rig.backends.last.fake.renderCount
        rig.frames(5)
        assertFalse(ride.paused)
        assertTrue(rig.backends.last.fake.renderCount > drawn)
    }

    @Test
    fun aRideThatStartsBeforeTheSurfaceWaitsPausedAndGoesOnWhenItArrives() {
        val rig = startedRig(withSurface = false)

        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(5)
        assertTrue(ride.paused)
        assertNotNull(ride.lostNote)
        assertEquals(0, rig.backends.created.size)

        rig.createSurface()
        assertNull(ride.lostNote)
        ride.onPauseAction(PauseAction.RESUME)
        rig.frames(5)

        assertEquals(1, rig.backends.created.size)
        assertFalse(ride.paused)
        assertTrue(rig.rendered() >= 5)
    }

    @Test
    fun theSurfaceSizeReachesTheBackendAndTheEngine() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        rig.frames(3)

        rig.app.onSurfaceResized(1000, 500, SURFACE_DENSITY)
        rig.frames(3)

        assertEquals(1, rig.backends.last.resizeCount)
        val size =
            rig.backends.last.fake
                .getSize(Vec2())
        assertEquals(500.0, size.x)
        assertEquals(250.0, size.y)
    }

    @Test
    fun anAppSwitchToTheBackgroundAndBackDoesNotLowerOrBlockTheLevel() {
        val rig = startedRig(level = GraphicsLevel.MEDIUM)
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(60)

        // the shell's order: the lifecycle first, then the surface goes away
        rig.app.onLifecycle(AppState.BACKGROUND)
        rig.app.onSurfaceDestroyed()
        rig.frames(30)
        assertTrue(ride.paused)
        assertFalse(rig.rendering())
        rig.app.onLifecycle(AppState.FOREGROUND)
        rig.createSurface()
        rig.frames(60)

        assertEquals(
            GraphicsLevel.MEDIUM,
            rig.app.settings
                .get()
                .graphicsLevel,
        )
        assertFalse(
            rig.app.settings
                .get()
                .graphicsAuto,
        )
        assertTrue(
            rig.app.store
                .get(CrashGuardSection)
                .blockedLevels
                .isEmpty(),
        )
        ride.onPauseAction(PauseAction.RESUME)
        rig.frames(5)
        assertFalse(ride.paused)
        assertNull(ride.feedbackText) // no "pick a lower level" hint
        assertTrue(rig.backends.last.attachCount == 2)
    }

    @Test
    fun aLossInTheForegroundBlocksTheLevelAndAsksForALowerOne() {
        val rig = startedRig(level = GraphicsLevel.MEDIUM)
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(60)

        // the same loss without the lifecycle first: the engine thinks the device is overloaded
        rig.app.onSurfaceDestroyed()
        rig.createSurface()
        ride.onPauseAction(PauseAction.RESUME)
        rig.frames(5)

        assertEquals(
            listOf(GraphicsLevel.MEDIUM),
            rig.app.store
                .get(CrashGuardSection)
                .blockedLevels,
        )
        assertNotNull(ride.feedbackText)
    }

    // ---- background and the crash guard ----

    @Test
    fun theBackgroundPausesTheRideAndClearsTheCrashGuardMarkUntilTheGraceTimeIsOver() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(10)
        assertTrue(rig.rendering())

        rig.app.onLifecycle(AppState.BACKGROUND)
        assertTrue(ride.paused)
        assertFalse(rig.rendering())

        rig.app.onLifecycle(AppState.FOREGROUND)
        rig.frames(10)
        assertFalse(rig.rendering()) // the system often kills right after an app switch: not marked yet

        rig.frames(60 * 6)
        assertTrue(rig.rendering())
    }

    @Test
    fun aRunThatEndedWhileDrawingIsACrashAtTheNextStart() {
        val first = startedRig(level = GraphicsLevel.MEDIUM)
        first.app.navigator.go(Route.Ride())
        first.frames(10)
        assertTrue(first.rendering())
        // the process dies here: nothing is released

        val second = AppRig(first.storage)

        assertTrue(second.app.previousRun is app.zoeshorsefarm.application.PreviousRun.Crashed)
        second.app.start()
        second.app.navigator.go(Route.Ride())
        val ride = second.app.model<RideScreenModel>()
        assertNotNull(ride.feedbackText) // the hint to pick a lower level (manual level above low)
        assertEquals(
            listOf(GraphicsLevel.MEDIUM),
            second.app.store
                .get(CrashGuardSection)
                .blockedLevels,
        )
    }

    // ---- language ----

    @Test
    fun aLanguageSwitchInTheSettingsRebuildsTheMenuButKeepsTheRide() {
        val rig = startedRig()
        rig.app.navigator.go(Route.Menu)
        val menu = rig.app.model<MainMenuModel>()
        val englishMenu = menu.items.first().label
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(5)
        rig.app.onFocusLost()
        val englishPause = ride.pauseButtons.first().label

        ride.onPauseAction(PauseAction.SETTINGS)
        val settings = rig.app.model<SettingsScreenModel>()
        val language = settings.sections.filterIsInstance<SettingsBlock.Rows>().first { it.id == "language" }
        (language.rows.first() as app.zoeshorsefarm.presentation.settings.ChoiceRow).select(Language.DE.id)
        rig.frames(3)
        rig.app.navigator.pop()

        assertTrue(rig.app.navigator.currentModel === ride)
        assertTrue(ride.pauseButtons.first().label != englishPause)
        assertEquals(
            Language.DE,
            rig.app.settings
                .get()
                .lang,
        )
        assertEquals(1, rig.backends.created.size)
        rig.app.navigator.go(Route.Menu)
        assertTrue(
            rig.app
                .model<MainMenuModel>()
                .items
                .first()
                .label != englishMenu,
        )
        assertEquals(RideModeId.FREE, ride.session.modeId)
    }

    @Test
    fun theDebugBoxTextNamesTheLevelAndListsTheErrorsOfTheApp() {
        val rig = AppRig(debug = true)
        rig.app.start()
        assertNull(rig.app.debugText)
        rig.app.navigator.go(Route.Ride())
        rig.frames(40)

        rig.app.recordError(IllegalStateException("boom"))
        rig.frames(40)

        val text = assertNotNull(rig.app.debugText)
        assertTrue(text.contains("boom"), text)
        assertTrue(rig.logged.any { it.contains("boom") })
    }
}
