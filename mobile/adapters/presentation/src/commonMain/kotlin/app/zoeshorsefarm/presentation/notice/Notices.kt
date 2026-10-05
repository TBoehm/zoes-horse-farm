package app.zoeshorsefarm.presentation.notice

import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.platform.InputMode
import app.zoeshorsefarm.platform.isPortrait

// Notices: "rotate device" (rule 12), "no 3D" (rule 7), start error, "saving not possible" (rule 46).

/** The two full-page notices; [id] is the key prefix (`notice.<id>.title`). */
enum class NoticeKind(
    val id: String,
) {
    /** The device cannot show 3D. */
    NO_3D("no3d"),

    /** Unexpected start error: a child-friendly notice instead of an empty page. */
    ERROR("error"),
}

/** Full-page notice with a horse, title, text and hint. */
class FullNotice(
    val kind: NoticeKind,
    private val i18n: I18n,
) {
    val emoji = "🐴"
    val title: String get() = i18n.t("notice.${kind.id}.title")
    val text: String get() = i18n.t("notice.${kind.id}.text")
    val hint: String get() = i18n.t("notice.${kind.id}.hint")
}

/**
 * In portrait orientation with touch mode active the rotate notice covers everything. The shell
 * reports the size of the window through [update]; [onBlockedChange] fires when the state changes
 * (the ride pauses, see `AppNavigator.emitRotateBlocked`).
 */
class RotateNotice(
    private val i18n: I18n,
    private val inputMode: InputMode,
    width: Int,
    height: Int,
    private val onBlockedChange: ((Boolean) -> Unit)? = null,
) {
    private var width = width
    private var height = height
    private val stopListening = inputMode.onChange { evaluate() }

    /** The notice is shown and the game is blocked. */
    var blocked: Boolean = false
        private set

    init {
        evaluate()
    }

    val title: String get() = i18n.t("notice.rotate.title")
    val text: String get() = i18n.t("notice.rotate.text")

    /** The window changed its size or orientation. */
    fun update(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        evaluate()
    }

    private fun evaluate() {
        val next = inputMode.touch && isPortrait(width, height)
        if (next == blocked) return
        blocked = next
        onBlockedChange?.invoke(blocked)
    }

    fun dispose() = stopListening()
}

/** The note that saving is not possible; the player can still play and closes it with "Okay". */
class SaveNotice(
    private val i18n: I18n,
) {
    private val listeners = LinkedHashSet<() -> Unit>()

    var visible: Boolean = false
        private set

    val text: String get() = i18n.t("notice.save.text")
    val okLabel: String get() = i18n.t("common.ok")

    fun show() = set(true)

    fun dismiss() = set(false)

    private fun set(value: Boolean) {
        if (value == visible) return
        visible = value
        for (listener in listeners.toList()) listener()
    }

    /** Calls [listener] when the note appears or disappears; returns the function that unsubscribes. */
    fun onChange(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}
