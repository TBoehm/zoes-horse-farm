package app.zoeshorsefarm.presentation.ride

import app.zoeshorsefarm.application.CameraMode
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.application.RideCommand
import app.zoeshorsefarm.application.RideSound
import app.zoeshorsefarm.application.SettingsSection
import app.zoeshorsefarm.application.modes.CourseLines
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.GameKey
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.RedirectModel
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.nav.finishedParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 3D engine as the ride screen model sees it, recording what it is asked. */
private class FakeEngine : RideEnginePort {
    override var graphicsLevel = GraphicsLevel.LOW
    override var contextLost = false
    var lostListeners = mutableListOf<() -> Unit>()
    var restoredListeners = mutableListOf<() -> Unit>()
    val calls = mutableListOf<String>()
    var lines: Triple<CourseLines?, String, String>? = null
    var graphicsHint = false
    var crashHint = false
    var lowFpsHintFires = false
    var lastMeasuring: Boolean? = null
    var cameraAfterToggle = CameraMode.RIDER

    override fun onContextLost(listener: () -> Unit): () -> Unit {
        lostListeners += listener
        return { lostListeners -= listener }
    }

    override fun onContextRestored(listener: () -> Unit): () -> Unit {
        restoredListeners += listener
        return { restoredListeners -= listener }
    }

    var watchdog: (() -> Unit)? = null

    override fun canHintLowerLevel(auto: Boolean) = !auto && graphicsLevel != GraphicsLevel.LOW

    override fun startRestoreWatchdog(onTimeout: () -> Unit) {
        watchdog = onTimeout
    }

    override fun cancelRestoreWatchdog() {
        watchdog = null
    }

    override fun setCameraMode(mode: CameraMode) {
        calls += "camera ${mode.id}"
    }

    override fun toggleCamera(): CameraMode {
        calls += "toggleCamera"
        return cameraAfterToggle
    }

    override fun showLines(
        lines: CourseLines?,
        startLabel: String,
        finishLabel: String,
    ) {
        this.lines = Triple(lines, startLabel, finishLabel)
    }

    override fun onRideRestarted() {
        calls += "restarted"
    }

    override fun interruptMeasuring() {
        calls += "interrupt"
    }

    override fun lowFpsHintFrame(
        rawDt: Double,
        measuring: Boolean,
    ): Boolean {
        lastMeasuring = measuring
        return lowFpsHintFires.also { lowFpsHintFires = false }
    }

    override fun takeGraphicsHint(): Boolean = graphicsHint.also { graphicsHint = false }

    override fun takeCrashHint(): Boolean = crashHint.also { crashHint = false }

    override fun reloadGraphics() {
        calls += "reload"
    }

    fun loseContext() {
        contextLost = true
        lostListeners.toList().forEach { it() }
    }

    fun restoreContext() {
        contextLost = false
        restoredListeners.toList().forEach { it() }
    }
}

private const val FRAME = 1.0 / 60

class RideScreenTest {
    private val app = TestApp()
    private val engine = FakeEngine()

    init {
        app.registerStubs("menu", "courseSelect", "results", "settings", "controlsHelp", "prestart")
    }

    private fun start(route: Route.Ride = Route.Ride()): RideScreenModel {
        var model: RideScreenModel? = null
        app.navigator.register("ride") {
            RideScreenModel(app.ctx, it as Route.Ride, seededRng(), engine).also { m -> model = m }
        }
        app.navigator.go(route)
        return assertNotNull(model)
    }

    private fun RideScreenModel.pauseWithTouch() {
        input.touch.pressPause()
        frame(FRAME, FRAME)
    }

    // ---- start ----

    @Test
    fun startsRunningWithTheSavedCameraAndNoPauseMenu() {
        app.settings.setCamera(CameraMode.RIDER)
        val model = start()
        assertFalse(model.paused)
        assertEquals("camera rider", engine.calls.first())
        assertFalse(model.music)
        assertFalse(model.rerenderOnLang)
    }

    @Test
    fun theRestartAtTheStartTellsTheEngineAndFreesTheTouchGallop() {
        val model = start()
        model.input.touch.setGallop(true)
        model.restart()
        assertFalse(model.input.touch.gallop)
        assertTrue(engine.calls.count { it == "restarted" } >= 2)
    }

    @Test
    fun aFreeRideHasNoHudAndNoLines() {
        val model = start()
        assertNull(model.hudState)
        assertNull(model.hud)
        assertNull(engine.lines?.first)
    }

    @Test
    fun aCourseRideShowsTheHudAndTheTranslatedLineLabels() {
        val model = start(Route.Ride(RideModeId.COURSE, 1))
        assertNotNull(model.hud)
        val state = assertNotNull(model.hudState)
        assertEquals("Ride over the start line", state.notice)
        val (lines, start, finish) = assertNotNull(engine.lines)
        assertNotNull(lines)
        assertEquals("Start", start)
        assertEquals("Finish", finish)
    }

    @Test
    fun theLineLabelsFollowTheLanguageWithoutRebuildingTheRide() {
        val model = start(Route.Ride(RideModeId.COURSE, 1))
        var told = 0
        model.changes.listen { told++ }
        app.i18n.setLang(Language.DE)
        assertEquals("Ziel", engine.lines?.third)
        assertTrue(told >= 1)
        assertEquals("Pause", model.pauseTitle)
    }

    @Test
    fun aPausedCourseHudFollowsTheLanguage() {
        val model = start(Route.Ride(RideModeId.COURSE, 1))
        model.input.touch.pressPause()
        model.frame(FRAME, FRAME)
        assertEquals("Ride over the start line", model.hudState?.notice)
        app.i18n.setLang(Language.DE)
        assertEquals("Reite über die Startlinie", model.hudState?.notice)
    }

    @Test
    fun aSwitchBetweenTouchAndKeyboardTellsTheUi() {
        val hybrid = TestApp(device = DeviceClass.HYBRID)
        hybrid.registerStubs("menu")
        val ride = RideScreenModel(hybrid.ctx, Route.Ride(), seededRng(), engine)
        assertTrue(ride.showPauseHint)
        var told = 0
        ride.changes.listen { told++ }
        hybrid.inputMode.onTouch()
        assertEquals(1, told)
        assertFalse(ride.showPauseHint)
        assertTrue(ride.input.touch.visible)
    }

    @Test
    fun aLockedCourseRedirectsToTheSelection() {
        val model = createRideScreen(app.ctx, Route.Ride(RideModeId.COURSE, 3), seededRng(), engine)
        assertIs<RedirectModel>(model)
        assertEquals(Route.CourseSelect, model.redirect)
        assertIs<RideScreenModel>(createRideScreen(app.ctx, Route.Ride(RideModeId.COURSE, 1), seededRng(), engine))
    }

    @Test
    fun anOpenCourseCanBeRiddenAfterTheUnlock() {
        app.store.update(ProgressSection) { it.copy(unlocked = 3) }
        assertIs<RideScreenModel>(createRideScreen(app.ctx, Route.Ride(RideModeId.COURSE, 3), seededRng(), engine))
    }

    // ---- pause ----

    @Test
    fun theTouchPauseButtonPausesTheRideAndTheSound() {
        val model = start()
        model.pauseWithTouch()
        assertTrue(model.paused)
        assertEquals("paused true", app.sound.calls.last())
        assertTrue(engine.calls.contains("interrupt"))
    }

    @Test
    fun continuingResumesTheRideAndDropsPendingEdges() {
        val model = start()
        model.pauseWithTouch()
        model.input.touch.pressJump()
        model.onPauseAction(PauseAction.RESUME)
        assertFalse(model.paused)
        assertEquals("paused false", app.sound.calls.last())
        // the jump pressed while paused is gone
        assertFalse(model.input.poll().jump)
    }

    @Test
    fun theFrameLoopSkipsTheRideWhilePausedButTheSceneStillDraws() {
        val model = start()
        model.pauseWithTouch()
        assertEquals(FrameResult.PAUSED, model.frame(FRAME, FRAME))
    }

    @Test
    fun theFrameThatSawThePauseEdgeStopsTheRide() {
        val model = start()
        model.input.touch.pressPause()
        assertEquals(FrameResult.STOP, model.frame(FRAME, FRAME))
        assertTrue(model.paused)
    }

    @Test
    fun aRunningFrameReportsRunning() {
        val model = start()
        assertEquals(FrameResult.RUNNING, model.frame(FRAME, FRAME))
    }

    @Test
    fun theRideGoesToTheBackgroundAndPausesAutomatically() {
        val model = start()
        app.lifecycle.update(AppState.BACKGROUND)
        assertTrue(model.paused)
    }

    @Test
    fun theRotateNoticePausesTheRideOnlyWhenItBlocks() {
        val model = start()
        app.navigator.emitRotateBlocked(false)
        assertFalse(model.paused)
        app.navigator.emitRotateBlocked(true)
        assertTrue(model.paused)
    }

    @Test
    fun aLostWindowFocusPausesTheRide() {
        val model = start()
        model.onFocusLost()
        assertTrue(model.paused)
    }

    @Test
    fun escapeContinuesWhilePausedAndIgnoresKeyRepeat() {
        val model = start()
        assertFalse(model.onKeyDown(GameKey.ESCAPE, repeat = false))
        model.pauseWithTouch()
        assertTrue(model.onKeyDown(GameKey.ESCAPE, repeat = true))
        assertTrue(model.paused)
        assertTrue(model.onKeyDown(GameKey.ESCAPE, repeat = false))
        assertFalse(model.paused)
        assertFalse(model.onKeyDown(null, repeat = false))
    }

    @Test
    fun theKeyboardOnlyListensWhileTheRideIsOnTopAndNotPaused() {
        val model = start()
        assertTrue(model.inputActive)
        model.pauseWithTouch()
        assertFalse(model.inputActive)
        model.onPauseAction(PauseAction.RESUME)
        assertTrue(model.inputActive)
        app.navigator.push(Route.Settings(fromPause = false))
        assertFalse(model.inputActive)
    }

    @Test
    fun theCameraButtonTogglesTheCameraAndSavesTheChoice() {
        val model = start()
        model.input.touch.pressCamera()
        model.frame(FRAME, FRAME)
        assertTrue(engine.calls.contains("toggleCamera"))
        assertEquals(CameraMode.RIDER, app.store.get(SettingsSection).camera)
    }

    @Test
    fun theHintForTheEscapeKeyShowsOnlyWithoutTouchControls() {
        assertFalse(start().showPauseHint)
        val keyboardApp = TestApp(touch = false)
        val ride = RideScreenModel(keyboardApp.ctx, Route.Ride(), seededRng(), engine)
        assertTrue(ride.showPauseHint)
        assertEquals("Esc = Pause", ride.pauseHint)
    }

    // ---- pause menu ----

    @Test
    fun thePauseMenuListsItsButtonsInOrderAndFocusesTheFirstOne() {
        val model = start()
        model.pauseWithTouch()
        assertEquals(
            listOf(PauseAction.RESUME, PauseAction.RESTART, PauseAction.QUIT, PauseAction.SETTINGS, PauseAction.HELP),
            model.pauseButtons.map { it.action },
        )
        assertEquals(
            listOf("Continue", "Start again", "To menu", "Settings", "Controls"),
            model.pauseButtons.map { it.label },
        )
        assertEquals(PauseAction.RESUME, model.focusedPauseAction)
        assertEquals("Pause", model.pauseTitle)
    }

    @Test
    fun theQuitButtonOfACourseLeadsBackToTheSelection() {
        val model = start(Route.Ride(RideModeId.COURSE, 1))
        model.pauseWithTouch()
        assertEquals("To courses", model.pauseButtons.first { it.action == PauseAction.QUIT }.label)
        model.onPauseAction(PauseAction.QUIT)
        assertEquals(Route.CourseSelect, app.navigator.current)
    }

    @Test
    fun theQuitButtonOfAFreeRideLeadsToTheMenu() {
        val model = start()
        model.pauseWithTouch()
        model.onPauseAction(PauseAction.QUIT)
        assertEquals(Route.Menu, app.navigator.current)
    }

    @Test
    fun settingsAndHelpOpenOnTopOfThePausedRide() {
        val model = start()
        model.pauseWithTouch()
        model.onPauseAction(PauseAction.SETTINGS)
        assertEquals(Route.Settings(fromPause = true), app.navigator.current)
        assertEquals(listOf("ride", "settings"), app.navigator.stack)
        app.navigator.pop()
        assertTrue(model.paused)
        model.onPauseAction(PauseAction.HELP)
        assertEquals(Route.ControlsHelp(fromPause = true), app.navigator.current)
    }

    @Test
    fun startAgainRestartsTheRideAndContinues() {
        val model = start()
        model.pauseWithTouch()
        engine.calls.clear()
        model.onPauseAction(PauseAction.RESTART)
        assertFalse(model.paused)
        assertTrue(engine.calls.contains("restarted"))
    }

    // ---- lost graphics ----

    @Test
    fun aLostGraphicsDevicePausesAndBlocksContinuing() {
        val model = start()
        engine.loseContext()
        assertTrue(model.paused)
        assertEquals("The graphics are gone for a moment. Back in a second.", model.lostNote)
        assertFalse(model.pauseButtons.first { it.action == PauseAction.RESUME }.enabled)
        model.onPauseAction(PauseAction.RESUME)
        assertTrue(model.paused)
        assertEquals(PauseAction.RESTART, model.focusedPauseAction)
    }

    @Test
    fun aDeviceThatDoesNotComeBackAsksForAReloadWhenTheWatchdogFires() {
        val model = start()
        engine.loseContext()
        assertNotNull(engine.watchdog)
        assertNull(model.pauseButtons.firstOrNull { it.action == PauseAction.RELOAD })
        engine.watchdog?.invoke()
        assertEquals("Please reload the page.", model.lostNote)
        assertEquals(PauseAction.RELOAD, model.focusedPauseAction)
        assertEquals("Reload", model.pauseButtons.first().label)
        model.onPauseAction(PauseAction.RELOAD)
        assertTrue(engine.calls.contains("reload"))
    }

    @Test
    fun theReloadActionDoesNothingBeforeTheWatchdogFired() {
        val model = start()
        engine.loseContext()
        model.onPauseAction(PauseAction.RELOAD)
        assertFalse(engine.calls.contains("reload"))
    }

    @Test
    fun aRestoredDeviceBringsBackTheContinueButtonAndStopsTheWatchdog() {
        val model = start()
        engine.loseContext()
        engine.watchdog?.invoke()
        engine.restoreContext()
        assertNull(model.lostNote)
        assertTrue(model.pauseButtons.first { it.action == PauseAction.RESUME }.enabled)
        assertNull(model.pauseButtons.firstOrNull { it.action == PauseAction.RELOAD })
        assertNull(engine.watchdog)
        model.onPauseAction(PauseAction.RESUME)
        assertFalse(model.paused)
    }

    @Test
    fun theWatchdogIsStoppedWhenTheRideIsLeft() {
        val model = start()
        engine.loseContext()
        val timeout = engine.watchdog
        model.destroy()
        assertNull(engine.watchdog)
        timeout?.invoke() // a late timeout of a left ride changes nothing
        assertNull(model.pauseButtons.firstOrNull { it.action == PauseAction.RELOAD })
    }

    @Test
    fun aRideThatStartsWithALostDeviceStartsPaused() {
        engine.contextLost = true
        val model = start()
        assertTrue(model.paused)
        assertNotNull(model.lostNote)
    }

    @Test
    fun afterALostDeviceTheHintAppearsOnceTheRideContinues() {
        val model = start()
        model.pauseWithTouch()
        engine.graphicsHint = true
        model.onPauseAction(PauseAction.RESUME)
        assertEquals(
            "The graphics were too much for this device. Pick a lower level in the settings.",
            model.feedbackText,
        )
        assertTrue(model.feedbackIsHint)
    }

    @Test
    fun aCrashHintShowsAtTheStartOnlyForAManualLevelAboveLow() {
        engine.crashHint = true
        engine.graphicsLevel = GraphicsLevel.MEDIUM
        app.settings.setGraphicsLevel(GraphicsLevel.MEDIUM)
        assertNotNull(start().feedbackText)
    }

    @Test
    fun aCrashHintIsDroppedForAutomaticGraphicsAndForTheLowestLevel() {
        engine.crashHint = true
        assertNull(start().feedbackText)
        assertFalse(engine.crashHint)
        engine.crashHint = true
        engine.graphicsLevel = GraphicsLevel.LOW
        app.settings.setGraphicsLevel(GraphicsLevel.LOW)
        assertNull(start().feedbackText)
    }

    // ---- feedback ----

    @Test
    fun feedbackShowsForTwoSecondsOfRealTime() {
        val model = start()
        model.execute(listOf(RideCommand.Feedback("feedback.knockdown")))
        assertEquals("Pole down!", model.feedbackText)
        assertFalse(model.feedbackIsHint)
        model.frame(FRAME, 1.5)
        assertEquals("Pole down!", model.feedbackText)
        model.frame(FRAME, 0.6)
        assertNull(model.feedbackText)
    }

    @Test
    fun aHintStaysFiveSeconds() {
        val model = start()
        engine.lowFpsHintFires = true
        model.frame(FRAME, FRAME)
        assertEquals("The graphics are too high for this device. Pick a lower level.", model.feedbackText)
        assertTrue(model.feedbackIsHint)
        model.frame(FRAME, 4.9)
        assertNotNull(model.feedbackText)
        model.frame(FRAME, 0.2)
        assertNull(model.feedbackText)
    }

    @Test
    fun theLowFrameRateHintIsOnlyMeasuredForAManualLevelAboveLow() {
        val model = start()
        model.frame(FRAME, FRAME)
        assertEquals(false, engine.lastMeasuring)
        app.settings.setGraphicsLevel(GraphicsLevel.HIGH)
        engine.graphicsLevel = GraphicsLevel.HIGH
        model.frame(FRAME, FRAME)
        assertEquals(true, engine.lastMeasuring)
    }

    // ---- commands of the ride session ----

    @Test
    fun theCommandsOfTheSessionReachInputSoundToastsAndNavigation() {
        val model = start()
        model.input.touch.setGallop(true)
        model.execute(listOf(RideCommand.EndGallop))
        assertFalse(model.input.touch.gallop)

        model.input.touch.setGallop(true)
        model.execute(listOf(RideCommand.ResetTouchGallop))
        assertFalse(model.input.touch.gallop)

        model.execute(listOf(RideCommand.Sound(RideSound.TAKEOFF), RideCommand.Sound(RideSound.RAIL_DOWN)))
        assertEquals(listOf("play takeoff", "play railDown"), app.sound.calls.takeLast(2))

        model.execute(listOf(RideCommand.Badges(listOf("firstJump", "jumpMouse"))))
        assertEquals(listOf("firstJump", "jumpMouse"), app.badgeToasts.toasts.map { it.badgeId })
    }

    @Test
    fun theFinishCommandLeavesTheRideForTheResults() {
        val model = start(Route.Ride(RideModeId.COURSE, 1))
        model.input.touch.setGallop(true)
        val params = finishedParams()
        val left = model.execute(listOf(RideCommand.Finished("results", params)))
        assertTrue(left)
        assertEquals(Route.Results(params), app.navigator.current)
        assertFalse(model.input.touch.gallop)
    }

    @Test
    fun executeReportsFalseWhenTheRideGoesOn() {
        val model = start()
        assertFalse(model.execute(listOf(RideCommand.Feedback("feedback.refusal"))))
    }

    @Test
    fun hoofBeatsSoundOnlyWhileTheRideIsRunning() {
        val model = start()
        model.onFootfall("canter")
        assertEquals("hoof canter", app.sound.calls.last())
        model.pauseWithTouch()
        app.sound.calls.clear()
        model.onFootfall("canter")
        assertEquals(emptyList(), app.sound.calls)
    }

    // ---- frame rate display ----

    @Test
    fun theFrameRateShowsOnlyWhenSwitchedOnAndFollowsTheMeter() {
        val model = start()
        assertNull(model.fpsText)
        app.settings.setShowFps(true)
        assertEquals("– fps · Low (auto)", model.fpsText)
        repeat(30) { model.frame(FRAME, FRAME) }
        assertEquals("60 fps · Low (auto)", model.fpsText)
        app.settings.setShowFps(false)
        assertNull(model.fpsText)
    }

    @Test
    fun switchingTheFrameRateOnAgainDropsTheOldValue() {
        val model = start()
        app.settings.setShowFps(true)
        repeat(30) { model.frame(FRAME, FRAME) }
        app.settings.setShowFps(false)
        app.settings.setShowFps(true)
        assertEquals("– fps · Low (auto)", model.fpsText)
    }

    @Test
    fun aManualLevelIsNotMarkedAutomatic() {
        val model = start()
        app.settings.setShowFps(true)
        app.settings.setGraphicsLevel(GraphicsLevel.LOW)
        assertEquals("– fps · Low", model.fpsText)
    }

    // ---- leaving ----

    @Test
    fun leavingTheRideReleasesEverything() {
        val model = start()
        model.pauseWithTouch()
        model.destroy()
        assertEquals("paused false", app.sound.calls.last())
        assertTrue(engine.lostListeners.isEmpty())
        assertTrue(engine.restoredListeners.isEmpty())
        // no reaction any more
        app.lifecycle.update(AppState.BACKGROUND)
        app.settings.setShowFps(true)
        assertNull(model.fpsText)
    }

    @Test
    fun theBusyFlagTellsTheGovernorNotToStepWhileAJumpIsInProgress() {
        val model = start()
        model.frame(FRAME, FRAME)
        assertFalse(model.busy)
    }

    @Test
    fun theViewIsTheSessionViewForTheEngine() {
        val model = start()
        model.frame(FRAME, FRAME)
        assertNotNull(model.view.horse)
        assertEquals(RideModeId.FREE, model.session.modeId)
        assertTrue(model.session.obstacles.isNotEmpty())
        assertIs<ScreenModel>(model)
    }
}
