package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamePromptTest {
    private val app = TestApp()

    private fun prompt() = NamePromptModel(app.ctx)

    @Test
    fun startsWithoutAnInputAndWithTheOkButtonOff() {
        val model = prompt()
        assertEquals("", model.input)
        assertFalse(model.okEnabled)
        assertTrue(model.music)
    }

    @Test
    fun theOkButtonIsOnForAValidNameOnly() {
        val model = prompt()
        model.onInput("Zora")
        assertTrue(model.okEnabled)
        model.onInput("   ")
        assertFalse(model.okEnabled)
        model.onInput("A name that is way too long")
        assertFalse(model.okEnabled)
    }

    @Test
    fun theInputFieldLeavesHeadroomForEmojiSurrogatePairs() {
        assertEquals(32, NamePromptModel.MAX_INPUT_LENGTH)
    }

    @Test
    fun submittingAValidNameSavesItAndContinuesWithTheStartSequence() {
        app.registerStubs("namePrompt", "controlsHelp", "menu")
        val model = prompt()
        model.onInput("  Zora ")
        model.submit()
        assertEquals("Zora", app.store.horse.name)
        assertTrue(app.store.horse.nameAnswered)
        // the controls help comes next on the first start
        assertEquals(Route.ControlsHelp(fromPause = false), app.navigator.current)
    }

    @Test
    fun submittingTheLanguageDefaultNameKeepsTheNameEmpty() {
        app.registerStubs("controlsHelp", "menu")
        app.i18n.setLang(Language.EN)
        val model = prompt()
        model.onInput("Flash")
        model.submit()
        assertNull(app.store.horse.name)
        assertTrue(app.store.horse.nameAnswered)
    }

    @Test
    fun submittingAnInvalidNameSavesNothingAndStaysOnTheScreen() {
        app.registerStubs("namePrompt", "menu", "controlsHelp")
        app.navigator.go(Route.NamePrompt)
        val model = prompt()
        model.onInput("")
        model.submit()
        assertFalse(app.store.horse.nameAnswered)
        assertEquals(Route.NamePrompt, app.navigator.current)
    }

    @Test
    fun skippingKeepsTheDefaultNameAndCountsAsAnswered() {
        app.registerStubs("controlsHelp", "menu")
        prompt().skip()
        assertNull(app.store.horse.name)
        assertTrue(app.store.horse.nameAnswered)
        assertEquals(Route.ControlsHelp(fromPause = false), app.navigator.current)
    }

    @Test
    fun afterTheHelpWasSeenTheMenuFollows() {
        app.registerStubs("menu")
        app.settings.markControlsHelpSeen()
        prompt().skip()
        assertEquals(Route.Menu, app.navigator.current)
    }

    @Test
    fun hasTheTextsOfTheQuestion() {
        val model = prompt()
        assertEquals("What is your horse's name?", model.title)
        assertEquals("Name", model.placeholder)
        assertEquals("1 to 16 letters", model.hint)
        assertEquals("Let's go", model.okLabel)
        assertEquals("Skip", model.skipLabel)
        assertEquals("🐴", model.emoji)
    }

    @Test
    fun theUiIsToldAboutInputChanges() {
        val model = prompt()
        var told = 0
        model.changes.listen { told++ }
        model.onInput("Z")
        assertEquals(1, told)
    }
}
