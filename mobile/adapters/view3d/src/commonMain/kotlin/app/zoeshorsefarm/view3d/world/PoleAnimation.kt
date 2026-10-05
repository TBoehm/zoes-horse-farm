package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.view3d.FALL_DURATION
import app.zoeshorsefarm.view3d.PoleRest
import app.zoeshorsefarm.view3d.RISE_DURATION
import app.zoeshorsefarm.view3d.easeInOut
import app.zoeshorsefarm.view3d.easeOut
import app.zoeshorsefarm.view3d.endProgressOf
import app.zoeshorsefarm.view3d.fallPointInto
import app.zoeshorsefarm.view3d.fallTarget
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

// Fall animation of the poles (element-local coordinates). The scratch objects are shared like the
// module-level ones of the web app: the scene model is confined to one thread.

private val X_AXIS = Vec3(1.0, 0.0, 0.0)
private val v1 = Vec3()
private val v3 = Vec3()
private val q1 = Quat()
private val q2 = Quat()
private val ea = Vec3()
private val eb = Vec3()
private val centerScratch = DoubleArray(3)

private const val RISE_LIFT = 0.25 // m, a pole that is put back is lifted a little on the way

internal enum class PoleState { UP, FALLING, DOWN, RISING }

/** Pose of a pole from its two ends and a roll about its axis (the pole lies along X). */
private fun poseFromEnds(
    a: Vec3,
    b: Vec3,
    roll: Double,
    outPos: Vec3,
    outQuat: Quat,
) {
    outPos.addVectors(a, b).multiplyScalar(0.5)
    v3.subVectors(b, a).normalize()
    q1.setFromUnitVectors(X_AXIS, v3)
    q2.setFromAxisAngle(X_AXIS, roll)
    outQuat.multiplyQuaternions(q1, q2)
}

/** A single pole with state and animation. [rng] is the pole's own random stream (its element's). */
internal class Pole(
    val def: PoleRest,
    val element: Element,
    val index: Int,
    val rng: () -> Double,
) {
    val length: Double
    var state = PoleState.UP
        private set
    var t = 0.0
        private set
    val pos = Vec3()
    val quat = Quat()
    private val restPos = Vec3()
    private val restQuat = Quat()
    private val fromA = Vec3()
    private val fromB = Vec3()
    private val toA = Vec3()
    private val toB = Vec3()
    private val fromPos = Vec3()
    private val fromQuat = Quat()
    private var roll = 0.0
    private var lead = 0

    init {
        val restA = Vec3().fromArray(def.a)
        val restB = Vec3().fromArray(def.b)
        length = restA.distanceTo(restB)
        poseFromEnds(restA, restB, 0.0, restPos, restQuat)
        pos.copy(restPos)
        quat.copy(restQuat)
    }

    /** Standing (or being put back). */
    val isUp: Boolean get() = state == PoleState.UP || state == PoleState.RISING

    fun startFall(side: Int) {
        // current ends from the current pose
        v1.set(length / 2, 0.0, 0.0).applyQuaternion(quat)
        fromA.copy(pos).sub(v1)
        fromB.copy(pos).add(v1)
        val target = fallTarget(pos.toArray(centerScratch), length, side, rng)
        toA.fromArray(target.a)
        toB.fromArray(target.b)
        roll = target.roll
        lead = target.lead
        state = PoleState.FALLING
        t = 0.0
    }

    fun startRise() {
        fromPos.copy(pos)
        fromQuat.copy(quat)
        state = PoleState.RISING
        t = 0.0
    }

    /** Advances the animation; returns true if the pose changed. */
    fun step(dt: Double): Boolean {
        when (state) {
            PoleState.FALLING -> {
                t = min(1.0, t + dt / FALL_DURATION)
                fallPointInto(ea, fromA, toA, endProgressOf(t, lead, true))
                fallPointInto(eb, fromB, toB, endProgressOf(t, lead, false))
                poseFromEnds(ea, eb, roll * easeOut(t), pos, quat)
                if (t >= 1) state = PoleState.DOWN
                return true
            }

            PoleState.RISING -> {
                t = min(1.0, t + dt / RISE_DURATION)
                val k = easeInOut(t)
                pos.lerpVectors(fromPos, restPos, k)
                pos.y += sin(t * PI) * RISE_LIFT
                quat.slerpQuaternions(fromQuat, restQuat, k)
                if (t >= 1) state = PoleState.UP
                return true
            }

            else -> {
                return false
            }
        }
    }
}
