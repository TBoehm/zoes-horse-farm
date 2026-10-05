package app.zoeshorsefarm.application

import kotlin.math.floor
import kotlin.math.max

// Unclean-exit guard (rule 4): detects that the game ended unexpectedly while the 3D picture was
// being drawn (the system killed the app because of overload). The handling of a lost 3D context
// never runs in that case because the whole app is gone, so the next start has to treat the
// leftover "rendering" mark like a context loss in the foreground.
//
// The guard keeps a small section in the save: it flips to "rendering" when a screen starts
// drawing the scene and back to "clean" when it stops or when the app goes to the background or
// closes. An app that was in the background and killed later therefore never counts. Writes happen
// on transitions, on a level change, and as a slow heartbeat (so the debug display can tell how
// long the crashed session lasted).
//
// Two more cases keep the guard from crying wolf: after the app came back to the foreground the
// mark is only set again after a grace time (the system often kills or reloads right after an app
// switch), and a leftover mark whose heartbeat is still fresh at the start belongs to an instance
// that is alive (a second window or tab), not to a crash; the tab id tells the two apart (see
// checkPreviousRun). The store writes its whole in-memory copy, so the guard never uses `update`:
// it writes only its own section through (`store.updateThrough`), which keeps what other instances
// saved in the storage and never touches this instance's in-memory state of the other sections.

// Technical value (no game play): how often the heartbeat refreshes the "last seen" time.
const val HEARTBEAT_INTERVAL_MS = 5000L

// A leftover mark younger than this many heartbeat intervals at the start is a live instance.
private const val LIVE_HEARTBEATS = 2

private const val MS_PER_SECOND = 1000.0

/** What a loss of the 3D picture in the foreground means for the level (same rule as a lost context). */
data class LevelDecision(
    val level: GraphicsLevel,
    val persist: Boolean,
    val hint: Boolean,
)

/** The graphics level and automatic flag a screen draws with. */
data class RenderInfo(
    val level: GraphicsLevel,
    val auto: Boolean,
)

/** A screen's claim "I am drawing the 3D scene"; see [CrashGuard.markRendering]. */
interface RenderLease {
    /** Call every frame (cheap, writes only when needed). */
    fun frame(info: RenderInfo)

    /** The screen stops drawing; releasing twice does nothing. */
    fun release()
}

/** The result of [CrashGuard.checkPreviousRun]. */
sealed interface PreviousRun {
    data object Clean : PreviousRun

    /** The previous run ended while drawing at [level] ([auto]: automatic graphics), [seconds] into the session. */
    data class Crashed(
        val level: GraphicsLevel?,
        val auto: Boolean,
        val seconds: Int,
    ) : PreviousRun
}

/**
 * @param store `updateThrough` changes one section against the stored one and writes only that section
 * @param settings the settings service (the only writer of the settings)
 * @param decide what a loss of the 3D picture in the foreground means for the level: (auto, level)
 * @param foregroundGraceMs time after the app came back before it is marked again
 * @param tabId identity of this app instance, constant across restarts of it (null: unknown). The web
 *   keeps it in `sessionStorage`, which survives the reload after a crash. The native app has exactly one
 *   instance per install and no such storage, so it passes null: a per-process id would be new after a
 *   crash and make the crashed run look like another live instance.
 */
class CrashGuard(
    private val store: Store,
    private val settings: SettingsService,
    private val clock: Clock,
    private val decide: (auto: Boolean, level: GraphicsLevel) -> LevelDecision,
    private val foregroundGraceMs: Long = (FOREGROUND_GRACE_S * MS_PER_SECOND).toLong(),
    private val tabId: String? = null,
) {
    /** What is persisted right now (rendering mark, level, auto flag). */
    private class Mirror(
        var rendering: Boolean,
        var level: GraphicsLevel?,
        var auto: Boolean,
    )

    private val leases = LinkedHashSet<Lease>()
    private var background = false
    private var returnedAtMs: Long? = null // when the app last came back to the foreground
    private var latestLevel = AUTO_START_LEVEL // of the most recent screen update
    private var latestAuto = true
    private var mirror: Mirror? = null
    private var lastBeatMs = 0L
    private var ownsMark = false // this guard set the persisted rendering mark (another instance's is not ours)

    private fun read() = store.get(CrashGuardSection)

    /** Changes the guard section only; other sections (here and in the storage) stay untouched. */
    private fun update(change: (CrashGuardState) -> CrashGuardState) = store.updateThrough(CrashGuardSection, change)

    private fun persisted(): Mirror =
        mirror ?: read().let { Mirror(it.rendering, it.level, it.auto) }.also { mirror = it }

    /** The mark belongs to another instance that is still drawing. */
    private fun isLiveOtherTab(saved: CrashGuardState): Boolean {
        if (tabId == null || saved.tabId == tabId) return false
        val sinceLastSeenMs = clock.nowMs() - saved.lastSeen
        return sinceLastSeenMs >= 0 && sinceLastSeenMs < LIVE_HEARTBEATS * HEARTBEAT_INTERVAL_MS
    }

    private fun wantRendering(): Boolean {
        if (leases.isEmpty() || background) return false
        val returnedAt = returnedAtMs
        return returnedAt == null || clock.nowMs() - returnedAt >= foregroundGraceMs
    }

    /** Brings the persisted mark in line with what the screens do right now. */
    private fun sync() {
        val now = clock.nowMs()
        val saved = persisted()
        if (!wantRendering()) {
            // only the own mark is cleared: a leftover one of a live second instance stays
            if (saved.rendering && ownsMark) {
                update { it.copy(rendering = false) }
                saved.rendering = false
            }
            ownsMark = false
            return
        }
        ownsMark = true
        val level = latestLevel
        val auto = latestAuto
        if (!saved.rendering) {
            lastBeatMs = now
            update { it.copy(rendering = true, level = level, auto = auto, tabId = tabId, since = now, lastSeen = now) }
            saved.rendering = true
            saved.level = level
            saved.auto = auto
        } else if (saved.level != level || saved.auto != auto) {
            update { it.copy(level = level, auto = auto) }
            saved.level = level
            saved.auto = auto
        } else if (now - lastBeatMs >= HEARTBEAT_INTERVAL_MS) {
            lastBeatMs = now
            // also restates the mark: another instance may have cleared it while this one still draws
            update { it.copy(rendering = true, level = level, auto = auto, tabId = tabId, lastSeen = now) }
            saved.rendering = true
            saved.level = level
            saved.auto = auto
        }
    }

    private inner class Lease : RenderLease {
        override fun frame(info: RenderInfo) {
            if (this !in leases) return
            latestLevel = info.level
            latestAuto = info.auto
            sync()
        }

        override fun release() {
            if (!leases.remove(this)) return
            sync()
        }
    }

    /**
     * A screen starts drawing the 3D scene (ride, free mode, pre-start, horse preview). Keep the
     * lease: call `lease.frame(info)` every frame (cheap, writes only when needed) and
     * `lease.release()` when the screen stops drawing. Screens may overlap (a rebuilt screen is
     * created before the old one is destroyed): the mark stays until every lease is released.
     * @param info current graphics level and automatic flag
     */
    fun markRendering(info: RenderInfo): RenderLease {
        latestLevel = info.level
        latestAuto = info.auto
        val lease = Lease()
        leases.add(lease)
        sync()
        return lease
    }

    /** The app goes to the background or closes: whatever happens next is not a crash. */
    fun markBackground() {
        background = true
        sync()
    }

    /**
     * The app is visible again: a screen that is still drawing is marked again, but only after
     * the grace time (the next frame of a lease notices it): an app that the system kills or
     * reloads right after an app switch is no crash of the game.
     */
    fun resume() {
        if (background) returnedAtMs = clock.nowMs()
        background = false
        sync()
    }

    /**
     * Start of the app, before the first ride: looks at the mark of the previous run. A leftover
     * "rendering" mark is a crash; the level rule is applied (automatic: low is saved; manual
     * above low: a hint is flagged for the next ride start). Whose mark it is decides:
     * - the mark of this very instance (same tab id) is a crash whatever the age of the heartbeat;
     * - the mark of another instance with a heartbeat younger than two intervals is a live second
     *   instance: no crash, the mark stays untouched;
     * - the mark of another instance with an old heartbeat (the whole system was killed) is a crash;
     * - without a tab id the owner is unknown: prefer detecting, a crash.
     */
    fun checkPreviousRun(): PreviousRun {
        val saved = read()
        mirror = null
        if (!saved.rendering || isLiveOtherTab(saved)) return PreviousRun.Clean
        // JS Math.round: halves round up
        val seconds = max(0.0, floor((saved.lastSeen - saved.since) / MS_PER_SECOND + 0.5)).toInt()
        val level = saved.level
        var hint = false
        if (level != null) {
            val decision = decide(saved.auto, level)
            if (decision.persist) settings.setAutoLevel(decision.level)
            hint = decision.hint
        }
        update { s ->
            s.copy(
                rendering = false,
                hintPending = s.hintPending || hint,
                blockedLevels = addBlockedLevel(s.blockedLevels, level),
                lastCrash = if (level != null) LastCrash(level, saved.auto, seconds, clock.nowIso()) else s.lastCrash,
            )
        }
        return PreviousRun.Crashed(level, saved.auto, seconds)
    }

    /** True once after a crash that calls for the "pick a lower level" hint. */
    fun takeHint(): Boolean {
        if (!read().hintPending) return false
        update { it.copy(hintPending = false) }
        return true
    }

    /** Levels that crashed or lost the 3D picture on this device (low to high). */
    fun blockedLevels(): List<GraphicsLevel> = read().blockedLevels

    /** Remembers a level that lost the 3D picture (e.g. after a regular 3D context loss). */
    fun blockLevel(level: GraphicsLevel) {
        update { it.copy(blockedLevels = addBlockedLevel(it.blockedLevels, level)) }
    }

    /** The player selected "Automatic" anew: every level may be tried again. */
    fun clearBlockedLevels() {
        update { it.copy(blockedLevels = emptyList()) }
    }

    /** The last detected crash for the debug display, or null. */
    fun lastCrash(): LastCrash? = read().lastCrash
}
