package app.zoeshorsefarm.input

import app.zoeshorsefarm.shared.clamp

// The contract between input and game (architecture "Eingabe"): one InputState per poll.

/**
 * One poll of the controls. [steer]: -1 left .. +1 right; [throttle]: -1 slower/reverse .. +1 faster;
 * [gallop]: the gallop control is on; [jump], [pause], [camera]: one-shot edges (true for exactly one poll).
 *
 * Mutable on purpose: the sources and [Input] each own one instance and refill it on every poll, so a
 * frame allocates nothing (like `SimInput`). The state a `poll()` returns is valid until the next poll
 * of the same source; copy the values you need to keep.
 */
data class InputState(
    var steer: Double = 0.0,
    var throttle: Double = 0.0,
    var gallop: Boolean = false,
    var jump: Boolean = false,
    var pause: Boolean = false,
    var camera: Boolean = false,
) {
    /** Overwrites all six values. */
    fun set(
        steer: Double,
        throttle: Double,
        gallop: Boolean,
        jump: Boolean,
        pause: Boolean,
        camera: Boolean,
    ) {
        this.steer = steer
        this.throttle = throttle
        this.gallop = gallop
        this.jump = jump
        this.pause = pause
        this.camera = camera
    }
}

/**
 * Combines the keyboard and touch states into [out] (a new state when omitted; pass a reused one to
 * avoid the allocation). [out] may be neither [k] nor [tc]. Returns [out].
 */
fun mergeInputs(
    k: InputState,
    tc: InputState,
    out: InputState = InputState(),
): InputState {
    out.set(
        steer = clamp(k.steer + tc.steer, -1.0, 1.0),
        throttle = clamp(k.throttle + tc.throttle, -1.0, 1.0),
        gallop = k.gallop || tc.gallop,
        jump = k.jump || tc.jump,
        pause = k.pause || tc.pause,
        camera = k.camera || tc.camera,
    )
    return out
}
