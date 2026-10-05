package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProcessTabIdTest {
    @Test
    fun `creates the id once and returns the same one afterwards`() {
        var n = 0
        val tab =
            ProcessTabId {
                n += 1
                "id-$n"
            }
        assertEquals("id-1", tab.id)
        assertEquals("id-1", tab.id)
        assertEquals(1, n)
    }

    @Test
    fun `another process instance gets another id`() {
        val a = ProcessTabId().id
        val b = ProcessTabId().id
        assertTrue(a.isNotBlank())
        assertNotEquals(a, b)
    }

    @Test
    fun `the app wide id is one value for the whole process`() {
        assertEquals(ProcessTabId.app.id, ProcessTabId.app.id)
    }
}
