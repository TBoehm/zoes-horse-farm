package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.describeBadge
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.presentation.Changes
import app.zoeshorsefarm.presentation.UiScheduler
import app.zoeshorsefarm.presentation.UiTask

// Short toast for instantly awarded badges, without interrupting the ride (rule 49). Small and at
// the bottom centre, so that it does not cover the course HUD (top left). In touch mode it sits in
// the free gap between the joystick and the Gallop/Jump buttons instead.

private const val MAX_VISIBLE = 3
private const val MAX_VISIBLE_LOW_SCREEN = 2
private const val LOW_SCREEN_HEIGHT = 520
private const val DEFAULT_DURATION_MS = 3200L
private const val LEAVE_MS = 400L
private const val STACK_GAP = 6.0
private const val GAP_MARGIN = 8.0
private const val GAP_MIN_WIDTH = 120.0

/** Stack position of a toast that is waiting or showing (oldest first). */
data class ToastSlot(
    val index: Int,
    val visible: Boolean,
)

/** Stack positions of [count] toasts, only the first [maxVisible] are shown. */
fun toastSlots(
    count: Int,
    maxVisible: Int,
): List<ToastSlot> = List(count) { ToastSlot(it, it < maxVisible) }

/** Bottom offsets of stacked toasts from their measured [heights]: each sits above the previous ones. */
fun stackOffsets(
    heights: List<Double>,
    gap: Double = STACK_GAP,
): List<Double> {
    val offsets = ArrayList<Double>(heights.size)
    var offset = 0.0
    for (height in heights) {
        offsets.add(offset)
        offset += height + gap
    }
    return offsets
}

/** Centre [x] of the free gap between two touch-control groups and the widest allowed toast. */
data class ToastGap(
    val x: Double,
    val maxWidth: Double,
)

/**
 * Horizontal placement inside the free gap between two touch-control groups (viewport pixels).
 * @param leftEdge right edge of the left group
 * @param rightEdge left edge of the right group
 */
fun toastGap(
    leftEdge: Double,
    rightEdge: Double,
    margin: Double = GAP_MARGIN,
    minWidth: Double = GAP_MIN_WIDTH,
): ToastGap = ToastGap((leftEdge + rightEdge) / 2, maxOf(minWidth, rightEdge - leftEdge - margin * 2))

/** A badge toast: [visible] false while it waits for a free slot, [leaving] while it fades out. */
data class BadgeToastState(
    val key: Long,
    val badgeId: String,
    val icon: String,
    val text: String,
    val visible: Boolean,
    val leaving: Boolean,
)

/**
 * The toasts of awarded badges. A toast shows for [durationMs]; a waiting toast starts its timer
 * only when it becomes visible. Turning the device moves the touch controls: call [relayout] then.
 *
 * @param viewportHeight the current height of the window in pixels (a low screen shows fewer toasts)
 */
class BadgeToastQueue(
    private val i18n: I18n,
    private val scheduler: UiScheduler,
    private val viewportHeight: () -> Int,
    private val durationMs: Long = DEFAULT_DURATION_MS,
) {
    private class Entry(
        val key: Long,
        val badgeId: String,
        val icon: String,
        val text: String,
    ) {
        var visible = false
        var leaving = false
        var started = false
        val timers = ArrayList<UiTask>()
    }

    /** Changes whenever [toasts] changes. */
    val changes = Changes()

    private val entries = ArrayList<Entry>()
    private var nextKey = 0L

    /** The toasts, oldest first (the one at the bottom of the stack first). */
    var toasts: List<BadgeToastState> = emptyList()
        private set

    /** Shows the toast of a badge; false (nothing shown) for an unknown badge id. */
    fun show(badgeId: String): Boolean {
        val badge = describeBadge(badgeId) ?: return false
        val text = i18n.t("badge.toast", mapOf("name" to i18n.t(badge.nameKey)))
        entries.add(Entry(nextKey++, badgeId, badgeIcon(badgeId), text))
        layout()
        return true
    }

    /** The window changed its size. */
    fun relayout() = layout()

    private fun layout() {
        val max = if (viewportHeight() <= LOW_SCREEN_HEIGHT) MAX_VISIBLE_LOW_SCREEN else MAX_VISIBLE
        val active = entries.filter { !it.leaving }
        val slots = toastSlots(active.size, max)
        active.forEachIndexed { i, entry ->
            entry.visible = slots[i].visible
            if (entry.visible) start(entry)
        }
        publish()
    }

    // A toast that is waiting for a free slot starts its timer only when it becomes visible
    private fun start(entry: Entry) {
        if (entry.started) return
        entry.started = true
        entry.timers +=
            scheduler.postDelayed((durationMs - LEAVE_MS).coerceAtLeast(0)) {
                entry.leaving = true
                layout() // the others move down right away
            }
        entry.timers +=
            scheduler.postDelayed(durationMs) {
                entries.remove(entry)
                layout()
            }
    }

    private fun publish() {
        toasts = entries.map { BadgeToastState(it.key, it.badgeId, it.icon, it.text, it.visible, it.leaving) }
        changes.fire()
    }

    /** Drops every toast and timer. */
    fun dispose() {
        for (entry in entries) entry.timers.forEach { it.cancel() }
        entries.clear()
        toasts = emptyList()
        changes.clear()
    }
}
