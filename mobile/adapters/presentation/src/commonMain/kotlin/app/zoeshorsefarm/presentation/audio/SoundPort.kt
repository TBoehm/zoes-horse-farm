package app.zoeshorsefarm.presentation.audio

import app.zoeshorsefarm.application.RideSound
import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.audio.Audio
import app.zoeshorsefarm.audio.AudioSettings

/**
 * What the screens need from the sound output (web: `services.audio`). [AudioSoundPort] connects it
 * to the synthesiser of `:adapters:audio`; tests use a recorder.
 */
interface SoundPort {
    /** Changes volumes and mutes; fields that are null keep their value. */
    fun setVolumes(
        musicVolume: Double? = null,
        musicMuted: Boolean? = null,
        sfxVolume: Double? = null,
        sfxMuted: Boolean? = null,
    )

    /** The screen wants the menu melody (or not). */
    fun setMusicWanted(wanted: Boolean)

    /** The app is in the background: silent at once. */
    fun setHidden(hidden: Boolean)

    /** The ride is paused: no effects. */
    fun setPaused(paused: Boolean)

    /** An effect the ride session asked for. */
    fun play(sound: RideSound)

    /** One hoof beat; [gait] is "back", "walk", "trot" or "canter". */
    fun hoof(gait: String)

    /** The start signal of a course ride (only on "Go", rule 26). */
    fun startSignal()
}

/** No sound at all (web: `services.audio` missing). */
object SilentSound : SoundPort {
    override fun setVolumes(
        musicVolume: Double?,
        musicMuted: Boolean?,
        sfxVolume: Double?,
        sfxMuted: Boolean?,
    ) = Unit

    override fun setMusicWanted(wanted: Boolean) = Unit

    override fun setHidden(hidden: Boolean) = Unit

    override fun setPaused(paused: Boolean) = Unit

    override fun play(sound: RideSound) = Unit

    override fun hoof(gait: String) = Unit

    override fun startSignal() = Unit
}

/** The [SoundPort] on the real [Audio] service. */
class AudioSoundPort(
    private val audio: Audio,
) : SoundPort {
    override fun setVolumes(
        musicVolume: Double?,
        musicMuted: Boolean?,
        sfxVolume: Double?,
        sfxMuted: Boolean?,
    ) = audio.setVolumes(musicVolume, musicMuted, sfxVolume, sfxMuted)

    override fun setMusicWanted(wanted: Boolean) = audio.setMusicWanted(wanted)

    override fun setHidden(hidden: Boolean) = audio.setHidden(hidden)

    override fun setPaused(paused: Boolean) = audio.setPaused(paused)

    override fun play(sound: RideSound) =
        when (sound) {
            RideSound.TAKEOFF -> audio.sfx.takeoff()
            RideSound.LANDING -> audio.sfx.landing()
            RideSound.RAIL_DOWN -> audio.sfx.railDown()
            RideSound.FINISH_SIGNAL -> audio.sfx.finishSignal()
        }

    override fun hoof(gait: String) = audio.sfx.hoof(gait)

    override fun startSignal() = audio.sfx.startSignal()
}

/** The volumes of the saved settings in the form the audio service starts with (`createAudio(settings)`). */
fun Settings.toAudioSettings(): AudioSettings = AudioSettings(musicVolume, musicMuted, sfxVolume, sfxMuted)
