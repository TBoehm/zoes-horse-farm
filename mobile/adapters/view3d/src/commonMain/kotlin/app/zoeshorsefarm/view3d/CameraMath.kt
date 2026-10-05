package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.wrapAngle
import app.zoeshorsefarm.shared.clamp
import kotlin.math.exp

// Pure helpers of the camera rig.

// Camera heading constants (technical, no game play): the camera heading trails the horse so that
// quick turns sweep the view calmly. At a constant turn rate w an exponential follower lags by
// w / stiffness: 2.7 rad/s (fastest player turn, TUNING.control.turnInPlace, also while backing)
// gives about 34 degrees for the follow camera and 13 degrees for the rider view.
const val FOLLOW_HEADING_STIFFNESS = 4.5
const val RIDER_HEADING_STIFFNESS = 12.0

// Cap of the follow camera's swing rate (rad/s). It must stay above the fastest turn the player
// can make: below it the lag would grow without bound during a long spin on the spot and the
// camera would whip around through 180 degrees once the cap is lifted. It only limits sudden jumps of
// the horse heading (refusal evasion, fence slide). Derived from the tuning so that it follows.
val FOLLOW_HEADING_MAX_RATE: Double = TUNING.control.turnInPlace * 1.2

/**
 * Eases a heading (radians) towards [target] like an exponential follower ([stiffness] in 1/s),
 * along the short way around the circle. [maxRate] (rad/s, optional) caps the swing speed.
 * Used so that fast horse turns do not whip the camera around.
 */
fun followHeading(
    current: Double,
    target: Double,
    stiffness: Double,
    dt: Double,
    maxRate: Double = Double.POSITIVE_INFINITY,
): Double {
    val diff = wrapAngle(target - current)
    val k = 1 - exp(-stiffness * dt)
    val maxStep = maxRate * dt
    return wrapAngle(current + clamp(diff * k, -maxStep, maxStep))
}
