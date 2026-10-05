package app.zoeshorsefarm.render.filament.mesh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InstanceDataTest {
    private fun translation(
        x: Float,
        y: Float,
        z: Float,
    ) = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

    private fun texel(
        texture: FloatArray,
        x: Int,
        y: Int,
    ): List<Float> {
        val o = (y * InstanceData.TEXTURE_WIDTH + x) * 4
        return texture.slice(o until o + 4)
    }

    @Test
    fun `a row holds 256 instances of 4 texels`() {
        assertEquals(4, InstanceData.TEXELS_PER_INSTANCE)
        assertEquals(256, InstanceData.INSTANCES_PER_ROW)
        assertEquals(1024, InstanceData.TEXTURE_WIDTH)
    }

    @Test
    fun `the number of rows rounds up`() {
        assertEquals(1, InstanceData.rowsFor(1))
        assertEquals(1, InstanceData.rowsFor(256))
        assertEquals(2, InstanceData.rowsFor(257))
        assertEquals(43, InstanceData.rowsFor(11_000))
    }

    @Test
    fun `an empty table still has one row so the texture can be created`() {
        assertEquals(1, InstanceData.rowsFor(0))
    }

    @Test
    fun `the texture size follows from capacity and four floats of four bytes per texel`() {
        assertEquals(1024L * 43 * 16, InstanceData.byteSize(11_000))
    }

    @Test
    fun `a capacity beyond the texture limit is rejected`() {
        assertFailsWith<IllegalArgumentException> { InstanceData.rowsFor(InstanceData.MAX_INSTANCES + 1) }
        assertEquals(InstanceData.MAX_ROWS, InstanceData.rowsFor(InstanceData.MAX_INSTANCES))
    }

    @Test
    fun `an instance is stored as the three rows of its affine matrix`() {
        val out = FloatArray(InstanceData.floatCount(1))
        InstanceData.pack(out, translation(10f, 20f, 30f), colors = null, colorComponents = 3, from = 0, to = 1)
        assertEquals(listOf(1f, 0f, 0f, 10f), texel(out, 0, 0))
        assertEquals(listOf(0f, 1f, 0f, 20f), texel(out, 1, 0))
        assertEquals(listOf(0f, 0f, 1f, 30f), texel(out, 2, 0))
    }

    @Test
    fun `rotation and scale land in the left three columns of the rows`() {
        // column-major: x axis (1, 2, 3), y axis (4, 5, 6), z axis (7, 8, 9), translation (10, 11, 12)
        val m = floatArrayOf(1f, 2f, 3f, 0f, 4f, 5f, 6f, 0f, 7f, 8f, 9f, 0f, 10f, 11f, 12f, 1f)
        val out = FloatArray(InstanceData.floatCount(1))
        InstanceData.pack(out, m, null, 3, 0, 1)
        assertEquals(listOf(1f, 4f, 7f, 10f), texel(out, 0, 0))
        assertEquals(listOf(2f, 5f, 8f, 11f), texel(out, 1, 0))
        assertEquals(listOf(3f, 6f, 9f, 12f), texel(out, 2, 0))
    }

    @Test
    fun `without colours the fourth texel is opaque white`() {
        val out = FloatArray(InstanceData.floatCount(1))
        InstanceData.pack(out, translation(0f, 0f, 0f), null, 3, 0, 1)
        assertEquals(listOf(1f, 1f, 1f, 1f), texel(out, 3, 0))
    }

    @Test
    fun `three component colours get an alpha of one`() {
        val out = FloatArray(InstanceData.floatCount(2))
        val matrices = translation(0f, 0f, 0f) + translation(1f, 1f, 1f)
        InstanceData.pack(out, matrices, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f), 3, 0, 2)
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 1f), texel(out, 3, 0))
        assertEquals(listOf(0.4f, 0.5f, 0.6f, 1f), texel(out, 7, 0))
    }

    @Test
    fun `four component colours keep their alpha`() {
        val out = FloatArray(InstanceData.floatCount(1))
        InstanceData.pack(out, translation(0f, 0f, 0f), floatArrayOf(0.1f, 0.2f, 0.3f, 0.7f), 4, 0, 1)
        assertEquals(listOf(0.1f, 0.2f, 0.3f, 0.7f), texel(out, 3, 0))
    }

    @Test
    fun `instance 256 starts the second row`() {
        val count = 257
        val matrices = FloatArray(count * 16)
        for (i in 0 until count) translation(i.toFloat(), 0f, 0f).copyInto(matrices, i * 16)
        val out = FloatArray(InstanceData.floatCount(count))
        InstanceData.pack(out, matrices, null, 3, 0, count)
        assertEquals(255f, texel(out, 255 * 4, 0)[3])
        assertEquals(256f, texel(out, 0, 1)[3])
    }

    @Test
    fun `packing a range leaves the other instances untouched`() {
        val matrices = translation(1f, 0f, 0f) + translation(2f, 0f, 0f) + translation(3f, 0f, 0f)
        val out = FloatArray(InstanceData.floatCount(3)) { -1f }
        InstanceData.pack(out, matrices, null, 3, 1, 2)
        assertEquals(listOf(-1f, -1f, -1f, -1f), texel(out, 0, 0))
        assertEquals(2f, texel(out, 4, 0)[3])
        assertEquals(listOf(-1f, -1f, -1f, -1f), texel(out, 8, 0))
    }

    @Test
    fun `the instances of a range occupy rows that can be uploaded alone`() {
        assertEquals(0..0, InstanceData.rowRange(0, 256))
        assertEquals(0..1, InstanceData.rowRange(200, 300))
        assertEquals(2..2, InstanceData.rowRange(512, 513))
    }

    @Test
    fun `row bytes start at the first float of the first row`() {
        val floats = FloatArray(InstanceData.floatCount(300)) { it.toFloat() }
        val rows = 1..1
        val bytes = InstanceData.rowBytes(floats, rows, ByteArray(0))
        assertEquals(InstanceData.rowByteCount(rows), bytes.size)
        val firstFloat = InstanceData.TEXTURE_WIDTH * 4
        assertContentEquals(
            InstanceData.toBytes(floatArrayOf(firstFloat.toFloat()), 1, ByteArray(0)),
            bytes.copyOfRange(0, 4),
        )
    }

    @Test
    fun `the byte count of rows is the width times 16 bytes per row`() {
        assertEquals(1024 * 16, InstanceData.rowByteCount(0..0))
        assertEquals(2 * 1024 * 16, InstanceData.rowByteCount(3..4))
    }

    @Test
    fun `floats convert to little endian bytes`() {
        val bytes = InstanceData.toBytes(floatArrayOf(1f, -2f), 2, ByteArray(0))
        assertContentEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, -64), bytes)
    }

    @Test
    fun `world bounds of instanced geometry take every instance into account`() {
        val geometry = Aabb(floatArrayOf(-1f, 0f, -1f), floatArrayOf(1f, 2f, 1f))
        val matrices = translation(10f, 0f, 0f) + translation(-10f, 5f, 0f)
        val box = Aabb.ofInstances(geometry, matrices, 2)
        assertContentEquals(floatArrayOf(-11f, 0f, -1f), box.min)
        assertContentEquals(floatArrayOf(11f, 7f, 1f), box.max)
    }
}
