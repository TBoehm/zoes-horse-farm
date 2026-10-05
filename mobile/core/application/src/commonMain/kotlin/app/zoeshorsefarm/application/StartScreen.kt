package app.zoeshorsefarm.application

/** A screen of the start sequence; [id] is the stable screen name. */
enum class StartScreen(
    val id: String,
) {
    NAME_PROMPT("namePrompt"),
    CONTROLS_HELP("controlsHelp"),
    MENU("menu"),
}
