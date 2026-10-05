package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.shared.Spring
import app.zoeshorsefarm.shared.clamp
import app.zoeshorsefarm.shared.createSpring
import app.zoeshorsefarm.shared.stepSpring
import app.zoeshorsefarm.view3d.horse.jumpParam
import app.zoeshorsefarm.view3d.horse.smoothstep
import kotlin.math.sin

// Where the rider looks (pure): into the curve, down at the fence on take-off, ahead over the fence
// in flight, and a calm glance around at halt. The targets are smoothed with critically damped
// springs so the head never snaps.

private const val YAW_PER_TURN = 0.4 // rad of head yaw per rad/s of turn rate
private const val YAW_MAX = 0.55 // about 31 degrees
private const val PITCH_MAX = 0.3
private const val TAKEOFF_DOWN = 0.14 // looking at the fence (nose down)
private const val FLIGHT_UP = -0.1 // looking ahead over the fence
private const val IDLE_YAW = 0.14
private const val OMEGA_YAW = 6.5
private const val OMEGA_PITCH = 7.5

/** Head yaw (rad, + = to the left) and pitch (rad, + = nose down) the rider wants. */
class LookTarget {
    var yaw = 0.0
    var pitch = 0.0
}

/**
 * Head yaw and pitch the rider wants for a horse [state] (turn rate, jump). [calm] (0..1) is the
 * share of halt, [time] the clock.
 */
fun lookTarget(
    state: Horse,
    calm: Double = 0.0,
    time: Double = 0.0,
    out: LookTarget = LookTarget(),
): LookTarget {
    // a right turn (turnRate > 0) looks to the right = negative yaw
    var yaw = clamp(-state.turnRate * YAW_PER_TURN, -YAW_MAX, YAW_MAX)
    yaw += IDLE_YAW * calm * sin(time * 0.31) * sin(time * 0.17 + 1)
    var pitch = 0.0
    val jump = state.jump
    if (jump != null) {
        val j = jumpParam(jump)
        pitch =
            TAKEOFF_DOWN * smoothstep(0.0, 0.6, j) * (1 - smoothstep(0.85, 1.25, j)) +
            FLIGHT_UP * smoothstep(1.0, 1.6, j) * (1 - smoothstep(2.0, 2.8, j))
    }
    out.yaw = clamp(yaw, -YAW_MAX, YAW_MAX)
    out.pitch = clamp(pitch, -PITCH_MAX, PITCH_MAX)
    return out
}

/** The smoothed look: [yaw].x and [pitch].x are the angles. */
class HeadLook {
    val yaw: Spring = createSpring(0.0)
    val pitch: Spring = createSpring(0.0)
    var time = 0.0
    val target = LookTarget()
}

fun createHeadLook(): HeadLook = HeadLook()

/** Advances the smoothed look; the result is look.yaw.x / look.pitch.x. */
fun stepHeadLook(
    look: HeadLook,
    dt: Double,
    state: Horse,
    calm: Double,
): HeadLook {
    look.time += dt
    lookTarget(state, calm, look.time, look.target)
    stepSpring(look.yaw, look.target.yaw, OMEGA_YAW, 1.0, dt)
    stepSpring(look.pitch, look.target.pitch, OMEGA_PITCH, 1.0, dt)
    return look
}
