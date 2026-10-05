package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.scene.math.Mat4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransformCacheTest {
    private val cache = TransformCache()

    @Test
    fun `the first matrix is a change and the same matrix is not`() {
        val matrix = Mat4().makeTranslation(1.0, 2.0, 3.0)
        assertTrue(cache.refresh(matrix))
        assertFalse(cache.refresh(matrix))
        assertEquals(3f, cache.current[14])
    }

    @Test
    fun `a different matrix is a change`() {
        cache.refresh(Mat4().makeTranslation(1.0, 0.0, 0.0))
        assertTrue(cache.refresh(Mat4().makeTranslation(2.0, 0.0, 0.0)))
    }

    @Test
    fun `invalidate makes the next matrix a change again`() {
        val matrix = Mat4()
        cache.refresh(matrix)
        cache.invalidate()
        assertTrue(cache.refresh(matrix))
    }

    @Test
    fun `commit compares what is already in current`() {
        cache.current[0] = 5f
        assertTrue(cache.commit())
        assertFalse(cache.commit())
        cache.current[0] = 6f
        assertTrue(cache.commit())
    }
}
