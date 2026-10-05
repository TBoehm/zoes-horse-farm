package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.scene.toFixed
import app.zoeshorsefarm.shared.clamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Pure planning of the site (part of world-layout.js): fence, environment, paddock, plant areas.

// --- Fence --------------------------------------------------------------------------------------

/** Measures of the arena fence (m). `offset`: fence line outside the riding area. */
data class FenceSpec(
    val height: Double = 1.2,
    val offset: Double = 0.18,
    val spacing: Double = 2.5,
    val post: Double = 0.12,
    val board: Double = 0.04,
)

val FENCE = FenceSpec()

/** Gate on the long side facing the stable (x = -20): `side` of the arena (-1), center `z`, `width`. */
data class GateSpec(
    val side: Int,
    val z: Double,
    val width: Double,
)

val GATE = GateSpec(side = -1, z = 22.0, width = 3.6)

enum class FenceStyle(
    val id: String,
) {
    ARENA("arena"),
    WOOD("wood"),
    PADDOCK("paddock"),
}

data class FencePost(
    val x: Double,
    val z: Double,
    val style: FenceStyle,
)

/** A fence part between two posts: center, length, yaw. */
data class FenceSegment(
    val x: Double,
    val z: Double,
    val len: Double,
    val ang: Double,
    val style: FenceStyle,
)

/** The gate gap on the line x = [x] between [z0] and [z1]. */
data class GateGap(
    val x: Double,
    val z0: Double,
    val z1: Double,
)

class FencePlan(
    val posts: List<FencePost>,
    val segments: List<FenceSegment>,
    val gate: GateGap?,
)

/** A straight run of fence from [a] to [b] (x, z); [spacing] of the posts, null for the default 3 m. */
data class FenceRun(
    val a: Vec2,
    val b: Vec2,
    val spacing: Double? = null,
)

private const val DEFAULT_RUN_SPACING = 3.0

private class FenceBuilder {
    val posts = ArrayList<FencePost>()
    val segments = ArrayList<FenceSegment>()
}

private fun fenceRun(
    a: Vec2,
    b: Vec2,
    spacing: Double,
    out: FenceBuilder,
    style: FenceStyle,
) {
    val dx = b.x - a.x
    val dz = b.z - a.z
    val len = hypot(dx, dz)
    val n = max(1, ceil(len / spacing - 1e-6).toInt())
    for (i in 0..n) out.posts.add(FencePost(a.x + dx * i / n, a.z + dz * i / n, style))
    val ang = atan2(dx, dz)
    for (i in 0 until n) {
        val t = (i + 0.5) / n
        out.segments.add(FenceSegment(a.x + dx * t, a.z + dz * t, len / n, ang, style))
    }
}

/** Plan of all fence parts: arena fence with gate gap + path fences. */
fun planFence(pathFence: List<FenceRun> = emptyList()): FencePlan {
    val out = FenceBuilder()
    val hx = ARENA.width / 2 + FENCE.offset
    val hz = ARENA.length / 2 + FENCE.offset
    val g0 = GATE.z - GATE.width / 2
    val g1 = GATE.z + GATE.width / 2
    val gx = GATE.side * hx
    val arena = FenceStyle.ARENA
    fenceRun(Vec2(hx, -hz), Vec2(hx, hz), FENCE.spacing, out, arena)
    fenceRun(Vec2(hx, hz), Vec2(-hx, hz), FENCE.spacing, out, arena)
    fenceRun(Vec2(gx, hz), Vec2(gx, g1), FENCE.spacing, out, arena)
    fenceRun(Vec2(gx, g0), Vec2(gx, -hz), FENCE.spacing, out, arena)
    fenceRun(Vec2(-hx, -hz), Vec2(hx, -hz), FENCE.spacing, out, arena)
    for (run in pathFence) fenceRun(run.a, run.b, run.spacing ?: DEFAULT_RUN_SPACING, out, FenceStyle.WOOD)
    return FencePlan(uniquePosts(out.posts), out.segments, GateGap(gx, g0, g1))
}

/** Drops posts that stand at the same place (corners of runs that meet). */
private fun uniquePosts(posts: List<FencePost>): List<FencePost> {
    val seen = HashSet<String>()
    return posts.filter { seen.add("${it.x.toFixed(2)},${it.z.toFixed(2)}") }
}

// --- Environment ----------------------------------------------------------------------------

data class StableSite(
    val x: Double,
    val z: Double,
    val depth: Double,
    val length: Double,
)

data class HutSite(
    val x: Double,
    val z: Double,
)

/** A piece of path: center, width, length and yaw. */
data class PathPiece(
    val x: Double,
    val z: Double,
    val w: Double,
    val l: Double,
    val ry: Double = 0.0,
)

/** Placement of buildings, paths and path fences (world coordinates). */
class Site(
    val stable: StableSite,
    val hut: HutSite,
    val path: List<PathPiece>,
    val pathFence: List<FenceRun>,
)

val SITE =
    Site(
        stable = StableSite(x = -47.0, z = 20.0, depth = 10.0, length = 30.0),
        hut = HutSite(x = 25.6, z = -22.0),
        // path from the gate (x = -20, z = 22) to the stable yard
        path =
            listOf(
                PathPiece(x = -28.6, z = 22.0, w = 3.6, l = 16.4, ry = PI / 2),
                PathPiece(x = -38.8, z = 20.0, w = 6.4, l = 32.0),
            ),
        pathFence =
            listOf(
                FenceRun(Vec2(-21.2, 24.3), Vec2(-35.4, 24.3)),
                FenceRun(Vec2(-21.2, 19.7), Vec2(-35.4, 19.7)),
            ),
    )

const val HILL_START = 90.0

private fun smooth(
    x: Double,
    a: Double,
    b: Double,
): Double {
    val t = clamp((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** Terrain height: flat around the facility, hills towards the horizon. */
fun terrainHeight(
    x: Double,
    z: Double,
): Double {
    val r = hypot(x, z)
    if (r < HILL_START) return 0.0
    val th = atan2(z, x)
    val ang =
        0.55 +
            0.25 * sin(2 * th + 0.7) +
            0.15 * sin(5 * th + 2.1) +
            0.08 * sin(11 * th + 4.0)
    val rise = smooth(r, HILL_START, 260.0)
    val far = smooth(r, 220.0, 460.0)
    val roll = sin(x * 0.031 + 1.3) * cos(z * 0.027) * 3
    return rise * (6 + 26 * ang + roll) + far * 30 * ang
}

/**
 * Is a world point on the sand of the riding area (inside its fence)? Hooves dust only there; the
 * path to the stable and the meadow do not. [margin] > 0 keeps that far inside.
 */
fun isOnArenaSand(
    x: Double,
    z: Double,
    margin: Double = 0.0,
): Boolean =
    abs(x) <= ARENA.width / 2 + FENCE.offset - margin &&
        abs(z) <= ARENA.length / 2 + FENCE.offset - margin

// --- Paddock ------------------------------------------------------------------------------------

/**
 * The paddock: a fenced piece of meadow west of the arena, south of the path to the stable, so
 * that it is seen from the arena. A rectangle on flat ground (terrain height 0).
 * [x], [z]: center; [width] along the local u axis, [depth] along the local v axis (m, inside the
 * fence lines); [rotation]: yaw about +Y (rad, the same sense as an obstacle's `rot`; 0 = width
 * along world x). Grazing horses go inside: pick points with [paddockPoint] and test with
 * [paddockContains].
 */
data class Paddock(
    val x: Double,
    val z: Double,
    val width: Double,
    val depth: Double,
    val rotation: Double,
)

val PADDOCK = Paddock(x = -34.0, z = -9.0, width = 20.0, depth = 14.0, rotation = 0.0)

/**
 * World position of a point of the paddock. [u], [v] in [-1, 1] are fractions of the half width
 * and half depth from the center (0, 0 = center, +-1 = on the fence line).
 */
fun paddockPoint(
    u: Double,
    v: Double,
    paddock: Paddock = PADDOCK,
): Vec2 {
    val lx = u * paddock.width / 2
    val lz = v * paddock.depth / 2
    val c = cos(paddock.rotation)
    val s = sin(paddock.rotation)
    return Vec2(paddock.x + lx * c + lz * s, paddock.z - lx * s + lz * c)
}

/**
 * Is a world point inside the paddock? [margin] > 0 keeps that far away from the fence (for
 * animals), a negative margin grows the area.
 */
fun paddockContains(
    x: Double,
    z: Double,
    margin: Double = 0.0,
    paddock: Paddock = PADDOCK,
): Boolean {
    val dx = x - paddock.x
    val dz = z - paddock.z
    val c = cos(paddock.rotation)
    val s = sin(paddock.rotation)
    val lx = dx * c - dz * s
    val lz = dx * s + dz * c
    return abs(lx) <= paddock.width / 2 - margin && abs(lz) <= paddock.depth / 2 - margin
}

/** Fence of the paddock (style PADDOCK: three rails, higher posts), without a gate gap. */
fun planPaddockFence(paddock: Paddock = PADDOCK): FencePlan {
    val out = FenceBuilder()
    val corners =
        listOf(
            paddockPoint(1.0, -1.0, paddock),
            paddockPoint(1.0, 1.0, paddock),
            paddockPoint(-1.0, 1.0, paddock),
            paddockPoint(-1.0, -1.0, paddock),
        )
    corners.forEachIndexed { i, a ->
        val b = corners[(i + 1) % corners.size]
        fenceRun(a, b, FENCE.spacing, out, FenceStyle.PADDOCK)
    }
    return FencePlan(uniquePosts(out.posts), out.segments, gate = null)
}

// --- Areas without plants -------------------------------------------------------------------

/** An axis-aligned area around ([x], [z]) with half width [hw] (along x) and half depth [hd] (along z). */
private class BlockedArea(
    val x: Double,
    val z: Double,
    val hw: Double,
    val hd: Double,
)

/** Axis-aligned box around the (rotated) paddock plus a clearance. */
private fun paddockBlock(
    paddock: Paddock,
    clearance: Double,
): BlockedArea {
    val c = abs(cos(paddock.rotation))
    val s = abs(sin(paddock.rotation))
    return BlockedArea(
        x = paddock.x,
        z = paddock.z,
        hw = (paddock.width * c + paddock.depth * s) / 2 + clearance,
        hd = (paddock.width * s + paddock.depth * c) / 2 + clearance,
    )
}

/** Areas where no plants may stand. */
private val BLOCKED =
    listOf(
        BlockedArea(0.0, 0.0, ARENA.width / 2 + 2.5, ARENA.length / 2 + 2.5),
        BlockedArea(SITE.stable.x, SITE.stable.z, SITE.stable.depth / 2 + 2, SITE.stable.length / 2 + 2),
        BlockedArea(-38.8, 20.0, 4.0, 17.0),
        BlockedArea(-28.6, 22.0, 9.0, 3.0),
        BlockedArea(SITE.hut.x, SITE.hut.z, 3.5, 4.0),
        // benches
        BlockedArea(23.5, 12.0, 2.0, 9.0),
        paddockBlock(PADDOCK, 1.0),
    )

fun isBlocked(
    x: Double,
    z: Double,
    margin: Double = 0.0,
): Boolean = BLOCKED.any { abs(x - it.x) < it.hw + margin && abs(z - it.z) < it.hd + margin }

// at most this many tries per wanted point before scatter gives up
private const val SCATTER_TRIES_PER_POINT = 60

/** Random points in an annulus outside blocked areas. [rng] gives numbers in [0, 1). */
fun scatter(
    rng: () -> Double,
    count: Int,
    minR: Double,
    maxR: Double,
    margin: Double = 0.0,
    accept: ((Double, Double) -> Boolean)? = null,
): List<Vec2> {
    val out = ArrayList<Vec2>(count)
    var guard = 0
    while (out.size < count && guard < count * SCATTER_TRIES_PER_POINT) {
        guard += 1
        val a = rng() * PI * 2
        val r = sqrt(minR * minR + rng() * (maxR * maxR - minR * minR))
        val x = cos(a) * r
        val z = sin(a) * r
        if (!isBlocked(x, z, margin) && (accept == null || accept(x, z))) out.add(Vec2(x, z))
    }
    return out
}

/** Visible instances per quality level: mandatory instances + share of the rest. */
fun instanceCount(
    total: Int,
    priority: Int,
    density: Double,
): Int {
    val prio = clamp(priority, 0, total)
    val d = clamp(density, 0.0, 1.0)
    // JS Math.round: halves go up
    return minOf(total, prio + floor((total - prio) * d + 0.5).toInt())
}
