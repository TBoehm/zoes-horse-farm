package app.zoeshorsefarm.audio

/**
 * Reentrant lock that serialises the game thread (facade calls) with the audio thread (render
 * callback). Held only for short moments: scheduling a sound or rendering one block.
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
