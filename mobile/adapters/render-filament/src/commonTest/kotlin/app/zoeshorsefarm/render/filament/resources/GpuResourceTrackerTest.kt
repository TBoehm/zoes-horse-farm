package app.zoeshorsefarm.render.filament.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuResourceTrackerTest {
    @Test
    fun `a new tracker is empty`() {
        val tracker = GpuResourceTracker()
        assertEquals(0L, tracker.totalBytes)
        assertEquals(0, tracker.totalCount)
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `registered resources add up by kind`() {
        val tracker = GpuResourceTracker()
        tracker.register(ResourceKind.VERTEX_BUFFER, "horse body", 1000)
        tracker.register(ResourceKind.VERTEX_BUFFER, "rider", 500)
        tracker.register(ResourceKind.TEXTURE, "sand", 4000)
        assertEquals(1500L, tracker.bytesOf(ResourceKind.VERTEX_BUFFER))
        assertEquals(2, tracker.countOf(ResourceKind.VERTEX_BUFFER))
        assertEquals(4000L, tracker.bytesOf(ResourceKind.TEXTURE))
        assertEquals(5500L, tracker.totalBytes)
        assertEquals(3, tracker.totalCount)
        assertEquals(0L, tracker.bytesOf(ResourceKind.SHADOW_MAP))
    }

    @Test
    fun `releasing a resource takes its bytes off`() {
        val tracker = GpuResourceTracker()
        val id = tracker.register(ResourceKind.TEXTURE, "grass", 2000)
        tracker.register(ResourceKind.TEXTURE, "sand", 1000)
        assertTrue(tracker.release(id))
        assertEquals(1000L, tracker.totalBytes)
        assertEquals(1, tracker.totalCount)
    }

    @Test
    fun `releasing twice or an unknown id does nothing`() {
        val tracker = GpuResourceTracker()
        val id = tracker.register(ResourceKind.TEXTURE, "grass", 2000)
        assertTrue(tracker.release(id))
        assertFalse(tracker.release(id))
        assertFalse(tracker.release(9999))
        assertEquals(0L, tracker.totalBytes)
    }

    @Test
    fun `ids are unique and never reused`() {
        val tracker = GpuResourceTracker()
        val a = tracker.register(ResourceKind.MATERIAL, "a", 0)
        tracker.release(a)
        val b = tracker.register(ResourceKind.MATERIAL, "b", 0)
        assertTrue(a != b)
    }

    @Test
    fun `a resource can change its size`() {
        val tracker = GpuResourceTracker()
        val id = tracker.register(ResourceKind.TEXTURE, "instances", 1000)
        tracker.resize(id, 3000)
        assertEquals(3000L, tracker.totalBytes)
    }

    @Test
    fun `resizing an unknown resource is an error`() {
        assertFailsWith<IllegalArgumentException> { GpuResourceTracker().resize(7, 10) }
    }

    @Test
    fun `negative sizes are rejected`() {
        assertFailsWith<IllegalArgumentException> { GpuResourceTracker().register(ResourceKind.TEXTURE, "x", -1) }
    }

    @Test
    fun `the snapshot lists what is alive with label and size`() {
        val tracker = GpuResourceTracker()
        tracker.register(ResourceKind.INDEX_BUFFER, "fence", 120)
        val entry = tracker.snapshot().single()
        assertEquals("fence", entry.label)
        assertEquals(ResourceKind.INDEX_BUFFER, entry.kind)
        assertEquals(120L, entry.bytes)
    }

    @Test
    fun `clear forgets everything`() {
        val tracker = GpuResourceTracker()
        tracker.register(ResourceKind.TEXTURE, "a", 10)
        tracker.clear()
        assertEquals(0L, tracker.totalBytes)
        assertEquals(0, tracker.totalCount)
    }

    @Test
    fun `megabytes are bytes over 1048576`() {
        val tracker = GpuResourceTracker()
        tracker.register(ResourceKind.TEXTURE, "a", 3L * 1024 * 1024)
        assertEquals(3.0, tracker.totalMegabytes)
    }
}
