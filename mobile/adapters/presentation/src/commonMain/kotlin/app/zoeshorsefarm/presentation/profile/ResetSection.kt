package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.resetProgress
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.Changes

/** Where the keyboard focus should go after a change of the reset section. */
enum class ResetFocus {
    NONE,

    /** The safe choice: Enter or Space does not delete by accident. */
    CANCEL,

    /** The "Delete progress" button, after the question was answered. */
    TRIGGER,
}

/**
 * "Delete progress" in the settings, only from the main menu (rule 48): the button asks, a question
 * with "Delete" and "Cancel" follows, and only the confirmation deletes (web: `reset-section.js`).
 */
class ResetSectionModel(
    private val ctx: AppContext,
) {
    /** Changes whenever [confirming], [status] or [focus] changes. */
    val changes = Changes()

    /** The question with its two buttons is shown instead of the "Delete progress" button. */
    var confirming: Boolean = false
        private set

    /** The focus the UI should move to after the last change. */
    var focus: ResetFocus = ResetFocus.NONE
        private set

    private var done = false

    /** Empty, or the "Progress deleted." note after a confirmation. */
    val status: String get() = if (done) ctx.t("reset.done") else ""

    val buttonLabel: String get() = ctx.t("reset.button")
    val question: String get() = ctx.t("reset.question")
    val confirmLabel: String get() = ctx.t("reset.confirm")
    val cancelLabel: String get() = ctx.t("reset.cancel")

    /** The "Delete progress" button was pressed: show the question. */
    fun request() {
        confirming = true
        done = false
        focus = ResetFocus.CANCEL
        changes.fire()
    }

    fun cancel() {
        confirming = false
        focus = ResetFocus.TRIGGER
        changes.fire()
    }

    /** Deletes the progress (only the progress fields, rule 48). */
    fun confirm() {
        resetProgress(ctx.store)
        confirming = false
        done = true
        focus = ResetFocus.TRIGGER
        changes.fire()
    }
}
