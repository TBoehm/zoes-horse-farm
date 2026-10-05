package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.graph.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailHoldTest {
    private fun mesh(visible: Boolean) = Group().also { it.visible = visible }

    @Test
    fun `hides the visible meshes while the stages are out of step and shows them again`() {
        val hold = DetailHold()
        val shown = mesh(true)
        val hidden = mesh(false)
        hold.sync(listOf(shown, hidden), false)
        assertFalse(shown.visible)
        assertEquals(1, hold.size)
        hold.sync(listOf(shown, hidden), true)
        assertTrue(shown.visible)
        assertFalse(hidden.visible) // never shown by the hold
        assertEquals(0, hold.size)
    }

    @Test
    fun `tells which meshes are held for a moment and not hidden for good`() {
        val hold = DetailHold()
        val held = mesh(true)
        val hidden = mesh(false)
        hold.sync(listOf(held, hidden), false)
        assertTrue(hold.has(held))
        assertFalse(hold.has(hidden))
        hold.restore()
        assertFalse(hold.has(held))
    }

    @Test
    fun `does nothing when the stages are in step`() {
        val hold = DetailHold()
        val shown = mesh(true)
        hold.sync(listOf(shown, mesh(false)), true)
        assertTrue(shown.visible)
        assertEquals(0, hold.size)
    }

    @Test
    fun `can be synced repeatedly without losing what is held`() {
        val hold = DetailHold()
        val a = mesh(true)
        hold.sync(listOf(a), false)
        hold.sync(listOf(a), false)
        assertEquals(1, hold.size)
        hold.sync(listOf(a), true)
        assertTrue(a.visible)
    }

    @Test
    fun `restores the held meshes before the density decides what is visible`() {
        val hold = DetailHold()
        val flowers = mesh(true)
        val birds = mesh(true)
        hold.sync(listOf(flowers, birds), false)
        hold.restore()
        assertTrue(flowers.visible && birds.visible)
        // the density stage of a downgrade then hides them for good
        flowers.visible = false
        birds.visible = false
        hold.sync(listOf(flowers, birds), true)
        assertFalse(flowers.visible || birds.visible)
    }
}
