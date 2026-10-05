package app.zoeshorsefarm.presentation.help

import app.zoeshorsefarm.i18n.STRING_AREAS
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HelpContentTest {
    private val de = STRING_AREAS.fold(emptyMap<String, String>()) { acc, area -> acc + area.de }
    private val en = STRING_AREAS.fold(emptyMap<String, String>()) { acc, area -> acc + area.en }

    private fun labels(rows: List<HelpRow>) = rows.flatMap { r -> r.keys.flatten().map { it.label ?: it.labelKey } }

    private fun textKeys(rows: List<HelpRow>) =
        rows.flatMap { r ->
            listOf(r.textKey) +
                r.keys.flatten().mapNotNull { it.labelKey } +
                r.glyphs.flatMap { listOfNotNull(it.labelKey, it.nameKey) }
        }

    @Test
    fun keyboardEveryKeyOfRule8IsThere() {
        val shown = labels(KEYBOARD_ROWS)
        for (key in listOf("W", "S", "A", "D", "↑", "↓", "←", "→", "Shift", "C", "Esc")) assertContains(shown, key)
        assertContains(shown, "help.key.space")
    }

    @Test
    fun touchJoystickCanterJumpCameraAndPause() {
        val kinds = TOUCH_ROWS.flatMap { r -> r.glyphs.map { it.kind } }
        for (kind in listOf(GlyphKind.STICK, GlyphKind.GALLOP, GlyphKind.JUMP, GlyphKind.SMALL)) {
            assertContains(kinds, kind)
        }
        val symbols = TOUCH_ROWS.flatMap { r -> r.glyphs.map { it.symbol } }
        assertContains(symbols, "🎥")
        assertContains(symbols, "❚❚")
    }

    @Test
    fun rowIdsAreUniqueWithinAMode() {
        for (rows in listOf(KEYBOARD_ROWS, TOUCH_ROWS)) {
            assertEquals(rows.size, rows.map { it.id }.toSet().size)
        }
    }

    @Test
    fun everyTextHasAGermanAndAnEnglishTranslation() {
        for (key in textKeys(KEYBOARD_ROWS) + textKeys(TOUCH_ROWS)) {
            assertTrue(key in de, "de $key")
            assertTrue(key in en, "en $key")
        }
    }

    @Test
    fun preselectsTheModeOfTheCurrentInputAndOffersBothModes() {
        assertEquals(HelpMode.TOUCH, defaultHelpMode(true))
        assertEquals(HelpMode.KEYBOARD, defaultHelpMode(false))
        assertEquals(listOf(HelpMode.KEYBOARD, HelpMode.TOUCH), HELP_MODES)
        assertSame(TOUCH_ROWS, rowsFor(HelpMode.TOUCH))
        assertSame(KEYBOARD_ROWS, rowsFor(HelpMode.KEYBOARD))
    }

    @Test
    fun theKeyboardRowsOfferAlternativesAndKeyGroups() {
        val steer = KEYBOARD_ROWS.first { it.id == "steer" }
        assertEquals(listOf(listOf("A", "D"), listOf("←", "→")), steer.keys.map { group -> group.map { it.label } })
        val faster = KEYBOARD_ROWS.first { it.id == "faster" }
        assertEquals(2, faster.keys.size)
    }

    @Test
    fun aGlyphWithoutALabelHasANameForScreenReaders() {
        for (glyph in TOUCH_ROWS.flatMap { it.glyphs }) {
            assertTrue(glyph.labelKey != null || glyph.symbol != null)
        }
        val stick = TOUCH_ROWS.first { it.id == "speed" }.glyphs.single()
        assertEquals("help.joystick", stick.nameKey)
    }
}
