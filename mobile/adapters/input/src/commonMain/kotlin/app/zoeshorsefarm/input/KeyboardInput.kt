package app.zoeshorsefarm.input

import app.zoeshorsefarm.platform.GameKey

// Keyboard input (rule 8): A/D steer, W/S speed, Shift (held) gallop, Space jump,
// Esc pause, C camera. A gallop that the game ends needs Shift to be pressed again (rule 9).
// Platform-neutral: the UI maps its key events to [GameKey] and calls onKeyDown/onKeyUp.

/**
 * @param isActive false while the ride is paused or another screen is on top; the keyboard then
 *   neither records nor consumes key presses (releases are always recorded).
 */
class KeyboardInput(
    private val isActive: () -> Boolean = { true },
) {
    private val down = BooleanArray(GameKey.entries.size)
    private var shiftLatched = false

    // A mode switch caused by the Shift key press itself happens before this class sees the key
    private var latchNextShiftPress = false
    private var jump = false
    private var pause = false
    private var camera = false

    private fun isDown(key: GameKey) = down[key.ordinal]

    private fun isShift() = isDown(GameKey.SHIFT_LEFT) || isDown(GameKey.SHIFT_RIGHT)

    /** True while a Shift key is physically held (also while a latch blocks the gallop). */
    val shiftHeld: Boolean get() = isShift()

    /**
     * A key was pressed. Returns true if the game consumed it (the UI should not handle it any
     * further, web: `preventDefault`), false while the keyboard is not [isActive].
     * [repeat]: auto-repeat of a held key, consumed but not counted.
     */
    fun onKeyDown(
        key: GameKey,
        repeat: Boolean = false,
    ): Boolean {
        if (!isActive()) return false
        if (repeat) return true
        down[key.ordinal] = true
        if (key == GameKey.SHIFT_LEFT || key == GameKey.SHIFT_RIGHT) {
            if (latchNextShiftPress) shiftLatched = true
        } else {
            latchNextShiftPress = false
        }
        when (key) {
            GameKey.SPACE -> jump = true
            GameKey.ESCAPE -> pause = true
            GameKey.KEY_C -> camera = true
            else -> Unit
        }
        return true
    }

    /** A key was released (always recorded, even while inactive). */
    fun onKeyUp(key: GameKey) {
        down[key.ordinal] = false
        if (!isShift()) shiftLatched = false
    }

    /** The window/app lost focus: no key is held any more. */
    fun onFocusLost() {
        down.fill(false)
        shiftLatched = false
        latchNextShiftPress = false
    }

    /** Reads the state; edges (jump, pause, camera) are reset in the process. */
    fun poll(): InputState {
        val right = isDown(GameKey.KEY_D) || isDown(GameKey.ARROW_RIGHT)
        val left = isDown(GameKey.KEY_A) || isDown(GameKey.ARROW_LEFT)
        val up = isDown(GameKey.KEY_W) || isDown(GameKey.ARROW_UP)
        val back = isDown(GameKey.KEY_S) || isDown(GameKey.ARROW_DOWN)
        val state =
            InputState(
                steer = (if (right) 1.0 else 0.0) - (if (left) 1.0 else 0.0),
                throttle = (if (up) 1.0 else 0.0) - (if (back) 1.0 else 0.0),
                gallop = isShift() && !shiftLatched,
                jump = jump,
                pause = pause,
                camera = camera,
            )
        clearEdges()
        latchNextShiftPress = false
        return state
    }

    /**
     * Gallop ended by the game: active again only after release and a new press.
     * [onNextShiftPress]: also latch a Shift press that arrives right after this call (the mode
     * switch is triggered by that very key press).
     */
    fun latchGallop(onNextShiftPress: Boolean = false) {
        if (isShift()) {
            shiftLatched = true
        } else if (onNextShiftPress) {
            latchNextShiftPress = true
        }
    }

    /** Drops pending jump/pause/camera edges (used when the ride resumes). */
    fun clearEdges() {
        jump = false
        pause = false
        camera = false
    }
}
