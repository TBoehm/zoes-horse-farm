package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The value in hundredths (two decimals, without the sign of a tiny negative number). */
private fun twoDecimals(v: Double): Int = (v * 100).roundToInt()

private fun triangles(g: Geometry) = g.position.count / 3

private fun petalVertices(g: Geometry) = g.float("petal").array.count { it == 1f }

/** Position-weighted sum of an attribute (the expected values come from the web app, three.js r186). */
private fun weighted(
    values: FloatArray,
    modulus: Int,
): Double {
    var s = 0.0
    for (i in values.indices) s += values[i] * ((i % modulus) + 1)
    return s
}

private fun assertBox(
    g: Geometry,
    min: Triple<Double, Double, Double>,
    max: Triple<Double, Double, Double>,
) {
    g.computeBoundingBox()
    val box = assertNotNull(g.boundingBox)
    assertEquals(min.first, box.min.x, 1e-7)
    assertEquals(min.second, box.min.y, 1e-7)
    assertEquals(min.third, box.min.z, 1e-7)
    assertEquals(max.first, box.max.x, 1e-7)
    assertEquals(max.second, box.max.y, 1e-7)
    assertEquals(max.third, box.max.z, 1e-7)
}

class BuildFlowerGeometryTest {
    private val g = buildFlowerGeometry()

    @Test
    fun `is a cheap flower - stem leaf five petals and a heart`() {
        assertEquals(11, triangles(g))
        assertEquals(15, petalVertices(g))
    }

    @Test
    fun `has the attributes the blossom shader needs one value per vertex`() {
        val n = g.position.count
        assertEquals(n, g.color.count)
        assertEquals(n, g.float("petal").count)
        assertEquals(1, g.float("petal").itemSize)
        assertEquals(n, g.normal.count)
        assertNull(g.index)
    }

    @Test
    fun `stands on the ground a little taller than the grass tufts`() {
        g.computeBoundingBox()
        val box = assertNotNull(g.boundingBox)
        assertEquals(0.0, box.min.y, 1e-6)
        assertTrue(box.max.y > 0.55)
        assertTrue(box.max.y < 0.75)
        assertTrue(box.max.x - box.min.x < 0.3)
    }

    @Test
    fun `lights like the ground - normals up`() {
        for (i in 0 until g.normal.count) assertEquals(1.0, g.normal.getY(i))
    }

    @Test
    fun `keeps petals bright and stem and leaves green`() {
        val color = g.color
        val petal = g.float("petal")
        for (i in 0 until color.count) {
            if (petal.getX(i) == 1.0) {
                assertTrue(min(color.getX(i), min(color.getY(i), color.getZ(i))) > 0.7)
            }
        }
        // the first vertices belong to the stem: greener than red
        assertTrue(color.getY(0) > color.getX(0))
    }

    @Test
    fun `reproduces the geometry of the web app`() {
        assertEquals(52.79281748458743, weighted(g.position.array, 5), 1e-4)
        assertEquals(210.52940990775824, weighted(g.color.array, 7), 1e-4)
        assertEquals(30.0, weighted(g.float("petal").array, 3), 1e-9)
        assertEquals(198.0, weighted(g.normal.array, 11), 1e-9)
        assertBox(
            g,
            Triple(-0.08090169727802277, 0.0, -0.09510564804077148),
            Triple(0.11999999731779099, 0.6499999761581421, 0.09510564804077148),
        )
        val sphere = assertNotNull(g.boundingSphere)
        assertEquals(0.3452104863771732, sphere.radius, 1e-7)
        assertEquals(0.01954915001988411, sphere.center.x, 1e-7)
        assertEquals(0.32499998807907104, sphere.center.y, 1e-7)
    }
}

class BuildPlanterGeometryTest {
    private val g = buildPlanterGeometry()

    @Test
    fun `is a flower box with a row of blossoms cheap enough for every jump`() {
        assertTrue(triangles(g) > 60)
        assertTrue(triangles(g) < 190)
        assertTrue(petalVertices(g) > 0)
        assertTrue(petalVertices(g) < g.position.count)
    }

    @Test
    fun `is a low box long along the jump standing on the ground`() {
        g.computeBoundingBox()
        val box = assertNotNull(g.boundingBox)
        val size = box.getSize(Vec3())
        assertEquals(0.0, box.min.y, 1e-6)
        assertTrue(size.z > 0.9)
        assertTrue(size.z < 1.15)
        assertTrue(size.x < 0.4)
        assertTrue(size.y > 0.25)
        assertTrue(size.y < 0.45)
    }

    @Test
    fun `has a wooden box that ends exactly at the height the wind shader keeps rigid`() {
        val p = g.position
        val heights = mutableSetOf<Int>()
        for (i in 0 until p.count) {
            if (p.getY(i) <= PLANTER_BOX_HEIGHT + 0.001) heights.add(twoDecimals(p.getY(i)))
        }
        // box bottom and box top (the corner of a leaf dips into the box); soil and blossoms above
        assertEquals(listOf(0, 20), heights.sorted())
        assertEquals(0.2, PLANTER_BOX_HEIGHT)
    }

    @Test
    fun `keeps some blossoms in their own colour for a mixed planting`() {
        val colors = g.color
        val petal = g.float("petal")
        val own = HashSet<Int>()
        for (i in 0 until colors.count) {
            if (petal.getX(i) == 0.0 && colors.getX(i) > 0.8 &&
                colors.getZ(i) < 0.9
            ) {
                own.add(twoDecimals(colors.getX(i)))
            }
        }
        assertTrue(own.isNotEmpty())
    }

    @Test
    fun `reproduces the geometry of the web app`() {
        assertEquals(402, g.position.count)
        assertEquals(323.18302544988694, weighted(g.position.array, 5), 1e-3)
        assertEquals(2865.783463272266, weighted(g.color.array, 7), 1e-3)
        assertEquals(384.0, weighted(g.float("petal").array, 3), 1e-9)
        assertEquals(48.052560687065125, weighted(g.normal.array, 11), 1e-3)
        assertBox(
            g,
            Triple(-0.13500000536441803, 0.0, -0.5),
            Triple(0.13500000536441803, 0.3856799900531769, 0.5),
        )
        assertNull(g.index)
    }
}
