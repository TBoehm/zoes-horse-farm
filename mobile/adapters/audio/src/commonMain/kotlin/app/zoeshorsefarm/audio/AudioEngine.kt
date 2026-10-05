package app.zoeshorsefarm.audio

import app.zoeshorsefarm.audio.synth.AutomationParam
import app.zoeshorsefarm.audio.synth.Biquad
import app.zoeshorsefarm.audio.synth.DynamicsCompressor
import app.zoeshorsefarm.audio.synth.FilterType
import app.zoeshorsefarm.audio.synth.GainBus
import app.zoeshorsefarm.audio.synth.PartitionedConvolver
import app.zoeshorsefarm.audio.synth.QUANTUM
import app.zoeshorsefarm.audio.synth.Synth
import app.zoeshorsefarm.audio.synth.convolverNormalizationScale
import kotlin.math.ceil

internal const val MASTER_LEVEL = 2.0
private const val LOOKAHEAD = 0.15
private const val SMOOTH = 0.02 // Time constant for volume changes (no clicks)
private const val MASTER_SMOOTH = 0.015
private const val MUSIC_LEVEL = 0.8 // Mix the music slightly quieter than the effects
private const val MUSIC_FADE_IN = 0.25
private const val MUSIC_FADE_OUT = 0.1
private const val REVERB_SEND = 0.3
private const val REVERB_SECONDS = 1.8
private const val REVERB_DECAY = 3.0
private const val REVERB_HIGHPASS_HZ = 250.0
private const val REVERB_PARTITION = 512
private const val SFX_START_OFFSET = 0.004
private const val SFX_SESSION_FADE = 0.008
private const val SFX_SESSION_RELEASE = 0.15
private const val RUN_RELEASE = 0.9
private const val RUN_RELEASE_FAST = 0.25
private const val RUN_FADE_FAST = 0.02
private const val BUS_POOL = 4
private const val VOICE_SEED = 2024

/**
 * The synthesiser and mixer behind the audio facade: the node graph of the web app, rendered as
 * PCM. Same structure as in `createAudio`:
 *
 * ```
 * effects -> session gain -> sfx channel -----------------------------+
 * melody  -> run gain -+-> music channel ------------------------------+-> master -> compressor -> out
 *                      +-> highpass 250 Hz -> send 0.3 -> reverb -> music channel
 * ```
 *
 * It renders in quanta of 128 frames like WebAudio and keeps its own clock ([currentTime]) that
 * advances with the rendered frames. The melody sequencer runs inside the render step (a lookahead
 * scheduler on the audio clock), so no timer thread is needed. Rendering does not allocate.
 *
 * Thread safety: [render] runs on the platform's audio thread, the controls on the game thread. Both
 * take [lock], a leaf lock: nothing inside calls out to the platform or the facade, so the facade can
 * safely call platform output methods (which may wait for the audio thread) while it holds its own lock.
 */
internal class AudioEngine(
    val sampleRate: Int,
    settings: AudioSettings,
    hidden: Boolean,
    private val lock: AudioLock,
) : PcmRenderer {
    private val rate = sampleRate.toDouble()
    private val synth = Synth(rate, createNoiseBuffer(sampleRate))
    private val voiceContext = VoiceContext(synth, Mulberry32(VOICE_SEED))
    private val loop = buildLoop()

    private val master = AutomationParam(rate, if (hidden) 0.0 else MASTER_LEVEL)
    private val musicChannel = AutomationParam(rate, channelGain(settings.musicVolume, settings.musicMuted))
    private val sfxChannel = AutomationParam(rate, channelGain(settings.sfxVolume, settings.sfxMuted))

    private val sfxSessions = Array(BUS_POOL) { GainBus(rate) }
    private val musicRuns = Array(BUS_POOL) { GainBus(rate) }
    private var session: GainBus? = null
    private var run: GainBus? = null
    private var runNextTime = 0.0
    private var runStep = 0
    private val plan = StepPlan()

    // Reverb: highpassed send into a convolution with a synthetic room, stereo out
    private val reverbHighpass = Biquad(rate).also { it.configure(FilterType.Highpass, REVERB_HIGHPASS_HZ, 1.0) }
    private val convolver: PartitionedConvolver
    private val reverbTailFrames: Int
    private var reverbFramesLeft = 0

    private val sfxMix = FloatArray(QUANTUM)
    private val musicDry = FloatArray(QUANTUM)
    private val reverbSend = FloatArray(QUANTUM)
    private val wetLeft = FloatArray(QUANTUM)
    private val wetRight = FloatArray(QUANTUM)
    private val outLeft = FloatArray(QUANTUM)
    private val outRight = FloatArray(QUANTUM)
    private var bufferedFrames = 0 // frames of outLeft/outRight not delivered yet
    private val compressor = DynamicsCompressor(sampleRate)

    init {
        val impulse = generateImpulse(sampleRate, seconds = REVERB_SECONDS, decay = REVERB_DECAY)
        val scale = convolverNormalizationScale(impulse, sampleRate)
        convolver = PartitionedConvolver(impulse, REVERB_PARTITION, scale)
        reverbTailFrames = impulse[0].size + 2 * REVERB_PARTITION
    }

    // ---- State for the facade and tests ----

    /** Time of the next quantum on the audio clock, in seconds. */
    val currentTime: Double get() = synth.currentFrame / rate

    val musicRunning: Boolean get() = lock.withLock { run != null }

    val voicesStarted: Long get() = synth.voicesStarted

    val voicesDropped: Long get() = synth.voicesDropped

    val activeVoices: Int get() = synth.activeVoices

    val musicChannelGain: Double get() = musicChannel.peek

    val sfxChannelGain: Double get() = sfxChannel.peek

    val masterGain: Double get() = master.peek

    // ---- Controls (game thread) ----

    /** Fades the channels to their new gains (no clicks while dragging a volume slider). */
    fun setChannelTargets(
        musicGain: Double,
        sfxGain: Double,
    ) = lock.withLock {
        val now = currentTime
        musicChannel.setTargetAtTime(musicGain, now, SMOOTH)
        sfxChannel.setTargetAtTime(sfxGain, now, SMOOTH)
    }

    fun setMasterHidden(hidden: Boolean) =
        lock.withLock {
            master.setTargetAtTime(if (hidden) 0.0 else MASTER_LEVEL, currentTime, MASTER_SMOOTH)
        }

    /** Starts the melody from the beginning with a soft fade-in. */
    fun startMusic() = lock.withLock { startMusicLocked() }

    private fun startMusicLocked() {
        if (run != null) return
        val now = currentTime
        val bus = acquire(musicRuns)
        bus.open(synth.currentFrame, 0.0)
        bus.gain.setValueAtTime(0.0, now)
        bus.gain.setTargetAtTime(MUSIC_LEVEL, now + 0.02, MUSIC_FADE_IN)
        run = bus
        runNextTime = now + 0.08
        runStep = 0
        tickMusic()
    }

    /** Fades the melody out. The fast variant is for the background: the audio stops almost at once. */
    fun stopMusic(fast: Boolean) = lock.withLock { stopMusicLocked(fast) }

    private fun stopMusicLocked(fast: Boolean) {
        val bus = run ?: return
        run = null
        val now = currentTime
        bus.gain.cancelAndHoldAtTime(now)
        bus.gain.setTargetAtTime(0.0, now, if (fast) RUN_FADE_FAST else MUSIC_FADE_OUT)
        bus.killFrame = synth.currentFrame + framesOf(if (fast) RUN_RELEASE_FAST else RUN_RELEASE)
    }

    /** Cuts all running effects (pause, background); later effects use a new session. */
    fun dropSfxSession() = lock.withLock { dropSfxSessionLocked() }

    private fun dropSfxSessionLocked() {
        val old = session ?: return
        session = null
        old.gain.setTargetAtTime(0.0, currentTime, SFX_SESSION_FADE)
        old.killFrame = synth.currentFrame + framesOf(SFX_SESSION_RELEASE)
    }

    /** Plays an effect [recipe] a few milliseconds from now on the current effects session. */
    fun playSfx(recipe: (VoiceContext, GainBus, Double) -> Unit) =
        lock.withLock {
            val bus =
                session ?: acquire(sfxSessions).also {
                    it.open(synth.currentFrame, 1.0)
                    session = it
                }
            recipe(voiceContext, bus, currentTime + SFX_START_OFFSET)
        }

    private fun framesOf(seconds: Double): Long = ceil(seconds * rate).toLong()

    /** A free bus of the pool; when all are in use the one that ends first is cut. */
    private fun acquire(pool: Array<GainBus>): GainBus {
        for (bus in pool) if (!bus.active) return bus
        var oldest = pool[0]
        for (bus in pool) if (bus.killFrame < oldest.killFrame) oldest = bus
        synth.stopVoicesOf(oldest)
        oldest.close()
        if (oldest === session) session = null
        return oldest
    }

    // ---- Melody sequencer ----

    private fun tickMusic() {
        val bus = run ?: return
        planSteps(
            now = currentTime,
            nextTime = runNextTime,
            step = runStep,
            lookahead = LOOKAHEAD,
            stepDuration = STEP_SECONDS,
            loopSteps = LOOP_STEPS,
            into = plan,
        )
        for (i in 0 until plan.count) {
            val events = loop[plan.stepAt(i)]
            val time = plan.timeAt(i)
            for (j in events.indices) playMusicEvent(voiceContext, bus, events[j], time)
        }
        runNextTime = plan.nextTime
        runStep = plan.step
    }

    // ---- Rendering ----

    /** Renders [frames] frames of stereo output. Called on the audio thread. */
    override fun render(
        left: FloatArray,
        right: FloatArray,
        frames: Int,
    ) {
        lock.withLock {
            var done = 0
            while (done < frames) {
                if (bufferedFrames == 0) {
                    renderQuantum()
                    bufferedFrames = QUANTUM
                }
                val n = minOf(bufferedFrames, frames - done)
                val from = QUANTUM - bufferedFrames
                outLeft.copyInto(left, done, from, from + n)
                outRight.copyInto(right, done, from, from + n)
                bufferedFrames -= n
                done += n
            }
        }
    }

    private fun renderQuantum() {
        val frame = synth.currentFrame
        tickMusic()
        endExpiredBuses(frame)
        synth.render(QUANTUM)

        // Effects: sessions -> sfx mix
        sfxMix.fill(0f)
        for (bus in sfxSessions) {
            if (!bus.active) continue
            val buffer = bus.buffer
            for (i in 0 until QUANTUM) sfxMix[i] += buffer[i] * bus.gain.next().toFloat()
            buffer.fill(0f)
        }

        // Melody: runs -> dry mix (also the input of the reverb)
        musicDry.fill(0f)
        var runActive = false
        for (bus in musicRuns) {
            if (!bus.active) continue
            runActive = true
            val buffer = bus.buffer
            for (i in 0 until QUANTUM) musicDry[i] += buffer[i] * bus.gain.next().toFloat()
            buffer.fill(0f)
        }
        renderReverb(runActive)

        // Channels, master and compressor
        for (i in 0 until QUANTUM) {
            val s = sfxMix[i] * sfxChannel.next().toFloat()
            val m = musicChannel.next().toFloat()
            val g = master.next().toFloat()
            outLeft[i] = (s + (musicDry[i] + wetLeft[i]) * m) * g
            outRight[i] = (s + (musicDry[i] + wetRight[i]) * m) * g
        }
        compressor.process(outLeft, outRight, QUANTUM)
        synth.currentFrame = frame + QUANTUM
    }

    private fun endExpiredBuses(frame: Long) {
        for (bus in sfxSessions) endIfExpired(bus, frame)
        for (bus in musicRuns) endIfExpired(bus, frame)
    }

    private fun endIfExpired(
        bus: GainBus,
        frame: Long,
    ) {
        if (bus.active && frame >= bus.killFrame) {
            synth.stopVoicesOf(bus)
            bus.close()
        }
    }

    /** The reverb only runs while the melody plays and until its tail has died out. */
    private fun renderReverb(runActive: Boolean) {
        if (runActive) {
            reverbFramesLeft = reverbTailFrames
        } else if (reverbFramesLeft > 0) {
            reverbFramesLeft -= QUANTUM
            if (reverbFramesLeft <= 0) {
                convolver.reset()
                reverbHighpass.reset()
            }
        }
        if (reverbFramesLeft <= 0) {
            wetLeft.fill(0f)
            wetRight.fill(0f)
            return
        }
        for (i in 0 until QUANTUM) {
            reverbSend[i] =
                (reverbHighpass.process(musicDry[i].toDouble()) * REVERB_SEND).toFloat()
        }
        convolver.process(reverbSend, wetLeft, wetRight, QUANTUM)
    }
}
