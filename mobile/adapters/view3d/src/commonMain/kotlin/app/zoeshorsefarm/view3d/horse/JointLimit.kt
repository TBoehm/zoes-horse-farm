package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.shared.clamp

// Limit of the angular velocity and acceleration of a joint (pure).
//
// The IK of the legs is exact, and so it is not smooth where the geometry is not: a straight leg
// bends by 0.3 rad for the first 3 cm that the hoof lifts, the hoof path changes its speed from
// one frame to the next at lift-off and touch-down, a pose blend moves a folded carpus by half a
// radian in one frame. Each of these is a one-frame kink (a V in the angle) that shows as a pop at
// 60 fps. The follower below takes the IK angle as its target and reaches it in the same frame
// whenever that needs no more than the allowed acceleration, so smooth motion passes through
// unchanged and without lag; only a kink is spread over a few frames, with a bounded error.

/** State of one joint: the angle [x] (rad) and the velocity [v] (rad/s) of the last step. */
class JointLimit {
    var x = 0.0
    var v = 0.0
    var primed = false
}

fun createJointLimiter(): JointLimit = JointLimit()

/** Puts the joint at angle [x] without velocity (first frame, teleports). */
fun snapJoint(
    s: JointLimit,
    x: Double,
): Double {
    s.x = x
    s.v = 0.0
    s.primed = true
    return x
}

/**
 * Moves the joint towards [target] within the limits [maxSpeed] (rad/s) and [maxAccel] (rad/s^2)
 * and returns the new angle. The first call takes the target as it is.
 */
fun limitJoint(
    s: JointLimit,
    target: Double,
    dt: Double,
    maxSpeed: Double,
    maxAccel: Double,
): Double {
    if (!s.primed) return snapJoint(s, target)
    if (!(dt > 0)) return s.x
    val dv = maxAccel * dt
    // the velocity that reaches the target in this step, within the acceleration limit
    val v = clamp(clamp((target - s.x) / dt, s.v - dv, s.v + dv), -maxSpeed, maxSpeed)
    s.v = v
    s.x += v * dt
    return s.x
}
