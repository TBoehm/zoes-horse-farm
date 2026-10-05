package app.zoeshorsefarm.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathTest {
    @Test
    fun clampKeepsValuesInsideTheRange() {
        assertEquals(0.5, clamp(0.5, 0.0, 1.0))
    }

    @Test
    fun clampLimitsValuesBelowAndAboveTheRange() {
        assertEquals(0.0, clamp(-3.0, 0.0, 1.0))
        assertEquals(1.0, clamp(7.0, 0.0, 1.0))
    }

    @Test
    fun clampAcceptsTheBoundsThemselves() {
        assertEquals(0.0, clamp(0.0, 0.0, 1.0))
        assertEquals(1.0, clamp(1.0, 0.0, 1.0))
    }

    @Test
    fun clampWorksForIntegers() {
        assertEquals(5, clamp(9, 1, 5))
        assertEquals(1, clamp(-9, 1, 5))
        assertEquals(3, clamp(3, 1, 5))
    }

    @Test
    fun isPlainObjectAcceptsPlainObjects() {
        assertTrue(isPlainObject(emptyMap<String, Any?>()))
        assertTrue(isPlainObject(mapOf("a" to 1)))
    }

    @Test
    fun isPlainObjectRejectsNullListsAndPrimitives() {
        assertFalse(isPlainObject(null))
        assertFalse(isPlainObject(emptyList<Any?>()))
        assertFalse(isPlainObject("x"))
        assertFalse(isPlainObject(3))
    }
}
