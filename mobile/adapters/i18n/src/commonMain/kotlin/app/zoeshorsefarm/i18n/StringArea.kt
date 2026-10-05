package app.zoeshorsefarm.i18n

/** The texts of one feature area: a dictionary per language (web: the `{ de, en }` default export). */
data class StringArea(
    val de: Map<String, String>,
    val en: Map<String, String>,
)

/** Builds a text table; a key listed twice is a bug (a plain `mapOf` would silently keep the last one). */
fun texts(vararg entries: Pair<String, String>): Map<String, String> {
    val table = LinkedHashMap<String, String>(entries.size)
    for ((key, text) in entries) {
        require(table.put(key, text) == null) { "Duplicate text key: $key" }
    }
    return table
}
