package app.zoeshorsefarm.app

import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.course.Pace
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.createRng
import app.zoeshorsefarm.domain.testing.Autopilot
import app.zoeshorsefarm.domain.testing.courseTargets
import app.zoeshorsefarm.domain.testing.routeOf
import app.zoeshorsefarm.input.mapStickXY
import app.zoeshorsefarm.presentation.ride.RideScreenModel
import kotlin.math.hypot

// The domain's autopilot as a player: it reads the horse of the ride model and answers with what a
// thumb on the touch controls could do (stick position, gallop toggle, jump press), so the whole
// stack from the input to the engine is exercised.

private const val STICK_STEP = 0.02
private const val STICK_STEPS = 50

/** Stick positions (x right, y up, unit disk) with the steer and throttle they map to. */
private object StickTable {
    val x = ArrayList<Double>()
    val y = ArrayList<Double>()
    val steer = ArrayList<Double>()
    val throttle = ArrayList<Double>()

    init {
        for (i in -STICK_STEPS..STICK_STEPS) {
            for (j in -STICK_STEPS..STICK_STEPS) {
                val px = i * STICK_STEP
                val py = j * STICK_STEP
                if (hypot(px, py) > 1.0) continue
                val out = mapStickXY(px, py)
                x.add(px)
                y.add(py)
                steer.add(out.steer)
                throttle.add(out.throttle)
            }
        }
    }

    /** The thumb position whose output is closest to the wanted steer and throttle. */
    fun nearest(
        wantedSteer: Double,
        wantedThrottle: Double,
    ): Pair<Double, Double> {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (k in x.indices) {
            val d = hypot(steer[k] - wantedSteer, throttle[k] - wantedThrottle)
            if (d < bestDistance) {
                bestDistance = d
                best = k
            }
        }
        return x[best] to y[best]
    }
}

internal class ScriptedRider(
    courseId: Int,
) {
    private val course = COURSES.first { it.id == courseId }
    private val targets = courseTargets(course)
    private val pilot: Autopilot
    private var jumping = false
    private var landed = 0

    init {
        val trot = course.pace == Pace.TROT
        pilot =
            Autopilot(
                route = routeOf(course),
                targets = targets,
                speed = if (trot) TUNING.speeds.trotMedium else TUNING.speeds.canterMedium,
                canter = !trot,
                rng = createRng(2),
            )
    }

    /** Sets the touch controls of [ride] for the next frame. */
    fun steer(ride: RideScreenModel) {
        val view = ride.view
        // the landing of a jump tells the autopilot to look for the next element
        if (jumping && view.horse.jump == null) {
            jumping = false
            pilot.onEvents(listOf(SimEvent.Landed(targets[landed].el.id, 1, knocked = false)))
            landed++
        }
        if (view.horse.jump != null) jumping = true
        val input = pilot.next(view.horse)
        val touch = ride.input.touch
        val (x, y) = StickTable.nearest(input.steer, input.throttle)
        touch.moveStickXY(x, y)
        if (input.gallop != touch.gallop) touch.toggleGallop()
        if (input.jump) touch.pressJump()
    }
}
