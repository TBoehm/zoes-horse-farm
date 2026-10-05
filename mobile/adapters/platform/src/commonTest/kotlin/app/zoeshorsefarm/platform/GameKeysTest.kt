package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameKeysTest {
    @Test
    fun `lists the keys the game reacts to`() {
        assertTrue("KeyW" in GAME_KEYS)
        assertTrue("ArrowLeft" in GAME_KEYS)
        assertFalse("KeyX" in GAME_KEYS)
        assertEquals(13, GAME_KEYS.size)
    }
}
