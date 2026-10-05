package app.zoeshorsefarm.presentation.audio

import app.zoeshorsefarm.presentation.UiScheduler
import app.zoeshorsefarm.presentation.UiTask

/**
 * Music per screen (rule 51), with an optional start delay: the results screen waits a moment so that
 * the melody does not clash with the finish signal.
 *
 * @param setWanted switches the menu melody on or off
 */
class MusicGate(
    private val scheduler: UiScheduler,
    private val setWanted: (Boolean) -> Unit,
) {
    private var timer: UiTask? = null
    private var wanted = false

    private fun apply(value: Boolean) {
        wanted = value
        setWanted(value)
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    /** A screen opened: [music] says whether it wants the melody, [musicDelayMs] when to start it. */
    fun onScreen(
        music: Boolean,
        musicDelayMs: Long = 0,
    ) {
        cancelTimer()
        when {
            !music -> {
                apply(false)
            }

            // music that already plays is not delayed again (e.g. a language change on the screen)
            musicDelayMs > 0 && !wanted -> {
                timer =
                    scheduler.postDelayed(musicDelayMs) {
                        timer = null
                        apply(true)
                    }
            }

            else -> {
                apply(true)
            }
        }
    }

    /** Drops a pending delayed start. */
    fun dispose() = cancelTimer()
}
