package app.zoeshorsefarm.domain.sim

// Seedable random number generator (mulberry32) for deterministic tests and replays.

// constants of mulberry32: state increment, shift widths and the odd multipliers' bits
private const val STATE_INCREMENT = 0x6d2b79f5
private const val SHIFT_FIRST = 15
private const val SHIFT_SECOND = 7
private const val SHIFT_OUTPUT = 14
private const val ODD_FIRST = 1
private const val ODD_SECOND = 61
private const val UINT_MASK = 0xFFFFFFFFL
private const val UINT_RANGE = 4294967296.0

/** Returns a function () -> [0, 1) that produces the same sequence for the same seed. */
fun createRng(seed: Int = 1): () -> Double {
    // the state is an unsigned 32 bit number; Int arithmetic wraps like the JS `>>> 0` / imul code
    var a = seed
    return {
        a += STATE_INCREMENT
        var t = a
        t = (t xor (t ushr SHIFT_FIRST)) * (t or ODD_FIRST)
        t = t xor (t + (t xor (t ushr SHIFT_SECOND)) * (t or ODD_SECOND))
        ((t xor (t ushr SHIFT_OUTPUT)).toLong() and UINT_MASK).toDouble() / UINT_RANGE
    }
}
