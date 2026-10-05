package app.zoeshorsefarm.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphicsLevelTest {
    @Test
    fun offersLowMediumHighInThisOrder() {
        assertEquals(listOf("low", "medium", "high"), GRAPHICS_LEVELS.map { it.id })
    }

    @Test
    fun automaticStartsAtTheSafestLevel() {
        assertEquals(GraphicsLevel.LOW, AUTO_START_LEVEL)
    }

    @Test
    fun findsLevelsByTheirStoredId() {
        assertEquals(GraphicsLevel.MEDIUM, GraphicsLevel.fromId("medium"))
        assertNull(GraphicsLevel.fromId("ultra"))
        assertNull(GraphicsLevel.fromId(null))
    }

    @Test
    fun foregroundGraceIsThreeSeconds() {
        assertEquals(3.0, FOREGROUND_GRACE_S)
    }
}
