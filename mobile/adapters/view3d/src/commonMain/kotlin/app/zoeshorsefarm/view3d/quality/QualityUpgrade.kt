package app.zoeshorsefarm.view3d.quality

import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import app.zoeshorsefarm.application.GraphicsLevel

// Upgrade governor (concept rule 4): while "Automatic" is on and the player rides, a device that
// has room to spare climbs one level at a time, starting from the low level the automatic begins
// with. The counterpart of the downgrade governor in QualityGovernor.kt, with the same way of
// measuring (only while riding, not in the first seconds after an interruption). Pure: the engine
// feeds it the frame times, applies the level it names, and tells it about every change.

/**
 * Technical constants (no game play, so not in Tuning): how much reserve the device must show
 * before the automatic risks a heavier level. The safety lies in the margin: the downgrade steps
 * in below 50 fps, so a climb needs clearly more than that, and in the long window.
 */
data class UpgradeOptions(
    /** Moving average. */
    val windowS: Double = 10.0,
    /** Average over the window (a 60 Hz screen delivers 60). */
    val minFps: Double = 57.0,
    /**
     * A frame slower than this is a hitch. At 60 Hz one missed refresh is 33 ms, so 25 ms (below
     * 40 fps) is the first duration that is clearly a missed one, but not mere jitter (16.7 ms +- 5).
     */
    val slowFrameS: Double = 0.025,
    /**
     * Hitches allowed in the window: a good average can hide stutter that a child sees. 2 % are
     * about 12 of 600 frames at 60 fps.
     */
    val maxSlowShare: Double = 0.02,
    /** After an interruption or a change: same as downgrading. */
    val graceS: Double = GOVERNOR_DEFAULTS.graceS,
    val maxFrameS: Double = GOVERNOR_DEFAULTS.maxFrameS,
    /** Minimum riding time after any level change, up or down. */
    val cooldownS: Double = 20.0,
)

val UPGRADE_DEFAULTS = UpgradeOptions()

/** The level the automatic has to climb to now and the average frame rate that allowed it. */
data class UpgradeStep(
    val level: GraphicsLevel,
    val fps: Double,
)

/**
 * The level the automatic may climb to from [level], or null. Only the next level up is a
 * candidate (never above high, a blocked level is not skipped over). Excluded are
 * - [blocked]: levels that crashed or lost the 3D picture on this device (crash guard),
 * - [left]: levels the downgrade governor stepped down from in this running session,
 * - levels for which [fits] is false: their (unreduced) memory estimate exceeds the budget of the
 *   device.
 */
fun nextUpgradeLevel(
    level: GraphicsLevel,
    blocked: Collection<GraphicsLevel> = emptyList(),
    left: Collection<GraphicsLevel> = emptyList(),
    fits: (GraphicsLevel) -> Boolean = { true },
): GraphicsLevel? {
    val next = GRAPHICS_LEVELS.getOrNull(GRAPHICS_LEVELS.indexOf(level) + 1) ?: return null
    if (next in blocked || next in left) return null
    return if (fits(next)) next else null
}

/**
 * Whether the upgrade governor may measure at all: only while riding ([measuring]) and with
 * "Automatic" on ([auto]). A manually chosen level is never raised, so it gets no measurement.
 */
fun upgradeMeasuring(
    measuring: Boolean,
    auto: Boolean,
): Boolean = measuring && auto

/**
 * [chooseTarget]: the level to climb to (see [nextUpgradeLevel]), or null; only called once the
 * frame rate says go.
 *
 * [frame] gets `measuring` = riding with "Automatic" on and the app visible; `busy` = a jump is in
 * progress or an obstacle is being approached (the decision about it is the session's): a step
 * never starts then (a stage can stall the frames for seconds), the measurement goes on. It returns
 * the step when the level has to go up now (cooldown and warm-up start by themselves), else null.
 */
class UpgradeGovernor(
    private val chooseTarget: () -> GraphicsLevel?,
    private val options: UpgradeOptions = UPGRADE_DEFAULTS,
) {
    private var grace = options.graceS
    private var cooldown = 0.0
    private val frames = FrameWindow(options.windowS, options.slowFrameS)

    fun frame(
        dtSeconds: Double,
        measuring: Boolean = true,
        busy: Boolean = false,
    ): UpgradeStep? = if (dtSeconds >= 0 && record(dtSeconds, measuring) && readyToClimb(busy)) climb() else null

    /** Adds the frame to the window; false while nothing is measured (interruption, warm-up). */
    private fun record(
        dtSeconds: Double,
        measuring: Boolean,
    ): Boolean {
        if (!measuring || dtSeconds > options.maxFrameS) {
            interrupt()
            return false
        }
        // the cooldown is riding time: pauses and menus do not shorten it
        if (cooldown > 0) cooldown = maxOf(0.0, cooldown - dtSeconds)
        if (grace > 0) {
            grace -= dtSeconds
            return false
        }
        frames.push(dtSeconds)
        return true
    }

    /** The window is full, the cooldown is over and the frame rate leaves room (and no jump is in progress). */
    private fun readyToClimb(busy: Boolean): Boolean {
        // while busy the window stays: the step follows right after the jump
        if (!frames.full || cooldown > 0 || busy) return false
        return frames.fpsOrNaN() >= options.minFps && frames.slowShare() <= options.maxSlowShare
    }

    private fun climb(): UpgradeStep? {
        val fps = frames.fpsOrNaN()
        val level = chooseTarget()
        frames.clear() // a step starts the measurement over; no target: look again in a full window
        if (level == null) return null
        cooldown = options.cooldownS
        grace = options.graceS
        return UpgradeStep(level, fps)
    }

    /** Frames around a stage of a level change, a pause or a menu are no measurement. */
    fun interrupt() {
        frames.clear()
        grace = options.graceS
    }

    /** The level changed (down, up, context loss, manual pick): wait before climbing again. */
    fun noteChange() {
        interrupt()
        cooldown = options.cooldownS
    }
}
