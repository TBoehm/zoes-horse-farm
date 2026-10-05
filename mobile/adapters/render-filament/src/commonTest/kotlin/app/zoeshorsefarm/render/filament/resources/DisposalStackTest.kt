package app.zoeshorsefarm.render.filament.resources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DisposalStackTest {
    @Test
    fun `resources are destroyed in reverse order of creation`() {
        val log = mutableListOf<String>()
        val stack = DisposalStack()
        stack.add("swap chain") { log += "destroy $it" }
        stack.add("view") { log += "destroy $it" }
        stack.add("engine") { log += "destroy $it" }
        stack.disposeAll()
        assertEquals(listOf("destroy engine", "destroy view", "destroy swap chain"), log)
    }

    @Test
    fun `add returns the resource so that creation and tracking are one expression`() {
        val resource = Any()
        assertSame(resource, DisposalStack().add(resource) {})
    }

    @Test
    fun `disposing twice destroys each resource once`() {
        var count = 0
        val stack = DisposalStack()
        stack.add(1) { count++ }
        stack.disposeAll()
        stack.disposeAll()
        assertEquals(1, count)
        assertEquals(0, stack.size)
    }

    @Test
    fun `a failing destroy does not stop the others and is reported`() {
        val log = mutableListOf<String>()
        val stack = DisposalStack()
        stack.add("a") { log += "a" }
        stack.add("b") { error("boom") }
        stack.add("c") { log += "c" }
        val errors = stack.disposeAll()
        assertEquals(listOf("c", "a"), log)
        assertEquals(1, errors.size)
        assertEquals("boom", errors.single().message)
    }

    @Test
    fun `a single resource can be disposed early and then is not disposed again`() {
        val log = mutableListOf<String>()
        val stack = DisposalStack()
        val a = stack.add("a") { log += "a" }
        stack.add("b") { log += "b" }
        assertTrue(stack.dispose(a))
        assertFalse(stack.dispose(a))
        stack.disposeAll()
        assertEquals(listOf("a", "b"), log)
    }

    @Test
    fun `size counts what is still held`() {
        val stack = DisposalStack()
        stack.add(1) {}
        stack.add(2) {}
        assertEquals(2, stack.size)
    }

    @Test
    fun `resources added while disposing are destroyed too`() {
        val log = mutableListOf<String>()
        val stack = DisposalStack()
        stack.add("outer") {
            log += "outer"
            stack.add("late") { log += "late" }
        }
        stack.disposeAll()
        assertEquals(listOf("outer", "late"), log)
    }
}
