package app.zoeshorsefarm.input

// State of the touch controls (rule 10): joystick on the left, "Gallop" (toggle) and "Jump" on the
// right, "Pause" and "Camera" at the top right. This class holds only the state; the Compose UI
// draws the controls and feeds the stick position and button presses in.

class TouchInput {
    private var stick = StickOutput.NEUTRAL
    private var jump = false
    private var pause = false
    private var camera = false

    /** The gallop button is on (a toggle). */
    var gallop: Boolean = false
        private set

    /** The touch controls are shown (touch mode). */
    var visible: Boolean = false
        private set

    /** The stick moved: [force] 0..1 of the stick radius, [radian] 0 = right, PI/2 = up. */
    fun moveStick(
        force: Double,
        radian: Double,
    ) {
        stick = mapStick(force, radian)
    }

    /** The stick moved to ([x], y UP) in stick-radius units, see [mapStickXY]. */
    fun moveStickXY(
        x: Double,
        y: Double,
    ) {
        stick = mapStickXY(x, y)
    }

    /** The thumb left the stick. */
    fun releaseStick() {
        stick = StickOutput.NEUTRAL
    }

    fun setGallop(on: Boolean) {
        gallop = on
    }

    fun toggleGallop() = setGallop(!gallop)

    fun pressJump() {
        jump = true
    }

    fun pressPause() {
        pause = true
    }

    fun pressCamera() {
        camera = true
    }

    /** Shows or hides the controls; hidden controls release the stick (the gallop state stays). */
    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (!visible) releaseStick()
    }

    private val state = InputState()

    /** Reads the state; edges (jump, pause, camera) are reset in the process. Reused by the next poll. */
    fun poll(): InputState {
        state.set(
            steer = stick.steer,
            throttle = stick.throttle,
            gallop = gallop,
            jump = jump,
            pause = pause,
            camera = camera,
        )
        clearEdges()
        return state
    }

    /** Drops pending jump/pause/camera edges (stick and gallop stay). */
    fun clearEdges() {
        jump = false
        pause = false
        camera = false
    }
}
