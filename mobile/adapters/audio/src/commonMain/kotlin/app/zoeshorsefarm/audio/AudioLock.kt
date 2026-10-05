package app.zoeshorsefarm.audio

/**
 * Reentrant lock that serialises the callers of the [Audio] facade. The audio thread never takes it
 * (the engine is lock-free, see [AudioEngine]), so holding it while calling the platform output is safe.
 */
internal expect class AudioLock() {
    fun lock()

    fun unlock()
}

internal inline fun <T> AudioLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
