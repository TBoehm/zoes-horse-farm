package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.shared.clamp
import app.zoeshorsefarm.shared.softReach
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// 2D leg IK in the sagittal plane (local y/z plane of the leg's parent bone). Pure.
// Angle convention: angD(dz, dy) = atan2(dz, -dy), 0 = straight down, positive = forwards.
// A bone rotation rotation.x = r changes a segment's angD by -r.

fun angD(
    dz: Double,
    dy: Double,
): Double = atan2(dz, -dy)

fun wrap(angle: Double): Double {
    var a = angle
    while (a > PI) a -= 2 * PI
    while (a < -PI) a += 2 * PI
    return a
}

/** Maximum forward angle of the femur (angD, ~ 77 degrees). */
private const val FEMUR_MAX = 1.35

/** Margin (m) that the leg keeps short of fully stretched when the carpus unfolds to reach. */
private const val REACH_MARGIN = 0.01

private fun dirZ(t: Double) = sin(t)

private fun dirY(t: Double) = -cos(t)

/** A point in the y/z plane of a bone frame. */
class RigPoint(
    val y: Double,
    val z: Double,
)

private class Seg(
    val len: Double,
    val ang: Double,
)

private fun seg(
    a: RigPoint,
    b: RigPoint,
): Seg {
    val dz = b.z - a.z
    val dy = b.y - a.y
    return Seg(hypot(dz, dy), angD(dz, dy))
}

/** Segment lengths and rest angles of a foreleg (scapula, humerus, forearm, cannon, pastern). */
class FrontRig(
    val a: RigPoint,
    val h: RigPoint,
    val lsc: Double,
    val tsc: Double,
    val l1: Double,
    val l2: Double,
    val l3: Double,
    val l4: Double,
    val t1: Double,
    val t2: Double,
    val t3: Double,
    val t4: Double,
    val d0: Double,
    val sigma: Double,
)

/** Foreleg: points {y, z} for scapula top, point of shoulder, elbow, carpus, fetlock, hoof. */
fun makeFrontRig(
    a: RigPoint,
    s: RigPoint,
    e: RigPoint,
    k: RigPoint,
    f: RigPoint,
    h: RigPoint,
): FrontRig {
    val sc = seg(a, s)
    val s1 = seg(s, e)
    val s2 = seg(e, k)
    val s3 = seg(k, f)
    val s4 = seg(f, h)
    val sf = seg(s, f)
    val sig = sign(wrap(s1.ang - sf.ang))
    return FrontRig(
        a = a,
        h = h,
        lsc = sc.len,
        tsc = sc.ang,
        l1 = s1.len,
        l2 = s2.len,
        l3 = s3.len,
        l4 = s4.len,
        t1 = s1.ang,
        t2 = s2.ang,
        t3 = s3.ang,
        t4 = s4.ang,
        d0 = wrap(s3.ang - s2.ang),
        sigma = if (sig == 0.0 || sig.isNaN()) -1.0 else sig,
    )
}

/** Segment lengths and rest angles of a hind leg (femur, tibia, cannon, pastern). */
class HindRig(
    val p: RigPoint,
    val h: RigPoint,
    val l1: Double,
    val l2: Double,
    val l3: Double,
    val l4: Double,
    val t1: Double,
    val t2: Double,
    val t3: Double,
    val t4: Double,
    val tLeg: Double,
    val sigma: Double,
)

/** Hind leg: hip, stifle, hock, fetlock, hoof. */
fun makeHindRig(
    p: RigPoint,
    t: RigPoint,
    k: RigPoint,
    f: RigPoint,
    h: RigPoint,
): HindRig {
    val s1 = seg(p, t)
    val s2 = seg(t, k)
    val s3 = seg(k, f)
    val s4 = seg(f, h)
    val sk = seg(p, k)
    val sf = seg(p, f)
    val sig = sign(wrap(s1.ang - sk.ang))
    return HindRig(
        p = p,
        h = h,
        l1 = s1.len,
        l2 = s2.len,
        l3 = s3.len,
        l4 = s4.len,
        t1 = s1.ang,
        t2 = s2.ang,
        t3 = s3.ang,
        t4 = s4.ang,
        tLeg = sf.ang,
        sigma = if (sig == 0.0 || sig.isNaN()) 1.0 else sig,
    )
}

// Result of twoBone, reused: the solvers run four times per frame
private object IkResult {
    var t1 = 0.0
    var mz = 0.0
    var my = 0.0
    var t2 = 0.0
    var reach = true
}

private fun twoBone(
    rootZ: Double,
    rootY: Double,
    tz: Double,
    ty: Double,
    l1: Double,
    l2: Double,
    sigma: Double,
    soft: Double,
) {
    val dz = tz - rootZ
    val dy = ty - rootY
    var d = hypot(dz, dy)
    val dMax = l1 + l2 - 1e-4
    val dMin = abs(l1 - l2) + 1e-4
    val reach = d <= dMax
    d = softReach(max(d, dMin), dMax, soft)
    val c = (l1 * l1 + d * d - l2 * l2) / (2 * l1 * d)
    val alpha = acos(clamp(c, -1.0, 1.0))
    val base = angD(dz, dy)
    val t1 = base + sigma * alpha
    val mz = rootZ + l1 * dirZ(t1)
    val my = rootY + l1 * dirY(t1)
    IkResult.t1 = t1
    IkResult.mz = mz
    IkResult.my = my
    IkResult.t2 = angD(tz - mz, ty - my)
    IkResult.reach = reach
}

/**
 * Slide of the shoulder blade (angD delta) for a hoof at the fore/aft offset dz (m, + = forwards)
 * from its neutral position. The blade rotates back with a leg that reaches backwards, which
 * lengthens the reach so that the long strides of the canter stay within the IK limits (without
 * it the leg is stretched and its joints snap when the hoof lifts); forwards it tilts a little
 * the other way. Continuous at dz = 0.
 */
fun scapulaSlide(dz: Double): Double = if (dz >= 0) clamp(-0.2 * dz, -0.15, 0.0) else clamp(0.7 * dz, -0.5, 0.0)

/**
 * Solve a foreleg. hz/hy: hoof point (local), past: absolute pastern angle (angD, local),
 * knee: carpus flexion (rad, 0 = straight), scap: scapula rotation (angD delta), soft: soft zone
 * of the reach (m, see softReach).
 * Returns rotation.x for [scapula, humerus, forearm, cannon, pastern] in [out].
 */
fun solveFront(
    rig: FrontRig,
    hz: Double,
    hy: Double,
    past: Double,
    knee: Double,
    scap: Double,
    out: DoubleArray = DoubleArray(5),
    soft: Double = 0.0,
): DoubleArray {
    val tsc = rig.tsc + scap
    val sz = rig.a.z + rig.lsc * dirZ(tsc)
    val sy = rig.a.y + rig.lsc * dirY(tsc)
    val fz = hz - rig.l4 * dirZ(past)
    val fy = hy - rig.l4 * dirY(past)
    var delta = rig.d0 - knee
    var len = sqrt(rig.l2 * rig.l2 + rig.l3 * rig.l3 + 2 * rig.l2 * rig.l3 * cos(delta))
    // A folded carpus shortens the leg. When the fetlock has to be further from the shoulder than
    // that, the carpus unfolds just enough to reach it (instead of clamping the chain, which makes
    // the joints snap when the hoof lifts or lands).
    val need = hypot(fz - sz, fy - sy) - rig.l1 + REACH_MARGIN
    if (len < need) {
        len = min(need, rig.l2 + rig.l3 - 1e-4)
        val c = (len * len - rig.l2 * rig.l2 - rig.l3 * rig.l3) / (2 * rig.l2 * rig.l3)
        delta = (if (delta < 0) -1 else 1) * acos(clamp(c, -1.0, 1.0))
    }
    val psi = atan2(rig.l3 * sin(delta), rig.l2 + rig.l3 * cos(delta))
    twoBone(sz, sy, fz, fy, rig.l1, len, rig.sigma, soft)
    val t1 = IkResult.t1
    val t2 = IkResult.t2 - psi
    val t3 = t2 + delta
    val d1 = wrap(t1 - rig.t1)
    val d2 = wrap(t2 - rig.t2)
    val d3 = wrap(t3 - rig.t3)
    val d4 = wrap(past - rig.t4)
    out[0] = -scap
    out[1] = -(d1 - scap)
    out[2] = -(d2 - d1)
    out[3] = -(d3 - d2)
    out[4] = -(d4 - d3)
    return out
}

/**
 * Solve a hind leg. cannon: absolute cannon angle (angD, local).
 * Returns rotation.x for [femur, tibia, cannon, pastern] in [out].
 */
fun solveHind(
    rig: HindRig,
    hz: Double,
    hy: Double,
    past: Double,
    cannon: Double,
    out: DoubleArray = DoubleArray(4),
    soft: Double = 0.0,
): DoubleArray {
    val fz = hz - rig.l4 * dirZ(past)
    val fy = hy - rig.l4 * dirY(past)
    val kz = fz - rig.l3 * dirZ(cannon)
    val ky = fy - rig.l3 * dirY(cannon)
    twoBone(rig.p.z, rig.p.y, kz, ky, rig.l1, rig.l2, rig.sigma, soft)
    var t1 = IkResult.t1
    var t2 = IkResult.t2
    if (t1 > FEMUR_MAX) {
        // limit the femur; the tibia then just points towards the hock
        t1 = FEMUR_MAX
        t2 = angD(kz - (rig.p.z + rig.l1 * dirZ(t1)), ky - (rig.p.y + rig.l1 * dirY(t1)))
    }
    val d1 = wrap(t1 - rig.t1)
    val d2 = wrap(t2 - rig.t2)
    val d3 = wrap(cannon - rig.t3)
    val d4 = wrap(past - rig.t4)
    out[0] = -d1
    out[1] = -(d2 - d1)
    out[2] = -(d3 - d2)
    out[3] = -(d4 - d3)
    return out
}

/** Angle of the hip -> fetlock line for a hoof point (drives the hind cannon tilt). */
fun hindSweep(
    rig: HindRig,
    hz: Double,
    hy: Double,
    past: Double,
): Double {
    val fz = hz - rig.l4 * dirZ(past)
    val fy = hy - rig.l4 * dirY(past)
    return wrap(angD(fz - rig.p.z, fy - rig.p.y) - rig.tLeg)
}
