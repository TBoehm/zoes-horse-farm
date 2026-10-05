package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.scene.material.BasicMaterial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgramBookTest {
    private val book = ProgramBook()

    @Test
    fun `a program is counted once however often it is booked`() {
        val material = BasicMaterial()
        assertTrue(book.add(material, "a"))
        assertFalse(book.add(material, "a"))
        assertEquals(1, book.count)
    }

    @Test
    fun `materials with the same key share a program`() {
        val first = BasicMaterial()
        val second = BasicMaterial()
        book.add(first, "a")
        book.add(second, "a")
        assertEquals(1, book.count)
        book.release(first)
        assertEquals(1, book.count)
        book.release(second)
        assertEquals(0, book.count)
    }

    @Test
    fun `a material keeps every program it was drawn with until it is released`() {
        val material = BasicMaterial()
        book.add(material, "a")
        book.add(material, "b")
        assertEquals(listOf("a", "b"), book.keys())
        book.release(material)
        assertEquals(0, book.count)
        assertEquals(0, book.materialCount())
    }

    @Test
    fun `releasing an unknown material is harmless and clear forgets everything`() {
        book.release(BasicMaterial())
        book.add(BasicMaterial(), "a")
        book.clear()
        assertEquals(0, book.count)
    }
}
