package app.zoeshorsefarm.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioEngineTest {
    private val sampleRate = 44100

    private fun engine(
        settings: AudioSettings = AudioSettings(),
        hidden: Boolean = false,
    ) = AudioEngine(sampleRate, settings, hidden, AudioLock())

    /** Renders [seconds] in blocks of [block] frames and returns the left channel. */
    private fun AudioEngine.render(
        seconds: Double,
        block: Int = 512,
    ): FloatArray {
        val total = (seconds * sampleRate).toInt()
        val out = FloatArray(total)
        val left = FloatArray(block)
        val right = FloatArray(block)
        var done = 0
        while (done < total) {
            val n = minOf(block, total - done)
            render(left, right, n)
            left.copyInto(out, done, 0, n)
            done += n
        }
        return out
    }

    private fun peak(
        x: FloatArray,
        fromSeconds: Double = 0.0,
        toSeconds: Double = Double.MAX_VALUE,
    ): Float {
        val from = (fromSeconds * sampleRate).toInt().coerceAtMost(x.size)
        val to = if (toSeconds == Double.MAX_VALUE) x.size else (toSeconds * sampleRate).toInt().coerceAtMost(x.size)
        var p = 0f
        for (i in from until to) p = maxOf(p, abs(x[i]))
        return p
    }

    private fun AudioEngine.sfx(play: (VoiceContext, app.zoeshorsefarm.audio.synth.GainBus, Double) -> Unit) =
        playSfx(play)

    @Test
    fun staysSilentWithoutSounds() {
        val e = engine()
        assertEquals(0f, peak(e.render(0.2)))
        assertEquals(0, e.activeVoices)
    }

    @Test
    fun anEffectSoundsAndThenDiesOut() {
        val e = engine()
        e.sfx { v, out, t -> hoof(v, out, t, "canter") }
        val out = e.render(1.0)
        assertTrue(peak(out, 0.0, 0.15) > 0.05f, "audible")
        assertTrue(peak(out, 0.5, 1.0) < 1e-4f, "silent afterwards")
        assertEquals(0, e.activeVoices)
    }

    @Test
    fun anEffectStartsAfterTheSchedulingLatencyAndTheLookAheadDelay() {
        val e = engine()
        e.sfx { v, out, t -> hoof(v, out, t, "trot") }
        val out = e.render(0.1)
        // 4 ms scheduling offset + 6 ms pre-delay of the compressor
        assertEquals(0f, peak(out, 0.0, 0.009))
        assertTrue(peak(out, 0.01, 0.1) > 0.01f)
    }

    @Test
    fun anUnknownGaitStartsNoVoice() {
        val e = engine()
        e.sfx { v, out, t -> hoof(v, out, t, "halt") }
        assertEquals(0L, e.voicesStarted)
        assertEquals(0, e.activeVoices)
    }

    @Test
    fun everyEffectStartsVoices() {
        val effects =
            mapOf<String, (VoiceContext, app.zoeshorsefarm.audio.synth.GainBus, Double) -> Unit>(
                "back" to { v, o, t -> hoof(v, o, t, "back") },
                "walk" to { v, o, t -> hoof(v, o, t, "walk") },
                "trot" to { v, o, t -> hoof(v, o, t, "trot") },
                "canter" to { v, o, t -> hoof(v, o, t, "canter") },
                "takeoff" to { v, o, t -> takeoff(v, o, t) },
                "landing" to { v, o, t -> landing(v, o, t) },
                "railDown" to { v, o, t -> railDown(v, o, t) },
                "startSignal" to { v, o, t -> startSignal(v, o, t) },
                "finishSignal" to { v, o, t -> finishSignal(v, o, t) },
            )
        for ((name, play) in effects) {
            val e = engine()
            e.sfx(play)
            assertTrue(e.voicesStarted > 0, name)
            assertTrue(peak(e.render(0.8)) > 0.01f, name)
        }
    }

    @Test
    fun aQuieterChannelGainMakesEffectsQuieter() {
        fun levelAt(sfxVolume: Double): Float {
            val e = engine(AudioSettings(sfxVolume = sfxVolume))
            e.sfx { v, out, t -> hoof(v, out, t, "canter") }
            return peak(e.render(0.3))
        }
        assertTrue(levelAt(0.25) < levelAt(0.5))
        assertTrue(levelAt(0.5) < levelAt(1.0))
    }

    @Test
    fun channelGainsFadeSmoothlyToTheirTarget() {
        val e = engine(AudioSettings(musicVolume = 0.5, sfxVolume = 0.5))
        assertEquals(0.25, e.musicChannelGain, 1e-9)
        e.setChannelTargets(musicGain = 0.64, sfxGain = 0.0)
        e.render(0.01)
        assertTrue(e.musicChannelGain > 0.25 && e.musicChannelGain < 0.64, "still moving")
        e.render(0.3)
        assertEquals(0.64, e.musicChannelGain, 1e-3)
        assertEquals(0.0, e.sfxChannelGain, 1e-3)
    }

    @Test
    fun aMutedEffectsChannelIsSilent() {
        val e = engine()
        e.setChannelTargets(musicGain = 0.25, sfxGain = 0.0)
        e.render(0.3)
        e.sfx { v, out, t -> landing(v, out, t) }
        assertTrue(peak(e.render(0.5)) < 1e-4f)
    }

    @Test
    fun hidingFadesTheMasterOutToSilence() {
        val e = engine()
        e.setMasterHidden(true)
        e.render(0.3)
        e.sfx { v, out, t -> landing(v, out, t) }
        assertTrue(peak(e.render(0.5)) < 1e-4f)
        e.setMasterHidden(false)
        e.render(0.3)
        e.sfx { v, out, t -> landing(v, out, t) }
        assertTrue(peak(e.render(0.5)) > 0.05f)
    }

    @Test
    fun theEngineCanStartHidden() {
        val e = engine(hidden = true)
        e.sfx { v, out, t -> landing(v, out, t) }
        assertEquals(0f, peak(e.render(0.3)))
    }

    @Test
    fun droppingTheEffectsSessionCutsRunningEffects() {
        fun run(drop: Boolean): FloatArray {
            val e = engine()
            e.sfx { v, out, t -> railDown(v, out, t) }
            e.render(0.05)
            if (drop) e.dropSfxSession()
            return e.render(0.8)
        }
        assertTrue(peak(run(drop = false), 0.3, 0.5) > 0.02f, "the rail impact rings at about 0.3 s")
        assertTrue(peak(run(drop = true), 0.15, 0.8) < 1e-4f)
    }

    @Test
    fun effectsAfterADroppedSessionPlayInANewSession() {
        val e = engine()
        e.sfx { v, out, t -> landing(v, out, t) }
        e.dropSfxSession()
        e.render(0.05)
        e.sfx { v, out, t -> landing(v, out, t) }
        assertTrue(peak(e.render(0.5), 0.05, 0.5) > 0.05f)
    }

    @Test
    fun theMelodyPlaysAndKeepsSchedulingNotes() {
        val e = engine()
        assertEquals(false, e.musicRunning)
        e.startMusic()
        assertEquals(true, e.musicRunning)
        val out = e.render(4.0)
        assertTrue(peak(out, 0.5, 4.0) > 0.01f)
        // 4 s are about 13 steps; every step has several notes
        assertTrue(e.voicesStarted > 30, "started ${e.voicesStarted}")
    }

    @Test
    fun theMelodyFadesInSoftly() {
        val e = engine()
        e.startMusic()
        val out = e.render(2.0)
        assertTrue(peak(out, 0.0, 0.1) < peak(out, 1.0, 2.0) * 0.5f)
    }

    @Test
    fun stoppingTheMelodyFadesItOutAndTheReverbTailRingsOut() {
        val e = engine()
        e.startMusic()
        e.render(2.0)
        e.stopMusic(fast = false)
        assertEquals(false, e.musicRunning)
        e.render(1.2) // the run is released after 0.9 s
        val tail = e.render(0.5)
        assertTrue(peak(tail) > 1e-6f, "reverb tail")
        assertTrue(peak(tail) < 0.01f)
        val later = e.render(3.0)
        assertTrue(peak(later, 2.0, 3.0) < 1e-5f, "silent at the end of the tail")
        assertEquals(0, e.activeVoices)
    }

    @Test
    fun aFastStopCutsTheMelodyQuickly() {
        val e = engine()
        e.startMusic()
        e.render(2.0)
        e.stopMusic(fast = true)
        e.render(0.4)
        assertEquals(0, e.activeVoices)
    }

    @Test
    fun theMelodyCanBeRestartedAfterAStop() {
        val e = engine()
        e.startMusic()
        e.render(1.0)
        e.stopMusic(fast = false)
        e.render(0.05)
        e.startMusic()
        e.render(0.6)
        assertTrue(e.musicRunning)
        assertTrue(peak(e.render(1.0)) > 0.01f)
    }

    @Test
    fun theOutputDoesNotDependOnTheBlockSize() {
        fun run(block: Int): FloatArray {
            val e = engine()
            e.startMusic()
            e.sfx { v, out, t -> hoof(v, out, t, "walk") }
            return e.render(1.5, block)
        }
        assertContentEquals(run(512), run(77))
    }

    @Test
    fun theOutputIsReproducible() {
        fun run(): FloatArray {
            val e = engine()
            e.startMusic()
            e.sfx { v, out, t -> landing(v, out, t) }
            return e.render(1.0)
        }
        assertContentEquals(run(), run())
    }

    @Test
    fun theClockAdvancesInQuanta() {
        val e = engine()
        assertEquals(0.0, e.currentTime)
        e.render(FloatArray(1), FloatArray(1), 1)
        assertEquals(128.0 / sampleRate, e.currentTime, 1e-12)
        e.render(FloatArray(200), FloatArray(200), 200)
        assertEquals(256.0 / sampleRate, e.currentTime, 1e-12)
    }

    @Test
    fun anExhaustedVoicePoolDropsNotesWithoutFailing() {
        val e = engine()
        repeat(30) { e.sfx { v, out, t -> railDown(v, out, t) } }
        assertTrue(e.voicesDropped > 0)
        assertTrue(peak(e.render(0.3)) > 0f)
    }

    @Test
    fun theOutputStaysWithinFullScaleEvenWithEverythingPlaying() {
        val e = engine()
        e.startMusic()
        repeat(4) {
            e.sfx { v, out, t -> landing(v, out, t) }
            e.sfx { v, out, t -> finishSignal(v, out, t) }
            e.sfx { v, out, t -> hoof(v, out, t, "canter") }
        }
        assertTrue(peak(e.render(2.0)) <= 1.0f)
    }
}
