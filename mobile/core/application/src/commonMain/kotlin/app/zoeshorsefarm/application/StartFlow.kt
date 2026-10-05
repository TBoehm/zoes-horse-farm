package app.zoeshorsefarm.application

// Start sequence (rules 43, 56): which screens come before the main menu. Every step that is done
// removes itself from the sequence, so each screen just asks for "the next one" when it is closed.

/** Screens still to show on this start, in order; the main menu is always the last one. */
fun startSequence(store: Store): List<StartScreen> {
    val screens = ArrayList<StartScreen>()
    if (needsNamePrompt(store)) screens.add(StartScreen.NAME_PROMPT)
    if (!store.get(SettingsSection).controlsHelpSeen) screens.add(StartScreen.CONTROLS_HELP)
    screens.add(StartScreen.MENU)
    return screens
}

/** The screen to show now: the first step of the sequence that is still open. */
fun nextStartScreen(store: Store): StartScreen = startSequence(store).first()
