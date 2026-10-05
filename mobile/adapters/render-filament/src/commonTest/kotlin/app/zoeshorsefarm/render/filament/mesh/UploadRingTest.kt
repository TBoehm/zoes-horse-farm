package app.zoeshorsefarm.render.filament.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UploadRingTest {
    @Test
    fun `a slot is big enough for what was asked`() {
        val slot = UploadRing(2).acquire(100)
        assertTrue(slot.bytes.size >= 100)
    }

    @Test
    fun `a slot in use is not handed out again`() {
        val ring = UploadRing(2)
        val a = ring.acquire(16)
        val b = ring.acquire(16)
        assertNotSame(a.bytes, b.bytes)
        assertTrue(a.isBusy && b.isBusy)
    }

    @Test
    fun `a slot comes back once Filament has consumed it`() {
        val ring = UploadRing(1)
        val a = ring.acquire(16)
        a.onConsumed()
        assertFalse(a.isBusy)
        val again = ring.acquire(16)
        assertSame(a.bytes, again.bytes)
    }

    @Test
    fun `when every slot is busy a one shot array is used and the ring is not corrupted`() {
        val ring = UploadRing(2)
        val a = ring.acquire(16)
        val b = ring.acquire(16)
        val extra = ring.acquire(16)
        assertNotSame(a.bytes, extra.bytes)
        assertNotSame(b.bytes, extra.bytes)
        assertEquals(1, ring.overflowCount)
        // consuming the one shot slot frees nothing in the ring
        extra.onConsumed()
        assertTrue(a.isBusy && b.isBusy)
    }

    @Test
    fun `a free slot grows when a bigger upload comes`() {
        val ring = UploadRing(1)
        val small = ring.acquire(8)
        small.onConsumed()
        val big = ring.acquire(64)
        assertTrue(big.bytes.size >= 64)
        assertEquals(0, ring.overflowCount)
    }

    @Test
    fun `a free slot keeps its array when a smaller upload comes`() {
        val ring = UploadRing(1)
        val big = ring.acquire(64)
        val array = big.bytes
        big.onConsumed()
        assertSame(array, ring.acquire(8).bytes)
    }

    @Test
    fun `the ring needs at least one slot`() {
        assertTrue(runCatching { UploadRing(0) }.isFailure)
    }
}
