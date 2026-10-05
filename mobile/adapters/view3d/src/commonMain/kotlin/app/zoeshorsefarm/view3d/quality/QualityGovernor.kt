package app.zoeshorsefarm.view3d.quality

import app.zoeshorsefarm.application.FOREGROUND_GRACE_S
import app.zoeshorsefarm.application.GraphicsLevel

// The downgrade governor, the "level too high" hint and the context-loss rule (rule 4). The
// upgrade governor is in QualityUpgrade.kt. Pure logic.

/**
 * Measuring values of the frame-rate checks. They are no game-play values (those belong to Tuning),
 * so they stay here: the downgrade governor, the upgrade governor and the "level too high" hint
 * measure the same way (rule 4): only while riding, not in the first 3 s, and a frame longer than
 * `maxFrameS` is a real interruption (suspend). Slower frames still count: a very slow device must
 * be able to step down. Pauses/hidden app are reported via measuring=false.
 */
data class GovernorOptions(
    /** Moving average window. */
    val windowS: Double = 5.0,
    val minFps: Double = 50.0,
    /** Grace period after an interruption. */
    val graceS: Double = 3.0,
    /** Minimum time between adjustments. */
    val cooldownS: Double = 10.0,
    val maxFrameS: Double = 2.0,
)

val GOVERNOR_DEFAULTS = GovernorOptions()

/** Options of [LowFpsHint]; the measuring values are those of the governor. */
data class LowFpsHintOptions(
    val windowS: Double = GOVERNOR_DEFAULTS.windowS,
    val graceS: Double = GOVERNOR_DEFAULTS.graceS,
    val maxFrameS: Double = GOVERNOR_DEFAULTS.maxFrameS,
    /** A manually chosen level below this average gets a hint. */
    val maxFps: Double = 30.0,
)

private const val MS_PER_S = 1000.0

/**
 * Frame duration from the clock (ms) when the caller has none: 0 at the first call, NaN without a
 * clock (the governors ignore NaN).
 */
private class FrameClock(
    private val now: (() -> Double)?,
) {
    private var lastNow = Double.NaN

    fun dtSeconds(): Double {
        val clock = now ?: return Double.NaN
        val t = clock()
        val dt = if (lastNow.isNaN()) 0.0 else (t - lastNow) / MS_PER_S
        lastNow = t
        return dt
    }
}

/**
 * Downgrade governor according to rule 4.
 *
 * [frame] is fed every frame: `measuring` = the player is riding (pre-start, ride, free mode) and
 * the window is visible. It returns the current level. [onChange] gets the new level and the
 * average frame rate that made the governor step down. [now] is an optional clock in ms, only used
 * by [frameFromClock].
 */
class QualityGovernor(
    level: GraphicsLevel = GraphicsLevel.MEDIUM,
    auto: Boolean = true,
    private val onChange: (level: GraphicsLevel, fps: Double) -> Unit = { _, _ -> },
    now: (() -> Double)? = null,
    private val options: GovernorOptions = GOVERNOR_DEFAULTS,
) {
    private var current = level
    private var isAuto = auto
    private var grace = options.graceS
    private var cooldown = 0.0
    private val frames = FrameWindow(options.windowS)
    private val clock = FrameClock(now)

    val level: GraphicsLevel get() = current

    val auto: Boolean get() = isAuto

    val averageFps: Double? get() = frames.averageFps()

    /** Report an interruption (pause, menu, hidden app). */
    fun interrupt() {
        frames.clear()
        grace = options.graceS
    }

    /** Set a level manually (no onChange). */
    fun setLevel(next: GraphicsLevel) {
        current = next
        interrupt()
    }

    fun setAuto(value: Boolean) {
        isAuto = value
        interrupt()
    }

    /** Like [frame], with the frame duration taken from the clock given to the constructor. */
    fun frameFromClock(measuring: Boolean = true): GraphicsLevel = frame(clock.dtSeconds(), measuring)

    fun frame(
        dtSeconds: Double,
        measuring: Boolean = true,
    ): GraphicsLevel {
        if (dtSeconds >= 0) measure(dtSeconds, measuring)
        return current
    }

    private fun measure(
        dtSeconds: Double,
        measuring: Boolean,
    ) {
        if (cooldown > 0) cooldown = maxOf(0.0, cooldown - dtSeconds)
        if (!isAuto) return
        if (!measuring || dtSeconds > options.maxFrameS) {
            interrupt()
            return
        }
        if (grace > 0) {
            grace -= dtSeconds
            return
        }
        frames.push(dtSeconds)
        if (frames.full && cooldown <= 0 && current != GraphicsLevel.LOW) stepDownIfSlow()
    }

    private fun stepDownIfSlow() {
        val fps = frames.fpsOrNaN()
        if (fps < options.minFps) {
            current = lowerLevel(current)
            cooldown = options.cooldownS
            frames.clear()
            onChange(current, fps)
        }
    }
}

/**
 * Does the "level too high" hint make sense? Only for a manual level that has a lower one to pick:
 * with "Automatic" on the governor steps down by itself, and at "low" the hint would send the
 * player to a level that does not exist.
 */
fun canHintLowerLevel(
    auto: Boolean,
    level: GraphicsLevel,
): Boolean = !auto && lowerLevel(level) != level

/**
 * Hint for a manually chosen level that is too high for the device (rule 4): the level stays, the
 * player only gets told. Create one instance per ride or free-mode session: it fires at most once
 * (until [reset]).
 *
 * [frame] returns true exactly once, when the average over the window is below the limit. Pass
 * measuring=false while paused, hidden, in menus or with "Automatic" on (the governor takes care of
 * that case).
 */
class LowFpsHint(
    private val options: LowFpsHintOptions = LowFpsHintOptions(),
) {
    private var grace = options.graceS
    private var shown = false
    private val frames = FrameWindow(options.windowS)

    fun frame(
        dtSeconds: Double,
        measuring: Boolean = true,
    ): Boolean {
        if (shown || !(dtSeconds >= 0)) return false
        when {
            !measuring || dtSeconds > options.maxFrameS -> interrupt()
            grace > 0 -> grace -= dtSeconds
            else -> shown = belowLimit(dtSeconds)
        }
        return shown
    }

    private fun belowLimit(dtSeconds: Double): Boolean {
        frames.push(dtSeconds)
        return frames.full && frames.fpsOrNaN() < options.maxFps
    }

    /** Report an interruption (pause, menu, hidden app, level change). */
    fun interrupt() {
        frames.clear()
        grace = options.graceS
    }

    /** A new ride begins (e.g. "Start again"): the hint may show once more. */
    fun reset() {
        shown = false
        interrupt()
    }
}

/**
 * A context loss within this many seconds of the app going to the background or coming back is not
 * counted as overload (technical value, no game play): mobile systems often drop the GPU context
 * on an app switch, and right after the return the app is still waking up. The crash guard uses
 * the same time ([FOREGROUND_GRACE_S]).
 */
const val CONTEXT_LOSS_GRACE_S: Double = FOREGROUND_GRACE_S

/**
 * What a lost 3D context means for the graphics level: [level] to continue with, [persist] = save
 * it, [hint] = tell the player to pick a lower level, [counted] = the loss shows an overloaded
 * device (then the level it happened at must not be climbed to again by the automatic, whatever the
 * loss did to the level itself).
 */
data class ContextLossOutcome(
    val level: GraphicsLevel,
    val persist: Boolean,
    val hint: Boolean,
    val counted: Boolean,
)

/**
 * What a lost context means for the graphics level (rule 4). A loss in the foreground shows that
 * the device is overloaded, so the level has to go down: with "Automatic" on it goes to low (and is
 * saved, the automatic stays on); a manually chosen level stays, the player only gets the hint to
 * pick a lower one. A loss while the app is hidden, or within [CONTEXT_LOSS_GRACE_S] of a
 * visibility change, says nothing about the device: nothing changes then.
 *
 * [visible]: the app is in the foreground; [sinceVisibilityChangeS]: seconds since it last went to
 * the background or came back (infinity: never).
 */
fun levelAfterContextLoss(
    auto: Boolean,
    level: GraphicsLevel,
    visible: Boolean = true,
    sinceVisibilityChangeS: Double = Double.POSITIVE_INFINITY,
): ContextLossOutcome {
    if (!visible || sinceVisibilityChangeS < CONTEXT_LOSS_GRACE_S) {
        return ContextLossOutcome(level, persist = false, hint = false, counted = false)
    }
    val lowered = lowerLevel(level) != level
    if (auto) return ContextLossOutcome(GraphicsLevel.LOW, persist = lowered, hint = false, counted = true)
    return ContextLossOutcome(level, persist = false, hint = canHintLowerLevel(auto, level), counted = true)
}

/** What the previous run's crash guard report says about the last run. */
data class StartupCrash(
    val crashed: Boolean,
    val auto: Boolean = false,
    /** Level the run crashed at, null if unknown. */
    val level: GraphicsLevel? = null,
)

/** Why the level changed at the start, for the debug box's "last change". */
enum class StartupChange {
    CRASH,
}

/**
 * The "last change" of the debug box when the previous run crashed (crash guard): the automatic
 * level was lowered to low at the start. A crash at low (or without a known level) or of a manual
 * level changed nothing, so there is nothing to report (null).
 */
fun startupCrashChange(startupCrash: StartupCrash?): StartupChange? {
    if (startupCrash == null || !startupCrash.crashed || !startupCrash.auto) return null
    val level = startupCrash.level
    if (level == null || level == GraphicsLevel.LOW) return null
    return StartupChange.CRASH
}
