package app.zoeshorsefarm.application

/**
 * Graphics quality levels a player (or the automatic) can select. Single source of truth for the
 * settings schema and the 3D quality presets.
 */
enum class GraphicsLevel(
    val id: String,
) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    companion object {
        fun fromId(id: String?): GraphicsLevel? = entries.firstOrNull { it.id == id }
    }
}

val GRAPHICS_LEVELS: List<GraphicsLevel> = GraphicsLevel.entries

/**
 * The level "Automatic" starts at (first start, and every time "Automatic" is selected anew, rule 4):
 * the safest one. The automatic works its way up from here while the device has room to spare.
 */
val AUTO_START_LEVEL: GraphicsLevel = GraphicsLevel.LOW

/**
 * Seconds after the app went to the background or came back to the foreground in which a loss of
 * the 3D picture says nothing about the device (technical value, no game play). Used by the
 * context-loss rule (quality) and the crash guard.
 */
const val FOREGROUND_GRACE_S: Double = 3.0
