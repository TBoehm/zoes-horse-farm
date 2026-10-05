package app.zoeshorsefarm.platform

// Touch mode (rule 11): touch-only devices always, keyboard-only devices never, devices with both
// start without it and switch on touch or on a game key. Phones and tablets are touch devices; a
// tablet with a hardware keyboard can be created as HYBRID by its shell.

/** The device class: what the player can use to steer. */
enum class DeviceClass { TOUCH, KEYBOARD, HYBRID }

/** [touchCapable]: the device has a touch screen; [finePointer]: it also has a mouse or trackpad. */
fun classifyDevice(
    touchCapable: Boolean,
    finePointer: Boolean,
): DeviceClass =
    when {
        !touchCapable -> DeviceClass.KEYBOARD
        finePointer -> DeviceClass.HYBRID
        else -> DeviceClass.TOUCH
    }

/** Whether the on-screen touch controls are shown; the input adapter reports touches and key presses. */
class InputMode(
    val device: DeviceClass,
) {
    private val listeners = LinkedHashSet<(Boolean) -> Unit>()

    var touch: Boolean = device == DeviceClass.TOUCH
        private set

    private fun set(next: Boolean) {
        if (next == touch) return
        touch = next
        for (listener in listeners.toList()) listener(next)
    }

    /** A finger touched the screen (a mouse or trackpad pointer does not call this). */
    fun onTouch() {
        if (device == DeviceClass.HYBRID) set(true)
    }

    /**
     * A key was pressed: [key] is its [GameKey], or null for any other key (those change nothing);
     * typing into a text field does not count.
     */
    fun onKey(
        key: GameKey?,
        inEditableField: Boolean = false,
    ) {
        if (device == DeviceClass.HYBRID && key != null && !inEditableField) set(false)
    }

    /** Calls [listener] with the new value on every change; returns the function that unsubscribes. */
    fun onChange(listener: (Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    companion object {
        /** The mode of a phone or tablet: touch controls always. */
        fun mobile() = InputMode(DeviceClass.TOUCH)
    }
}

/** Taller than wide, in any common unit. */
fun isPortrait(
    width: Int,
    height: Int,
): Boolean = height > width
