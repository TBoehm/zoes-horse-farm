package app.zoeshorsefarm.presentation.help

import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.InputMode
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlsHelpTest {
    private val app = TestApp(touch = true)

    @Test
    fun startsWithTheModeThatMatchesTheInput() {
        assertEquals(HelpMode.TOUCH, ControlsHelpModel(app.ctx).mode)
        val keyboard = TestApp(touch = false)
        assertEquals(HelpMode.KEYBOARD, ControlsHelpModel(keyboard.ctx).mode)
    }

    @Test
    fun theModeSwitchChangesTheRows() {
        val model = ControlsHelpModel(app.ctx)
        assertEquals(TOUCH_ROWS, model.rows)
        model.setMode(HelpMode.KEYBOARD)
        assertEquals(KEYBOARD_ROWS, model.rows)
        assertEquals(HelpMode.KEYBOARD, model.mode)
    }

    @Test
    fun theModeSwitchIsAChoiceOfBothModesWithTranslatedLabels() {
        val model = ControlsHelpModel(app.ctx)
        assertEquals(listOf("keyboard", "touch"), model.modeOptions.map { it.id })
        assertEquals(listOf("Keyboard", "Touch"), model.modeOptions.map { it.label })
        assertEquals("touch", model.selectedModeId)
        app.i18n.setLang(Language.DE)
        assertEquals(listOf("Tastatur", "Touch"), model.modeOptions.map { it.label })
    }

    @Test
    fun theUiIsToldAboutAModeChange() {
        val model = ControlsHelpModel(app.ctx)
        var told = 0
        model.changes.listen { told++ }
        model.setMode(HelpMode.KEYBOARD)
        model.setMode(HelpMode.KEYBOARD)
        assertEquals(1, told)
    }

    @Test
    fun resolvesTheTextOfARowAndOfAKeyCap() {
        val model = ControlsHelpModel(app.ctx)
        model.setMode(HelpMode.KEYBOARD)
        assertEquals("jump", model.text(KEYBOARD_ROWS.first { it.id == "jump" }.textKey))
        assertEquals("Space", model.keyCapText(KeyCap(labelKey = "help.key.space")))
        assertEquals("W", model.keyCapText(KeyCap(label = "W")))
    }

    @Test
    fun theMusicPlaysUnlessItIsShownOverThePausedRide() {
        assertTrue(ControlsHelpModel(app.ctx, fromPause = false).music)
        assertFalse(ControlsHelpModel(app.ctx, fromPause = true).music)
    }

    @Test
    fun gotItFromThePauseMenuPopsBackToThePausedRide() {
        app.registerStubs("ride", "controlsHelp")
        app.navigator.go(Route.Ride())
        app.navigator.push(Route.ControlsHelp(fromPause = true))
        ControlsHelpModel(app.ctx, fromPause = true).done()
        assertEquals(Route.Ride(), app.navigator.current)
        assertTrue(app.store.settings.controlsHelpSeen)
    }

    @Test
    fun gotItOnTheFirstStartContinuesWithTheNextStartScreen() {
        app.registerStubs("namePrompt", "controlsHelp", "menu")
        app.navigator.go(Route.ControlsHelp())
        ControlsHelpModel(app.ctx).done()
        // the name question is still open
        assertEquals(Route.NamePrompt, app.navigator.current)
        assertTrue(app.store.settings.controlsHelpSeen)
    }

    @Test
    fun gotItGoesToTheMenuWhenTheNameQuestionIsAnswered() {
        app.registerStubs("namePrompt", "controlsHelp", "menu")
        app.store.update(HorseSection) { it.copy(nameAnswered = true) }
        app.navigator.go(Route.ControlsHelp())
        ControlsHelpModel(app.ctx).done()
        assertEquals(Route.Menu, app.navigator.current)
    }

    @Test
    fun aKeyboardDeviceThatSwitchesToTouchStillStartsOnItsOwnMode() {
        val hybrid =
            AppContext(
                store = app.store,
                settings = app.settings,
                inputMode = InputMode(DeviceClass.HYBRID),
                clock = app.clock,
                i18n = app.i18n,
                navigator = app.navigator,
                scheduler = app.scheduler.ui,
                lifecycle = app.lifecycle,
                badgeToasts = app.badgeToasts,
                version = "x",
            )
        assertEquals(HelpMode.KEYBOARD, ControlsHelpModel(hybrid).mode)
    }
}
