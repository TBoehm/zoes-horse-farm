package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.shared.clamp
import kotlin.math.exp

// Random timing of small life signs (pure): blinking, idle gestures at halt (head shake, toss,
// hoof scrape) and tail swishes. The random numbers come from an injected rng so that tests are
// deterministic.
//
// Real horses blink about 8-19 times a minute (Merkel et al., Sci. Rep. 2020); a blink takes about
// 0.12-0.15 s and attention (an approaching jump) lowers the rate.

/** Values of the blink schedule; the defaults are the real ones, tests may override single values. */
data class BlinkConfig(
    /** s between blinks. */
    val interval: ClosedFloatingPointRange<Double> = 3.0..8.0,
    /** s from open to open again. */
    val duration: ClosedFloatingPointRange<Double> = 0.12..0.15,
    /** Share of blinks followed by a second one. */
    val doubleChance: Double = 0.15,
    /** s between the two blinks of a double blink. */
    val doubleGap: ClosedFloatingPointRange<Double> = 0.12..0.3,
    /** The lid closes faster than it opens. */
    val closeShare: Double = 0.4,
    /** Intervals get longer while the horse is alert. */
    val alertFactor: Double = 1.5,
)

private fun between(
    rng: () -> Double,
    range: ClosedFloatingPointRange<Double>,
): Double = range.start + (range.endInclusive - range.start) * rng()

/** Eyelid closure 0 (open) ... 1 (closed) for a blink at progress p in [0, 1]. */
fun blinkCurve(
    p: Double,
    closeShare: Double = BlinkConfig().closeShare,
): Double {
    if (p <= 0 || p >= 1) return 0.0
    return if (p < closeShare) smoothstep(0.0, closeShare, p) else 1 - smoothstep(closeShare, 1.0, p)
}

/**
 * Blink scheduler. `step(dt, alert)` returns the closure of the eyelids (0..1); `alert` (0..1)
 * lengthens the intervals.
 */
class BlinkScheduler(
    private val rng: () -> Double,
    private val cfg: BlinkConfig = BlinkConfig(),
) {
    var closure = 0.0
        private set

    // the first blink comes sooner
    private var wait = between(rng, cfg.interval) * 0.5

    // elapsed time of the running blink, -1 = none
    private var blinkT = -1.0
    private var duration = 0.0

    // blinks still to come in a double blink
    private var pending = 0
    private var gap = 0.0

    fun step(
        dt: Double,
        alert: Double = 0.0,
    ): Double {
        if (blinkT >= 0) {
            blinkT += dt
            if (blinkT >= duration) {
                blinkT = -1.0
                if (pending > 0) {
                    pending -= 1
                    gap = between(rng, cfg.doubleGap)
                } else {
                    wait = between(rng, cfg.interval) * (1 + (cfg.alertFactor - 1) * clamp(alert, 0.0, 1.0))
                }
            }
        } else if (gap > 0) {
            gap -= dt
            if (gap <= 0) {
                gap = 0.0
                blinkT = 0.0
                duration = between(rng, cfg.duration)
            }
        } else {
            wait -= dt
            if (wait <= 0) {
                blinkT = 0.0
                duration = between(rng, cfg.duration)
                pending = if (rng() < cfg.doubleChance) 1 else 0
            }
        }
        closure = if (blinkT >= 0) blinkCurve(blinkT / duration, cfg.closeShare) else 0.0
        return closure
    }
}

fun createBlinkScheduler(
    rng: () -> Double,
    cfg: BlinkConfig = BlinkConfig(),
): BlinkScheduler = BlinkScheduler(rng, cfg)

/** The idle gestures at halt. [id] is the stable name. */
enum class GestureId(
    val id: String,
) {
    SHAKE("shake"),
    TOSS("toss"),
    PAW("paw"),
}

/** An idle gesture: kind, duration range (s) and relative probability. */
class IdleGesture(
    val id: GestureId,
    val duration: ClosedFloatingPointRange<Double>,
    val chance: Double,
)

val IDLE_GESTURES: List<IdleGesture> =
    listOf(
        IdleGesture(GestureId.SHAKE, 1.1..1.6, 1.0),
        IdleGesture(GestureId.TOSS, 0.9..1.3, 1.0),
        IdleGesture(GestureId.PAW, 1.6..2.4, 1.2),
    )

/** Values of the gesture schedule. */
data class GestureConfig(
    /** s of standing still before the next gesture. */
    val interval: ClosedFloatingPointRange<Double> = 6.0..14.0,
    /** s. */
    val fadeIn: Double = 0.25,
    /** s. */
    val fadeOut: Double = 0.35,
    /** 1/s, how fast a gesture is taken back when the horse has to move. */
    val gateRate: Double = 10.0,
)

/**
 * Result of the gesture scheduler (a reused object): [id] (null = none), [t] (s since the start),
 * [duration], [weight] 0..1 and [leg] (0 or 1, the foreleg of a hoof scrape). `weight` is the
 * envelope times the gate: it never jumps.
 */
class GestureState {
    var id: GestureId? = null
    var t = 0.0
    var duration = 0.0
    var weight = 0.0
    var leg = 0
}

/**
 * Gesture scheduler. `step(dt, allowed)` advances the timer only while `allowed` (the horse stands
 * still); if `allowed` ends during a gesture it fades out quickly and the gesture is dropped.
 */
class GestureScheduler(
    private val rng: () -> Double,
    private val gestures: List<IdleGesture> = IDLE_GESTURES,
    private val cfg: GestureConfig = GestureConfig(),
) {
    val state = GestureState()
    private val total = gestures.sumOf { it.chance }
    private var wait = between(rng, cfg.interval) * 0.6
    private var gate = 0.0
    private var spec: IdleGesture? = null

    private fun pick(): IdleGesture {
        var r = rng() * total
        for (g in gestures) {
            r -= g.chance
            if (r <= 0) return g
        }
        return gestures[gestures.size - 1]
    }

    fun step(
        dt: Double,
        allowed: Boolean,
    ): GestureState {
        val out = state
        // the gate follows `allowed` smoothly (exact exponential), so weight never jumps
        val goal = if (allowed) 1.0 else 0.0
        gate = goal + (gate - goal) * exp(-cfg.gateRate * dt)
        val current = spec
        if (current == null) {
            if (allowed) {
                wait -= dt
                if (wait <= 0) {
                    val picked = pick()
                    spec = picked
                    out.id = picked.id
                    out.t = 0.0
                    out.duration = between(rng, picked.duration)
                    out.leg = if (rng() < 0.5) 0 else 1
                }
            }
            out.weight = 0.0
            return out
        }
        out.t += dt
        val env =
            smoothstep(0.0, cfg.fadeIn, out.t) *
                (1 - smoothstep(out.duration - cfg.fadeOut, out.duration, out.t))
        out.weight = env * gate
        val finished = out.t >= out.duration
        val dropped = !allowed && gate < 0.01
        if (finished || dropped) {
            spec = null
            out.id = null
            out.weight = 0.0
            wait = between(rng, cfg.interval) * (if (dropped) 0.5 else 1.0)
        }
        return out
    }
}

/** Timer with a random interval: `step(dt)` returns true when it fires and re-arms itself. */
class RandomTimer(
    private val rng: () -> Double,
    private val range: ClosedFloatingPointRange<Double>,
) {
    private var wait = range.start + (range.endInclusive - range.start) * rng()

    fun step(dt: Double): Boolean {
        wait -= dt
        if (wait > 0) return false
        wait = range.start + (range.endInclusive - range.start) * rng()
        return true
    }
}

fun createRandomTimer(
    rng: () -> Double,
    range: ClosedFloatingPointRange<Double>,
): RandomTimer = RandomTimer(rng, range)
