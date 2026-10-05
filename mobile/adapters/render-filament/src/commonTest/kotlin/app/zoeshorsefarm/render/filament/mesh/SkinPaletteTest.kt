package app.zoeshorsefarm.render.filament.mesh

import app.zoeshorsefarm.render.filament.math.Mat4Ops
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkinPaletteTest {
    private fun identity() = FloatArray(16).also { Mat4Ops.identity(it, 0) }

    private fun translation(
        x: Float,
        y: Float,
        z: Float,
    ) = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

    private fun assertMatrix(
        expected: FloatArray,
        actual: FloatArray,
        offset: Int = 0,
    ) {
        for (i in 0 until 16) {
            assertTrue(
                abs(expected[i] - actual[offset + i]) < 1e-5f,
                "element $i: ${expected[i]} vs ${actual[offset + i]}",
            )
        }
    }

    @Test
    fun `a skeleton in its bind pose gives identity matrices`() {
        val bind = translation(0f, 1f, 0f)
        val inverseBind = FloatArray(16).also { Mat4Ops.invert(bind, 0, it, 0) }
        val palette = SkinPalette(2)
        val out = FloatArray(32)
        palette.compute(identity(), identity(), bind + bind, inverseBind + inverseBind, out)
        assertMatrix(identity(), out, 0)
        assertMatrix(identity(), out, 16)
    }

    @Test
    fun `a moved bone gives the translation it moved by`() {
        val bind = translation(0f, 1f, 0f)
        val inverseBind = FloatArray(16).also { Mat4Ops.invert(bind, 0, it, 0) }
        val moved = translation(2f, 1f, 0f)
        val out = FloatArray(16)
        SkinPalette(1).compute(identity(), identity(), moved, inverseBind, out)
        assertMatrix(translation(2f, 0f, 0f), out)
    }

    @Test
    fun `the palette is expressed in the model space of the mesh`() {
        // the mesh was bound at a translation of (5, 0, 0): bone matrices are taken relative to it
        val meshBind = translation(5f, 0f, 0f)
        val meshBindInverse = FloatArray(16).also { Mat4Ops.invert(meshBind, 0, it, 0) }
        val boneWorld = translation(6f, 0f, 0f) // the bone moved one unit in x since the bind
        val boneInverse = translation(-5f, 0f, 0f) // inverse of its world matrix at bind time
        val out = FloatArray(16)
        SkinPalette(1).compute(meshBindInverse, meshBind, boneWorld, boneInverse, out)
        assertMatrix(translation(1f, 0f, 0f), out)
    }

    @Test
    fun `fewer matrices than bones are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SkinPalette(2).compute(identity(), identity(), identity(), identity(), FloatArray(32))
        }
        assertFailsWith<IllegalArgumentException> {
            SkinPalette(
                2,
            ).compute(identity(), identity(), identity() + identity(), identity() + identity(), FloatArray(16))
        }
    }

    @Test
    fun `the bone count is limited to what Filament skins`() {
        assertEquals(256, SkinPalette.MAX_BONES)
        assertFailsWith<IllegalArgumentException> { SkinPalette(257) }
        assertFailsWith<IllegalArgumentException> { SkinPalette(0) }
    }

    @Test
    fun `byteSize is 64 bytes per bone`() {
        assertEquals(64L * 40, SkinPalette.byteSize(40))
    }

    @Test
    fun `fromBoneMatrices equals compute for the same bones`() {
        val bind = translation(0f, 1f, 0f)
        val inverseBind = FloatArray(16).also { Mat4Ops.invert(bind, 0, it, 0) }
        val bone = translation(2f, 1f, 0.5f)
        val meshWorld = translation(5f, 0f, 0f)
        val meshWorldInverse = FloatArray(16).also { Mat4Ops.invert(meshWorld, 0, it, 0) }
        val expected = FloatArray(16)
        SkinPalette(1).compute(meshWorldInverse, bind, bone, inverseBind, expected)
        val boneMatrix = FloatArray(16).also { Mat4Ops.multiply(bone, 0, inverseBind, 0, it, 0) }
        val actual = FloatArray(16)
        SkinPalette(1).fromBoneMatrices(meshWorldInverse, bind, boneMatrix, actual)
        assertMatrix(expected, actual)
    }

    @Test
    fun `fromBoneMatrices rejects short arrays`() {
        assertFailsWith<IllegalArgumentException> {
            SkinPalette(2).fromBoneMatrices(identity(), identity(), FloatArray(16), FloatArray(32))
        }
    }
}
