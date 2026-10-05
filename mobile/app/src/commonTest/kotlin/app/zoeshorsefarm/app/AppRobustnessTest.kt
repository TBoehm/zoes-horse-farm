package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.CrashGuardSection
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.audio.OutputState
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.GameKey
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.notice.NoticeKind
import app.zoeshorsefarm.presentation.ride.PauseAction
import app.zoeshorsefarm.presentation.ride.RideScreenModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Edge cases of the host contract: no surface, bad backends, background, battery levers, start failures.

class AppRobustnessTest {
    private fun rideRig(
        quiet: Boolean = false,
        withSurface: Boolean = true,
        state: AppState = AppState.FOREGROUND,
    ): AppRig {
        val rig = AppRig(quiet = quiet, withSurface = withSurface, state = state)
        rig.app.start()
        rig.app.navigator.go(Route.Ride())
        return rig
    }

    // ---- reload, build errors ----

    @Test
    fun reloadWithoutASurfaceIsNoDeadEnd() {
        val rig = rideRig()
        val ride = rig.app.model<RideScreenModel>()
        rig.frames(10)
        rig.app.onSurfaceDestroyed()
        rig.frames(60 * 9)
        assertTrue(ride.pauseButtons.any { it.action == PauseAction.RELOAD })

        ride.onPauseAction(PauseAction.RELOAD)

        // the ride waits for a surface: no lost note, "Continue" works, no Reload button any more
        assertNull(ride.lostNote)
        assertTrue(ride.pauseButtons.none { it.action == PauseAction.RELOAD })
        assertTrue(ride.pauseButtons.first { it.action == PauseAction.RESUME }.enabled)
        rig.createSurface()
        ride.onPauseAction(PauseAction.RESUME)
        rig.frames(10)
        assertEquals(2, rig.backends.created.size)
        assertFalse(ride.paused)
        assertTrue(rig.backends.last.fake.renderCount >= 10)
    }

    @Test
    fun aBackendThatCannotBeCreatedIsTheNo3dNotice() {
        val rig = rideRig(withSurface = false)
        rig.backends.available = false

        rig.createSurface()

        assertEquals(NoticeKind.NO_3D, rig.app.notice?.kind)
        assertTrue(rig.app.model<RideScreenModel>().paused) // a lost device with Reload
    }

    @Test
    fun anErrorWhileBuildingTheBackendIsTheErrorNoticeAndIsLogged() {
        val rig = rideRig(withSurface = false)
        rig.backends.failWith = IllegalStateException("no metal")

        rig.createSurface()

        assertEquals(NoticeKind.ERROR, rig.app.notice?.kind)
        assertTrue(rig.logged.any { it.contains("no metal") })
        assertTrue(
            rig.app.errorLog.entries
                .any { it.message.contains("no metal") },
        )
    }

    @Test
    fun reloadAfterABuildErrorTriesAgain() {
        val rig = rideRig(withSurface = false)
        rig.backends.failWith = IllegalStateException("no metal")
        rig.createSurface()
        val ride = rig.app.model<RideScreenModel>()
        assertTrue(ride.paused)
        rig.backends.failWith = null

        rig.frames(60 * 9)
        ride.onPauseAction(PauseAction.RELOAD)
        ride.onPauseAction(PauseAction.RESUME)
        rig.frames(5)

        assertFalse(ride.paused)
        assertEquals(1, rig.backends.created.size)
    }

    @Test
    fun theCrashOfThePreviousRunIsNotReportedAgainAfterAReload() {
        val first = AppRig(debug = true)
        first.app.settings.setGraphicsLevel(GraphicsLevel.MEDIUM)
        first.app.start()
        first.app.navigator.go(Route.Ride())
        first.frames(10)
        val second = AppRig(first.storage, debug = true)
        second.app.start()
        second.app.navigator.go(Route.Ride())
        val ride = second.app.model<RideScreenModel>()
        second.frames(40)
        val before = second.app.debugText.orEmpty()

        second.backends.last.fake
            .simulateContextLoss()
        second.frames(60 * 9)
        ride.onPauseAction(PauseAction.RELOAD)
        second.frames(40)

        assertEquals(2, second.backends.created.size)
        // the box says what the crash guard knows (the last crash), the engine only reports a new crash once
        assertTrue(before.isNotEmpty())
        assertTrue(
            second.app.store
                .get(CrashGuardSection)
                .lastCrash != null,
        )
    }

    // ---- navigation ----

    @Test
    fun aSecondRideRightAfterTheFirstStartsFromItsOwnStartPose() {
        val rig = AppRig()
        rig.app.start()
        rig.app.navigator.go(Route.Ride())
        val first = rig.app.model<RideScreenModel>()
        first.input.touch.moveStick(1.0, kotlin.math.PI / 2)
        rig.frames(120)

        rig.app.navigator.go(Route.Ride())
        val second = rig.app.model<RideScreenModel>()
        rig.frames(5)

        assertTrue(first !== second)
        assertEquals(1, rig.backends.created.size)
        assertTrue(
            rig.app.store
                .get(CrashGuardSection)
                .rendering,
        )
        // the new ride is the one the engine runs: its horse moves, the old one stays where it was
        val startZ = second.view.horse.z
        val oldZ = first.view.horse.z
        second.input.touch.moveStick(1.0, kotlin.math.PI / 2)
        rig.frames(60)
        assertTrue(second.view.horse.z != startZ)
        assertEquals(oldZ, first.view.horse.z)
    }

    // ---- start of the app ----

    @Test
    fun anErrorWhileWiringTheAppGivesTheErrorNoticeInsteadOfAnException() {
        val boom =
            object : AbstractList<String?>() {
                override val size = 1

                override fun get(index: Int): String? = throw IllegalStateException("no languages")
            }
        val logged = ArrayList<String>()
        val platform =
            AppPlatform(
                keyValueBackend = null,
                audio = app.zoeshorsefarm.audio.AudioPlatform(null) { _, _ -> app.zoeshorsefarm.audio.Cancellable { } },
                renderBackends = TestBackends(),
                deviceInfo = { BIG_DEVICE },
                preferredLanguages = boom,
                logSink = { logged.add(it) },
            )

        val result = ZoesHorseFarmApp.create(platform)

        assertIs<AppCreation.Failed>(result)
        assertEquals(NoticeKind.ERROR, result.notice.kind)
        assertTrue(result.notice.title.isNotEmpty())
        assertTrue(logged.any { it.contains("no languages") })
    }

    @Test
    fun anAppThatStartsInTheBackgroundIsHiddenAndNotMarkedAsDrawing() {
        val rig = AppRig(state = AppState.BACKGROUND)
        rig.app.start()

        assertTrue(
            rig.app.audio
                .getState()
                .hidden,
        )
        rig.app.navigator.go(Route.Ride())
        rig.frames(10)
        assertFalse(
            rig.app.store
                .get(CrashGuardSection)
                .rendering,
        )
    }

    // ---- sound unlock ----

    @Test
    fun aGameKeyUnlocksTheSoundButEscapeAndTypingDoNot() {
        val rig = AppRig(inputDevice = DeviceClass.HYBRID)
        rig.app.start()
        assertFalse(
            rig.app.audio
                .getState()
                .unlocked,
        )

        rig.app.onKey(GameKey.ESCAPE, down = true)
        rig.app.onKey(GameKey.KEY_A, down = true, inEditableField = true)
        assertFalse(
            rig.app.audio
                .getState()
                .unlocked,
        )

        rig.app.onKey(GameKey.ARROW_UP, down = true)

        assertTrue(
            rig.app.audio
                .getState()
                .unlocked,
        )
        assertEquals(OutputState.Running, OutputState.Running)
    }

    @Test
    fun aKeyThatIsNotAGameKeyUnlocksTheSoundToo() {
        val rig = AppRig()
        rig.app.start()

        rig.app.onKey(null, down = true)

        assertTrue(
            rig.app.audio
                .getState()
                .unlocked,
        )
    }

    @Test
    fun anIpadWithAKeyboardSwitchesTheTouchControlsOnTheFirstGameKey() {
        val rig = AppRig(inputDevice = DeviceClass.HYBRID)
        rig.app.start()
        assertFalse(rig.app.inputMode.touch)

        rig.app.onTouch()
        assertTrue(rig.app.inputMode.touch)
        rig.app.onKey(GameKey.ARROW_UP, down = true)

        assertFalse(rig.app.inputMode.touch)
    }

    // ---- background and size ----

    @Test
    fun nothingIsDrawnInTheBackgroundButTheTimersGoOn() {
        val rig = rideRig(quiet = true)
        rig.frames(10)
        val drawn = rig.backends.last.quiet.renders
        var fired = false
        rig.app.scheduler.postDelayed(500) { fired = true }

        rig.app.onLifecycle(AppState.BACKGROUND)
        rig.frames(60)

        assertEquals(drawn, rig.backends.last.quiet.renders)
        assertTrue(fired)
        rig.app.onLifecycle(AppState.FOREGROUND)
        rig.frames(5)
        assertEquals(true, rig.backends.last.quiet.renders > drawn)
    }

    @Test
    fun aSurfaceWithoutAreaIsNotDrawnOnAndWaitsForItsSize() {
        val rig = AppRig(quiet = true, withSurface = false)
        rig.app.start()
        rig.app.navigator.go(Route.Ride())
        rig.app.onSurfaceCreated(rig.surface, 0, 0, SURFACE_DENSITY)
        rig.frames(10)
        assertEquals(0, rig.backends.created.size)

        rig.app.onSurfaceResized(SURFACE_WIDTH, SURFACE_HEIGHT, SURFACE_DENSITY)
        rig.frames(10)

        assertEquals(1, rig.backends.created.size)
        assertEquals(
            SURFACE_WIDTH,
            rig.backends.last.size
                ?.widthPx,
        )
        assertTrue(rig.backends.last.quiet.renders >= 10)

        rig.app.onSurfaceResized(0, 0, SURFACE_DENSITY)
        val drawn = rig.backends.last.quiet.renders
        rig.frames(10)
        assertEquals(drawn, rig.backends.last.quiet.renders)
    }

    // ---- battery levers ----

    @Test
    fun theAppWantsFramesWhileARideDrawsAndNotWhileItIsPausedAndIdle() {
        val rig = AppRig(quiet = true)
        val changes = ArrayList<Boolean>()
        rig.app.start()
        rig.app.onDemandChanged = { changes.add(it) }
        assertFalse(rig.app.wantsFrames) // a menu without timers

        rig.app.navigator.go(Route.Ride())
        rig.frames(10)
        assertTrue(rig.app.wantsFrames)

        rig.app.onFocusLost()
        rig.frames(10)
        assertFalse(rig.app.wantsFrames)

        rig.app.model<RideScreenModel>().onPauseAction(PauseAction.RESUME)
        assertTrue(rig.app.wantsFrames)
        assertEquals(listOf(true, false, true), changes.filterIndexed { i, _ -> i < 3 })
    }

    @Test
    fun aWaitingTimerOfAScreenWantsFramesUntilItHasRun() {
        val rig = AppRig(quiet = true)
        rig.app.start()
        rig.frames(5)
        assertFalse(rig.app.wantsFrames)
        val changes = ArrayList<Boolean>()
        rig.app.onDemandChanged = { changes.add(it) }

        rig.app.scheduler.postDelayed(100) { }
        assertTrue(rig.app.wantsFrames)
        assertEquals(listOf(true), changes)

        rig.frames(10)

        assertFalse(rig.app.wantsFrames)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun theAppDoesNotWantFramesAfterDispose() {
        val rig = AppRig(quiet = true)
        rig.app.start()
        rig.app.navigator.go(Route.Ride())
        rig.frames(5)
        assertTrue(rig.app.wantsFrames)

        rig.app.dispose()

        assertFalse(rig.app.wantsFrames)
    }

    @Test
    fun theThirtyFramesLeverStartsFromThePlatformAndSurvivesAReload() {
        val rig = AppRig(quiet = true, capTo30Fps = true)
        rig.app.start()
        rig.app.navigator.go(Route.Ride())
        rig.frames(60)
        val capped = rig.backends.last.quiet.renders
        assertTrue(capped in 25..35, "drawn at 60 frames per second input: $capped")

        rig.app.setCapTo30Fps(false)
        val before = rig.backends.last.quiet.renders
        rig.frames(60)
        assertTrue(rig.backends.last.quiet.renders - before >= 55)

        rig.app.setCapTo30Fps(true)
        val ride = rig.app.model<RideScreenModel>()
        rig.app.onFocusLost()
        rig.backends.last.quiet
            .loseContext()
        rig.frames(60 * 9)
        ride.onPauseAction(PauseAction.RELOAD)
        ride.onPauseAction(PauseAction.RESUME)
        val reloaded = rig.backends.last.quiet.renders
        rig.frames(60)
        assertEquals(2, rig.backends.created.size)
        assertTrue(rig.backends.last.quiet.renders - reloaded in 25..35)
    }
}
