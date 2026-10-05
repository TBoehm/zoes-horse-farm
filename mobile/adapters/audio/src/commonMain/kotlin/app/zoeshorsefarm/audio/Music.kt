// Voices of the menu melody. Each function schedules a note from time `t` onto the output `out`.
package app.zoeshorsefarm.audio

import app.zoeshorsefarm.audio.synth.FilterType
import app.zoeshorsefarm.audio.synth.GainBus
import app.zoeshorsefarm.audio.synth.OscType

private val MELODY_DETUNES = doubleArrayOf(-4.0, 4.0)

internal fun playMusicEvent(
    v: VoiceContext,
    out: GainBus,
    ev: MusicEvent,
    t: Double,
) {
    val length = ev.steps * STEP_SECONDS
    val freq = midiToFreq(ev.midi)
    when (ev.voice) {
        MusicVoice.Melody -> {
            for (detune in MELODY_DETUNES) {
                v.sink.softNote(out, t, OscType.Triangle, freq, detune, peak = 0.1, length = length)
            }
        }

        MusicVoice.Bass -> {
            v.sink.softNote(out, t, OscType.Sine, freq, 0.0, peak = 0.3, length = length)
            v.sink.softNote(out, t, OscType.Triangle, freq * 2, 0.0, peak = 0.04, length = length)
        }

        MusicVoice.Arp -> {
            tone(v, out, t, freq = freq, attack = 0.006, decay = 0.38, peak = 0.07)
            tone(v, out, t, type = OscType.Triangle, freq = freq * 2, attack = 0.004, decay = 0.15, peak = 0.012)
        }

        MusicVoice.Tick -> {
            noiseBurst(
                v,
                out,
                t,
                filter = FilterType.Highpass,
                freq = 5200 * jitter(v, 0.05),
                attack = 0.002,
                decay = 0.04,
                peak = 0.03,
            )
        }
    }
}
