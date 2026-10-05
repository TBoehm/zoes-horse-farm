package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.POLE_LENGTH
import app.zoeshorsefarm.domain.sim.STAND_WIDTH
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.sim.Zone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Pure calculations for the 3D world (no scene model): obstacle layout, flags, take-off aid and
// lines. The falling poles are in FallingPoles.kt, fence, terrain and plant planning in
// SiteLayout.kt. The view code only uses these.

const val POLE_RADIUS = 0.05
const val POLE_GEOM_LENGTH = POLE_LENGTH - 0.02
const val STAND_X = POLE_LENGTH / 2 + STAND_WIDTH / 2

// lower ends of the cross poles in low cups
private const val CROSS_LOW_Y = 0.17

// --- Obstacles -------------------------------------------------------------------------------

/** Jump axis [n] and cross axis [t] (right-hand side when jumping in +n). */
data class Axes(
    val n: Vec2,
    val t: Vec2,
)

fun axesOf(rot: Double = 0.0): Axes = Axes(n = Vec2(sin(rot), cos(rot)), t = Vec2(-cos(rot), sin(rot)))

/** Sign of the element-local x coordinate of the red and the white flag. */
data class FlagSides(
    val red: Int,
    val white: Int,
)

/**
 * Flag sides: red on +t, white on -t. In the element-local frame (+Z = n), +X points to -t, so
 * red stands at local x < 0. Returns the sign of the local x coordinate per color.
 */
fun flagSides(): FlagSides = FlagSides(red = -1, white = 1)

/** Stand height for an element. */
fun standHeight(element: Element): Double = max(1.45, element.height + 0.55)

/** Positions of the stand rows along n (oxer: front and back). */
fun standRows(element: Element): DoubleArray {
    if (element.kind != ElementKind.OXER) return doubleArrayOf(0.0)
    val s = element.spread
    return doubleArrayOf(-s / 2, s / 2)
}

/**
 * Rest pose of one pole in the local frame (+Z = n, +X = -t): ends [a] and [b] as [x, y, z].
 * [rail] is the index of the falling rail, -1 = fixed (never falls).
 */
class PoleRest(
    val rail: Int,
    val a: DoubleArray,
    val b: DoubleArray,
)

private fun pole(
    rail: Int,
    ax: Double,
    ay: Double,
    az: Double,
    bx: Double,
    by: Double,
    bz: Double,
) = PoleRest(rail, doubleArrayOf(ax, ay, az), doubleArrayOf(bx, by, bz))

/**
 * Rest poses of all poles of an element in the local frame (+Z = n, +X = -t). Height = top of the
 * highest pole (cross: at the crossing point).
 */
fun polesOf(element: Element): List<PoleRest> {
    val h = element.height
    val s = if (element.kind == ElementKind.OXER) element.spread else 0.0
    val half = POLE_GEOM_LENGTH / 2
    val top = h - POLE_RADIUS
    return when (element.kind) {
        ElementKind.CROSS -> {
            val y1 = max(CROSS_LOW_Y + 0.1, 2 * top - CROSS_LOW_Y)
            val dz = POLE_RADIUS + 0.004
            listOf(
                pole(0, -half, CROSS_LOW_Y, -dz, half, y1, -dz),
                pole(0, -half, y1, dz, half, CROSS_LOW_Y, dz),
                pole(-1, -half, POLE_RADIUS, 0.32, half, POLE_RADIUS, 0.32),
            )
        }

        ElementKind.VERTICAL -> {
            val out = mutableListOf(pole(0, -half, top, 0.0, half, top, 0.0))
            if (h >= 0.7) {
                val y = (0.32 + top) / 2
                out.add(pole(-1, -half, y, 0.0, half, y, 0.0))
            }
            out
        }

        ElementKind.OXER -> {
            listOf(
                pole(0, -half, top, -s / 2, half, top, -s / 2),
                pole(1, -half, top, s / 2, half, top, s / 2),
                pole(-1, -half, h * 0.45, -s / 2, half, h * 0.45, -s / 2),
            )
        }
    }
}

/** Element label: number, with a/b for combinations; null without a number. */
fun labelOf(
    obstacle: Obstacle,
    index: Int,
): String? {
    val number = obstacle.number ?: return null
    if (obstacle.elements.size > 1) return "$number${if (index == 0) 'a' else 'b'}"
    return number.toString()
}

/** Highlight text: the given number, with a/b appended for combinations. */
fun highlightText(
    obstacle: Obstacle,
    index: Int,
    number: Int?,
): String? {
    if (number == null) return labelOf(obstacle, index)
    val text = number.toString()
    if (obstacle.elements.size > 1 && number >= 0) return text + if (index == 0) 'a' else 'b'
    return text
}

// --- Take-off aid ----------------------------------------------------------------------------

/** Where the take-off band lies: center ([x], [z]), yaw [rotY], [width] across and [depth] along the jump. */
data class AidPlacement(
    val x: Double,
    val z: Double,
    val rotY: Double,
    val width: Double,
    val depth: Double,
)

/**
 * Take-off band placement: front edge = center - dir * n * spread / 2; the band spans zone.near to
 * zone.far in front of the front edge (against the approach direction), as wide as the pole.
 * Returns null without an element or zone, or for an empty zone.
 */
fun aidPlacement(
    element: Element?,
    dir: Int,
    zone: Zone?,
): AidPlacement? {
    if (element == null || zone == null) return null
    val near = max(0.0, min(zone.near, zone.far))
    val far = max(zone.near, zone.far)
    val depth = far - near
    if (!(depth > 0)) return null
    val d = if (dir < 0) -1 else 1
    val n = axesOf(element.rot).n
    val half = (if (element.kind == ElementKind.OXER) element.spread else 0.0) / 2
    val s = -d * (half + (near + far) / 2)
    return AidPlacement(
        x = element.x + n.x * s,
        z = element.z + n.z * s,
        rotY = element.rot,
        width = POLE_LENGTH,
        depth = depth,
    )
}

// --- Start/finish lines --------------------------------------------------------------------------

/** Center, length and rotation (about Y, direction a to b) of a line from [a] to [b]. */
data class LineSegment(
    val a: Vec2,
    val b: Vec2,
    val cx: Double,
    val cz: Double,
    val length: Double,
    val angle: Double,
)

fun lineSegment(
    a: Vec2,
    b: Vec2,
): LineSegment {
    val dx = b.x - a.x
    val dz = b.z - a.z
    return LineSegment(
        a = a,
        b = b,
        cx = (a.x + b.x) / 2,
        cz = (a.z + b.z) / 2,
        length = hypot(dx, dz),
        angle = atan2(dx, dz),
    )
}

/** A flag post of a line. */
data class LinePost(
    val x: Double,
    val z: Double,
    val red: Boolean,
)

/**
 * Flag posts of a line. The domain puts end `a` on the rider's LEFT and `b` on the RIGHT (see
 * `line()` in the course definitions), and the flags follow the obstacle rule: red on the right,
 * white on the left, in riding direction.
 */
fun linePosts(seg: LineSegment): List<LinePost> =
    listOf(
        LinePost(seg.a.x, seg.a.z, red = false),
        LinePost(seg.b.x, seg.b.z, red = true),
    )

/** The translated texts of the start and finish signs (empty when not given). */
data class LineLabels(
    val start: String = "",
    val finish: String = "",
)

/** The start and finish lines of a course with their sign texts (any line may be missing). */
data class CourseLines(
    val start: Line?,
    val finish: Line?,
    val labels: LineLabels = LineLabels(),
)

enum class LineKind { START, FINISH }

/** One sign with its line; `finish` selects the chequered look. */
data class LineSign(
    val kind: LineKind,
    val seg: LineSegment,
    val text: String,
    val finish: Boolean,
)

// the two lines count as one when their ends lie closer than this (m) in total
private const val SAME_LINE_DISTANCE = 0.5

/** Signs for start/finish; if both lines coincide there is one shared sign. */
fun planLines(lines: CourseLines?): List<LineSign> {
    if (lines == null) return emptyList()
    // The texts are translated by the caller (i18n); there is no built-in fallback text
    val labels = lines.labels
    val s = lines.start?.let { lineSegment(it.a, it.b) }
    val f = lines.finish?.let { lineSegment(it.a, it.b) }
    val same =
        s != null && f != null &&
            hypot(s.a.x - f.a.x, s.a.z - f.a.z) + hypot(s.b.x - f.b.x, s.b.z - f.b.z) < SAME_LINE_DISTANCE
    val out = ArrayList<LineSign>(2)
    if (s != null) {
        out.add(
            LineSign(
                kind = LineKind.START,
                seg = s,
                text = if (same) "${labels.start} · ${labels.finish}" else labels.start,
                finish = same,
            ),
        )
    }
    if (f != null && !same) out.add(LineSign(LineKind.FINISH, f, labels.finish, finish = true))
    return out
}
