package app.zoeshorsefarm.platform

/**
 * The one list of keys the game reacts to (physical key codes as `KeyboardEvent.code` names them;
 * the input adapter maps the platform's key events to them). A hardware keyboard on a tablet
 * handles exactly these keys, and pressing any of them ends the touch mode (rule 11).
 */
val GAME_KEYS: Set<String> =
    setOf(
        "KeyW",
        "KeyA",
        "KeyS",
        "KeyD",
        "ArrowUp",
        "ArrowDown",
        "ArrowLeft",
        "ArrowRight",
        "ShiftLeft",
        "ShiftRight",
        "Space",
        "Escape",
        "KeyC",
    )
