package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.answerName
import app.zoeshorsefarm.application.isValidName
import app.zoeshorsefarm.application.nextStartScreen
import app.zoeshorsefarm.application.skipName
import app.zoeshorsefarm.domain.horse.NAME_MAX_LENGTH
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.Changes
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.nav.toRoute

/**
 * Name question on the first start (rule 43). Shown on every start until answered or skipped; both
 * go on with the next step of the start sequence (the controls help, then the menu, rule 56).
 */
class NamePromptModel(
    private val ctx: AppContext,
) : ScreenModel {
    val changes = Changes()

    /** What the player typed so far. */
    var input: String = ""
        private set

    override val music = true

    val emoji = "🐴"
    val title: String get() = ctx.t("namePrompt.title")
    val placeholder: String get() = ctx.t("namePrompt.placeholder")
    val hint: String get() = ctx.t("namePrompt.hint")
    val okLabel: String get() = ctx.t("namePrompt.ok")
    val skipLabel: String get() = ctx.t("namePrompt.skip")

    /** The "Let's go" button works only for a valid name. */
    val okEnabled: Boolean get() = isValidName(input)

    /** The text field changed. */
    fun onInput(text: String) {
        input = text
        changes.fire()
    }

    /** "Let's go": saves a valid name and goes on; an invalid one saves nothing. */
    fun submit() {
        if (answerName(ctx.store, input, defaultName = ctx.t("horse.defaultName"))) done()
    }

    fun skip() {
        skipName(ctx.store)
        done()
    }

    private fun done() = ctx.navigator.go(nextStartScreen(ctx.store).toRoute())

    override fun destroy() = changes.clear()

    companion object {
        /**
         * Limit of the text field. It counts UTF-16 units while the name limit counts characters:
         * headroom for surrogate pairs (emoji); `cleanName` enforces the real limit.
         */
        const val MAX_INPUT_LENGTH = NAME_MAX_LENGTH * 2
    }
}
