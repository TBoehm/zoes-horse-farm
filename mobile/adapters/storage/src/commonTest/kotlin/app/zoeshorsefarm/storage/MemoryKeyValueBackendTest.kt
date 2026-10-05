package app.zoeshorsefarm.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryKeyValueBackendTest {
    @Test
    fun `stores reads and removes strings`() {
        val backend = MemoryKeyValueBackend()
        assertNull(backend.getString("a"))
        backend.setString("a", "1")
        backend.setString("a", "2")
        assertEquals("2", backend.getString("a"))
        backend.remove("a")
        assertNull(backend.getString("a"))
        backend.remove("a")
    }

    @Test
    fun `starts with the given entries`() {
        val backend = MemoryKeyValueBackend(mapOf("k" to "v"))
        assertEquals("v", backend.getString("k"))
    }

    @Test
    fun `the process session backend is one shared instance`() {
        ProcessSession.backend.setString("shared-test-key", "x")
        assertEquals("x", ProcessSession.backend.getString("shared-test-key"))
        ProcessSession.backend.remove("shared-test-key")
    }
}
