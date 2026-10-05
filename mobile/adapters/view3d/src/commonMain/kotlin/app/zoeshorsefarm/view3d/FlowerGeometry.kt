package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.OctahedronGeometry
import app.zoeshorsefarm.scene.geometry.PlaneGeometry
import app.zoeshorsefarm.scene.geometry.TetrahedronGeometry
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Low-poly geometry of the meadow flowers and of the flower boxes at the jump standards. Both carry
// a `petal` attribute (1 on the blossoms that take the colour of the instance, 0 elsewhere) for the
// wind effect `patchBlossoms`. Procedural, nothing is loaded (rule 2).

private val tmp = Color()

/** A growing list of floats (the vertex data is Float32 in the end, so values are narrowed on the way in). */
private class FloatList {
    var data = FloatArray(INITIAL_CAPACITY)
    var size = 0

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v.toFloat()
    }

    fun add(v: DoubleArray) {
        for (x in v) add(x)
    }

    fun toArray(): FloatArray = data.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}

/** Collects triangles with vertex colours and the `petal` weight; build() makes the geometry. */
private class BlossomBuilder {
    private val positions = FloatList()
    private val colors = FloatList()
    private val petals = FloatList()

    fun color(
        hex: Int,
        shade: Double = 1.0,
    ): DoubleArray {
        tmp.set(hex)
        return doubleArrayOf(tmp.r * shade, tmp.g * shade, tmp.b * shade)
    }

    /** One triangle: three points [x, y, z] and three colours [r, g, b]. */
    fun tri(
        points: Array<DoubleArray>,
        cols: Array<DoubleArray>,
        petal: Double = 0.0,
    ) {
        for (p in points) positions.add(p)
        for (c in cols) colors.add(c)
        repeat(3) { petals.add(petal) }
    }

    /** A geometry with a uniform colour, moved by [matrix]. */
    fun part(
        geometry: Geometry,
        matrix: Mat4,
        hex: Int,
        petal: Double = 0.0,
    ) {
        val g = geometry.toNonIndexed().let { if (it === geometry) geometry.clone() else it }
        g.applyMatrix4(matrix)
        val p = g.position
        val c = color(hex)
        for (i in 0 until p.count) {
            positions.add(p.getX(i))
            positions.add(p.getY(i))
            positions.add(p.getZ(i))
            colors.add(c)
            petals.add(petal)
        }
        g.dispose()
        geometry.dispose()
    }

    fun build(upNormals: Boolean = false): Geometry {
        val g = Geometry()
        val position = positions.toArray()
        g.setAttribute("position", FloatAttribute(position, 3))
        g.setAttribute("color", FloatAttribute(colors.toArray(), 3))
        g.setAttribute("petal", FloatAttribute(petals.toArray(), 1))
        if (upNormals) {
            // lit like the ground, so that a flower does not turn dark on its shaded side
            val normals = FloatArray(position.size)
            for (i in 1 until normals.size step 3) normals[i] = 1f
            g.setAttribute("normal", FloatAttribute(normals, 3))
        } else {
            g.computeVertexNormals()
        }
        g.computeBoundingSphere()
        return g
    }
}

private fun pt(
    x: Double,
    y: Double,
    z: Double,
) = doubleArrayOf(x, y, z)

private object FlowerShape {
    const val HEIGHT = 0.6
    const val PETALS = 5
    const val PETAL_RADIUS = 0.1
    const val PETAL_HALF_WIDTH = 0.042
    const val PETAL_LIFT = 0.05
    const val STEM = 0x4f7a2f
    const val HEART = 0xe8b923
}

private fun addStemAndLeaf(b: BlossomBuilder) {
    val h = FlowerShape.HEIGHT
    val dark = b.color(FlowerShape.STEM, 0.7)
    val light = b.color(FlowerShape.STEM, 1.15)
    // stem: one quad (two triangles), double-sided in the material
    val w = 0.012
    b.tri(arrayOf(pt(-w, 0.0, 0.0), pt(w, 0.0, 0.0), pt(w * 0.6, h, 0.0)), arrayOf(dark, dark, light))
    b.tri(arrayOf(pt(-w, 0.0, 0.0), pt(w * 0.6, h, 0.0), pt(-w * 0.6, h, 0.0)), arrayOf(dark, light, light))
    // leaf
    b.tri(arrayOf(pt(0.0, 0.1, 0.0), pt(0.12, 0.25, 0.02), pt(0.02, 0.17, -0.02)), arrayOf(dark, light, dark))
}

private fun addPetals(b: BlossomBuilder) {
    val h = FlowerShape.HEIGHT
    val base = doubleArrayOf(0.78, 0.78, 0.78)
    val tip = doubleArrayOf(1.0, 1.0, 1.0)
    for (i in 0 until FlowerShape.PETALS) {
        val a = i.toDouble() / FlowerShape.PETALS * PI * 2
        val cx = cos(a)
        val cz = sin(a)
        val px = -cz * FlowerShape.PETAL_HALF_WIDTH
        val pz = cx * FlowerShape.PETAL_HALF_WIDTH
        b.tri(
            arrayOf(
                pt(cx * 0.015 - px, h, cz * 0.015 - pz),
                pt(cx * 0.015 + px, h, cz * 0.015 + pz),
                pt(cx * FlowerShape.PETAL_RADIUS, h + FlowerShape.PETAL_LIFT, cz * FlowerShape.PETAL_RADIUS),
            ),
            arrayOf(base, base, tip),
            petal = 1.0,
        )
    }
}

private fun addHeart(b: BlossomBuilder) {
    val h = FlowerShape.HEIGHT
    val heart = b.color(FlowerShape.HEART)
    val r = 0.028
    for (i in 0 until 3) {
        val a0 = i / 3.0 * PI * 2
        val a1 = (i + 1) / 3.0 * PI * 2
        b.tri(
            arrayOf(
                pt(0.0, h + 0.02, 0.0),
                pt(cos(a0) * r, h + 0.01, sin(a0) * r),
                pt(
                    cos(a1) * r,
                    h + 0.01,
                    sin(a1) * r,
                ),
            ),
            arrayOf(heart, heart, heart),
        )
    }
}

/**
 * One meadow flower (11 triangles): a thin stem, one leaf, five petals bent up into a cup and a
 * small heart. Stands at the origin, about 0.6 m tall (taller than the grass); the petals are
 * white-ish and take the colour of the instance.
 */
fun buildFlowerGeometry(): Geometry {
    val b = BlossomBuilder()
    addStemAndLeaf(b)
    addPetals(b)
    addHeart(b)
    return b.build(upNormals = true)
}

/** Height (m) of the wooden box of a flower box: it stays rigid, the plants above it sway. */
const val PLANTER_BOX_HEIGHT = 0.2

private object PlanterShape {
    // across the jump (x)
    const val WIDTH = 0.27
    const val HEIGHT = PLANTER_BOX_HEIGHT

    // along the jump (z)
    const val LENGTH = 1.0
    const val WOOD = 0x6f4a2e
    const val SOIL = 0x3b2c20
    const val LEAF = 0x3f7a35
    val ACCENT = intArrayOf(0xf4f1e6, 0xf2cf2e)
}

private const val PLANTER_SLOTS = 10

private val matrix = Mat4()
private val noRotation = Quat()
private val position = Vec3()
private val scale = Vec3()

private fun at(
    x: Double,
    y: Double,
    z: Double,
    s: Double = 1.0,
): Mat4 = matrix.compose(position.set(x, y, z), noRotation, scale.set(s, s, s))

/**
 * A flower box: wooden box with soil, leaves and a row of pompom blossoms (about 130 triangles).
 * The blossoms take the colour of the instance; a few white and yellow ones keep their own, so a
 * box looks like a mixed planting. Stands on the ground at the origin, long side along z.
 */
fun buildPlanterGeometry(): Geometry {
    val b = BlossomBuilder()
    val h = PlanterShape.HEIGHT
    val l = PlanterShape.LENGTH
    b.part(BoxGeometry(PlanterShape.WIDTH, h, l), at(0.0, h / 2, 0.0), PlanterShape.WOOD)
    val soil = PlaneGeometry(PlanterShape.WIDTH - 0.04, l - 0.04).rotateX(-PI / 2)
    b.part(soil, at(0.0, h + 0.004, 0.0), PlanterShape.SOIL)
    for (i in 0 until PLANTER_SLOTS) {
        val z = -l / 2 + 0.08 + i.toDouble() / (PLANTER_SLOTS - 1) * (l - 0.16)
        val x = (if (i % 2 == 0) -1 else 1) * 0.045
        // leaves below, blossom on top
        b.part(TetrahedronGeometry(0.07), at(x, h + 0.04, z), PlanterShape.LEAF)
        val accent = i % 4 == 2
        b.part(
            OctahedronGeometry(0.062),
            at(x, h + 0.115, z, 0.9 + (i % 3) * 0.12),
            if (accent) PlanterShape.ACCENT[(i shr 2) % PlanterShape.ACCENT.size] else 0xffffff,
            if (accent) 0.0 else 1.0,
        )
    }
    return b.build()
}
