package app.zoeshorsefarm.domain.course

import app.zoeshorsefarm.domain.sim.COMBI_DISTANCE
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan

// Course layouts (concept rule 25) and the fixed practice layout of free mode (rule 41).
// Coordinates per docs/specs/springreiten-trainer/architecture.md: meters, arena x in [-20, 20],
// z in [-35, 35]; rot such that +n = (sin rot, cos rot) is the jump direction.
//
// Built following simple course-building rules: obstacles on lines with related distances
// (5 canter strides), turns with radii that are rideable at canter (sim: about 8 m at
// medium canter), change of rein from course 2, first obstacle low and approached
// straight. `track` holds the turn waypoints per leg; they yield the ideal line for the
// allowed time (Scoring).

private const val N = 0.0 // jump toward +z
private const val S = PI // toward -z
private const val LINE_LENGTH = 6.0
private const val START_BACK = 5.0 // halt this far before the start line
private val LANDING_FREE = TUNING.course.landingFree // straight stretch after landing before a turn
private const val DIAG_DEG = 15.0
private const val DIAG = (DIAG_DEG * PI) / 180 // angle of the diagonal to the longitudinal axis
private const val DIAG_EXIT_ARC_STEPS = 4 // segments of the turn at the end of the diagonal
private const val QUARTER_TURN_DEG = 90.0
private const val HALF_TURN_DEG = 180.0
private const val FULL_TURN_DEG = 360.0
private const val DIAG_ENTRY_SWEEP_DEG = 105.0 // arc from the short side onto the diagonal
private const val DIAG_EXIT_Z = -21.9 // the turn at the end of the diagonal starts here
private const val DEG = PI / 180

private fun heading(
    dx: Double,
    dz: Double,
) = atan2(dx, dz)

private fun spreadFor(
    kind: ElementKind,
    height: Double,
): Double {
    if (kind != ElementKind.OXER) return 0.0
    val spreads = TUNING.course.oxerSpread
    return spreads.byMaxHeight.firstOrNull { height <= it.maxHeight }?.spread ?: spreads.tall
}

/** Oxer depth for a given oxer height (tuning). */
private fun oxerSpread(height: Double) = spreadFor(ElementKind.OXER, height)

private fun element(
    id: String,
    kind: ElementKind,
    height: Double,
    x: Double,
    z: Double,
    rot: Double,
) = Element(id, kind, height, spreadFor(kind, height), x, z, rot)

private fun single(
    number: Int?,
    id: String,
    kind: ElementKind,
    height: Double,
    x: Double,
    z: Double,
    rot: Double,
    directed: Boolean = true,
) = Obstacle(number, listOf(element(id, kind, height, x, z, rot)), directed)

/** A kind and a height, for the two parts of a combination. */
private class Part(
    val kind: ElementKind,
    val height: Double,
)

/** Double combination: a at (x, z), b at distance COMBI_DISTANCE in direction +n. */
private fun combination(
    number: Int?,
    id: String,
    a: Part,
    b: Part,
    x: Double,
    z: Double,
    rot: Double,
    directed: Boolean = true,
): Obstacle {
    val bx = x + sin(rot) * COMBI_DISTANCE
    val bz = z + cos(rot) * COMBI_DISTANCE
    return Obstacle(
        number,
        listOf(
            element("${id}a", a.kind, a.height, x, z, rot),
            element("${id}b", b.kind, b.height, bx, bz, rot),
        ),
        directed,
    )
}

/**
 * Related distance on a line, center to center: n canter strides + landing + takeoff
 * (edge to edge n * 3.7 m + 3.6 m), plus half the oxer depths.
 */
fun relatedDistance(
    strides: Int,
    spreadFrom: Double = 0.0,
    spreadTo: Double = 0.0,
): Double {
    val c = TUNING.course
    return strides * c.stride + 2 * c.takeoffLanding + spreadFrom / 2 + spreadTo / 2
}

/** Line (about 6 m) across the riding direction rot through (x, z). */
private fun line(
    x: Double,
    z: Double,
    rot: Double,
): Line {
    val dirX = sin(rot)
    val dirZ = cos(rot)
    val h = LINE_LENGTH / 2
    return Line(
        a = Vec2(x + dirZ * h, z - dirX * h),
        b = Vec2(x - dirZ * h, z + dirX * h),
        dir = Vec2(dirX, dirZ),
    )
}

private fun poseBefore(start: Line): Pose {
    val mx = (start.a.x + start.b.x) / 2
    val mz = (start.a.z + start.b.z) / 2
    return Pose(
        x = mx - start.dir.x * START_BACK,
        z = mz - start.dir.z * START_BACK,
        heading = heading(start.dir.x, start.dir.z),
    )
}

// ---- Turn waypoints (top view, angles in degrees as in mathematics: x, z) ----

private fun arc(
    cx: Double,
    cz: Double,
    r: Double,
    a0: Double,
    a1: Double,
    steps: Int = 3,
): List<Vec2> {
    val out = ArrayList<Vec2>()
    for (i in 0..steps) {
        val a = (a0 + ((a1 - a0) * i) / steps) * DEG
        out.add(Vec2(cx + r * cos(a), cz + r * sin(a)))
    }
    return out
}

/**
 * Turn from a longitudinal line x1 to the opposite direction on line x2, at the upper (side = +1)
 * or lower (side = -1) short side: quarter arc, straight, quarter arc with radius r.
 */
private fun turnAround(
    x1: Double,
    x2: Double,
    zc: Double,
    side: Int,
    r: Double,
): List<Vec2> {
    val s = sign(x2 - x1)
    val mid =
        if (side > 0) {
            90.0
        } else if (s > 0) {
            270.0
        } else {
            -90.0
        }
    val a0 = if (s > 0) 180.0 else 0.0
    val a1 =
        if (side > 0) {
            (if (s > 0) 0.0 else 180.0)
        } else if (s > 0) {
            360.0
        } else {
            -180.0
        }
    return arc(x1 + s * r, zc, r, a0, mid, 2) + arc(x2 - s * r, zc, r, mid, a1, 2)
}

/** x of the diagonal through e (toward -z, to side sx) at height z. */
private fun diagX(
    e: Vec2,
    sx: Int,
    z: Double,
) = e.x + sx * tan(DIAG) * (e.z - z)

private fun diagDown(sx: Int) = heading(sx * sin(DIAG), -cos(DIAG))

/** From a longitudinal line (toward +z) across the upper short side into the diagonal through e. */
private fun intoDiag(
    fromX: Double,
    zc: Double,
    e: Vec2,
    sx: Int,
    r: Double = TUNING.course.diagonalTurnRadius,
): List<Vec2> {
    val endZ = zc - r * sin(DIAG)
    val cx = diagX(e, sx, endZ) + sx * r * cos(DIAG)
    return arc(fromX - sx * r, zc, r, if (sx > 0) 0.0 else HALF_TURN_DEG, QUARTER_TURN_DEG, 2) +
        arc(cx, zc, r, QUARTER_TURN_DEG, QUARTER_TURN_DEG + sx * DIAG_ENTRY_SWEEP_DEG)
}

/** End of the diagonal through e: turn onto the longitudinal line laneX (toward +z). */
private fun outOfDiag(
    e: Vec2,
    sx: Int,
    laneX: Double,
): List<Vec2> {
    val px = diagX(e, sx, DIAG_EXIT_Z)
    val r = abs(laneX - px) / (1 + cos(DIAG))
    val cx = px - sx * r * cos(DIAG)
    val cz = DIAG_EXIT_Z - r * sin(DIAG)
    val startDeg = if (sx > 0) DIAG_DEG else HALF_TURN_DEG - DIAG_DEG
    val endDeg = if (sx > 0) -HALF_TURN_DEG else FULL_TURN_DEG
    return arc(cx, cz, r, startDeg, endDeg, DIAG_EXIT_ARC_STEPS)
}

private class CourseParts(
    val start: Line,
    val finish: Line,
    val obstacles: List<Obstacle>,
    val track: List<List<Vec2>>,
)

private fun course(
    id: Int,
    pace: Pace,
    parts: CourseParts,
): Course {
    val c =
        Course(
            id = id,
            pace = pace,
            obstacles = parts.obstacles,
            start = parts.start,
            finish = parts.finish,
            startPose = poseBefore(parts.start),
            track = parts.track,
            allowedTimeS = 0,
        )
    return c.copy(allowedTimeS = allowedTime(c))
}

private val P2_DIAG = Vec2(-4.0, 3.0)
private val P3_DIAG = Vec2(4.0, 3.0)
private val P4_DIAG = Vec2(-4.0, 3.0)

private val V = ElementKind.VERTICAL
private val X = ElementKind.CROSS
private val O = ElementKind.OXER

private val none = emptyList<Vec2>()

/** The five courses (rule 25). */
val COURSES: List<Course> =
    listOf(
        // P1: 4 crosses on a large oval with two straight lines - rideable at trot
        course(
            1,
            Pace.TROT,
            CourseParts(
                start = line(-1.0, -28.0, PI / 2),
                finish = line(-10.0, -25.0, S),
                obstacles =
                    listOf(
                        single(1, "p1-1", X, 0.4, 10.0, -11.0, N),
                        single(2, "p1-2", X, 0.45, 10.0, -11 + relatedDistance(5), N),
                        single(3, "p1-3", X, 0.5, -10.0, 11.0, S),
                        single(4, "p1-4", X, 0.5, -10.0, 11 - relatedDistance(5), S),
                    ),
                track =
                    listOf(
                        arc(4.0, -22.0, 6.0, -90.0, 0.0, 2),
                        none,
                        turnAround(10.0, -10.0, 25.0, 1, 5.0),
                        none,
                        none,
                    ),
            ),
        ),
        // P2: crosses and verticals; figure eight with a change of rein across the diagonal
        course(
            2,
            Pace.CANTER,
            CourseParts(
                start = line(13.0, -26.0, N),
                finish = line(-13.0, 25.0, N),
                obstacles =
                    listOf(
                        single(1, "p2-1", X, 0.5, 13.0, -11.0, N),
                        single(2, "p2-2", V, 0.6, 13.0, -11 + relatedDistance(5), N),
                        single(3, "p2-3", V, 0.6, P2_DIAG.x, P2_DIAG.z, diagDown(1)),
                        single(4, "p2-4", X, 0.5, -13.0, -10.0, N),
                        single(5, "p2-5", V, 0.6, -13.0, -10 + relatedDistance(5), N),
                    ),
                track =
                    listOf(
                        none,
                        none,
                        intoDiag(13.0, -11 + relatedDistance(5) + LANDING_FREE, P2_DIAG, 1),
                        outOfDiag(P2_DIAG, 1, -13.0),
                        none,
                        none,
                    ),
            ),
        ),
        // P3: first oxer; mirrored figure eight, finishing through the middle
        course(
            3,
            Pace.CANTER,
            CourseParts(
                start = line(-14.0, -26.0, N),
                finish = line(-7.0, -12.0, S),
                obstacles =
                    listOf(
                        single(1, "p3-1", X, 0.5, -14.0, -11.0, N),
                        single(2, "p3-2", V, 0.6, -14.0, -11 + relatedDistance(5), N),
                        single(3, "p3-3", V, 0.65, P3_DIAG.x, P3_DIAG.z, diagDown(-1)),
                        single(4, "p3-4", O, 0.7, 14.0, -9.5, N),
                        single(5, "p3-5", V, 0.65, 14.0, -9.5 + relatedDistance(5, oxerSpread(0.7)), N),
                        single(6, "p3-6", V, 0.7, -7.0, 4.0, S),
                    ),
                track =
                    listOf(
                        none,
                        none,
                        intoDiag(-14.0, -11 + relatedDistance(5) + LANDING_FREE, P3_DIAG, -1),
                        outOfDiag(P3_DIAG, -1, 14.0),
                        none,
                        turnAround(14.0, -7.0, -9.5 + relatedDistance(5, oxerSpread(0.7)) + LANDING_FREE, 1, 8.0),
                        none,
                    ),
            ),
        ),
        // P4: verticals and oxers mixed; figure eight and a final line
        course(
            4,
            Pace.CANTER,
            CourseParts(
                start = line(14.0, -26.0, N),
                finish = line(7.0, -24.0, S),
                obstacles =
                    listOf(
                        single(1, "p4-1", V, 0.7, 14.0, -11.0, N),
                        single(2, "p4-2", O, 0.75, 14.0, -11 + relatedDistance(5, 0.0, oxerSpread(0.75)), N),
                        single(3, "p4-3", V, 0.75, P4_DIAG.x, P4_DIAG.z, diagDown(1)),
                        single(4, "p4-4", O, 0.8, -14.0, -9.5, N),
                        single(5, "p4-5", V, 0.8, -14.0, -9.5 + relatedDistance(5, oxerSpread(0.8)), N),
                        single(6, "p4-6", O, 0.8, 7.0, 10.0, S),
                        single(7, "p4-7", V, 0.8, 7.0, 10 - relatedDistance(5, oxerSpread(0.8)), S),
                    ),
                track =
                    listOf(
                        none,
                        none,
                        intoDiag(
                            14.0,
                            -11 + relatedDistance(5, 0.0, oxerSpread(0.75)) + oxerSpread(0.75) / 2 + LANDING_FREE,
                            P4_DIAG,
                            1,
                        ),
                        outOfDiag(P4_DIAG, 1, -14.0),
                        none,
                        turnAround(-14.0, 7.0, 10 + oxerSpread(0.8) / 2 + 14, 1, 8.0),
                        none,
                        none,
                    ),
            ),
        ),
        // P5: change of rein through the middle (without a jump), combination after a wide turn,
        // final line with a related distance
        course(
            5,
            Pace.CANTER,
            CourseParts(
                start = line(14.0, -26.0, N),
                finish = line(-7.0, 23.0, N),
                obstacles =
                    listOf(
                        single(1, "p5-1", V, 0.75, 14.0, -11.0, N),
                        single(2, "p5-2", O, 0.8, 14.0, -11 + relatedDistance(5, 0.0, oxerSpread(0.8)), N),
                        single(3, "p5-3", O, 0.8, -14.0, -11.0, N),
                        single(4, "p5-4", V, 0.85, -14.0, -11 + relatedDistance(5, oxerSpread(0.8)), N),
                        combination(5, "p5-5", Part(V, 0.8), Part(O, 0.85), 7.0, 11.5, S),
                        single(
                            6,
                            "p5-6",
                            V,
                            0.85,
                            7.0,
                            11.5 - COMBI_DISTANCE - relatedDistance(5, oxerSpread(0.85)),
                            S,
                        ),
                        single(7, "p5-7", O, 0.85, -7.0, -11.5, N),
                        single(8, "p5-8", V, 0.85, -7.0, -11.5 + relatedDistance(5, oxerSpread(0.85)), N),
                    ),
                track =
                    listOf(
                        none,
                        none,
                        turnAround(
                            14.0,
                            0.0,
                            -11 + relatedDistance(5, 0.0, oxerSpread(0.8)) + oxerSpread(0.8) / 2 + LANDING_FREE,
                            1,
                            7.0,
                        ) + turnAround(0.0, -14.0, -11 - oxerSpread(0.8) / 2 - 14, -1, 7.0),
                        none,
                        turnAround(-14.0, 7.0, 11.5 + 14, 1, 8.0),
                        none,
                        turnAround(
                            7.0,
                            -7.0,
                            11.5 - COMBI_DISTANCE - relatedDistance(5, oxerSpread(0.85)) - LANDING_FREE,
                            -1,
                            7.0,
                        ),
                        none,
                        none,
                    ),
            ),
        ),
    )

/** The course with the given id; falls back to the first course for an unknown id. */
fun courseById(id: Int?): Course = COURSES.firstOrNull { it.id == id } ?: COURSES[0]

/** The course with the given id as a numeric string; falls back to the first course. */
fun courseById(id: String?): Course {
    val number = id?.trim()?.toDoubleOrNull() ?: return COURSES[0]
    return COURSES.firstOrNull { it.id.toDouble() == number } ?: COURSES[0]
}

// Free mode: undirected, without numbers; every line can be approached from both directions,
// room to turn at the short sides and between the lines

/** The fixed practice layout of free mode (rule 41). */
val FREE_LAYOUT =
    FreeLayout(
        obstacles =
            listOf(
                single(null, "f1", X, 0.4, 13.0, -12.0, N, false),
                single(null, "f2", V, 0.6, 13.0, 12.0, N, false),
                single(null, "f3", O, 0.7, -13.0, -12.0, N, false),
                single(null, "f4", O, 0.85, -13.0, 12.0, N, false),
                combination(null, "f5", Part(V, 0.65), Part(O, 0.75), 0.0, -COMBI_DISTANCE / 2, N, false),
            ),
        startPose = Pose(-6.5, -26.0, 0.0),
    )
