package app.zoeshorsefarm.presentation.audio

import app.zoeshorsefarm.application.SoundChannel
import app.zoeshorsefarm.audio.AudioSettings
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.nav.finishedParams
import kotlin.test.Test
import kotlin.test.assertEquals

private class MusicScreen(
    override val music: Boolean,
    override val musicDelayMs: Long = 0,
) : ScreenModel

class AudioWiringTest {
    private val app = TestApp()
    private val lifecycle = app.lifecycle
    private val wiring = AudioWiring(app.ctx)

    init {
        app.navigator.register("menu") { MusicScreen(music = true) }
        app.navigator.register("ride") { MusicScreen(music = false) }
        app.navigator.register("results") { MusicScreen(music = true, musicDelayMs = 1500) }
    }

    @Test
    fun theStoredVolumesGoToTheAudioWhenTheyChange() {
        app.settings.setVolume(SoundChannel.MUSIC, 0.8)
        assertEquals("volumes 0.8 false 0.5 false", app.sound.calls.last())
        app.settings.setMuted(SoundChannel.SFX, true)
        assertEquals("volumes 0.8 false 0.5 true", app.sound.calls.last())
    }

    @Test
    fun theMusicWishOfTheScreenGoesToTheAudio() {
        app.navigator.go(Route.Menu)
        assertEquals(listOf("music true"), app.sound.calls)
        app.navigator.go(Route.Ride())
        assertEquals(listOf("music true", "music false"), app.sound.calls)
    }

    @Test
    fun aScreenWithADelayStartsTheMusicLater() {
        app.navigator.go(Route.Ride())
        app.sound.calls.clear()
        app.navigator.go(Route.Results(finishedParams()))
        assertEquals(emptyList(), app.sound.calls)
        app.scheduler.advance(1500)
        assertEquals(listOf("music true"), app.sound.calls)
    }

    @Test
    fun theBackgroundMutesTheAudioAndTheForegroundBringsItBack() {
        lifecycle.update(AppState.BACKGROUND)
        lifecycle.update(AppState.FOREGROUND)
        assertEquals(listOf("hidden true", "hidden false"), app.sound.calls)
    }

    @Test
    fun theAudioStartsWithTheSavedSettings() {
        app.settings.setVolume(SoundChannel.SFX, 0.25)
        app.settings.setMuted(SoundChannel.MUSIC, true)
        assertEquals(
            AudioSettings(0.5, true, 0.25, false),
            app.ctx.settings
                .get()
                .toAudioSettings(),
        )
    }

    @Test
    fun disposeStopsListening() {
        wiring.dispose()
        app.settings.setVolume(SoundChannel.MUSIC, 0.1)
        lifecycle.update(AppState.BACKGROUND)
        app.navigator.go(Route.Menu)
        assertEquals(emptyList(), app.sound.calls)
    }
}
