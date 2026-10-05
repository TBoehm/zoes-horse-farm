package app.zoeshorsefarm.domain.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RngTest {
    @Test
    fun returnsTheSameSequenceIn01ForTheSameSeed() {
        val a = createRng(42)
        val b = createRng(42)
        repeat(100) {
            val v = a()
            assertEquals(b(), v)
            assertTrue(v >= 0)
            assertTrue(v < 1)
        }
    }

    @Test
    fun returnsDifferentSequencesForDifferentSeeds() {
        assertNotEquals(createRng(2)(), createRng(1)())
    }

    @Test
    fun isRoughlyUniformlyDistributed() {
        val rng = createRng(7)
        var sum = 0.0
        repeat(10000) { sum += rng() }
        assertTrue(sum / 10000 > 0.48)
        assertTrue(sum / 10000 < 0.52)
    }

    @Test
    fun matchesTheSequenceOfTheWebApp() {
        // values printed by src/domain/sim/rng.js, so replays and seeded tests agree on both platforms
        val one = createRng(1)
        assertEquals(0.6270739405881613, one())
        assertEquals(0.002735721180215478, one())
        assertEquals(0.5274470399599522, one())
        val answer = createRng(42)
        assertEquals(0.6011037519201636, answer())
        assertEquals(0.44829055899754167, answer())
        assertEquals(0.26642920868471265, createRng(0)())
        assertEquals(0.9040917356032878, createRng(1077)())
    }
}
