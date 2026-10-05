package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

// Camera (rules 13, 14): default is diagonally behind/above horse and rider, rider view between
// the ears.

/** The two camera views. [id] is the name stored in the settings. */
enum class CameraMode(
    val id: String,
) {
    FOLLOW("follow"),
    RIDER("rider"),
    ;

    companion object {
        /** The mode with this stored name, or null if there is none. */
        fun fromId(id: String): CameraMode? = entries.firstOrNull { it.id == id }
    }
}

val CAMERA_MODES: List<CameraMode> = CameraMode.entries

// Eyes slightly behind and above the poll so that the ears and the mane stay in view
private const val RIDER_BACK = 0.5
private const val RIDER_UP = 0.24
private const val RIDER_LOOK_DOWN = 1.0

private const val FOLLOW_BACK = 7.5
private const val FOLLOW_HEIGHT = 3.6
private const val FOLLOW_LOOK_AHEAD = 6.0
private const val FOLLOW_LOOK_HEIGHT = 1.1
private const val FOLLOW_STIFFNESS = 5.0

// When jumping, lift the camera only halfway so the jump stays visible
private const val JUMP_LIFT_SHARE = 0.5

// the look point eases a little faster than the position
private const val LOOK_FOLLOW_FACTOR = 1.6

// the rider view looks this far ahead (m)
private const val RIDER_LOOK_DISTANCE = 10.0

/**
 * The camera heading trails the horse's heading (constants and reasoning: CameraMath.kt), so the
 * quick turns of the agile steering (SRT-009) sweep the view calmly. The rider view's look
 * direction trails only slightly. Moves [camera] every frame without allocating.
 */
class CameraRig(
    private val camera: Camera,
) {
    var mode: CameraMode = CameraMode.FOLLOW
        private set

    private var initialized = false
    private var camHeading = 0.0
    private var headingReady = false
    private val pos = Vec3()
    private val look = Vec3()
    private val tmp = Vec3()
    private val desiredPos = Vec3()
    private val desiredLook = Vec3()

    /** Eases the camera heading towards the horse's heading (calm view on fast turns). */
    private fun trailHeading(
        dt: Double,
        heading: Double,
        stiffness: Double,
        maxRate: Double,
    ): Double {
        camHeading = if (headingReady) followHeading(camHeading, heading, stiffness, dt, maxRate) else heading
        headingReady = true
        return camHeading
    }

    private fun followTargets(state: Horse) {
        val fx = sin(camHeading)
        val fz = cos(camHeading)
        val lift = state.y * JUMP_LIFT_SHARE
        desiredPos.set(state.x - fx * FOLLOW_BACK, FOLLOW_HEIGHT + lift, state.z - fz * FOLLOW_BACK)
        desiredLook.set(
            state.x + fx * FOLLOW_LOOK_AHEAD,
            FOLLOW_LOOK_HEIGHT + lift,
            state.z + fz * FOLLOW_LOOK_AHEAD,
        )
    }

    /** Switches the view; the camera starts over from the target. */
    fun setMode(next: CameraMode) {
        mode = next
        initialized = false
        headingReady = false
    }

    /** Like [setMode] with a stored name; unknown names change nothing. */
    fun setMode(id: String) {
        CameraMode.fromId(id)?.let { setMode(it) }
    }

    /** Switches to the other view and returns it. */
    fun toggle(): CameraMode {
        setMode(if (mode == CameraMode.FOLLOW) CameraMode.RIDER else CameraMode.FOLLOW)
        return mode
    }

    /** Jump straight to the target position (e.g. after a restart). */
    fun snap() {
        initialized = false
        headingReady = false
    }

    /** Moves the camera for a frame of [dt] seconds; the rider view needs the [earAnchor] of the horse. */
    fun update(
        dt: Double,
        state: Horse,
        earAnchor: Node?,
    ) {
        if (mode == CameraMode.RIDER && earAnchor != null) {
            updateRider(dt, state, earAnchor)
            return
        }
        trailHeading(dt, state.heading, FOLLOW_HEADING_STIFFNESS, FOLLOW_HEADING_MAX_RATE)
        followTargets(state)
        if (!initialized) {
            pos.copy(desiredPos)
            look.copy(desiredLook)
            initialized = true
        } else {
            val k = 1 - exp(-FOLLOW_STIFFNESS * dt)
            pos.lerp(desiredPos, k)
            look.lerp(desiredLook, min(1.0, k * LOOK_FOLLOW_FACTOR))
        }
        camera.position.copy(pos)
        camera.lookAt(look)
    }

    private fun updateRider(
        dt: Double,
        state: Horse,
        earAnchor: Node,
    ) {
        earAnchor.updateWorldMatrix(true, false)
        earAnchor.getWorldPosition(tmp)
        val fx = sin(state.heading)
        val fz = cos(state.heading)
        camera.position.set(tmp.x - fx * RIDER_BACK, tmp.y + RIDER_UP, tmp.z - fz * RIDER_BACK)
        // the look direction trails the heading slightly; the eye position stays on the horse
        val h = trailHeading(dt, state.heading, RIDER_HEADING_STIFFNESS, Double.POSITIVE_INFINITY)
        look.set(
            tmp.x + sin(h) * RIDER_LOOK_DISTANCE,
            tmp.y - RIDER_LOOK_DOWN,
            tmp.z + cos(h) * RIDER_LOOK_DISTANCE,
        )
        camera.lookAt(look)
    }
}
