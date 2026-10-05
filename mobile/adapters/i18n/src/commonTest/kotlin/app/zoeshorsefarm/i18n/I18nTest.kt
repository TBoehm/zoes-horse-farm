package app.zoeshorsefarm.i18n

import app.zoeshorsefarm.application.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class I18nTest {
    private fun emptyI18n(onMissing: (String) -> Unit = {}) = I18n(areas = emptyList(), onMissing = onMissing)

    // detectLang (rule 6)

    @Test
    fun detectLangPicksGermanForAGermanBrowserLanguage() {
        assertEquals(Language.DE, detectLang(listOf("de-DE", "en")))
        assertEquals(Language.DE, detectLang(listOf("de")))
        assertEquals(Language.DE, detectLang("de-AT"))
    }

    @Test
    fun detectLangPicksEnglishOtherwise() {
        assertEquals(Language.EN, detectLang(listOf("en-US", "de")))
        assertEquals(Language.EN, detectLang(listOf("fr-FR")))
        assertEquals(Language.EN, detectLang(emptyList<String>()))
        assertEquals(Language.EN, detectLang(null as List<String?>?))
        assertEquals(Language.EN, detectLang(null as String?))
    }

    @Test
    fun detectLangUsesTheFirstNonEmptyEntryAndIgnoresTheCase() {
        assertEquals(Language.DE, detectLang(listOf(null, "", "DE-ch", "en")))
        assertEquals(Language.EN, detectLang(listOf("", "en", "de")))
    }

    // t

    @Test
    fun tReturnsTheTextOfTheActiveLanguageWithPlaceholders() {
        val i18n = emptyI18n()
        i18n.registerStrings(
            StringArea(
                de = texts("x.hi" to "Hallo {name}"),
                en = texts("x.hi" to "Hi {name}"),
            ),
        )
        i18n.setLang(Language.DE)
        assertEquals("Hallo Blitz", i18n.t("x.hi", mapOf("name" to "Blitz")))
        i18n.setLang(Language.EN)
        assertEquals(Language.EN, i18n.lang)
        assertEquals("Hi Flash", i18n.t("x.hi", mapOf("name" to "Flash")))
    }

    @Test
    fun tStartsInGermanAndKeepsUnknownPlaceholders() {
        val i18n = emptyI18n()
        i18n.registerStrings(StringArea(de = texts("x.a" to "A {one} {two}"), en = texts("x.a" to "A {one} {two}")))
        assertEquals(Language.DE, i18n.lang)
        assertEquals("A 1 {two}", i18n.t("x.a", mapOf("one" to 1)))
        assertEquals("A {one} {two}", i18n.t("x.a"))
    }

    @Test
    fun tFormatsWholeNumbersLikeJavaScript() {
        val i18n = emptyI18n()
        i18n.registerStrings(StringArea(de = texts("x.n" to "{n} {m}"), en = texts("x.n" to "{n} {m}")))
        assertEquals("30 2.5", i18n.t("x.n", mapOf("n" to 30.0, "m" to 2.5)))
        assertEquals("0 7", i18n.t("x.n", mapOf("n" to -0.0, "m" to 7L)))
    }

    @Test
    fun tFallsBackToGermanThenEnglishForAMissingKey() {
        val i18n = emptyI18n()
        i18n.registerStrings(StringArea(de = texts("x.de" to "nur deutsch"), en = texts("x.en" to "english only")))
        i18n.setLang(Language.EN)
        assertEquals("nur deutsch", i18n.t("x.de"))
        i18n.setLang(Language.DE)
        assertEquals("english only", i18n.t("x.en"))
    }

    @Test
    fun tReportsAnUnknownKeyAndReturnsAnEmptyText() {
        val missing = mutableListOf<String>()
        val i18n = emptyI18n(onMissing = { missing += it })
        assertEquals("", i18n.t("nope.key"))
        assertEquals(listOf("nope.key"), missing)
    }

    @Test
    fun registeringAnAreaAgainOverridesTheSameKeys() {
        val i18n = emptyI18n()
        i18n.registerStrings(StringArea(de = texts("x.k" to "eins"), en = texts("x.k" to "one")))
        i18n.registerStrings(StringArea(de = texts("x.k" to "zwei"), en = texts("x.k" to "two")))
        assertEquals("zwei", i18n.t("x.k"))
    }

    @Test
    fun anI18nWithAllStringsKnowsTheRealTexts() {
        val i18n = I18n()
        assertEquals("Einstellungen", i18n.t("menu.settings"))
        i18n.setLang(Language.EN)
        assertEquals("Settings", i18n.t("menu.settings"))
        assertEquals("Version 1.2", i18n.t("settings.version", mapOf("version" to "1.2")))
    }

    // language changes

    @Test
    fun setLangNotifiesTheListenersOnlyOnARealChange() {
        val i18n = emptyI18n()
        val seen = mutableListOf<Language>()
        i18n.onLangChange { seen += it }
        i18n.setLang(Language.DE)
        i18n.setLang(Language.EN)
        i18n.setLang(Language.EN)
        i18n.setLang(Language.DE)
        assertEquals(listOf(Language.EN, Language.DE), seen)
    }

    @Test
    fun theUnsubscribeFunctionRemovesTheListener() {
        val i18n = emptyI18n()
        val seen = mutableListOf<Language>()
        val off = i18n.onLangChange { seen += it }
        i18n.setLang(Language.EN)
        off()
        i18n.setLang(Language.DE)
        assertEquals(listOf(Language.EN), seen)
    }

    @Test
    fun aListenerMayUnsubscribeItselfDuringTheNotification() {
        val i18n = emptyI18n()
        val calls = mutableListOf<String>()
        var offFirst: () -> Unit = {}
        offFirst =
            i18n.onLangChange {
                calls += "first"
                offFirst()
            }
        i18n.onLangChange { calls += "second" }
        i18n.setLang(Language.EN)
        i18n.setLang(Language.DE)
        assertEquals(listOf("first", "second", "second"), calls)
    }

    // texts() helper

    @Test
    fun textsRejectsADuplicateKey() {
        assertFailsWith<IllegalArgumentException> { texts("a" to "1", "a" to "2") }
    }
}
