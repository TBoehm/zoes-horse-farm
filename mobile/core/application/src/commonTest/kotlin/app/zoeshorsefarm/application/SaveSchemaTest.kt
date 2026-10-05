package app.zoeshorsefarm.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveSchemaTest {
    // field specs

    @Test
    fun enumAcceptsOnlyListedValues() {
        val spec = Field.oneOf(listOf("a", "b"), "a")
        assertTrue(spec.check("b"))
        assertFalse(spec.check("c"))
        assertFalse(spec.check(null))
        assertEquals("a", spec.fallback(SaveEnv()))
    }

    @Test
    fun enumCanListNullAsAValidValue() {
        assertTrue(Field.oneOf(listOf("a", null), null).check(null))
    }

    @Test
    fun boolAcceptsOnlyBooleans() {
        val spec = Field.bool(true)
        assertTrue(spec.check(false))
        assertFalse(spec.check(0))
        assertFalse(spec.check("true"))
    }

    @Test
    fun numberAcceptsFiniteNumbersInsideTheLimitsLimitsIncluded() {
        val spec = Field.number(0.0, 1.0, 0.5)
        assertTrue(spec.check(0))
        assertTrue(spec.check(1.0))
        assertFalse(spec.check(-0.01))
        assertFalse(spec.check(1.01))
        assertFalse(spec.check(Double.NaN))
        assertFalse(spec.check(Double.POSITIVE_INFINITY))
        assertFalse(spec.check("0.5"))
    }

    // objectSection

    private val section =
        ObjectSection(
            mapOf(
                "mode" to Field.oneOf(listOf("x", "y"), "x"),
                "lang" to FieldSpec({ env -> env.defaultLang?.id }, { v -> LANGS.any { it.id == v } }),
            ),
        )

    @Test
    fun buildsDefaultsResolvingFallbacksFromTheEnvironment() {
        assertEquals(mapOf("mode" to "x", "lang" to "de"), section.defaults(SaveEnv(Language.DE)))
    }

    @Test
    fun replacesInvalidFieldsWithTheEnvDependentFallbackAndKeepsValidOnes() {
        val clean = section.sanitize(mapOf("mode" to "y", "lang" to "fr"), SaveEnv(Language.EN))
        assertEquals(mapOf("mode" to "y", "lang" to "en"), clean)
    }

    @Test
    fun keepsUnknownFields() {
        val clean = section.sanitize(mapOf("mode" to "x", "lang" to "de", "future" to mapOf("a" to 1)), SaveEnv())
        assertEquals(mapOf("a" to 1), clean["future"])
    }

    @Test
    fun returnsTheDefaultsForInputThatIsNoObject() {
        val expected = mapOf("mode" to "x", "lang" to "en")
        for (raw in listOf(null, 42, "text", emptyList<Any?>())) {
            assertEquals(expected, section.sanitize(raw, SaveEnv(Language.EN)), "raw = $raw")
        }
    }

    @Test
    fun anAbsentFieldTakesTheFallbackEvenWhenNullIsAListedValue() {
        val nullable = ObjectSection(mapOf("level" to Field.oneOf(listOf("a", null), "a")))
        assertEquals("a", nullable.sanitize(emptyMap<String, Any?>())["level"])
        assertNull(nullable.sanitize(mapOf("level" to null))["level"])
    }

    @Test
    fun doesNotMutateTheRawInput() {
        val raw = mutableMapOf<String, Any?>("mode" to "bad")
        section.sanitize(raw, SaveEnv(Language.EN))
        assertEquals(mapOf<String, Any?>("mode" to "bad"), raw)
    }

    // the registered sections

    @Test
    fun registersEverySectionOfTheSaveGameByName() {
        assertEquals(
            listOf("settings", "horse", "progress", "crashGuard"),
            SAVE_SECTIONS.map { it.name },
        )
    }

    @Test
    fun theTreeOfASectionReadsBackToTheSameValue() {
        for (section in SAVE_SECTIONS) {
            val defaults = section.sanitizedTree(null, SaveEnv())
            assertEquals(defaults, section.sanitizedTree(defaults, SaveEnv()), section.name)
        }
    }

    @Test
    fun unknownFieldsOfEverySectionSurviveASanitizeRoundTrip() {
        for (section in SAVE_SECTIONS) {
            val raw = mapOf("futureField" to mapOf("a" to listOf(1, "two", null)))
            val tree = section.sanitizedTree(raw, SaveEnv())
            assertEquals(raw["futureField"], tree["futureField"], section.name)
        }
    }

    // languages

    @Test
    fun offersGermanAndEnglish() {
        assertEquals(listOf("de", "en"), LANGS.map { it.id })
        assertEquals(Language.DE, Language.fromId("de"))
        assertNull(Language.fromId("fr"))
        assertNull(Language.fromId(null))
    }
}
