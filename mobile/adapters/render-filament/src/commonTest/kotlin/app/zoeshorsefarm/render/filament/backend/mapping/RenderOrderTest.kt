package app.zoeshorsefarm.render.filament.backend.mapping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderOrderTest {
    @Test
    fun `the default render order is Filament's default priority`() {
        assertEquals(4, RenderOrder.priority(0))
    }

    @Test
    fun `the sky draws first and the pin last`() {
        assertEquals(0, RenderOrder.priority(-10))
        assertEquals(0, RenderOrder.priority(-9))
        assertEquals(6, RenderOrder.priority(2))
        assertEquals(7, RenderOrder.priority(3))
        assertEquals(7, RenderOrder.priority(5))
    }

    @Test
    fun `the blend order keeps the order of the render orders`() {
        val orders = listOf(-10, -9, 0, 2, 3, 5)
        val blend = orders.map { RenderOrder.blendOrder(it) }
        assertEquals(blend.sorted(), blend)
        assertEquals(blend.toSet().size, blend.size)
        assertTrue(blend.all { it >= 0 })
        assertEquals(0, RenderOrder.blendOrder(-1000))
        assertEquals(32767, RenderOrder.blendOrder(1_000_000))
    }
}
