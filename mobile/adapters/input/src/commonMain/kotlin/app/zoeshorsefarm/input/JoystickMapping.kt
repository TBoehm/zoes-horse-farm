package app.zoeshorsefarm.input

import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.shared.clamp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

// Pure mapping of a thumb-stick position to steer/throttle (rule 10).
//
// Dead zone: hybrid "scaled radial followed by sloped scaled axial" dead zone
// (minuino.github.io/thumbstick-deadzones; radial part after J. Sutphin, "Doing Thumbstick Dead
// Zones Right", gamedeveloper.com). The radial zone makes a small deflection in ANY direction
// neutral; the rest of the length is rescaled to 0..1, so the output starts at 0 without a jump.
// The axial zones act on the unit direction components: a hold near horizontal gives no throttle,
// a hold near vertical gives no steering. Without them the two controls bleed into each other
// (turning on the spot would start rein-back, steering at trot would brake, a thumb wobble on a
// straight approach would turn the horse).
//
// Steering gain/saturation: the sideways component is multiplied by a gain so that full lock is
// reached at TUNING.control.stickSteerFull (<= 2/3) of the stick radius. A 45 degree forward hold
// then already gives full lock, as on a car-style touch steering control. Fully pulled down gives
// throttle of about -1 (exactly -1 on the vertical axis).

private val STICK_DEAD_ZONE = TUNING.control.stickDeadZone

// gain on the rescaled sideways component: raw deflection `stickSteerFull` maps to full lock
private val STEER_GAIN = (1 - STICK_DEAD_ZONE) / (TUNING.control.stickSteerFull - STICK_DEAD_ZONE)

private val AXIAL_THROTTLE = TUNING.control.stickAxialThrottle
private val AXIAL_STEER = TUNING.control.stickAxialSteer

/** Axial dead zone of one unit direction component, rescaled so it starts at 0 at the edge. */
private fun axial(
    component: Double,
    zone: Double,
): Double {
    val rescaled = (abs(component) - zone) / (1 - zone)
    // plain 0 inside the zone (no negative zero)
    return if (rescaled > 0) sign(component) * rescaled else 0.0
}

/**
 * @param force stick deflection, 0..1 (values above 1 are clamped)
 * @param radian angle, 0 = right, PI/2 = up
 * @return steer: +1 right; throttle: +1 faster
 */
fun mapStick(
    force: Double,
    radian: Double,
): StickOutput {
    if (!force.isFinite() || !radian.isFinite()) return StickOutput.NEUTRAL
    val mag = clamp(force, 0.0, 1.0)
    if (mag < STICK_DEAD_ZONE) return StickOutput.NEUTRAL
    // scaled radial dead zone: length rescaled from [dead zone, 1] to [0, 1]
    val scaled = (mag - STICK_DEAD_ZONE) / (1 - STICK_DEAD_ZONE)
    return StickOutput(
        steer = clamp(axial(cos(radian), AXIAL_STEER) * scaled * STEER_GAIN, -1.0, 1.0),
        throttle = clamp(axial(sin(radian), AXIAL_THROTTLE) * scaled, -1.0, 1.0),
    )
}

/**
 * [mapStick] for a thumb position in stick-radius units: [x] right, [y] UP (flip the screen y before
 * calling), (0, 0) = centre, length 1 = rim of the stick.
 */
fun mapStickXY(
    x: Double,
    y: Double,
): StickOutput {
    if (!x.isFinite() || !y.isFinite()) return StickOutput.NEUTRAL
    return mapStick(hypot(x, y), atan2(y, x))
}
