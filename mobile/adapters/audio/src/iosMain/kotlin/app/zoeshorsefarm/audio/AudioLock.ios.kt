package app.zoeshorsefarm.audio

import platform.Foundation.NSRecursiveLock

internal actual class AudioLock {
    private val delegate = NSRecursiveLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}
