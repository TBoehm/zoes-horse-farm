package app.zoeshorsefarm.domain.sim

// Seedable random number generator (mulberry32) for deterministic tests and replays.

/** Returns a function () -> [0, 1) that produces the same sequence for the same seed. */
fun createRng(seed: Int = 1): () -> Double {
    // the state is an unsigned 32 bit number; Int arithmetic wraps like the JS `>>> 0` / imul code
    var a = seed
    return {
        a += 0x6d2b79f5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}
