package app.zoeshorsefarm.presentation.help

import app.zoeshorsefarm.application.nextStartScreen
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.Changes
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.nav.toRoute
import app.zoeshorsefarm.presentation.settings.ChoiceOption

/**
 * Controls help (rule 56): key caps or touch symbols with a short text. Shown once after the name
 * question, again on demand from the main menu and from the pause menu.
 *
 * [fromPause]: opened on top of the paused ride. The shown input type is local state: nothing in the
 * app changes the language while this screen is open, so nothing has to survive a rebuild.
 * Not ported: the initial keyboard focus on the mode switch (a DOM detail; the UI may focus it).
 */
class ControlsHelpModel(
    private val ctx: AppContext,
    private val fromPause: Boolean = false,
) : ScreenModel {
    val changes = Changes()

    var mode: HelpMode = defaultHelpMode(ctx.inputMode.touch)
        private set

    override val music: Boolean = !fromPause

    val title: String get() = ctx.t("help.title")
    val modeLabel: String get() = ctx.t("help.mode")
    val doneLabel: String get() = ctx.t("help.done")

    /** The rows of the selected mode. */
    val rows: List<HelpRow> get() = rowsFor(mode)

    /** The mode switch: one option per input type. */
    val modeOptions: List<ChoiceOption> =
        HELP_MODES.map { m -> ChoiceOption(m.id) { ctx.t("help.mode.${m.id}") } }

    val selectedModeId: String get() = mode.id

    fun setMode(next: HelpMode) {
        if (next == mode) return
        mode = next
        changes.fire()
    }

    /** The translated text of a key (row text, glyph label, screen-reader name). */
    fun text(key: String): String = ctx.t(key)

    /** What a key cap shows: its fixed label or the translated text. */
    fun keyCapText(cap: KeyCap): String = cap.labelKey?.let(ctx::t) ?: cap.label.orEmpty()

    /** "Got it": back to the paused ride, or on with the start sequence (the main menu once all is done). */
    fun done() {
        ctx.settings.markControlsHelpSeen()
        if (fromPause) ctx.navigator.pop() else ctx.navigator.go(nextStartScreen(ctx.store).toRoute())
    }

    override fun destroy() = changes.clear()
}
