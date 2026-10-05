package app.zoeshorsefarm.i18n

import app.zoeshorsefarm.application.LANGS
import app.zoeshorsefarm.application.Language
import kotlin.math.abs

// Bilingual texts (rule 6). Feature areas register their texts with registerStrings.

private val PLACEHOLDER = Regex("""\{(\w+)\}""")

// beyond this a double is printed in exponent form by JavaScript, which no text here needs
private const val MAX_PLAIN_WHOLE_NUMBER = 1e15

/** German if the first preferred language is German, otherwise English (web: `detectLang`). */
fun detectLang(languages: List<String?>?): Language {
    val first = languages?.firstOrNull { !it.isNullOrEmpty() }
    return if (first != null && first.lowercase().startsWith("de")) Language.DE else Language.EN
}

/** [detectLang] for a single language tag. */
fun detectLang(language: String?): Language = detectLang(listOf(language))

/** A parameter as JavaScript's `String(value)` prints it: a whole `Double` has no ".0". */
private fun paramText(value: Any?): String =
    if (value is Double &&
        isPlainWholeNumber(value)
    ) {
        value.toLong().toString()
    } else {
        value.toString()
    }

private fun isPlainWholeNumber(value: Double): Boolean =
    abs(value) < MAX_PLAIN_WHOLE_NUMBER && value == value.toLong().toDouble()

/**
 * The text tables and the current language. Not a global: the app creates one and hands it to the UI.
 *
 * @param areas the areas registered at the start (all areas of the app by default)
 * @param onMissing called with the key of an unknown text (web: `console.error`); the text is then empty
 */
class I18n(
    areas: List<StringArea> = STRING_AREAS,
    private val onMissing: (String) -> Unit = {},
) {
    private val dictionaries: Map<Language, MutableMap<String, String>> =
        LANGS.associateWith { mutableMapOf() }
    private val listeners = LinkedHashSet<(Language) -> Unit>()

    /** The current language, German at the start. */
    var lang: Language = Language.DE
        private set

    init {
        areas.forEach(::registerStrings)
    }

    /** Adds the texts of an area; a key that exists already is replaced. */
    fun registerStrings(area: StringArea) {
        dictionary(Language.DE) += area.de
        dictionary(Language.EN) += area.en
    }

    /** Switches the language and notifies the listeners; the same language again does nothing. */
    fun setLang(next: Language) {
        if (next == lang) return
        lang = next
        for (listener in listeners.toList()) listener(next)
    }

    /** Calls [listener] on every language change. Returns the function that removes it again. */
    fun onLangChange(listener: (Language) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /**
     * The text of [key] in the current language (German, then English as a fallback) with its
     * `{name}` placeholders filled from [params]; a placeholder without a parameter stays as it is.
     */
    fun t(
        key: String,
        params: Map<String, Any?>? = null,
    ): String {
        val text = dictionary(lang)[key] ?: dictionary(Language.DE)[key] ?: dictionary(Language.EN)[key]
        if (text == null) {
            onMissing(key)
            return ""
        }
        if (params == null) return text
        return PLACEHOLDER.replace(text) { match ->
            val name = match.groupValues[1]
            if (params.containsKey(name)) paramText(params[name]) else match.value
        }
    }

    // every Language has a table (built from LANGS)
    private fun dictionary(language: Language): MutableMap<String, String> = dictionaries.getValue(language)
}
