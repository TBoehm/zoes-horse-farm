package app.zoeshorsefarm.presentation.help

// What the controls help shows (rule 56, rule 8, rule 10): plain data, the screen renders it.
// A key cap is a [label] shown as is or a [labelKey] (a translated text, e.g. the space bar).
// `keys` is a list of alternatives; each alternative is a group of key caps shown side by side.

/** The two kinds of input the help explains; [id] gives the text key `help.mode.<id>`. */
enum class HelpMode(
    val id: String,
) {
    KEYBOARD("keyboard"),
    TOUCH("touch"),
    ;

    companion object {
        fun fromId(id: String?): HelpMode? = entries.firstOrNull { it.id == id }
    }
}

data class KeyCap(
    val label: String? = null,
    val labelKey: String? = null,
)

/** How a touch glyph looks: it copies the touch controls of the ride. */
enum class GlyphKind {
    STICK,
    GALLOP,
    JUMP,
    SMALL,
}

/**
 * A glyph that looks like a touch control. [symbol] is a decorative character, [labelKey] the label
 * of the button, [nameKey] the text a screen reader gets for a symbol without a label.
 */
data class HelpGlyph(
    val kind: GlyphKind,
    val symbol: String? = null,
    val labelKey: String? = null,
    val nameKey: String? = null,
)

/** One line of the help: the visual ([keys] for the keyboard, [glyphs] for touch) and its text. */
data class HelpRow(
    val id: String,
    val textKey: String,
    val keys: List<List<KeyCap>> = emptyList(),
    val glyphs: List<HelpGlyph> = emptyList(),
)

private fun key(label: String) = KeyCap(label = label)

val KEYBOARD_ROWS: List<HelpRow> =
    listOf(
        HelpRow("faster", "help.kb.faster", keys = listOf(listOf(key("W")), listOf(key("↑")))),
        HelpRow("slower", "help.kb.slower", keys = listOf(listOf(key("S")), listOf(key("↓")))),
        HelpRow(
            "steer",
            "help.kb.steer",
            keys = listOf(listOf(key("A"), key("D")), listOf(key("←"), key("→"))),
        ),
        HelpRow("gallop", "help.kb.gallop", keys = listOf(listOf(key("Shift")))),
        HelpRow("jump", "help.kb.jump", keys = listOf(listOf(KeyCap(labelKey = "help.key.space")))),
        HelpRow("camera", "help.kb.camera", keys = listOf(listOf(key("C")))),
        HelpRow("pause", "help.kb.pause", keys = listOf(listOf(key("Esc")))),
    )

// Glyphs look like the touch controls of the ride: the joystick, "Canter", "Jump", and the small
// camera and pause buttons.
val TOUCH_ROWS: List<HelpRow> =
    listOf(
        HelpRow(
            "speed",
            "help.touch.speed",
            glyphs = listOf(HelpGlyph(GlyphKind.STICK, symbol = "↕", nameKey = "help.joystick")),
        ),
        HelpRow(
            "steer",
            "help.touch.steer",
            glyphs = listOf(HelpGlyph(GlyphKind.STICK, symbol = "↔", nameKey = "help.joystick")),
        ),
        HelpRow(
            "gallop",
            "help.touch.gallop",
            glyphs = listOf(HelpGlyph(GlyphKind.GALLOP, labelKey = "ride.touch.gallop")),
        ),
        HelpRow(
            "jump",
            "help.touch.jump",
            glyphs = listOf(HelpGlyph(GlyphKind.JUMP, labelKey = "ride.touch.jump")),
        ),
        HelpRow(
            "cameraPause",
            "help.touch.cameraPause",
            glyphs = listOf(HelpGlyph(GlyphKind.SMALL, symbol = "🎥"), HelpGlyph(GlyphKind.SMALL, symbol = "❚❚")),
        ),
    )

val HELP_MODES: List<HelpMode> = listOf(HelpMode.KEYBOARD, HelpMode.TOUCH)

/** The mode shown first: the one that matches the current input (rule 56). */
fun defaultHelpMode(touch: Boolean): HelpMode = if (touch) HelpMode.TOUCH else HelpMode.KEYBOARD

fun rowsFor(mode: HelpMode): List<HelpRow> = if (mode == HelpMode.TOUCH) TOUCH_ROWS else KEYBOARD_ROWS
