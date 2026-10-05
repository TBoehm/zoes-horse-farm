package app.zoeshorsefarm.presentation.courses

import app.zoeshorsefarm.domain.course.Course
import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.POLE_LENGTH
import app.zoeshorsefarm.domain.sim.Placed
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.presentation.theme.Argb
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Top-down plan of a course (prestart map, rules 26, 28). The web draws it on a canvas; here the
// plan is data: a list of shapes in plan pixels, in drawing order, which the Compose layer paints
// with its own canvas. Plan: arena length (z) to the right, width (x) upwards.

/** Colours of the plan, the same as in the web canvas code. */
object PlanColors {
    val ARENA_FILL = Argb.rgb(0xe8d3a5)
    val ARENA_STROKE = Argb.rgb(0x8a6a43)
    val START = Argb.rgb(0x2f7d32)
    val FINISH = Argb.rgb(0xb3261e)
    val POLE = Argb.rgb(0xd23b2f)
    val FLAG_RED = Argb.rgb(0xe53935)
    val FLAG_WHITE = Argb.rgb(0xffffff)
    val FLAG_STROKE = Argb.rgb(0x333333)

    /** Jump-direction arrow, number badge outline and number. */
    val NUMBER = Argb.rgb(0x1f3b8a)
    val NUMBER_FILL = Argb.rgb(0xffffff)
}

/** A shape of the plan; all lengths are plan pixels. */
sealed interface PlanShape

/** Rounded rectangle with its top-left corner at ([x], [y]). */
data class PlanRoundRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val radius: Double,
    val fill: Argb,
    val stroke: Argb,
    val strokeWidth: Double,
) : PlanShape

/** A line; [dash] is the on/off pattern of a dashed line, [roundCap] rounds its ends. */
data class PlanLine(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val color: Argb,
    val width: Double,
    val dash: List<Double>? = null,
    val roundCap: Boolean = false,
) : PlanShape

/** A filled triangle (the head of an arrow). */
data class PlanPolygon(
    val points: List<Pair<Double, Double>>,
    val fill: Argb,
) : PlanShape

data class PlanCircle(
    val cx: Double,
    val cy: Double,
    val radius: Double,
    val fill: Argb,
    val stroke: Argb,
    val strokeWidth: Double,
) : PlanShape

/**
 * Text centred horizontally at ([x], [y]). [y] is the text baseline, or the vertical middle of the
 * text when [middleBaseline] is set (number badges). [fontWeight] is a CSS weight (800, 900).
 */
data class PlanLabel(
    val text: String,
    val x: Double,
    val y: Double,
    val color: Argb,
    val fontSize: Double,
    val fontWeight: Int,
    val middleBaseline: Boolean = false,
) : PlanShape

/** The drawing: its size, the metres-to-pixels [scale] and the shapes in drawing order. */
class CoursePlan(
    val width: Double,
    val height: Double,
    val scale: Double,
    val shapes: List<PlanShape>,
) {
    private val originX = width / 2
    private val originY = height / 2

    /** The plan point of the world point ([x], [z]) in metres. */
    fun project(
        x: Double,
        z: Double,
    ): Pair<Double, Double> = Pair(originX + z * scale, originY - x * scale)
}

/** The two ends of the arrow across a start or finish line, in world metres. */
data class CrossingArrow(
    val from: Vec2,
    val to: Vec2,
)

/** Arrow across a start or finish line in riding direction, centred on the line (world metres). */
fun crossingArrow(
    line: Line,
    length: Double = 4.0,
): CrossingArrow {
    val mx = (line.a.x + line.b.x) / 2
    val mz = (line.a.z + line.b.z) / 2
    val half = length / 2
    return CrossingArrow(
        from = Vec2(mx - line.dir.x * half, mz - line.dir.z * half),
        to = Vec2(mx + line.dir.x * half, mz + line.dir.z * half),
    )
}

/** World positions of the two flags of an obstacle element. */
data class FlagPoints(
    val red: Vec2,
    val white: Vec2,
)

private const val FLAG_MARGIN = 0.4

/**
 * World positions (x, z) of the two flags of an obstacle element. Rule: red on the rider's right
 * (+t, the cross axis of the element), white on the left, seen in jump direction.
 */
fun flagPoints(
    el: Placed,
    margin: Double = FLAG_MARGIN,
): FlagPoints {
    val tx = -cos(el.rot)
    val tz = sin(el.rot)
    val d = POLE_LENGTH / 2 + margin
    return FlagPoints(
        red = Vec2(el.x + tx * d, el.z + tz * d),
        white = Vec2(el.x - tx * d, el.z - tz * d),
    )
}

private const val PADDING = 14.0
private const val ARENA_RADIUS = 10.0
private const val ARENA_STROKE_WIDTH = 3.0
private const val LINE_WIDTH = 3.0
private val LINE_DASH = listOf(6.0, 5.0)
private const val LINE_ARROW_LENGTH = 4.5
private const val LABEL_LIFT = 6.0
private const val LABEL_WEIGHT = 800
private const val MIN_LABEL_SIZE = 11.0
private const val LABEL_SCALE = 1.6
private const val ARROW_LINE_WIDTH = 2.0
private const val ARROW_HEAD = 8.0
private const val ARROW_HEAD_ANGLE = 0.45
private const val POLE_MIN_WIDTH = 4.0
private const val POLE_SCALE = 0.45
private const val FLAG_MIN_RADIUS = 3.0
private const val FLAG_SCALE = 0.4
private const val FLAG_STROKE_WIDTH = 1.0
private const val JUMP_ARROW_HALF = 3.2
private const val NUMBER_OFFSET = 2.4
private const val NUMBER_MIN_RADIUS = 10.0
private const val NUMBER_SCALE = 1.5
private const val NUMBER_STROKE_WIDTH = 2.5
private const val NUMBER_TEXT_SCALE = 1.2
private const val NUMBER_WEIGHT = 900
private const val NUMBER_TEXT_DROP = 1.0

/**
 * Builds the plan of [course] for a drawing area of [width] x [height] pixels. [startLabel] and
 * [finishLabel] are the translated legend texts next to the two lines (null: no text).
 */
fun buildCoursePlan(
    course: Course,
    width: Double,
    height: Double,
    startLabel: String? = null,
    finishLabel: String? = null,
): CoursePlan {
    val scale = min((width - PADDING * 2) / ARENA.length, (height - PADDING * 2) / ARENA.width)
    val plan = CoursePlan(width, height, scale, emptyList())
    val shapes = ArrayList<PlanShape>()
    val builder = PlanBuilder(plan, shapes)

    val (ax, ay) = plan.project(ARENA.width / 2, -ARENA.length / 2)
    shapes +=
        PlanRoundRect(
            ax,
            ay,
            ARENA.length * scale,
            ARENA.width * scale,
            ARENA_RADIUS,
            PlanColors.ARENA_FILL,
            PlanColors.ARENA_STROKE,
            ARENA_STROKE_WIDTH,
        )
    builder.line(course.start, PlanColors.START, startLabel)
    builder.line(course.finish, PlanColors.FINISH, finishLabel)
    for (obstacle in course.obstacles) {
        for (el in obstacle.elements) builder.element(el)
        builder.obstacleMarks(obstacle.elements.first(), obstacle.elements.last(), obstacle.number)
    }
    return CoursePlan(width, height, scale, shapes)
}

private class PlanBuilder(
    private val plan: CoursePlan,
    private val shapes: MutableList<PlanShape>,
) {
    private val scale = plan.scale

    /** Start or finish line: dashed, an arrow across it in riding direction, an optional label. */
    fun line(
        l: Line,
        color: Argb,
        label: String?,
    ) {
        val (x1, y1) = plan.project(l.a.x, l.a.z)
        val (x2, y2) = plan.project(l.b.x, l.b.z)
        shapes += PlanLine(x1, y1, x2, y2, color, LINE_WIDTH, dash = LINE_DASH)
        val (from, to) = crossingArrow(l, LINE_ARROW_LENGTH)
        val (f0x, f0y) = plan.project(from.x, from.z)
        val (f1x, f1y) = plan.project(to.x, to.z)
        arrow(f0x, f0y, f1x, f1y, color, roundCap = false)
        if (label != null) {
            shapes +=
                PlanLabel(
                    label,
                    (x1 + x2) / 2,
                    minOf(y1, y2, f0y, f1y) - LABEL_LIFT,
                    color,
                    max(MIN_LABEL_SIZE, scale * LABEL_SCALE),
                    LABEL_WEIGHT,
                )
        }
    }

    /** The poles of an element (two for an oxer) and its two flags, red on the right. */
    fun element(el: Element) {
        val tx = -cos(el.rot)
        val tz = sin(el.rot)
        val half = POLE_LENGTH / 2
        val spread = el.spread
        val nx = sin(el.rot)
        val nz = cos(el.rot)
        val offsets = if (spread > 0) listOf(-spread / 2, spread / 2) else listOf(0.0)
        for (o in offsets) {
            val (x1, y1) = plan.project(el.x + nx * o - tx * half, el.z + nz * o - tz * half)
            val (x2, y2) = plan.project(el.x + nx * o + tx * half, el.z + nz * o + tz * half)
            shapes +=
                PlanLine(x1, y1, x2, y2, PlanColors.POLE, max(POLE_MIN_WIDTH, scale * POLE_SCALE), roundCap = true)
        }
        val flags = flagPoints(el)
        flag(flags.red, PlanColors.FLAG_RED)
        flag(flags.white, PlanColors.FLAG_WHITE)
    }

    private fun flag(
        point: Vec2,
        fill: Argb,
    ) {
        val (fx, fy) = plan.project(point.x, point.z)
        shapes +=
            PlanCircle(
                fx,
                fy,
                max(FLAG_MIN_RADIUS, scale * FLAG_SCALE),
                fill,
                PlanColors.FLAG_STROKE,
                FLAG_STROKE_WIDTH,
            )
    }

    /** The arrow in jump direction through the middle of the obstacle and its number on the left side. */
    fun obstacleMarks(
        first: Placed,
        last: Placed,
        number: Int?,
    ) {
        val cx = (first.x + last.x) / 2
        val cz = (first.z + last.z) / 2
        val nx = sin(first.rot)
        val nz = cos(first.rot)
        val (s0x, s0y) = plan.project(cx - nx * JUMP_ARROW_HALF, cz - nz * JUMP_ARROW_HALF)
        val (s1x, s1y) = plan.project(cx + nx * JUMP_ARROW_HALF, cz + nz * JUMP_ARROW_HALF)
        arrow(s0x, s0y, s1x, s1y, PlanColors.NUMBER, roundCap = true)
        if (number == null || number == 0) return
        // number on the side (left in jump direction)
        val tx = cos(first.rot)
        val tz = -sin(first.rot)
        val off = POLE_LENGTH / 2 + NUMBER_OFFSET
        val (bx, by) = plan.project(cx + tx * off, cz + tz * off)
        val r = max(NUMBER_MIN_RADIUS, scale * NUMBER_SCALE)
        shapes += PlanCircle(bx, by, r, PlanColors.NUMBER_FILL, PlanColors.NUMBER, NUMBER_STROKE_WIDTH)
        shapes +=
            PlanLabel(
                number.toString(),
                bx,
                by + NUMBER_TEXT_DROP,
                PlanColors.NUMBER,
                r * NUMBER_TEXT_SCALE,
                NUMBER_WEIGHT,
                middleBaseline = true,
            )
    }

    private fun arrow(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        color: Argb,
        roundCap: Boolean,
    ) {
        val angle = atan2(y1 - y0, x1 - x0)
        shapes += PlanLine(x0, y0, x1, y1, color, ARROW_LINE_WIDTH, roundCap = roundCap)
        shapes +=
            PlanPolygon(
                listOf(
                    Pair(x1, y1),
                    Pair(
                        x1 - ARROW_HEAD * cos(angle - ARROW_HEAD_ANGLE),
                        y1 - ARROW_HEAD * sin(angle - ARROW_HEAD_ANGLE),
                    ),
                    Pair(
                        x1 - ARROW_HEAD * cos(angle + ARROW_HEAD_ANGLE),
                        y1 - ARROW_HEAD * sin(angle + ARROW_HEAD_ANGLE),
                    ),
                ),
                color,
            )
    }
}
