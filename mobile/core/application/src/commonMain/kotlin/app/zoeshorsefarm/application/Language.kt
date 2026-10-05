package app.zoeshorsefarm.application

/** Languages the game offers (rule 6). The [id] is what the save game stores. */
enum class Language(
    val id: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        fun fromId(id: String?): Language? = entries.firstOrNull { it.id == id }
    }
}

/** All offered languages; single source for the save schema and the UI. */
val LANGS: List<Language> = Language.entries
