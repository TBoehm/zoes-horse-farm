package app.zoeshorsefarm.shared

import kotlin.math.exp
import kotlin.math.min

// Soft limit of the reach of a two-bone limb (pure), shared by the horse legs and the rider's arms.

/**
 * Soft limit of the reach: a target distance up to [soft] short of the full stretch is kept, further
 * out it is compressed so that it approaches the full stretch without ever touching it. With
 * soft = 0 it is the hard clamp. A hard clamp locks the leg straight for as long as the hoof
 * target is out of reach (the body is in the air during a jump, a long stride ends at the full
 * stretch) and then, when the target comes back, the knee bends by 40 degrees or more in one frame
 * (acos has an infinite slope at the full stretch). The compression keeps the slope bounded, so
 * that a landing, a take-off or a lift-off bends the joints smoothly. The price is a hoof that
 * stays up to a few millimetres short of a target near the full stretch (the rest pose and the
 * stance of the gaits are near it).
 */
fun softReach(
    d: Double,
    dMax: Double,
    soft: Double,
): Double {
    if (soft <= 0) return min(d, dMax)
    val dSoft = dMax - soft
    if (d <= dSoft) return d
    return dSoft + soft * (1 - exp(-(d - dSoft) / soft))
}
