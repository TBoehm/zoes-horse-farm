package app.zoeshorsefarm.presentation.nav

/**
 * What the navigator needs to know about the model of a screen (web: the object a screen factory
 * returns). The Compose layer renders the concrete model type of the current screen.
 */
interface ScreenModel {
    /** The menu melody should play on this screen (rule 51). */
    val music: Boolean get() = false

    /** Start the melody this long after the screen opened (results: after the finish signal). */
    val musicDelayMs: Long get() = 0

    /** Show this route instead (guards, e.g. a locked course); the model is not shown then. */
    val redirect: Route? get() = null

    /** Rebuild the model when the language changes (default); a ride keeps its state instead. */
    val rerenderOnLang: Boolean get() = true

    /** The screen became the top screen again (back from a screen that covered it). */
    fun onShow() = Unit

    /** Another screen is about to be put on top of this one. */
    fun onCover() = Unit

    /** The screen was left: release listeners and timers. */
    fun destroy() = Unit
}
