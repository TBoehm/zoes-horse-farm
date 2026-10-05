package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.HopView
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.domain.sim.RefusalType
import app.zoeshorsefarm.domain.sim.RefusalView
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.view3d.horse.createHorse
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

// Continuity of the rider pose from frame to frame (SRT-011, rule 24): a scripted ride is stepped
// at 60 fps through the real horse (motion, poses) and rider (seat, IK); no joint angle or
// hand/hip/head position may jump by more than a sane amount between two frames, whatever the
// sim does (gait changes, take-off, flight, landing, refusal, rein-back, hop, turns).

private const val DT = 1.0 / 60

private val ANGLE_BONES =
    listOf(
        "pelvis",
        "spine",
        "chest",
        "neck",
        "head",
        "LupperArm",
        "Lforearm",
        "Lhand",
        "Rhand",
        "Lthigh",
        "Lshin",
        "Lfoot",
        "Rfoot",
        "pony1",
        "pony2",
        "pony3",
    )
private val POINT_BONES = listOf("pelvis", "head", "Lhand", "Rhand", "Lfoot", "Rfoot")

// Largest accepted change between two frames (60 fps). The scripted ride peaks at about 0.08 rad
// and 3 cm; the limits leave room for tuning but catch a visible pop (a hand or head that
// moves 4+ cm or a joint that turns 6 degrees in one frame).
private const val MAX_ANGLE_STEP = 0.1
private val MAX_POINT_STEP =
    mapOf(
        "pelvis" to 0.04,
        "head" to 0.05,
        "Lhand" to 0.04,
        "Rhand" to 0.04,
        "Lfoot" to 0.01,
        "Rfoot" to 0.01,
    )

/** One segment of the scripted ride: duration (s) and the state at time t (s into the segment). */
private class Segment(
    val duration: Double,
    val at: (Double) -> Horse,
)

private fun hold(
    duration: Double,
    gait: Gait,
    speed: Double,
    turnRate: Double = 0.0,
) = Segment(duration) { Horse(gait = gait, speed = speed, turnRate = turnRate) }

private fun ramp(
    duration: Double,
    from: Double,
    to: Double,
    gait: Gait,
) = Segment(duration) { t -> Horse(gait = gait, speed = from + ((to - from) * t) / duration) }

private const val JUMP_TOTAL = 1.0
private const val JUMP_TAKEOFF = 0.2
private const val JUMP_LANDING = 0.25

private fun jumpSegment(speed: Double = 4.0) =
    Segment(JUMP_TOTAL) { t ->
        val s = t / JUMP_TOTAL
        var phase = JumpPhase.TAKEOFF
        var progress = s / JUMP_TAKEOFF
        if (s >= 1 - JUMP_LANDING) {
            phase = JumpPhase.LANDING
            progress = (s - (1 - JUMP_LANDING)) / JUMP_LANDING
        } else if (s >= JUMP_TAKEOFF) {
            phase = JumpPhase.FLIGHT
            progress = (s - JUMP_TAKEOFF) / (1 - JUMP_TAKEOFF - JUMP_LANDING)
        }
        Horse(gait = Gait.CANTER, speed = speed, y = 0.9 * sin(PI * s), jump = JumpView(phase, min(1.0, progress)))
    }

private fun refusalSegment() =
    Segment(1.2) { t ->
        Horse(
            gait = if (t < 0.5) Gait.CANTER else Gait.HALT,
            speed = max(0.0, 4 - 9 * t),
            refusal = RefusalView(RefusalType.STOP, t / 1.2),
        )
    }

private fun runoutSegment() =
    Segment(1.2) { t ->
        Horse(
            gait = Gait.CANTER,
            speed = 4.0,
            turnRate = 1.2 * sin((PI * t) / 1.2),
            refusal = RefusalView(RefusalType.RUNOUT, t / 1.2),
        )
    }

private fun hopSegment() =
    Segment(0.4) { t ->
        Horse(gait = Gait.CANTER, speed = 4.0, hop = HopView(t / 0.4), y = 0.2 * sin((PI * t) / 0.4))
    }

private val SCRIPT =
    listOf(
        hold(1.5, Gait.HALT, 0.0),
        ramp(1.5, 0.5, 1.6, Gait.WALK),
        hold(0.5, Gait.HALT, 0.0),
        ramp(1.0, 1.6, 3.2, Gait.WALK),
        ramp(2.0, 3.2, 3.6, Gait.TROT),
        ramp(1.5, 3.6, 5.2, Gait.CANTER),
        jumpSegment(5.2), // index 6
        hold(1.5, Gait.CANTER, 5.2),
        ramp(1.0, 5.2, 3.0, Gait.TROT),
        hold(0.5, Gait.CANTER, 5.2, turnRate = 0.5),
        Segment(1.0) { Horse(gait = Gait.CANTER, speed = 5.2, turnRate = -0.8) },
        refusalSegment(),
        hold(2.5, Gait.HALT, 0.0),
        // rein-back, then straight into a canter, a hop and a run-out
        hold(1.0, Gait.BACK, -1.2),
        ramp(1.5, 0.5, 4.0, Gait.CANTER),
        hopSegment(),
        hold(1.0, Gait.CANTER, 4.0),
        runoutSegment(),
        // jump in trot, stop after the landing: pat on the neck
        ramp(1.0, 3.4, 3.4, Gait.TROT),
        jumpSegment(3.4),
        hold(0.3, Gait.TROT, 3.0),
        hold(4.0, Gait.HALT, 0.0), // last segment: the pat
    )
private const val FIRST_JUMP = 6

private class Worst(
    var value: Double = 0.0,
    var frame: Int = 0,
)

private class HandTrack(
    val lz: Double,
    val ly: Double,
    val rz: Double,
    val ry: Double,
)

private class RideResult(
    val worst: Map<String, Worst>,
    val track: List<HandTrack>,
    val starts: List<Int>,
)

/**
 * Rides the script at 60 fps. Returns the largest per-frame change per tracked quantity, the
 * track of both hands (rider space) over time and the frame at which each segment starts.
 */
private fun ride(
    script: List<Segment> = SCRIPT,
    quality: GraphicsLevel = GraphicsLevel.LOW,
): RideResult {
    val horse = createHorse(quality = quality)
    val rider = horse.rider!!
    val base = Mat4()
    val worst = HashMap<String, Worst>()
    val prevQ = HashMap<String, Quat>()
    val prevP = HashMap<String, Vec3>()
    val left = Vec3()
    val right = Vec3()
    val track = ArrayList<HandTrack>()
    val starts = ArrayList<Int>()
    var frame = 0

    fun note(
        key: String,
        value: Double,
    ) {
        val w = worst[key]
        if (w == null || value > w.value) worst[key] = Worst(value, frame)
    }
    for (seg in script) {
        starts.add(frame)
        var t = 0.0
        while (t < seg.duration - 1e-9) {
            horse.update(DT, seg.at(t))
            rider.group.updateMatrixWorld(true)
            base.copy(rider.group.matrixWorld).invert()
            for (name in ANGLE_BONES) {
                val b = rider.bones.getValue(name)
                prevQ[name]?.let { note("angle:$name", it.angleTo(b.quaternion)) }
                prevQ[name] = b.quaternion.clone()
            }
            for (name in POINT_BONES) {
                val p = Vec3().setFromMatrixPosition(rider.bones.getValue(name).matrixWorld).applyMatrix4(base)
                prevP[name]?.let { note("pos:$name", it.distanceTo(p)) }
                prevP[name] = p
            }
            left.setFromMatrixPosition(rider.bones.getValue("Lhand").matrixWorld).applyMatrix4(base)
            right.setFromMatrixPosition(rider.bones.getValue("Rhand").matrixWorld).applyMatrix4(base)
            track.add(HandTrack(left.z, left.y, right.z, right.y))
            t += DT
            frame++
        }
    }
    horse.dispose()
    return RideResult(worst, track, starts)
}

class RiderContinuityTest {
    private val result = ride()

    @Test
    fun `no joint angle jumps between two frames anywhere in the ride`() {
        for (name in ANGLE_BONES) {
            val w = result.worst.getValue("angle:$name")
            assertTrue(w.value < MAX_ANGLE_STEP, "$name at frame ${w.frame}: ${w.value}")
        }
    }

    @Test
    fun `no pelvis or head or hand or foot position jumps between two frames`() {
        for (name in POINT_BONES) {
            val w = result.worst.getValue("pos:$name")
            assertTrue(w.value < MAX_POINT_STEP.getValue(name), "$name at frame ${w.frame}: ${w.value}")
        }
    }

    @Test
    fun `crest release the hands go forward along the neck in flight and back after landing`() {
        val jumpStart = result.starts[FIRST_JUMP]

        fun frames(s: Double) = round(s * 60).toInt()
        val before = result.track[jumpStart - 2]
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0 until frames(JUMP_TOTAL)) peak = max(peak, result.track[jumpStart + i].lz)
        val after = result.track[jumpStart + frames(JUMP_TOTAL) + frames(1.2)]
        assertTrue(peak > before.lz + 0.1, "peak $peak before ${before.lz}")
        assertTrue(abs(after.lz - before.lz) < 0.05)
        // the hands follow the neck down, they do not rise above the approach height
        val flightY = result.track[jumpStart + frames(0.45)].ly
        assertTrue(flightY < before.ly)
    }

    @Test
    fun `pats the neck with the right hand when the horse stands after a jump`() {
        val last = result.starts[result.starts.size - 1]
        var reach = Double.NEGATIVE_INFINITY
        for (i in last until result.track.size) reach = max(reach, result.track[i].rz)
        val rest = result.track[result.track.size - 1]
        assertTrue(reach > rest.rz + 0.08, "reach $reach rest ${rest.rz}")
        // the left hand stays on the reins
        assertTrue(abs(result.track[last + 120].lz - rest.lz) < 0.02)
    }
}
