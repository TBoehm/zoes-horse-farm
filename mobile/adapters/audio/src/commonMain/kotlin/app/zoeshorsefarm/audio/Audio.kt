// Audio module: pure PCM synthesis, no audio files. See [Audio].
package app.zoeshorsefarm.audio

private const val SUSPEND_DELAY_MS = 120L

/** The effects that are counted in [AudioState.sfxCounts]. */
enum class SfxName(
    val id: String,
) {
    Hoof("hoof"),
    Takeoff("takeoff"),
    Landing("landing"),
    RailDown("railDown"),
    StartSignal("startSignal"),
    FinishSignal("finishSignal"),
}

/** How many effects of each kind were really played (not dropped). Telemetry for the smoke tests. */
data class SfxCounts(
    val hoof: Int = 0,
    val takeoff: Int = 0,
    val landing: Int = 0,
    val railDown: Int = 0,
    val startSignal: Int = 0,
    val finishSignal: Int = 0,
)

/**
 * Snapshot of the audio service.
 *
 * @property unlocked the audio session is activated (the output exists)
 * @property running the output is pulling audio right now
 * @property failed audio is not available on this device
 */
data class AudioState(
    val unlocked: Boolean = false,
    val running: Boolean = false,
    val failed: Boolean = false,
    val hidden: Boolean = false,
    val paused: Boolean = false,
    val musicWanted: Boolean = false,
    val musicPlaying: Boolean = false,
    val sfxCounts: SfxCounts = SfxCounts(),
)

/**
 * Tells the app when the audio needs the user to interact before it can run: before the first
 * unlock and whenever the system stopped the output while the app is visible. The app calls
 * [Audio.onUserInteraction] on the next touch. The mobile counterpart of the gesture listeners of
 * the web app.
 */
fun interface UnlockListener {
    fun onUnlockNeeded(needed: Boolean)
}

/**
 * The audio service of the game: a synthesised menu melody and sound effects, no audio files.
 *
 * Behaves like `createAudio` of the web app. The output is only created by [unlock] (the "audio
 * session activation"); until then all calls are remembered or ignored silently and no sound is
 * counted. Any failure of the platform output turns audio off instead of breaking the game.
 *
 * Thread safe: the game thread calls it while the platform's audio thread renders.
 */
class Audio(
    settings: AudioSettings = AudioSettings(),
    private val platform: AudioPlatform,
) {
    // Guards the state of the facade. Held while calling the platform output (start, resume, suspend,
    // close), which may wait for the audio thread: that thread never takes a lock (see AudioEngine).
    private val lock = AudioLock()
    private var settings =
        normalizeSettings(
            settings.musicVolume,
            settings.musicMuted,
            settings.sfxVolume,
            settings.sfxMuted,
        )
    private var output: PcmOutput? = null
    internal var engine: AudioEngine? = null
        private set
    private var failed = false
    private var disposed = false
    private var wanted = false
    private var hidden = false
    private var paused = false
    private var suspendTimer: Cancellable? = null
    private var unlockListener: UnlockListener? = null
    private var armed = false // the listener was told that an unlock is needed
    private val counts = IntArray(SfxName.entries.size)

    /** The effects. Each call is a no-op unless it can really be heard (see [getState]). */
    val sfx = Sfx(this)

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // audio must never break the game
        }
    }

    private fun build() {
        val factory = platform.outputFactory
        if (factory == null) {
            failed = true
            return
        }
        try {
            val out = factory.create()
            output = out
            out.setStateListener { onStateChange() }
            val created = AudioEngine(out.sampleRate, settings, hidden)
            engine = created
            out.start(created)
        } catch (_: Exception) {
            teardown()
            failed = true
        }
    }

    private fun teardown() {
        suspendTimer?.cancel()
        suspendTimer = null
        engine = null
        val old = output
        output = null
        old?.setStateListener(null)
        guard { old?.close() }
    }

    private fun isRunning() = output?.state == OutputState.Running

    private fun resumeOutput() {
        val out = output ?: return
        if (out.state != OutputState.Running) out.resume()
    }

    private fun setChannelGains() {
        engine?.setChannelTargets(
            channelGain(settings.musicVolume, settings.musicMuted),
            channelGain(settings.sfxVolume, settings.sfxMuted),
        )
    }

    // ---- Music ----

    private fun syncMusic() {
        val e = engine ?: return
        if (disposed) return
        val want = shouldMusicRun(wanted, hidden, settings.musicMuted)
        if (want && !e.musicRunning) {
            e.startMusic()
        } else if (!want && e.musicRunning) {
            e.stopMusic(fast = hidden)
        }
    }

    // ---- Effects ----

    private fun canPlaySfx() =
        engine != null &&
            !disposed &&
            !paused &&
            !hidden &&
            isRunning() &&
            !settings.sfxMuted &&
            settings.sfxVolume > 0

    internal fun playSfx(
        name: SfxName,
        gait: String = "",
    ) {
        lock.withLock {
            if (!canPlaySfx()) return
            counts[name.ordinal]++
            guard { engine?.playSfx(name, gait) }
        }
    }

    // ---- Public API ----

    /**
     * Activates the audio session: creates the output and starts it. Safe to call repeatedly; it also
     * tries to run the output again after the system stopped it. Call it from the first touch and
     * whenever the app comes back to the foreground.
     */
    fun unlock() {
        lock.withLock { unlockLocked() }
    }

    private fun unlockLocked() {
        if (disposed) return
        guard {
            if (engine == null && !failed) build()
            if (engine == null) return@guard
            if (hidden) applyHidden() else resumeOutput()
            syncMusic()
        }
    }

    /**
     * To be called on every user interaction while the [UnlockListener] reports that an unlock is
     * needed: unlocks, and ends the request once the output really runs.
     */
    fun onUserInteraction() {
        lock.withLock {
            unlockLocked()
            if (unlockUnavailableOrUnneeded() || isRunning()) disarm()
        }
    }

    // iOS interrupts the output (phone call, lock screen, other app) and a resume without user
    // interaction can be refused: while the app is visible and the output is not running, ask for an
    // interaction.
    private fun onStateChange() {
        lock.withLock {
            if (engine == null || disposed) return
            if (isRunning()) {
                disarm()
            } else if (!hidden) {
                arm()
            }
        }
    }

    private fun unlockUnavailable() = disposed || failed

    // No unlock request makes sense: audio is gone, or the app is in the background (it suspends itself)
    private fun unlockUnavailableOrUnneeded() = unlockUnavailable() || hidden

    private fun arm() {
        val listener = unlockListener
        if (armed || listener == null || unlockUnavailable()) return
        armed = true
        listener.onUnlockNeeded(true)
    }

    private fun disarm() {
        if (!armed) return
        armed = false
        unlockListener?.onUnlockNeeded(false)
    }

    /**
     * Registers [listener] to be told when an unlock is needed (right away, as nothing is unlocked yet).
     * Returns a function that removes it again.
     */
    fun installUnlock(listener: UnlockListener): () -> Unit {
        lock.withLock {
            disarm()
            unlockListener = listener
            arm()
        }
        return {
            lock.withLock {
                disarm()
                unlockListener = null
            }
        }
    }

    /** Changes volumes and mutes; fields that are null keep their value. Takes effect without clicks. */
    fun setVolumes(
        musicVolume: Double? = null,
        musicMuted: Boolean? = null,
        sfxVolume: Double? = null,
        sfxMuted: Boolean? = null,
    ) {
        lock.withLock {
            if (disposed) return
            settings =
                normalizeSettings(
                    musicVolume ?: settings.musicVolume,
                    musicMuted ?: settings.musicMuted,
                    sfxVolume ?: settings.sfxVolume,
                    sfxMuted ?: settings.sfxMuted,
                )
            guard {
                setChannelGains()
                syncMusic()
            }
        }
    }

    /** The screen wants the menu melody (or not). Remembered until the audio is unlocked and visible. */
    fun setMusicWanted(value: Boolean) {
        lock.withLock {
            wanted = value
            guard { syncMusic() }
        }
    }

    private fun applyHidden() {
        val e = engine ?: return
        if (output == null) return
        suspendTimer?.cancel()
        suspendTimer = null
        e.setMasterHidden(hidden)
        if (hidden) {
            suspendTimer = platform.scheduler.postDelayed(SUSPEND_DELAY_MS) { suspendIfStillHidden() }
        } else {
            resumeOutput()
            if (!isRunning()) arm()
        }
    }

    private fun suspendIfStillHidden() {
        lock.withLock {
            val out = output ?: return
            if (hidden && out.state == OutputState.Running) guard { out.suspend() }
        }
    }

    /** The app moves to the background (true): silent at once, the output is suspended shortly after. */
    fun setHidden(value: Boolean) {
        lock.withLock {
            if (disposed) return
            if (value == hidden) return
            hidden = value
            guard {
                if (hidden) engine?.dropSfxSession()
                applyHidden()
                syncMusic()
            }
        }
    }

    /** While paused, running effects are cut and new ones are ignored. */
    fun setPaused(value: Boolean) {
        lock.withLock {
            if (disposed) return
            paused = value
            if (paused) guard { engine?.dropSfxSession() }
        }
    }

    /** Releases the output. Afterwards every call is a no-op. */
    fun dispose() {
        lock.withLock {
            if (disposed) return
            disarm()
            guard {
                val e = engine
                if (e != null && e.musicRunning) e.stopMusic(fast = true)
            }
            disposed = true
            guard { teardown() }
        }
    }

    fun getState(): AudioState =
        lock.withLock {
            AudioState(
                unlocked = engine != null,
                running = isRunning(),
                failed = failed,
                hidden = hidden,
                paused = paused,
                musicWanted = wanted,
                musicPlaying = engine?.musicRunning == true,
                sfxCounts =
                    SfxCounts(
                        hoof = counts[SfxName.Hoof.ordinal],
                        takeoff = counts[SfxName.Takeoff.ordinal],
                        landing = counts[SfxName.Landing.ordinal],
                        railDown = counts[SfxName.RailDown.ordinal],
                        startSignal = counts[SfxName.StartSignal.ordinal],
                        finishSignal = counts[SfxName.FinishSignal.ordinal],
                    ),
            )
        }
}

/** The sound effects of the game. */
class Sfx internal constructor(
    private val audio: Audio,
) {
    /** One hoof beat on sand; [gait] is "back", "walk", "trot" or "canter" (other gaits are silent). */
    fun hoof(gait: String) = audio.playSfx(SfxName.Hoof, gait)

    fun takeoff() = audio.playSfx(SfxName.Takeoff)

    fun landing() = audio.playSfx(SfxName.Landing)

    fun railDown() = audio.playSfx(SfxName.RailDown)

    fun startSignal() = audio.playSfx(SfxName.StartSignal)

    fun finishSignal() = audio.playSfx(SfxName.FinishSignal)
}

/** Creates the audio service for the current platform (iOS: AVAudioEngine, JVM: javax.sound). */
fun createAudio(settings: AudioSettings = AudioSettings()): Audio = Audio(settings, createPlatformAudio())

/** The platform output and timer; one implementation per target. */
expect fun createPlatformAudio(): AudioPlatform
