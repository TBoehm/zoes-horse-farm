package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.geometry.ConeGeometry
import app.zoeshorsefarm.scene.geometry.CylinderGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.IcosahedronGeometry
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.MathUtils
import app.zoeshorsefarm.view3d.GeometryBuilder
import app.zoeshorsefarm.view3d.HILL_START
import app.zoeshorsefarm.view3d.PartTransform
import app.zoeshorsefarm.view3d.jitterVertices
import app.zoeshorsefarm.view3d.shadeByHeight
import app.zoeshorsefarm.view3d.terrainHeight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Terrain and plant geometries of the environment (trees, bushes, grass tufts).

// --- Terrain -------------------------------------------------------------------------------

private val TERRAIN_RADII =
    doubleArrayOf(0.0, 24.0, 42.0, 60.0, 78.0, 96.0, 116.0, 140.0, 170.0, 205.0, 250.0, 300.0, 360.0, 430.0, 520.0)
private const val TERRAIN_SEGMENTS = 72
private const val UV_TILE = 6.0 // m per grass texture tile

/** Rings of triangles around the arena; the colour fades to haze towards the horizon. */
internal fun buildTerrain(rng: () -> Double): Geometry {
    val seg = TERRAIN_SEGMENTS
    val positions = ArrayList<Double>()
    val colors = ArrayList<Double>()
    val uvs = ArrayList<Double>()
    val indices = ArrayList<Int>()
    val c = Color()
    val base = Color(0xffffff)
    val haze = Color(0xa9bcb6)
    val dark = Color(0xc9d6b8)

    fun vertex(
        x: Double,
        z: Double,
    ) {
        val y = (if (hypot(x, z) < HILL_START) -0.03 else 0.0) + terrainHeight(x, z)
        positions.add(x)
        positions.add(y)
        positions.add(z)
        uvs.add(x / UV_TILE)
        uvs.add(-z / UV_TILE)
        val r = hypot(x, z)
        val n = 0.5 + 0.5 * sin(x * 0.09 + rng() * 0.6) * cos(z * 0.07)
        c.copy(base).lerp(dark, n * 0.6)
        c.lerp(haze, MathUtils.smoothstep(r, 160.0, 480.0) * 0.85)
        colors.add(c.r)
        colors.add(c.g)
        colors.add(c.b)
    }
    vertex(0.0, 0.0)
    for (i in 1 until TERRAIN_RADII.size) {
        for (k in 0 until seg) {
            val a = k.toDouble() / seg * PI * 2
            vertex(cos(a) * TERRAIN_RADII[i], sin(a) * TERRAIN_RADII[i])
        }
    }
    for (k in 0 until seg) indices.addAll(listOf(0, 1 + (k + 1) % seg, 1 + k))
    for (i in 1 until TERRAIN_RADII.size - 1) {
        val r0 = 1 + (i - 1) * seg
        val r1 = 1 + i * seg
        for (k in 0 until seg) {
            val k1 = (k + 1) % seg
            indices.addAll(listOf(r0 + k, r0 + k1, r1 + k, r0 + k1, r1 + k1, r1 + k))
        }
    }
    val g = Geometry()
    g.setAttribute("position", FloatAttribute(positions, 3))
    g.setAttribute("uv", FloatAttribute(uvs, 2))
    g.setAttribute("color", FloatAttribute(colors, 3))
    g.setIndex(indices)
    g.computeVertexNormals()
    return g
}

// --- Plant geometries ------------------------------------------------------------------------

private class Blob(
    val x: Double,
    val y: Double,
    val z: Double,
    val r: Double,
    val color: Int,
)

private val DECIDUOUS_BLOBS =
    listOf(
        Blob(0.0, 4.7, 0.0, 2.4, 0x4d7a2f),
        Blob(1.2, 3.9, 0.5, 1.8, 0x56832f),
        Blob(-1.0, 4.0, -0.6, 1.9, 0x47722b),
        Blob(0.1, 5.7, -0.2, 1.6, 0x5c8a34),
    )

private const val TRUNK_COLOR = 0x5a4532

/** A broadleaf tree: trunk, two branches and blobs of leaves. [detail] 0 is the cheap model. */
internal fun deciduousGeometry(
    rng: () -> Double,
    detail: Int = 1,
): Geometry {
    val b = GeometryBuilder()
    val trunk = CylinderGeometry(0.15, 0.26, 3.4, if (detail != 0) 7 else 5, 1, true)
    trunk.translate(0.0, 1.7, 0.0)
    b.add(trunk, TRUNK_COLOR)
    val branch = CylinderGeometry(0.05, 0.1, 1.6, 5)
    branch.translate(0.0, 0.8, 0.0)
    if (detail != 0) {
        b.add(branch.clone(), TRUNK_COLOR, PartTransform(y = 2.6, rz = 0.8))
        b.add(branch, TRUNK_COLOR, PartTransform(y = 2.8, rz = -0.7, ry = 1.2))
    }
    for (blob in if (detail != 0) DECIDUOUS_BLOBS else DECIDUOUS_BLOBS.take(3)) {
        val ico = IcosahedronGeometry(blob.r, detail)
        jitterVertices(ico, blob.r * 0.35, rng)
        val g = b.add(ico, blob.color, PartTransform(x = blob.x, y = blob.y, z = blob.z), jitter = 0.12, rng = rng)
        shadeByHeight(g, 2.4, 7.0, 0.5, 1.12)
    }
    return b.build()
}

/** A tier of a conifer: radius, height and the height of its bottom. */
private class Tier(
    val r: Double,
    val h: Double,
    val y: Double,
)

private val CONIFER_TIERS_HIGH =
    listOf(Tier(2.0, 3.2, 1.0), Tier(1.55, 2.8, 2.7), Tier(1.1, 2.4, 4.3), Tier(0.65, 1.9, 5.8))
private val CONIFER_TIERS_LOW = listOf(Tier(1.9, 3.6, 1.0), Tier(1.3, 3.2, 3.2), Tier(0.75, 2.6, 5.2))

/** A conifer: trunk and cones. [detail] 0 is the cheap model (the distant forest uses it). */
internal fun coniferGeometry(
    rng: () -> Double,
    detail: Int = 1,
): Geometry {
    val b = GeometryBuilder()
    val trunk = CylinderGeometry(0.1, 0.2, 1.8, if (detail != 0) 6 else 4, 1, true)
    trunk.translate(0.0, 0.9, 0.0)
    b.add(trunk, 0x4e3b2a)
    for (tier in if (detail != 0) CONIFER_TIERS_HIGH else CONIFER_TIERS_LOW) {
        val cone = ConeGeometry(tier.r, tier.h, if (detail != 0) 8 else 6, 1, true)
        jitterVertices(cone, 0.25, rng)
        cone.translate(0.0, tier.y + tier.h / 2, 0.0)
        val g = b.add(cone, 0x2e5230, jitter = 0.1, rng = rng)
        shadeByHeight(g, 1.0, 7.7, 0.55, 1.15)
    }
    return b.build()
}

/** A bush: two blobs. [detail] 0 is the cheap model. */
internal fun bushGeometry(
    rng: () -> Double,
    detail: Int = 1,
): Geometry {
    val b = GeometryBuilder()
    for (blob in listOf(Blob(0.0, 0.55, 0.0, 0.85, 0x4a742d), Blob(0.55, 0.42, 0.2, 0.62, 0x4a742d))) {
        val ico = IcosahedronGeometry(blob.r, detail)
        jitterVertices(ico, blob.r * 0.4, rng)
        val g = b.add(ico, blob.color, PartTransform(x = blob.x, y = blob.y, z = blob.z), jitter = 0.15, rng = rng)
        shadeByHeight(g, 0.0, 1.4, 0.5, 1.1)
    }
    return b.build()
}

private const val TUFT_BLADES = 5
private const val TUFT_BLADE_WIDTH = 0.035

/** A grass tuft: five leaning blades (one triangle each); normals point up, so tufts are lit like the ground. */
internal fun tuftGeometry(rng: () -> Double): Geometry {
    val positions = ArrayList<Double>()
    val colors = ArrayList<Double>()
    val base = Color(0x40602a)
    val tip = Color(0x9bb760)
    for (i in 0 until TUFT_BLADES) {
        val a = i.toDouble() / TUFT_BLADES * PI * 2 + rng() * 0.6
        val h = 0.22 + rng() * 0.25
        val lean = 0.06 + rng() * 0.1
        val cx = cos(a) * 0.04
        val cz = sin(a) * 0.04
        val px = -sin(a) * TUFT_BLADE_WIDTH
        val pz = cos(a) * TUFT_BLADE_WIDTH
        positions.addAll(listOf(cx - px, 0.0, cz - pz, cx + px, 0.0, cz + pz))
        positions.addAll(listOf(cx + cos(a) * lean, h, cz + sin(a) * lean))
        colors.addAll(listOf(base.r, base.g, base.b, base.r, base.g, base.b, tip.r, tip.g, tip.b))
    }
    val g = Geometry()
    g.setAttribute("position", FloatAttribute(positions, 3))
    g.setAttribute("color", FloatAttribute(colors, 3))
    val normals = FloatArray(positions.size)
    for (i in 1 until normals.size step 3) normals[i] = 1f
    g.setAttribute("normal", FloatAttribute(normals, 3))
    g.setAttribute("uv", FloatAttribute(FloatArray(positions.size / 3 * 2), 2))
    return g
}
