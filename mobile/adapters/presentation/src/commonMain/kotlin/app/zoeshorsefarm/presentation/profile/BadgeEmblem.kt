package app.zoeshorsefarm.presentation.profile

private val ICONS: Map<String, String> =
    mapOf(
        "firstJump" to "🐎",
        "jumpMouse" to "🐭",
        "clean" to "✨",
        "oxerPro" to "🏅",
        "comboPro" to "🎯",
        "allOpen" to "🔓",
        "starRider" to "⭐",
        "busy" to "🐝",
    )

/** The icon shown in the badge emblem; a trophy for a badge without one of its own. */
const val DEFAULT_BADGE_ICON = "🏆"

/** The emblem character of a badge (web: `badgeEmblem`): the UI draws it in a round frame, grey until earned. */
fun badgeIcon(id: String): String = ICONS[id] ?: DEFAULT_BADGE_ICON
