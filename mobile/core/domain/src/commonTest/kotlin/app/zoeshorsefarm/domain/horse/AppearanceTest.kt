package app.zoeshorsefarm.domain.horse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppearanceTest {
    @Test
    fun offersTheCoatsAndMarkingsOfTheWebApp() {
        assertEquals(listOf("chestnut", "bay", "black", "grey", "pinto"), COATS.map { it.id })
        assertEquals(listOf("none", "star", "blaze", "snip"), MARKINGS.map { it.id })
    }

    @Test
    fun aNewHorseIsABayWithAStar() {
        assertEquals(Appearance(Coat.BAY, Marking.STAR), DEFAULT_APPEARANCE)
    }

    @Test
    fun findsAnOptionByItsStoredId() {
        assertEquals(Coat.GREY, Coat.fromId("grey"))
        assertEquals(Marking.BLAZE, Marking.fromId("blaze"))
        assertNull(Coat.fromId("purple"))
        assertNull(Marking.fromId(null))
    }
}
