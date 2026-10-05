package app.zoeshorsefarm.view3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Meadow flowers: where the patches grow and which flower stands where (pure, no scene model).
// The placement is deterministic for a seeded rng and keeps off everything in `isBlocked`
// (sand, paths, buildings, benches, paddock).

/** A flower colour; the index in [FLOWER_COLORS] is what the flowers refer to. [hex] is sRGB. */
class FlowerColor(
    val name: String,
    val hex: Int,
)

val FLOWER_COLORS: List<FlowerColor> =
    listOf(
        FlowerColor("white", 0xf4f1e6),
        FlowerColor("yellow", 0xf2cf2e),
        FlowerColor("pink", 0xe9719f),
        FlowerColor("violet", 0x8a5bc8),
        FlowerColor("blue", 0x4f7fe0),
        FlowerColor("red", 0xd8453b),
    )

/**
 * Technical values of the placement (look of the meadow, no game play). [perPatch]: flowers per
 * patch (min, max); the random patches grow from [innerRadius] to [outerRadius], measured from
 * the arena center; [accentShare]: share of patches with a second colour; [accentFlowers]: share
 * of the flowers of such a patch in the second colour.
 */
data class MeadowOptions(
    val patchCount: Int = 44,
    val perPatch: ClosedFloatingPointRange<Double> = 55.0..110.0,
    val innerRadius: Double = 24.0,
    val outerRadius: Double = 72.0,
    val patchRadius: ClosedFloatingPointRange<Double> = 2.2..4.8,
    val accentShare: Double = 0.4,
    val accentFlowers: Double = 0.22,
    val scale: ClosedFloatingPointRange<Double> = 0.75..1.2,
)

/** A patch of flowers: center, radius, main [color] and optional second [accent] (indexes of [FLOWER_COLORS]). */
data class Patch(
    val x: Double,
    val z: Double,
    val radius: Double,
    val color: Int,
    val accent: Int?,
)

/** One flower: place, [scale], [yaw], [color] (index of [FLOWER_COLORS]) and the index of its [patch]. */
data class Flower(
    val x: Double,
    val z: Double,
    val scale: Double,
    val yaw: Double,
    val color: Int,
    val patch: Int,
)

data class MeadowPlan(
    val patches: List<Patch>,
    val flowers: List<Flower>,
)

// Fixed patches by the buildings, the paths and the paddock: x, z, radius
private val ANCHORS =
    listOf(
        doubleArrayOf(33.0, -19.5, 3.0),
        doubleArrayOf(29.5, -30.5, 3.2),
        doubleArrayOf(34.5, -27.0, 2.6),
        doubleArrayOf(-31.0, 9.0, 3.0),
        doubleArrayOf(-31.0, 34.0, 3.4),
        doubleArrayOf(-26.5, 30.0, 2.4),
        doubleArrayOf(-47.0, -9.0, 3.2),
        doubleArrayOf(-34.0, -19.8, 3.4),
        doubleArrayOf(-45.5, -14.5, 2.6),
        doubleArrayOf(26.5, 4.0, 2.8),
    )

private const val TWO_PI = 2 * PI

// tries per wanted patch before the placement gives up
private const val PATCH_TRIES = 80

// tries per flower to find a free spot in its patch
private const val FLOWER_TRIES = 8

// distance of patch centers (as a share of the sum of radii) and of a patch to blocked areas
private const val PATCH_GAP = 0.8
private const val PATCH_CLEARANCE = 0.5
private const val FLOWER_CLEARANCE = 0.4

// patches crowd towards the arena, where the camera looks
private const val NEAR_ARENA_BIAS = 1.4

private fun between(
    rng: () -> Double,
    range: ClosedFloatingPointRange<Double>,
): Double = range.start + rng() * (range.endInclusive - range.start)

private class Colors(
    val color: Int,
    val accent: Int?,
)

/** Picks the main and the second colour of a patch. */
private fun pickColors(
    rng: () -> Double,
    accentShare: Double,
): Colors {
    val color = floor(rng() * FLOWER_COLORS.size).toInt()
    var accent: Int? = null
    if (rng() < accentShare) {
        accent = (color + 1 + floor(rng() * (FLOWER_COLORS.size - 1)).toInt()) % FLOWER_COLORS.size
    }
    return Colors(color, accent)
}

private fun planPatches(
    rng: () -> Double,
    o: MeadowOptions,
): List<Patch> {
    val patches = ArrayList<Patch>()
    val fits = { x: Double, z: Double, radius: Double ->
        !isBlocked(x, z, radius * PATCH_CLEARANCE) &&
            patches.all { hypot(it.x - x, it.z - z) >= (it.radius + radius) * PATCH_GAP }
    }
    val add = { x: Double, z: Double, radius: Double ->
        val colors = pickColors(rng, o.accentShare)
        patches.add(Patch(x, z, radius, colors.color, colors.accent))
    }
    for ((x, z, radius) in ANCHORS) {
        if (patches.size < o.patchCount && !isBlocked(x, z, radius * PATCH_CLEARANCE)) add(x, z, radius)
    }
    var guard = 0
    while (patches.size < o.patchCount && guard < o.patchCount * PATCH_TRIES) {
        guard += 1
        val a = rng() * TWO_PI
        val r = o.innerRadius + (o.outerRadius - o.innerRadius) * rng().pow(NEAR_ARENA_BIAS)
        val x = cos(a) * r
        val z = sin(a) * r
        val radius = between(rng, o.patchRadius)
        if (fits(x, z, radius)) add(x, z, radius)
    }
    return patches
}

/** The flowers of one patch (fewer when a spot cannot be found), in the order they were planned. */
private fun planPatchFlowers(
    rng: () -> Double,
    patch: Patch,
    index: Int,
    o: MeadowOptions,
): List<Flower> {
    val count = jsRound(between(rng, o.perPatch))
    val list = ArrayList<Flower>(count)
    repeat(count) {
        var x = 0.0
        var z = 0.0
        var d = 0.0
        var placed = false
        var tries = 0
        while (tries < FLOWER_TRIES && !placed) {
            val a = rng() * TWO_PI
            d = patch.radius * sqrt(rng())
            x = patch.x + cos(a) * d
            z = patch.z + sin(a) * d
            placed = !isBlocked(x, z, FLOWER_CLEARANCE)
            tries += 1
        }
        if (placed) {
            // a little taller in the middle of the patch
            val center = 1 - d / patch.radius
            val accent = patch.accent
            val color = if (accent != null && rng() < o.accentFlowers) accent else patch.color
            val scale = between(rng, o.scale) + center * 0.15
            val yaw = rng() * TWO_PI
            list.add(Flower(x, z, scale, yaw, color, index))
        }
    }
    return list
}

/**
 * Plans the meadow: `patches` and `flowers`. The flowers are ordered round-robin over the
 * patches, so that the first n flowers are a thinner copy of the whole meadow: a lower quality
 * level just draws fewer. [options] has the number of patches, flowers per patch and the other
 * values of the placement.
 */
fun planMeadow(
    rng: () -> Double,
    options: MeadowOptions = MeadowOptions(),
): MeadowPlan {
    val patches = planPatches(rng, options)
    val lists = patches.mapIndexed { index, patch -> planPatchFlowers(rng, patch, index, options) }
    val flowers = ArrayList<Flower>(lists.sumOf { it.size })
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (k in 0 until longest) {
        for (list in lists) if (k < list.size) flowers.add(list[k])
    }
    return MeadowPlan(patches, flowers)
}

/**
 * Patches butterflies hover over: the ones closest to the arena (within sight), at most [count].
 */
fun butterflyAnchors(
    patches: List<Patch>,
    count: Int,
    maxDistance: Double = 60.0,
): List<PatchAnchor> =
    patches
        .filter { hypot(it.x, it.z) < maxDistance }
        .sortedBy { hypot(it.x, it.z) }
        .take(count)
        .map { PatchAnchor(it.x, it.z, it.radius) }
