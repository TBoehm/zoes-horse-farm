package app.zoeshorsefarm.input

/**
 * Merges keyboard and touch into one [InputState] (contract: architecture "Eingabe").
 *
 * @param touchMode the touch mode (rule 11) at the start; later changes come in through
 *   [onTouchModeChange].
 * @param isActive see [KeyboardInput]
 */
class Input(
    touchMode: Boolean,
    isActive: () -> Boolean = { true },
    val keyboard: KeyboardInput = KeyboardInput(isActive),
    val touch: TouchInput = TouchInput(),
) {
    init {
        touch.setVisible(touchMode)
    }

    /**
     * The touch mode switched (call it on every change of the input mode). Rules 9/11: switching ends an
     * active gallop. The switch is often caused by the Shift key itself (the mode detection sees its key
     * press first), so call this BEFORE passing that key press to [keyboard]: it must not count.
     */
    fun onTouchModeChange(on: Boolean) {
        touch.setVisible(on)
        touch.setGallop(false)
        keyboard.latchGallop(onNextShiftPress = true)
    }

    fun poll(): InputState = mergeInputs(keyboard.poll(), touch.poll())

    /** The game ends the gallop (refusal, fence): touch off, shift must be pressed again. */
    fun endGallop() {
        touch.setGallop(false)
        keyboard.latchGallop()
    }

    /** After "Continue": drops pending edges of both sources, the gallop state stays. */
    fun clearEdges() {
        keyboard.clearEdges()
        touch.clearEdges()
    }

    fun resetTouchGallop() {
        touch.setGallop(false)
    }
}
