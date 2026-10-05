package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.horse.COATS
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.MARKINGS
import app.zoeshorsefarm.domain.horse.Marking
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import kotlin.test.Test
import kotlin.test.assertEquals

class CoatsTest {
    private fun lum(c: DoubleArray) = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]

    @Test
    fun `offers the five coats and four head markings of rule 43`() {
        assertEquals(listOf("chestnut", "bay", "black", "grey", "pinto"), COATS.map { it.id })
        assertEquals(listOf("none", "star", "blaze", "snip"), MARKINGS.map { it.id })
    }

    @Test
    fun `default is bay with star and invalid values fall back to the default`() {
        assertEquals(Coat.BAY, normalizeAppearance().coat)
        assertEquals(Marking.STAR, normalizeAppearance().marking)
        val invalid = normalizeAppearance(Coat.fromId("zebra"), Marking.fromId("x"))
        assertEquals(Coat.BAY, invalid.coat)
        assertEquals(Marking.STAR, invalid.marking)
        val grey = normalizeAppearance(Coat.fromId("grey"), Marking.fromId("none"))
        assertEquals(Coat.GREY, grey.coat)
        assertEquals(Marking.NONE, grey.marking)
    }

    @Test
    fun `chestnut is reddish with long hair lighter than the coat`() {
        val p = coatParams(Coat.CHESTNUT)
        assertGreater(p.base[0], p.base[2] * 2)
        assertGreater(lum(p.hair), lum(p.base))
        assertEquals(0.0, p.points)
    }

    @Test
    fun `bay is a brown coat with black long hair and black lower legs`() {
        val p = coatParams(Coat.BAY)
        assertLess(lum(p.hair), 0.08)
        assertEquals(1.0, p.points)
        assertLess(lum(p.pointColor), 0.08)
        assertGreater(lum(p.base), 0.15)
    }

    @Test
    fun `black is black and grey is light with dapples and pinto has white patches`() {
        assertLess(lum(coatParams(Coat.BLACK).base), 0.1)
        val g = coatParams(Coat.GREY)
        assertGreater(lum(g.base), 0.7)
        assertEquals(1.0, g.dapple)
        assertLess(lum(g.muzzle), 0.3)
        assertEquals(1.0, coatParams(Coat.PINTO).pinto)
    }

    @Test
    fun `markings are clear on dark coats and barely visible on the grey`() {
        fun contrast(coat: Coat) = lum(coatParams(coat).white) - lum(coatParams(coat).base)
        assertGreater(contrast(Coat.BAY), 0.5)
        assertLess(contrast(Coat.GREY), 0.2)
    }

    @Test
    fun `markingIndex none 0 star 1 blaze 2 snip 3`() {
        assertEquals(listOf(0, 1, 2, 3), MARKINGS.map { markingIndex(it) })
        assertEquals(1, markingIndex(Marking.fromId("foo")))
    }
}
