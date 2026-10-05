package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.shared.clamp
import kotlin.math.floor
import kotlin.math.min

// Key poses for jump, hop and refusal (pure).
// A pose vector holds body/neck values and, per leg, the hoof offset relative to the rest position
// in the local frame of the leg's parent bone (i.e. body-relative), plus flexions.

// Indices of the body/neck values at the start of a pose vector.

/** Body pitch, + = nose down (rad). */
const val POSE_PITCH = 0

/** Extra body height (m). */
const val POSE_DY = 1

/** Back arch (bascule), + = round back. */
const val POSE_BEND = 2

/** Neck, + = forward/down. */
const val POSE_NECK = 3

/** Head at the poll, + = nose towards the chest. */
const val POSE_HEAD = 4

/** Tail lift. */
const val POSE_TAIL = 5

/** Z of the ground pivot for pitch (m). */
const val POSE_PIVOT = 6

/** Number of body/neck values: the leg values start at this index. */
const val LEG_OFFSET = 7

/** Per leg: dz, dy, flex, past. */
const val LEG_VALUES = 4

const val POSE_SIZE = LEG_OFFSET + 4 * LEG_VALUES

private fun pose(
    pitch: Double = 0.0,
    dy: Double = 0.0,
    bend: Double = 0.0,
    neck: Double = 0.0,
    head: Double = 0.0,
    tail: Double = 0.0,
    pivot: Double = 0.0,
    legs: List<DoubleArray>,
): DoubleArray {
    val v = DoubleArray(POSE_SIZE)
    v[POSE_PITCH] = pitch
    v[POSE_DY] = dy
    v[POSE_BEND] = bend
    v[POSE_NECK] = neck
    v[POSE_HEAD] = head
    v[POSE_TAIL] = tail
    v[POSE_PIVOT] = pivot
    legs.forEachIndexed { i, l ->
        for (j in 0 until LEG_VALUES) v[LEG_OFFSET + i * LEG_VALUES + j] = l[j]
    }
    return v
}

private fun legs(vararg l: DoubleArray): List<DoubleArray> = l.toList()

private fun l(
    dz: Double,
    dy: Double,
    flex: Double,
    past: Double,
) = doubleArrayOf(dz, dy, flex, past)

private val NEUTRAL_LEGS =
    legs(l(0.0, 0.0, 0.0, 0.0), l(0.0, 0.0, 0.0, 0.0), l(0.0, 0.0, 0.0, 0.0), l(0.0, 0.0, 0.0, 0.0))

// Legs: [LF, RF, LH, RH] each [dz, dy, flex, past]. Approach on the left lead; landing first on
// the non-leading (RF), then on the leading foreleg (LF).
val JUMP_KEYS: List<DoubleArray> =
    listOf(
        // J = 0: approach (neutral)
        pose(pivot = -0.6, legs = NEUTRAL_LEGS),
        // 0.5: take-off - forehand lifts, hind legs step far under
        pose(
            pitch = -0.22,
            neck = -0.12,
            head = 0.05,
            tail = 0.2,
            pivot = -0.3,
            legs =
                legs(
                    l(0.22, 0.4, 1.7, 0.9),
                    l(0.26, 0.3, 1.4, 0.7),
                    l(0.36, 0.0, 0.05, 0.0),
                    l(0.3, 0.0, 0.05, 0.0),
                ),
        ),
        // 1.0: hindquarters push off, forelegs tightly folded
        pose(
            pitch = -0.4,
            bend = 0.02,
            neck = 0.12,
            head = 0.08,
            tail = 0.4,
            pivot = -0.3,
            legs =
                legs(
                    l(0.26, 0.66, 2.6, 1.3),
                    l(0.3, 0.62, 2.5, 1.25),
                    l(-0.08, 0.0, -0.15, -0.25),
                    l(-0.12, 0.0, -0.15, -0.25),
                ),
        ),
        // 1.5: flight - bascule, neck long forward/down, hind legs trailing
        pose(
            pitch = 0.04,
            bend = 0.14,
            neck = 0.42,
            head = 0.12,
            tail = 0.6,
            pivot = 0.1,
            legs =
                legs(
                    l(0.18, 0.7, 2.8, 1.4),
                    l(0.21, 0.68, 2.75, 1.35),
                    l(-0.36, 0.3, 0.5, 0.6),
                    l(-0.4, 0.27, 0.5, 0.6),
                ),
        ),
        // 2.0: forelegs extend for landing, hind legs folded
        pose(
            pitch = 0.24,
            bend = 0.05,
            neck = 0.02,
            head = 0.02,
            tail = 0.5,
            pivot = 0.65,
            legs =
                legs(
                    l(0.2, 0.12, 0.15, 0.15),
                    l(0.1, 0.02, 0.0, 0.0),
                    l(0.3, 0.3, 0.7, 0.8),
                    l(0.26, 0.28, 0.7, 0.8),
                ),
        ),
        // 2.5: forehand landed, hind legs come forward under the body
        pose(
            pitch = 0.1,
            bend = -0.02,
            neck = -0.18,
            head = -0.08,
            tail = 0.3,
            pivot = 0.65,
            legs =
                legs(
                    l(0.06, 0.0, 0.0, 0.0),
                    l(-0.14, 0.0, 0.0, 0.0),
                    l(0.36, 0.16, 0.5, 0.5),
                    l(0.3, 0.22, 0.6, 0.5),
                ),
        ),
        // 3.0: canter away (neutral)
        pose(pivot = 0.65, legs = NEUTRAL_LEGS),
    )

/** Refusal "stop": abrupt braking, forelegs braced, haunches lowered, head up. */
val STOP_POSE: DoubleArray =
    pose(
        pitch = -0.1,
        dy = -0.05,
        neck = -0.5,
        head = -0.25,
        tail = 0.1,
        pivot = 0.7,
        legs =
            legs(
                l(0.24, 0.0, -0.1, -0.15),
                l(0.2, 0.0, -0.1, -0.15),
                l(0.34, 0.0, 0.25, 0.1),
                l(0.3, 0.0, 0.25, 0.1),
            ),
    )

/** Phase + progress -> continuous jump parameter J in [0, 3]. */
fun jumpParam(jump: JumpView?): Double {
    if (jump == null) return 0.0
    val p = clamp(jump.progress, 0.0, 1.0)
    return when (jump.phase) {
        JumpPhase.TAKEOFF -> p
        JumpPhase.FLIGHT -> 1 + p
        JumpPhase.LANDING -> 2 + p
    }
}

/** Catmull-Rom over evenly spaced key poses (spacing 0.5 in J). */
fun samplePoses(
    keys: List<DoubleArray>,
    j: Double,
    out: DoubleArray = DoubleArray(POSE_SIZE),
): DoubleArray {
    val x = clamp(j / 0.5, 0.0, (keys.size - 1).toDouble())
    val i = min(keys.size - 2, floor(x).toInt())
    val t = x - i
    val k0 = keys[maxOf(0, i - 1)]
    val k1 = keys[i]
    val k2 = keys[i + 1]
    val k3 = keys[min(keys.size - 1, i + 2)]
    val t2 = t * t
    val t3 = t2 * t
    for (n in 0 until POSE_SIZE) {
        out[n] =
            0.5 *
            (
                2 * k1[n] +
                    (-k0[n] + k2[n]) * t +
                    (2 * k0[n] - 5 * k1[n] + 4 * k2[n] - k3[n]) * t2 +
                    (-k0[n] + 3 * k1[n] - 3 * k2[n] + k3[n]) * t3
            )
    }
    return out
}
