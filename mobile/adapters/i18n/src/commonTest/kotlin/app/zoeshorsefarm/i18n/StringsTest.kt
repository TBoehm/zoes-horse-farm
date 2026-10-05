package app.zoeshorsefarm.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringsTest {
    private val de = STRING_AREAS.fold(emptyMap<String, String>()) { acc, area -> acc + area.de }
    private val en = STRING_AREAS.fold(emptyMap<String, String>()) { acc, area -> acc + area.en }

    private fun placeholders(text: String) =
        Regex("""\{\w+\}""")
            .findAll(text)
            .map { it.value }
            .toList()
            .sorted()

    @Test
    fun haveTheSameKeysInGermanAndEnglish() {
        assertEquals(de.keys.sorted(), en.keys.sorted())
    }

    @Test
    fun areNotEmptyAndHaveTheSamePlaceholders() {
        for (key in de.keys) {
            assertTrue(de.getValue(key).isNotBlank(), "de $key is empty")
            assertTrue(en.getValue(key).isNotBlank(), "en $key is empty")
            assertEquals(placeholders(de.getValue(key)), placeholders(en.getValue(key)), key)
        }
    }

    @Test
    fun doNotDefineAKeyTwiceAcrossAreas() {
        val keys = STRING_AREAS.flatMap { it.de.keys }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everyAreaHasTheSameKeysInBothLanguages() {
        for (area in STRING_AREAS) assertEquals(area.de.keys, area.en.keys)
    }

    @Test
    fun sayFallenPoleInsteadOfAbwurfBecauseAChildReadsAbwurfAsAFallOfTheRider() {
        assertEquals("Stange gefallen!", de["feedback.knockdown"])
        assertEquals("Gefallene Stangen", de["results.knockdowns"])
        assertEquals("Pole down!", en["feedback.knockdown"])
        for (text in de.values) assertFalse(Regex("Abwurf|Abwürfe").containsMatchIn(text), text)
    }

    @Test
    fun theTableHasEveryKeyOfTheWebApp() {
        // 19 core + 31 riding + 32 profile + 17 badges + 38 courses + 6 audio + 21 help + 36 debug
        val webAreas = STRING_AREAS - DATE_STRINGS
        assertEquals(200, webAreas.sumOf { it.de.size })
        assertEquals(8, webAreas.size)
    }

    @Test
    fun theNativeDateAreaHasTheLongPatternAndTwelveMonthNames() {
        for (table in listOf(DATE_STRINGS.de, DATE_STRINGS.en)) {
            assertEquals(13, table.size)
            val pattern = table.getValue("date.long")
            assertTrue("{day}" in pattern && "{month}" in pattern && "{year}" in pattern)
            for (month in 1..12) assertTrue(table.getValue("date.month.$month").isNotBlank())
        }
    }
}
