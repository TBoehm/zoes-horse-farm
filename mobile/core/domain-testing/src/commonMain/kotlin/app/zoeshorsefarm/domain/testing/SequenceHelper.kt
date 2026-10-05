package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.HopView
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.domain.sim.RefusalType
import app.zoeshorsefarm.domain.sim.RefusalView
import kotlin.math.abs
import kotlin.math.max

// Test helper: drives the horse animation through scripted ride sequences at a fixed frame rate
// and measures how much the pose changes from frame to frame (rule 24: no visible popping). Only
// used by tests; it has no dependency on the 3D view.
//
// A script is a list of steps. Each step lasts `durationS` seconds and sets (part of) the simulation
// state `sim.horse`: gait, speed, turnRate and optionally jump / hop / refusal, which advance their
// own progress from 0 to 1 over the step. Speed and turn are a constant or a linear ramp. The speed
// of a gait change is not ramped automatically: write the ramp as steps if the sim does so.

const val FRAME = 1.0 / 60

/** A value that is constant or ramps linearly from [from] to [to] over a script step. */
class Ramp(
    val from: Double,
    val to: Double,
) {
    /** The value at progress [p] in [0, 1]. */
    fun at(p: Double): Double = from + (to - from) * p

    companion object {
        fun constant(value: Double) = Ramp(value, value)
    }
}

/** One step of a script; the web `for` is [durationS], `turn` is the turn rate (rad/s). */
data class ScriptStep(
    val durationS: Double,
    val gait: Gait = Gait.HALT,
    val speed: Ramp? = null,
    val turn: Ramp? = null,
    val jump: JumpPhase? = null,
    val hop: Boolean = false,
    val refusal: RefusalType? = null,
)

/** `sim.horse`-like state for [step] at progress [p] in [0, 1]. */
fun stateAt(
    step: ScriptStep,
    p: Double,
): Horse =
    Horse(
        gait = step.gait,
        speed = step.speed?.at(p) ?: 0.0,
        turnRate = step.turn?.at(p) ?: 0.0,
        y = 0.0,
        jump = step.jump?.let { JumpView(it, p, "") },
        hop = if (step.hop) HopView(p) else null,
        refusal = step.refusal?.let { RefusalView(it, p, "") },
    )

/**
 * Runs the script, calls [onFrame] (state, step index, t) after each frame has been advanced by
 * [advance] (dt, state). Returns the number of frames.
 */
fun runScript(
    script: List<ScriptStep>,
    advance: (dt: Double, state: Horse) -> Unit,
    onFrame: (state: Horse, stepIndex: Int, t: Double) -> Unit,
    dt: Double = FRAME,
): Int {
    var t = 0.0
    var frames = 0
    script.forEachIndexed { index, st ->
        val n = max(1, jsRound(st.durationS / dt).toInt())
        for (i in 0 until n) {
            val state = stateAt(st, (i + 0.5) / n)
            advance(dt, state)
            onFrame(state, index, t)
            t += dt
            frames++
        }
    }
    return frames
}

/** The channel with the largest change, see [DeltaTracker.worst]. */
data class WorstDelta(
    val name: String,
    val value: Double,
    val where: String,
)

/** Tracks the maximum per-frame change of named scalar channels. */
class DeltaTracker {
    private val previous = HashMap<String, Double>()

    /** Largest change per channel (channels with at least two samples). */
    val max = LinkedHashMap<String, Double>()

    /** Where (tag and time) the largest change of each channel happened. */
    val where = HashMap<String, String>()

    /** Value of channel [name] at time [t]. */
    fun sample(
        name: String,
        value: Double,
        t: Double,
        tag: String = "",
    ) {
        val before = previous[name]
        if (before != null) {
            if (name !in max) max[name] = 0.0
            val d = abs(value - before)
            if (d > (max[name] ?: 0.0)) {
                max[name] = d
                where[name] = "$tag@${fixed2(t)}s"
            }
        }
        previous[name] = value
    }

    /** The channel with the largest change among those that pass [filter]. */
    fun worst(filter: (String) -> Boolean = { true }): WorstDelta {
        var best = WorstDelta("", 0.0, "")
        for ((name, value) in max) {
            if (filter(name) && value > best.value) best = WorstDelta(name, value, where[name] ?: "")
        }
        return best
    }

    // JS toFixed(2)
    private fun fixed2(t: Double): String {
        val cents = jsRound(abs(t) * 100).toLong()
        val sign = if (t < 0 && cents != 0L) "-" else ""
        return "$sign${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }
}

/** The sequences the tests use (durations in seconds, speeds from the gait speeds of the tuning). */
object Sequences {
    private fun r(value: Double) = Ramp.constant(value)

    private fun r(
        from: Double,
        to: Double,
    ) = Ramp(from, to)

    /** halt -> walk -> trot -> canter -> trot -> walk -> halt, with the speed ramps of the sim */
    val gaitLadder: List<ScriptStep> =
        listOf(
            ScriptStep(1.5, Gait.HALT),
            ScriptStep(0.8, Gait.WALK, speed = r(0.0, 1.5)),
            ScriptStep(2.5, Gait.WALK, speed = r(1.6)),
            ScriptStep(0.6, Gait.TROT, speed = r(1.6, 3.2)),
            ScriptStep(2.5, Gait.TROT, speed = r(3.2)),
            ScriptStep(0.8, Gait.CANTER, speed = r(3.2, 6.0)),
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0)),
            ScriptStep(0.6, Gait.TROT, speed = r(6.0, 3.2)),
            ScriptStep(2.0, Gait.TROT, speed = r(3.2)),
            ScriptStep(0.6, Gait.WALK, speed = r(3.2, 1.6)),
            ScriptStep(1.5, Gait.WALK, speed = r(1.6)),
            ScriptStep(0.6, Gait.HALT, speed = r(1.6, 0.0)),
            ScriptStep(2.0, Gait.HALT),
        )

    /** canter in circles with a change of direction (lead change) */
    val leadChange: List<ScriptStep> =
        listOf(
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0), turn = r(-0.5)),
            ScriptStep(0.6, Gait.CANTER, speed = r(6.0), turn = r(-0.5, 0.5)),
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0), turn = r(0.5)),
            ScriptStep(0.6, Gait.CANTER, speed = r(6.0), turn = r(0.5, -0.5)),
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0), turn = r(-0.5)),
        )

    /** approach, jump, landing, canter away */
    val jump: List<ScriptStep> =
        listOf(
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0)),
            ScriptStep(0.2, Gait.CANTER, speed = r(6.0), jump = JumpPhase.TAKEOFF),
            ScriptStep(0.6, Gait.CANTER, speed = r(6.0), jump = JumpPhase.FLIGHT),
            ScriptStep(0.25, Gait.CANTER, speed = r(6.0), jump = JumpPhase.LANDING),
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0)),
        )

    /** jump at a trot, then a hop over a small obstacle */
    val jumpTrot: List<ScriptStep> =
        listOf(
            ScriptStep(3.0, Gait.TROT, speed = r(3.4)),
            ScriptStep(0.25, Gait.TROT, speed = r(3.4), jump = JumpPhase.TAKEOFF),
            ScriptStep(0.5, Gait.TROT, speed = r(3.4), jump = JumpPhase.FLIGHT),
            ScriptStep(0.3, Gait.TROT, speed = r(3.4), jump = JumpPhase.LANDING),
            ScriptStep(2.0, Gait.TROT, speed = r(3.4)),
            ScriptStep(0.4, Gait.TROT, speed = r(3.4), hop = true),
            ScriptStep(2.0, Gait.TROT, speed = r(3.4)),
        )

    /** refusal: the horse brakes in front of the jump (through the gaits, as the sim does) and stands */
    val refusalStop: List<ScriptStep> =
        listOf(
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0)),
            ScriptStep(0.25, Gait.TROT, speed = r(5.0, 3.0), refusal = RefusalType.STOP),
            ScriptStep(0.25, Gait.WALK, speed = r(3.0, 0.6), refusal = RefusalType.STOP),
            ScriptStep(0.7, Gait.HALT, speed = r(0.0), refusal = RefusalType.STOP),
            ScriptStep(2.5, Gait.HALT),
            ScriptStep(0.8, Gait.WALK, speed = r(0.0, 1.5)),
            ScriptStep(2.0, Gait.WALK, speed = r(1.5)),
        )

    /** frontal stop at the fence: gait and speed drop to zero in one frame */
    val fenceStop: List<ScriptStep> =
        listOf(
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0)),
            ScriptStep(2.5, Gait.HALT, speed = r(0.0)),
            ScriptStep(0.8, Gait.WALK, speed = r(0.2, 1.8)),
            ScriptStep(0.5, Gait.TROT, speed = r(2.0, 3.0)),
            ScriptStep(3.0, Gait.TROT, speed = r(3.0)),
            ScriptStep(2.0, Gait.HALT, speed = r(0.0)),
        )

    /** run-out to the side */
    val runout: List<ScriptStep> =
        listOf(
            ScriptStep(3.0, Gait.CANTER, speed = r(6.0)),
            ScriptStep(0.6, Gait.CANTER, speed = r(5.0), turn = r(1.2), refusal = RefusalType.RUNOUT),
            ScriptStep(2.0, Gait.CANTER, speed = r(6.0)),
        )

    /** turning on the spot, rein-back */
    val spot: List<ScriptStep> =
        listOf(
            ScriptStep(1.0, Gait.HALT),
            ScriptStep(2.0, Gait.HALT, turn = r(1.4)),
            ScriptStep(1.5, Gait.HALT),
            ScriptStep(1.2, Gait.BACK, speed = r(-0.1, -0.5)),
            ScriptStep(1.0, Gait.BACK, speed = r(-0.5)),
            ScriptStep(0.5, Gait.HALT, speed = r(0.0, 0.0)),
            ScriptStep(2.0, Gait.HALT),
        )

    /** All sequences by their web name. */
    val all: Map<String, List<ScriptStep>> =
        mapOf(
            "gaitLadder" to gaitLadder,
            "leadChange" to leadChange,
            "jump" to jump,
            "jumpTrot" to jumpTrot,
            "refusalStop" to refusalStop,
            "fenceStop" to fenceStop,
            "runout" to runout,
            "spot" to spot,
        )
}
