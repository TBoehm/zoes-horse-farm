package app.zoeshorsefarm.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandQueueTest {
    private class Received(
        val kind: Int,
        val arg: Int,
        val a: Double,
        val b: Double,
    )

    private fun CommandQueue.drainAll(): List<Received> {
        val out = mutableListOf<Received>()
        drain { kind, arg, a, b -> out += Received(kind, arg, a, b) }
        return out
    }

    @Test
    fun deliversCommandsInOrderWithAllFields() {
        val q = CommandQueue(8)
        assertTrue(q.offer(1, 10, 0.5, 1.5))
        assertTrue(q.offer(2, 20, 2.5, 3.5))
        val got = q.drainAll()
        assertEquals(listOf(1, 2), got.map { it.kind })
        assertEquals(listOf(10, 20), got.map { it.arg })
        assertEquals(listOf(0.5, 2.5), got.map { it.a })
        assertEquals(listOf(1.5, 3.5), got.map { it.b })
    }

    @Test
    fun isEmptyAfterDraining() {
        val q = CommandQueue(4)
        q.offer(1, 0, 0.0, 0.0)
        assertEquals(1, q.drainAll().size)
        assertEquals(0, q.drainAll().size)
    }

    @Test
    fun refusesCommandsWhenFullAndCountsThem() {
        val q = CommandQueue(4)
        repeat(4) { assertTrue(q.offer(it, 0, 0.0, 0.0)) }
        assertFalse(q.offer(99, 0, 0.0, 0.0))
        assertFalse(q.offer(98, 0, 0.0, 0.0))
        assertEquals(2, q.dropped)
        assertEquals(listOf(0, 1, 2, 3), q.drainAll().map { it.kind })
        // there is room again
        assertTrue(q.offer(5, 0, 0.0, 0.0))
    }

    @Test
    fun keepsOrderAcrossManyWrapArounds() {
        val q = CommandQueue(4)
        var next = 0
        var expected = 0
        repeat(100) { round ->
            repeat(1 + round % 4) { assertTrue(q.offer(next++, 0, 0.0, 0.0)) }
            for (c in q.drainAll()) assertEquals(expected++, c.kind)
        }
        assertEquals(next, expected)
        assertEquals(0, q.dropped)
    }

    @Test
    fun theCapacityIsRoundedUpToAPowerOfTwo() {
        val q = CommandQueue(5)
        repeat(8) { assertTrue(q.offer(it, 0, 0.0, 0.0)) }
        assertFalse(q.offer(8, 0, 0.0, 0.0))
    }
}
