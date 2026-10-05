package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FakeStore
import kotlin.test.Test
import kotlin.test.assertEquals

class StartFlowTest {
    private fun store(
        named: Boolean = true,
        helpSeen: Boolean = false,
    ) = FakeStore(
        settings = Settings(controlsHelpSeen = helpSeen),
        horse = HorseProfile(nameAnswered = named, name = null),
    )

    @Test
    fun firstStartIsNameQuestionControlsHelpThenTheMenu() {
        // rule 56
        assertEquals(
            listOf(StartScreen.NAME_PROMPT, StartScreen.CONTROLS_HELP, StartScreen.MENU),
            startSequence(store(named = false)),
        )
    }

    @Test
    fun existingSaveThatNeverClosedTheHelpShowsTheHelpOnceThenTheMenu() {
        assertEquals(
            listOf(StartScreen.CONTROLS_HELP, StartScreen.MENU),
            startSequence(store(named = true, helpSeen = false)),
        )
    }

    @Test
    fun nameNotAnsweredYetButHelpAlreadySeenAsksTheNameFirst() {
        assertEquals(
            listOf(StartScreen.NAME_PROMPT, StartScreen.MENU),
            startSequence(store(named = false, helpSeen = true)),
        )
    }

    @Test
    fun everythingDoneGoesStraightToTheMenu() {
        assertEquals(listOf(StartScreen.MENU), startSequence(store(named = true, helpSeen = true)))
    }

    @Test
    fun theScreensKeepTheirIds() {
        assertEquals(listOf("namePrompt", "controlsHelp", "menu"), StartScreen.entries.map { it.id })
    }

    @Test
    fun nextStartScreenWalksTheSequenceAsTheStepsGetDone() {
        val s = FakeStore(settings = Settings(controlsHelpSeen = false))
        assertEquals(StartScreen.NAME_PROMPT, nextStartScreen(s))
        s.update(HorseSection) { it.copy(nameAnswered = true) }
        assertEquals(StartScreen.CONTROLS_HELP, nextStartScreen(s))
        s.update(SettingsSection) { it.copy(controlsHelpSeen = true) }
        assertEquals(StartScreen.MENU, nextStartScreen(s))
    }
}
