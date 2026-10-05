package app.zoeshorsefarm.platform

/**
 * The one list of keys the game reacts to (web: `GAME_KEYS`). The keyboard adapter (`:adapters:input`)
 * handles exactly these keys, and pressing any of them ends the touch mode (rule 11, [InputMode]), so
 * the two must never drift apart: both use this enum. [code] is the web `KeyboardEvent.code` the key
 * was ported from; a hardware keyboard on a tablet maps its key events to the enum.
 */
enum class GameKey(
    val code: String,
) {
    KEY_W("KeyW"),
    KEY_A("KeyA"),
    KEY_S("KeyS"),
    KEY_D("KeyD"),
    ARROW_UP("ArrowUp"),
    ARROW_DOWN("ArrowDown"),
    ARROW_LEFT("ArrowLeft"),
    ARROW_RIGHT("ArrowRight"),
    SHIFT_LEFT("ShiftLeft"),
    SHIFT_RIGHT("ShiftRight"),
    SPACE("Space"),
    ESCAPE("Escape"),
    KEY_C("KeyC"),
    ;

    companion object {
        /** The game key for a web key code, or null for every other key. */
        fun fromCode(code: String?): GameKey? = entries.firstOrNull { it.code == code }
    }
}
