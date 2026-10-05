package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.POLE_LENGTH
import app.zoeshorsefarm.domain.sim.STAND_WIDTH
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.sim.axisOf
import app.zoeshorsefarm.domain.sim.toLocal
import kotlin.math.abs
import kotlin.math.hypot

// Geometric check of obstacle layouts (courses and free mode).
// Test oracle (CoursesTest, LayoutCheckTest), never part of the game build; pure.

/** Limits a layout has to respect (meters). */
data class LayoutLimits(
    // m of straight approach before the leading edge
    val approach: Double = 14.0,
    // m clear after landing (rear pole)
    val landing: Double = 8.0,
    val corridorHalfWidth: Double = POLE_LENGTH / 2 + 1,
    // m from the element to the fence
    val fenceClearance: Double = 4.0,
    // m between elements of different obstacles
    val minGap: Double = 3.0,
    // m between start/finish line and elements
    val lineClearance: Double = 2.0,
)

val LAYOUT_LIMITS = LayoutLimits()

private val HALF_X = ARENA.width / 2
private val HALF_Z = ARENA.length / 2

/** Oriented rectangle: center, axis u (unit vector), half extent along u and across. */
data class LayoutRect(
    val cx: Double,
    val cz: Double,
    val ux: Double,
    val uz: Double,
    val halfAlong: Double,
    val halfAcross: Double,
)

/** A start/finish line to check, with a name for the messages. */
data class LayoutLine(
    val name: String,
    val a: Vec2,
    val b: Vec2,
)

private fun rect(
    cx: Double,
    cz: Double,
    u: Vec2,
    halfAlong: Double,
    halfAcross: Double,
) = LayoutRect(cx, cz, u.x, u.z, halfAlong, halfAcross)

private fun rectCorners(r: LayoutRect): List<Vec2> {
    val vx = -r.uz
    val vz = r.ux
    val signs = listOf(1 to 1, 1 to -1, -1 to -1, -1 to 1)
    return signs.map { (sa, sc) ->
        Vec2(
            r.cx + r.ux * r.halfAlong * sa + vx * r.halfAcross * sc,
            r.cz + r.uz * r.halfAlong * sa + vz * r.halfAcross * sc,
        )
    }
}

private fun grow(
    r: LayoutRect,
    margin: Double,
) = r.copy(halfAlong = r.halfAlong + margin, halfAcross = r.halfAcross + margin)

/** Footprint of an element (poles + stands, for oxers including the depth). */
fun footprint(element: Element): LayoutRect =
    rect(
        element.x,
        element.z,
        axisOf(element),
        element.spread / 2 + 0.3,
        POLE_LENGTH / 2 + STAND_WIDTH,
    )

/**
 * Approach corridor of an obstacle: straight approach before the first element up to clear
 * space after landing behind the last. Undirected (free mode): approach from both directions.
 */
fun corridorOf(
    obstacle: Obstacle,
    limits: LayoutLimits = LAYOUT_LIMITS,
): LayoutRect {
    val first = obstacle.elements.first()
    val last = obstacle.elements.last()
    val n = axisOf(first)
    val lastAlong = toLocal(first, last.x, last.z).along
    val front = -first.spread / 2 - limits.approach
    val back = lastAlong + last.spread / 2 + (if (obstacle.directed) limits.landing else limits.approach)
    val mid = (front + back) / 2
    return rect(
        first.x + n.x * mid,
        first.z + n.z * mid,
        n,
        (back - front) / 2,
        limits.corridorHalfWidth,
    )
}

private fun project(
    corners: List<Vec2>,
    ax: Double,
    az: Double,
): Pair<Double, Double> {
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    for (p in corners) {
        val v = p.x * ax + p.z * az
        if (v < min) min = v
        if (v > max) max = v
    }
    return min to max
}

/** Do two oriented rectangles overlap (separating axis test)? */
fun rectsOverlap(
    a: LayoutRect,
    b: LayoutRect,
): Boolean {
    val ca = rectCorners(a)
    val cb = rectCorners(b)
    val axes = listOf(Vec2(a.ux, a.uz), Vec2(-a.uz, a.ux), Vec2(b.ux, b.uz), Vec2(-b.uz, b.ux))
    for (axis in axes) {
        val (minA, maxA) = project(ca, axis.x, axis.z)
        val (minB, maxB) = project(cb, axis.x, axis.z)
        if (maxA <= minB || maxB <= minA) return false
    }
    return true
}

/** Does the segment p -> q intersect the rectangle? (treated as a very thin rectangle) */
fun segmentHitsRect(
    p: Vec2,
    q: Vec2,
    r: LayoutRect,
): Boolean {
    val dx = q.x - p.x
    val dz = q.z - p.z
    val len = hypot(dx, dz)
    val seg = rect((p.x + q.x) / 2, (p.z + q.z) / 2, Vec2(dx / len, dz / len), len / 2, 1e-3)
    return rectsOverlap(seg, r)
}

private fun insideArena(
    p: Vec2,
    margin: Double,
) = abs(p.x) <= HALF_X - margin + 1e-9 && abs(p.z) <= HALF_Z - margin + 1e-9

private class PlacedElement(
    val obstacleIndex: Int,
    val element: Element,
    val fp: LayoutRect,
)

private fun checkFences(
    all: List<PlacedElement>,
    limits: LayoutLimits,
    issues: MutableList<String>,
) {
    for (placed in all) {
        if (!rectCorners(placed.fp).all { insideArena(it, limits.fenceClearance) }) {
            issues.add("${placed.element.id}: less than ${fmt(limits.fenceClearance)} m from the fence")
        }
    }
}

private fun checkCorridors(
    obstacles: List<Obstacle>,
    all: List<PlacedElement>,
    limits: LayoutLimits,
    issues: MutableList<String>,
) {
    obstacles.forEachIndexed { oi, obstacle ->
        val corridor = corridorOf(obstacle, limits)
        val label = obstacle.elements[0].id
        if (!rectCorners(corridor).all { insideArena(it, 0.0) }) {
            issues.add("$label: approach corridor extends outside the arena")
        }
        for (other in all) {
            if (other.obstacleIndex != oi && rectsOverlap(corridor, other.fp)) {
                issues.add("$label: ${other.element.id} is in the approach corridor")
            }
        }
    }
}

private fun checkGaps(
    all: List<PlacedElement>,
    limits: LayoutLimits,
    issues: MutableList<String>,
) {
    val half = limits.minGap / 2
    for (i in all.indices) {
        for (j in i + 1 until all.size) {
            val different = all[i].obstacleIndex != all[j].obstacleIndex
            if (different && rectsOverlap(grow(all[i].fp, half), grow(all[j].fp, half))) {
                issues.add("${all[i].element.id}/${all[j].element.id}: too close together")
            }
        }
    }
}

private fun checkLine(
    line: LayoutLine,
    obstacles: List<Obstacle>,
    all: List<PlacedElement>,
    limits: LayoutLimits,
    issues: MutableList<String>,
) {
    for (p in listOf(line.a, line.b)) {
        if (!insideArena(p, 3.0)) issues.add("${line.name}: end point too close to the fence")
    }
    for (placed in all) {
        if (segmentHitsRect(line.a, line.b, grow(placed.fp, limits.lineClearance))) {
            issues.add("${line.name}: too close to ${placed.element.id}")
        }
    }
    for (obstacle in obstacles) {
        if (segmentHitsRect(line.a, line.b, corridorOf(obstacle, limits))) {
            issues.add("${line.name}: crosses the approach corridor of ${obstacle.elements[0].id}")
        }
    }
}

/**
 * Checks a layout and returns a list of problems (empty = fine).
 * [lines]: optional start/finish lines.
 */
fun checkLayout(
    obstacles: List<Obstacle>,
    lines: List<LayoutLine> = emptyList(),
    limits: LayoutLimits = LAYOUT_LIMITS,
): List<String> {
    val issues = ArrayList<String>()
    val all = ArrayList<PlacedElement>()
    obstacles.forEachIndexed { oi, obstacle ->
        for (element in obstacle.elements) all.add(PlacedElement(oi, element, footprint(element)))
    }
    checkFences(all, limits, issues)
    checkCorridors(obstacles, all, limits, issues)
    checkGaps(all, limits, issues)
    for (line in lines) checkLine(line, obstacles, all, limits, issues)
    return issues
}

/** JS prints whole numbers without a decimal point; so do the messages. */
private fun fmt(v: Double): String = if (v == kotlin.math.floor(v)) v.toLong().toString() else v.toString()
