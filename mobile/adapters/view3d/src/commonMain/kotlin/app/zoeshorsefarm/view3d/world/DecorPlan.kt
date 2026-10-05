package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.domain.sim.ARENA
import app.zoeshorsefarm.view3d.FENCE
import app.zoeshorsefarm.view3d.FenceStyle
import app.zoeshorsefarm.view3d.GATE
import app.zoeshorsefarm.view3d.PADDOCK
import app.zoeshorsefarm.view3d.paddockPoint
import app.zoeshorsefarm.view3d.planFence
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

// Decoration of the riding facility: the bunting along the arena fence, the flower pots at the gate
// and the props in the paddock (pure, no scene model; the meshes are built in ArenaDecor.kt).

/** Colours of the pennants (sRGB hex). */
val BUNTING_COLORS: List<Int> = listOf(0xd94a4a, 0xf2c744, 0x3f7fd0, 0x4aa05a, 0xf4f1ea, 0xe98a2c)

// Technical values of the look (no game play)
private const val PENNANT_WIDTH = 0.26
private const val PENNANT_LENGTH = 0.32

// distance between two pennants along the string
private const val PENNANT_PITCH = 0.46
private const val STRING_HEIGHT_ABOVE_FENCE = 0.08

// sag of the string as a share of the span, and its limit
private const val SAG_SHARE = 0.07
private const val MAX_SAG = 0.2

/** A point of the bunting: a string end or a pennant on the string. */
data class Point3(
    val x: Double,
    val y: Double,
    val z: Double,
)

/** One string between two posts: [a] and [b] at the post tops, [sag] at the middle. */
data class BuntingString(
    val a: Point3,
    val b: Point3,
    val sag: Double,
)

/**
 * A pennant hanging from the string at ([x], [y], [z]); ([tx], [tz]) is the unit tangent along the
 * string. [span] is the string, [index] the place on it; a [core] pennant belongs to the thinner
 * string of the medium level.
 */
data class Pennant(
    val x: Double,
    val y: Double,
    val z: Double,
    val tx: Double,
    val tz: Double,
    val width: Double,
    val length: Double,
    val color: Int,
    val span: Int,
    val index: Int,
    val core: Boolean,
)

class BuntingPlan(
    val strings: List<BuntingString>,
    val pennants: List<Pennant>,
    val coreCount: Int,
)

/**
 * Bunting along the arena fence, one string per span between two posts (none across the gate).
 * Every second pennant (`core`) comes first: the first `coreCount` pennants are an evenly thinner
 * string, used by the medium level.
 */
fun planBunting(): BuntingPlan {
    val spans = planFence().segments.filter { it.style == FenceStyle.ARENA }
    val strings = ArrayList<BuntingString>()
    val all = ArrayList<Pennant>()
    val y = FENCE.height + STRING_HEIGHT_ABOVE_FENCE
    spans.forEachIndexed { spanIndex, span ->
        val tx = sin(span.ang)
        val tz = cos(span.ang)
        val a = Point3(span.x - tx * span.len / 2, y, span.z - tz * span.len / 2)
        val b = Point3(span.x + tx * span.len / 2, y, span.z + tz * span.len / 2)
        val sag = min(MAX_SAG, span.len * SAG_SHARE)
        strings.add(BuntingString(a, b, sag))
        val n = maxOf(1, floor(span.len / PENNANT_PITCH).toInt())
        for (index in 0 until n) {
            val s = (index + 0.5) / n
            all.add(
                Pennant(
                    x = a.x + (b.x - a.x) * s,
                    y = y - sag * 4 * s * (1 - s),
                    z = a.z + (b.z - a.z) * s,
                    tx = tx,
                    tz = tz,
                    width = PENNANT_WIDTH,
                    length = PENNANT_LENGTH,
                    color = BUNTING_COLORS[(spanIndex * 3 + index) % BUNTING_COLORS.size],
                    span = spanIndex,
                    index = index,
                    core = index % 2 == 0,
                ),
            )
        }
    }
    val core = all.filter { it.core }
    return BuntingPlan(strings, core + all.filter { !it.core }, core.size)
}

/** A flower pot at the gate: place, [scale] and the [flower] colour (sRGB hex). */
data class PotSpot(
    val x: Double,
    val z: Double,
    val scale: Double,
    val flower: Int,
)

/** Flower pots against the outside of the arena fence on both sides of the gate. */
fun planPots(): List<PotSpot> {
    val x = GATE.side * (ARENA.width / 2 + FENCE.offset) - 0.34
    val z0 = GATE.z - GATE.width / 2
    val z1 = GATE.z + GATE.width / 2
    return listOf(
        PotSpot(x, z0 - 0.75, 1.1, 0xe9719f),
        PotSpot(x - 0.05, z0 - 1.65, 0.85, 0xf2cf2e),
        PotSpot(x, z1 + 0.75, 1.1, 0xd8453b),
        PotSpot(x - 0.05, z1 + 1.65, 0.9, 0xf4f1ea),
    )
}

/** Place of a prop of the paddock; [rotation] is a yaw about +Y. */
data class PropSpot(
    val x: Double,
    val z: Double,
    val rotation: Double,
)

class PaddockProps(
    val shelter: PropSpot,
    val trough: PropSpot,
    val rack: PropSpot,
)

/**
 * Props inside the paddock: a field shelter against the far fence (its open side faces the middle
 * of the paddock), a water trough and a hay rack.
 */
fun planPaddockProps(): PaddockProps {
    val shelter = paddockPoint(-0.8, 0.0)
    val trough = paddockPoint(0.74, 0.8)
    val rack = paddockPoint(-0.35, -0.8)
    return PaddockProps(
        shelter = PropSpot(shelter.x, shelter.z, atan2(PADDOCK.x - shelter.x, PADDOCK.z - shelter.z)),
        trough = PropSpot(trough.x, trough.z, PADDOCK.rotation),
        rack = PropSpot(rack.x, rack.z, PADDOCK.rotation),
    )
}
