package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.IcosahedronGeometry
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.texture.createRng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Expected values were computed with the web app (three.js r186 in node, textures.js).

/** Position-weighted sum of a float attribute: catches changes of any value and of their order. */
private fun weighted(
    values: FloatArray,
    modulus: Int,
): Double {
    var s = 0.0
    for (i in values.indices) s += values[i] * ((i % modulus) + 1)
    return s
}

class GeometryBuilderTest {
    private fun builtOfThreeParts(): Geometry {
        val rng = createRng(3)
        val builder = GeometryBuilder()
        builder.add(
            BoxGeometry(1.0, 2.0, 3.0),
            0xb8643b,
            PartTransform(x = 1.0, y = 2.0, z = 3.0, ry = 0.5),
            jitter = 0.1,
            rng = rng,
        )
        builder.add(IcosahedronGeometry(1.0, 1), 0x477a33, jitter = 0.15, rng = rng)
        builder.add(boxOnGround(1.0, 1.0, 1.0), 0xffffff)
        return builder.build()
    }

    @Test
    fun `merges coloured parts into one non-indexed geometry with uv and bounds`() {
        val g = builtOfThreeParts()
        assertEquals(312, g.position.count)
        assertNull(g.index)
        assertEquals(312, g.uv.count)
        assertEquals(312, g.color.count)
        val sphere = assertNotNull(g.boundingSphere)
        assertEquals(3.494203964458535, sphere.radius, 1e-6)
        assertEquals(0.5789648294448853, sphere.center.x, 1e-6)
        assertEquals(1.0, sphere.center.y, 1e-6)
        assertEquals(1.778043270111084, sphere.center.z, 1e-6)
    }

    @Test
    fun `colours and positions reproduce the web app including the jitter of the rng`() {
        val g = builtOfThreeParts()
        assertEquals(804.4106074701995, weighted(g.color.array, 7), 1e-3)
        assertEquals(697.3655332922935, weighted(g.position.array, 5), 1e-3)
    }

    @Test
    fun `colours are the linear working colours of the sRGB input`() {
        val builder = GeometryBuilder()
        builder.add(boxOnGround(1.0, 1.0, 1.0), 0xffffff)
        val g = builder.build()
        assertEquals(1.0, g.color.getX(0), 1e-6)
        val half = GeometryBuilder()
        half.add(boxOnGround(1.0, 1.0, 1.0), 0x808080)
        // sRGB 0x80 is about 0.2158 in linear space
        assertEquals(0.2158605, half.build().color.getX(0), 1e-5)
    }

    @Test
    fun `counts the parts and starts over after build`() {
        val builder = GeometryBuilder()
        assertEquals(0, builder.count)
        builder.add(BoxGeometry(), 0xffffff)
        builder.add(BoxGeometry(), 0xff0000)
        assertEquals(2, builder.count)
        builder.build()
        assertEquals(0, builder.count)
        assertEquals(0, builder.build().attributes.size)
    }

    @Test
    fun `an indexed part is disposed when it is taken over`() {
        val box = BoxGeometry()
        GeometryBuilder().add(box, 0xffffff)
        assertEquals(1, box.disposeCount)
    }

    @Test
    fun `applies a matrix transform like the parts transform`() {
        val builder = GeometryBuilder()
        val matrix = Mat4().makeTranslation(5.0, 0.0, 0.0)
        val g = builder.add(boxOnGround(2.0, 2.0, 2.0), 0xffffff, matrix)
        g.computeBoundingBox()
        val box = assertNotNull(g.boundingBox)
        assertEquals(4.0, box.min.x, 1e-6)
        assertEquals(6.0, box.max.x, 1e-6)
    }

    @Test
    fun `an already coloured geometry is cloned and moved by addPainted`() {
        val painted = GeometryBuilder().let { it.add(BoxGeometry(), 0x336699) }
        val builder = GeometryBuilder()
        val copy = builder.addPainted(painted, Mat4().makeTranslation(0.0, 3.0, 0.0))
        assertFalse(copy === painted)
        assertEquals(painted.position.getY(0) + 3.0, copy.position.getY(0), 1e-6)
        assertEquals(painted.color.getX(0), copy.color.getX(0))
        assertEquals(1, builder.count)
    }

    @Test
    fun `a jitter needs an rng`() {
        assertFailsWith<IllegalArgumentException> {
            GeometryBuilder().add(BoxGeometry(), 0xffffff, jitter = 0.1)
        }
    }

    @Test
    fun `parts with other attributes cannot be merged`() {
        val builder = GeometryBuilder()
        builder.add(BoxGeometry(), 0xffffff)
        val odd = Geometry()
        odd.setAttribute("position", FloatAttribute(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), 3))
        builder.addPainted(odd)
        assertFailsWith<IllegalStateException> { builder.build() }
    }
}

class GeometryHelpersTest {
    @Test
    fun `boxOnGround has its bottom at y = 0`() {
        val g = boxOnGround(2.0, 3.0, 4.0)
        g.computeBoundingBox()
        val box = assertNotNull(g.boundingBox)
        assertEquals(0.0, box.min.y, 1e-9)
        assertEquals(3.0, box.max.y, 1e-9)
        assertEquals(2.0, box.max.x - box.min.x, 1e-9)
    }

    @Test
    fun `jitterVertices moves equal positions equally and reproduces the web app`() {
        val ico = IcosahedronGeometry(1.0, 1)
        jitterVertices(ico, 0.3, createRng(9))
        assertEquals(240, ico.position.count)
        assertEquals(69.30542615801096, weighted(ico.position.array, 11), 1e-3)
        assertEquals(-67.47812574636191, weighted(ico.normal.array, 13), 1e-3)
        // no holes: vertices that shared a place before still share one
        val seen = HashMap<String, Int>()
        for (i in 0 until ico.position.count) {
            val key = "${ico.position.getX(i)},${ico.position.getY(i)},${ico.position.getZ(i)}"
            seen[key] = (seen[key] ?: 0) + 1
        }
        assertTrue(seen.size < ico.position.count)
    }

    @Test
    fun `jitterVertices returns the geometry it was given`() {
        val g = IcosahedronGeometry(1.0, 0)
        assertTrue(jitterVertices(g, 0.1, createRng(1)) === g)
    }

    @Test
    fun `shadeByHeight darkens towards the bottom`() {
        val g = BoxGeometry(1.0, 2.0, 1.0).toNonIndexed()
        g.setAttribute("color", FloatAttribute(FloatArray(g.position.count * 3) { 0.5f }, 3))
        shadeByHeight(g, -1.0, 1.0)
        // vertex 0 is at the top (y = 1): factor 1.1; vertex 7 is at the bottom (y = -1): factor 0.55
        assertEquals(1.0, g.position.getY(0), 1e-9)
        assertEquals(0.55, g.color.getX(0), 1e-6)
        assertEquals(-1.0, g.position.getY(7), 1e-9)
        assertEquals(0.275, g.color.getX(7), 1e-6)
    }
}
