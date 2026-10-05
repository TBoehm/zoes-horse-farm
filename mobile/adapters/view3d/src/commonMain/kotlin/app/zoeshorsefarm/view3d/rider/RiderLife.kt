package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.shared.Spring
import app.zoeshorsefarm.shared.createSpring
import app.zoeshorsefarm.shared.stepSpring
import app.zoeshorsefarm.view3d.horse.smoothstep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Small signs of life for the rider (pure): breathing with a little shoulder movement, and a pat on
// the horse's neck once it stands after a jump.

private const val BREATH_HZ = 0.27
private const val BREATH_CHEST = 0.016 // rad of chest pitch
private const val SHOULDER_HZ = 0.11
private const val SHOULDER_ROLL = 0.012 // rad, slow weight shift
private const val PAT_DURATION = 1.7 // s
private const val PAT_COUNT = 3

// s after the landing in which a halt still earns a pat
private const val PAT_WINDOW = 8.0

// 1/s, how fast the hand follows the pat timeline
private const val REACH_OMEGA = 18.0

/** Chest pitch and chest roll (rad) of the breathing. */
class Breath {
    var chest = 0.0
    var roll = 0.0
}

/** Chest pitch and chest roll (rad) for the clock [time]; calmer and larger at halt. */
fun breathing(
    time: Double,
    calm: Double = 0.0,
    out: Breath = Breath(),
): Breath {
    val amp = 0.45 + 0.55 * calm
    out.chest = BREATH_CHEST * amp * sin(2 * PI * BREATH_HZ * time)
    out.roll = SHOULDER_ROLL * calm * sin(2 * PI * SHOULDER_HZ * time + 0.7)
    return out
}

/**
 * State of the pat: [reach] (0..1, hand on the neck) and [tap] (0..1, the small up-and-down of the
 * pat, fades with the reach); both are zero when there is no pat.
 */
class Pat {
    var armed = false
    var since = 0.0
    var t = -1.0
    var reach = 0.0
    var tap = 0.0
    val reachSpring: Spring = createSpring(0.0)
}

fun createPat(): Pat = Pat()

/**
 * Tracks "jumped, then came to a halt" and plays one pat per jump. [jumping] = the horse is in a
 * jump, [halted] = the horse stands (gait halt, halt weight ~1).
 */
fun stepPat(
    p: Pat,
    dt: Double,
    jumping: Boolean = false,
    halted: Boolean = false,
): Pat {
    if (jumping) {
        p.armed = true
        p.since = 0.0
        p.t = -1.0
    } else if (p.armed) {
        p.since += dt
        if (p.since > PAT_WINDOW) {
            p.armed = false
        } else if (halted) {
            p.armed = false
            p.t = 0.0
        }
    }
    if (p.t >= 0) {
        // the player rides on again: the hand goes back (quickly, through the reach spring)
        if (!halted) {
            p.t = -1.0
        } else {
            p.t += dt
            if (p.t >= PAT_DURATION) p.t = -1.0
        }
    }
    var reach = 0.0
    var tap = 0.0
    if (p.t >= 0) {
        val u = p.t / PAT_DURATION
        reach = smoothstep(0.0, 0.2, u) * (1 - smoothstep(0.82, 1.0, u))
        tap = 0.5 - 0.5 * cos(2 * PI * PAT_COUNT * u)
    }
    // a cancelled pat must not make the hand jump: the reach always goes through a spring
    p.reach = stepSpring(p.reachSpring, reach, REACH_OMEGA, 1.0, dt).x
    p.tap = tap * p.reach
    return p
}
