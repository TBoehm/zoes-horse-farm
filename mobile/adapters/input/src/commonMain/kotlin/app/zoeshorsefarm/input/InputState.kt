package app.zoeshorsefarm.input

import app.zoeshorsefarm.shared.clamp

// The contract between input and game (architecture "Eingabe"): one InputState per poll.

/**
 * One poll of the controls. [steer]: -1 left .. +1 right; [throttle]: -1 slower/reverse .. +1 faster;
 * [gallop]: the gallop control is on; [jump], [pause], [camera]: one-shot edges (true for exactly one poll).
 */
data class InputState(
    val steer: Double = 0.0,
    val throttle: Double = 0.0,
    val gallop: Boolean = false,
    val jump: Boolean = false,
    val pause: Boolean = false,
    val camera: Boolean = false,
)

/** Combines the keyboard and touch states into one InputState. */
fun mergeInputs(
    k: InputState,
    tc: InputState,
): InputState =
    InputState(
        steer = clamp(k.steer + tc.steer, -1.0, 1.0),
        throttle = clamp(k.throttle + tc.throttle, -1.0, 1.0),
        gallop = k.gallop || tc.gallop,
        jump = k.jump || tc.jump,
        pause = k.pause || tc.pause,
        camera = k.camera || tc.camera,
    )
