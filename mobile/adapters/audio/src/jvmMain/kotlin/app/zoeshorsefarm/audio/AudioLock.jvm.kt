package app.zoeshorsefarm.audio

import java.util.concurrent.locks.ReentrantLock

internal actual class AudioLock {
    private val delegate = ReentrantLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}
