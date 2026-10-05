package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.shared.softReach
import kotlin.math.abs
import kotlin.math.max

// Reach of a limb of the rider (pure). A hard clamp at full extension makes the elbow/knee angle
// change very fast for a small move of the target (the angle follows acos of the distance), which
// shows as a pop when a hand is carried forward over the horse's neck. The soft limit compresses
// the last part of the reach smoothly, so the limb straightens gently and never fully.

/** Share of the full reach where the compression starts. */
private const val KNEE = 0.86

/** The limb never gets longer than this share of l1 + l2. */
private const val FULL = 0.998

/** Distance the limb is posed for when the target is [dist] away. */
fun limbReach(
    dist: Double,
    l1: Double,
    l2: Double,
): Double {
    val max = l1 + l2
    val min = abs(l1 - l2) + 1e-3
    return softReach(max(dist, min), FULL * max, (FULL - KNEE) * max)
}
