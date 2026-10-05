package app.zoeshorsefarm.domain.horse

// Horse appearance values the player can choose from (concept: "My horse"). The [id] is what the
// save file stores and the texts are keyed by.

enum class Coat(
    val id: String,
) {
    CHESTNUT("chestnut"),
    BAY("bay"),
    BLACK("black"),
    GREY("grey"),
    PINTO("pinto"),
    ;

    companion object {
        /** The coat with this stored id, or null. */
        fun fromId(id: String?): Coat? = entries.firstOrNull { it.id == id }
    }
}

enum class Marking(
    val id: String,
) {
    NONE("none"),
    STAR("star"),
    BLAZE("blaze"),
    SNIP("snip"),
    ;

    companion object {
        /** The marking with this stored id, or null. */
        fun fromId(id: String?): Marking? = entries.firstOrNull { it.id == id }
    }
}

/** All coats, in the order of the choice screen. */
val COATS: List<Coat> = Coat.entries

/** All markings, in the order of the choice screen. */
val MARKINGS: List<Marking> = Marking.entries

/** Coat and marking of a horse. */
data class Appearance(
    val coat: Coat,
    val marking: Marking,
)

/** Appearance of a new horse; single source for the save schema and the 3D view. */
val DEFAULT_APPEARANCE = Appearance(Coat.BAY, Marking.STAR)
