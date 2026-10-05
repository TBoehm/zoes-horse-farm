package app.zoeshorsefarm.domain.course

import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.Vec2

/** A start or finish line: end points [a] and [b] (about 6 m apart) and the riding direction [dir]. */
data class Line(
    val a: Vec2,
    val b: Vec2,
    val dir: Vec2,
)

/** The pace a course is meant to be ridden at; it sets the reference speed of the allowed time. */
enum class Pace(
    val id: String,
) {
    TROT("trot"),
    CANTER("canter"),
}

/**
 * A course: obstacles in riding order, start and finish line, the pose the horse starts from and
 * the turn waypoints ([track]: one list per leg, the last one before the finish) that give the
 * ideal line for the allowed time. [allowedTimeS] is computed from the ideal line (rule 33).
 */
data class Course(
    val id: Int,
    val pace: Pace = Pace.CANTER,
    val obstacles: List<Obstacle>,
    val start: Line,
    val finish: Line,
    val startPose: Pose = Pose(0.0, 0.0, 0.0),
    val track: List<List<Vec2>> = emptyList(),
    val allowedTimeS: Int = 0,
)

/** The fixed practice layout of free mode (rule 41). */
data class FreeLayout(
    val obstacles: List<Obstacle>,
    val startPose: Pose,
)
