package app.zoeshorsefarm.i18n

import app.zoeshorsefarm.domain.progress.BADGES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BadgeStringsTest {
    private val strings = BADGES_STRINGS

    private fun placeholders(text: String) =
        Regex("""\{\w+\}""")
            .findAll(text)
            .map { it.value }
            .toList()
            .sorted()

    @Test
    fun deAndEnHaveTheSameKeys() {
        assertEquals(strings.de.keys.sorted(), strings.en.keys.sorted())
    }

    @Test
    fun containAllKeysFromBadgesNonEmpty() {
        for (lang in listOf(strings.de, strings.en)) {
            for (badge in BADGES) {
                assertTrue(lang[badge.nameKey]?.isNotEmpty() == true, badge.nameKey)
                assertTrue(lang[badge.conditionKey]?.isNotEmpty() == true, badge.conditionKey)
            }
        }
    }

    @Test
    fun namesMatchTheConcept() {
        assertEquals(
            listOf(
                "Erster Sprung",
                "Springmaus",
                "Fehlerfrei",
                "Oxer-Profi",
                "Kombi-Könner",
                "Alles offen",
                "Sternenreiter",
                "Fleißig",
            ),
            BADGES.map { strings.de[it.nameKey] },
        )
    }

    @Test
    fun oxerAndCombinationBadgesSayThatItMustHappenInACourseRideThatReachesTheFinish() {
        for (id in listOf("oxerPro", "comboPro")) {
            val key = assertNotNull(BADGES.firstOrNull { it.id == id }).conditionKey
            assertTrue(Regex("im Parcours.*Ziel").containsMatchIn(strings.de.getValue(key)), "de $id")
            assertTrue(Regex("in a course.*finish").containsMatchIn(strings.en.getValue(key)), "en $id")
        }
    }

    @Test
    fun placeholdersMatchInBothLanguages() {
        for (key in strings.de.keys) {
            assertEquals(placeholders(strings.de.getValue(key)), placeholders(strings.en.getValue(key)), key)
        }
    }
}
