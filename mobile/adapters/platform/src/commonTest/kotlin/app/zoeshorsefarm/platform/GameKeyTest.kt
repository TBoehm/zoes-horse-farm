package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameKeyTest {
    @Test
    fun `lists the keys the game reacts to`() {
        assertEquals(GameKey.KEY_W, GameKey.fromCode("KeyW"))
        assertEquals(GameKey.ARROW_LEFT, GameKey.fromCode("ArrowLeft"))
        assertNull(GameKey.fromCode("KeyX"))
        assertNull(GameKey.fromCode(null))
        assertEquals(13, GameKey.entries.size)
    }

    @Test
    fun `every key has its own web code`() {
        assertEquals(
            GameKey.entries.size,
            GameKey.entries
                .map { it.code }
                .toSet()
                .size,
        )
        for (key in GameKey.entries) assertEquals(key, GameKey.fromCode(key.code))
    }
}
