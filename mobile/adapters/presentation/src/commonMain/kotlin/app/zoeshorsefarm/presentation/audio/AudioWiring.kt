package app.zoeshorsefarm.presentation.audio

import app.zoeshorsefarm.platform.AppLifecycle
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.presentation.AppContext

/**
 * Sound wiring (web: `registerAudio`, SRT-006): stored volumes to the audio, the music wish of the
 * screens to the music gate, and the background (app lifecycle) to the audio's hidden state.
 *
 * Not part of it: the unlock of the audio session (the shell calls `Audio.unlock()` and
 * `onUserInteraction()` itself, see the audio module) and the creation of the service, which starts
 * with `settings.get().toAudioSettings()`.
 */
class AudioWiring(
    ctx: AppContext,
    lifecycle: AppLifecycle,
) {
    private val gate = MusicGate(ctx.scheduler, ctx.sound::setMusicWanted)
    private val subscriptions = ArrayList<() -> Unit>()

    init {
        subscriptions +=
            ctx.settings.onChange { s ->
                ctx.sound.setVolumes(s.musicVolume, s.musicMuted, s.sfxVolume, s.sfxMuted)
            }
        subscriptions += ctx.navigator.onScreen { gate.onScreen(it.music, it.musicDelayMs) }
        subscriptions += lifecycle.onChange { ctx.sound.setHidden(it == AppState.BACKGROUND) }
    }

    fun dispose() {
        subscriptions.forEach { it() }
        subscriptions.clear()
        gate.dispose()
    }
}
