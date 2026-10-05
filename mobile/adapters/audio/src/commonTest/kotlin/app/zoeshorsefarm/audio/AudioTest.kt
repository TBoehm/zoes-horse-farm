package app.zoeshorsefarm.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AudioTest {
    private val outputs = mutableListOf<FakePcmOutput>()
    private val scheduler = ManualScheduler()
    private val platform =
        AudioPlatform(
            outputFactory = PcmOutputFactory { FakePcmOutput().also { outputs += it } },
            scheduler = scheduler,
        )

    private fun make(settings: AudioSettings = AudioSettings()) = Audio(settings, platform)

    private val output get() = outputs[0]

    private fun Audio.engineOrFail() = engine ?: error("audio is not unlocked")

    // ---- before unlock ----

    @Test
    fun beforeUnlockNoOutputIsCreatedAndAllCallsAreIgnoredSilently() {
        val audio = make()
        audio.sfx.hoof("walk")
        audio.sfx.takeoff()
        audio.sfx.landing()
        audio.sfx.railDown()
        audio.sfx.startSignal()
        audio.sfx.finishSignal()
        audio.setMusicWanted(true)
        audio.setVolumes(musicVolume = 0.2)
        audio.setHidden(true)
        audio.setHidden(false)
        audio.setPaused(true)
        audio.setPaused(false)
        assertEquals(0, outputs.size)
        assertFalse(audio.getState().unlocked)
    }

    @Test
    fun theMusicRequestIsRememberedAndTheMelodyStartsAfterUnlock() {
        val audio = make()
        audio.setMusicWanted(true)
        audio.unlock()
        assertEquals(1, outputs.size)
        assertTrue(audio.getState().musicPlaying)
        output.pump(0.1)
        assertTrue(audio.engineOrFail().voicesStarted > 0)
    }

    @Test
    fun unlockIsRepeatableAndCreatesOnlyOneOutput() {
        val audio = make()
        audio.unlock()
        audio.unlock()
        assertEquals(1, outputs.size)
        assertTrue(audio.getState().unlocked)
        assertTrue(audio.getState().running)
    }

    // ---- missing or failing output ----

    @Test
    fun withoutAnOutputFactoryAudioHasNoEffectAtAll() {
        val audio = Audio(AudioSettings(), AudioPlatform(null, scheduler))
        audio.setMusicWanted(true)
        audio.unlock()
        audio.sfx.hoof("trot")
        audio.setVolumes(sfxVolume = 1.0)
        audio.dispose()
        assertTrue(audio.getState().failed)
        assertFalse(audio.getState().unlocked)
    }

    @Test
    fun aThrowingFactoryIsCaught() {
        val audio = Audio(AudioSettings(), AudioPlatform({ error("not allowed") }, scheduler))
        audio.setMusicWanted(true)
        audio.unlock()
        audio.sfx.landing()
        assertTrue(audio.getState().failed)
    }

    @Test
    fun anOutputThatCannotBeStartedMarksAudioAsFailedAndIsClosed() {
        val broken = FakePcmOutput().also { it.startFails = true }
        val audio = Audio(AudioSettings(), AudioPlatform({ broken }, scheduler))
        audio.setMusicWanted(true)
        audio.unlock()
        assertTrue(audio.getState().failed)
        assertFalse(audio.getState().unlocked)
        assertTrue(broken.closed)
        audio.unlock() // does not try again
        audio.sfx.hoof("walk")
        assertEquals(0, audio.getState().sfxCounts.hoof)
    }

    // ---- effects ----

    @Test
    fun effectsSoundAfterUnlock() {
        val audio = make()
        audio.unlock()
        val before = audio.engineOrFail().voicesStarted
        audio.sfx.hoof("canter")
        assertTrue(output.pump(0.3) > 0.01f)
        assertTrue(audio.engineOrFail().voicesStarted > before)
    }

    @Test
    fun everyEffectAndEveryGaitStartsVoicesAndAnUnknownGaitDoesNot() {
        val audio = make()
        audio.unlock()
        val calls =
            mapOf<String, () -> Unit>(
                "back" to { audio.sfx.hoof("back") },
                "walk" to { audio.sfx.hoof("walk") },
                "trot" to { audio.sfx.hoof("trot") },
                "canter" to { audio.sfx.hoof("canter") },
                "takeoff" to { audio.sfx.takeoff() },
                "landing" to { audio.sfx.landing() },
                "railDown" to { audio.sfx.railDown() },
                "startSignal" to { audio.sfx.startSignal() },
                "finishSignal" to { audio.sfx.finishSignal() },
            )
        for ((name, call) in calls) {
            val before = audio.engineOrFail().voicesStarted
            call()
            output.pump(0.01)
            assertTrue(audio.engineOrFail().voicesStarted > before, name)
        }
        val before = audio.engineOrFail().voicesStarted
        audio.sfx.hoof("halt")
        output.pump(0.01)
        assertEquals(before, audio.engineOrFail().voicesStarted)
    }

    @Test
    fun pauseIgnoresNewEffectsAndCutsRunningOnes() {
        val audio = make()
        audio.unlock()
        audio.sfx.hoof("trot")
        output.pump(0.05)
        audio.setPaused(true)
        val started = audio.engineOrFail().voicesStarted
        audio.sfx.takeoff()
        assertEquals(started, audio.engineOrFail().voicesStarted)
        output.pump(0.3)
        assertTrue(output.pump(0.3) < 1e-4f, "the running effect is cut")
        audio.setPaused(false)
        audio.sfx.hoof("walk")
        assertTrue(output.pump(0.2) > 0.005f)
        assertTrue(audio.engineOrFail().voicesStarted > started)
    }

    @Test
    fun aMutedEffectsChannelStartsNoVoicesAndTheMusicIsUntouched() {
        val audio = make(AudioSettings(sfxMuted = true))
        audio.setMusicWanted(true)
        audio.unlock()
        val started = audio.engineOrFail().voicesStarted
        audio.sfx.landing()
        assertEquals(started, audio.engineOrFail().voicesStarted)
        assertTrue(audio.getState().musicPlaying)
    }

    @Test
    fun anEffectsVolumeOfZeroStartsNoVoices() {
        val audio = make(AudioSettings(sfxVolume = 0.0))
        audio.unlock()
        audio.sfx.landing()
        assertEquals(0L, audio.engineOrFail().voicesStarted)
        assertEquals(0, audio.getState().sfxCounts.landing)
    }

    @Test
    fun getStateCountsTheEffectsThatWerePlayedPerName() {
        val audio = make()
        assertEquals(SfxCounts(), audio.getState().sfxCounts)
        audio.unlock()
        audio.sfx.startSignal()
        audio.sfx.hoof("walk")
        audio.sfx.hoof("trot")
        audio.sfx.finishSignal()
        val state = audio.getState()
        assertEquals(SfxCounts(hoof = 2, startSignal = 1, finishSignal = 1), state.sfxCounts)
        // the state is a snapshot: later effects do not change it
        audio.sfx.hoof("walk")
        assertEquals(2, state.sfxCounts.hoof)
        assertEquals(3, audio.getState().sfxCounts.hoof)
    }

    @Test
    fun anUnknownGaitStillCountsAsAHoofEffectLikeOnTheWeb() {
        val audio = make()
        audio.unlock()
        audio.sfx.hoof("halt")
        assertEquals(1, audio.getState().sfxCounts.hoof)
    }

    @Test
    fun doesNotCountEffectsThatAreDropped() {
        val audio = make()
        audio.sfx.landing() // before unlock
        audio.unlock()
        audio.setPaused(true)
        audio.sfx.landing()
        audio.setPaused(false)
        audio.setHidden(true)
        audio.sfx.landing()
        audio.setHidden(false)
        audio.setVolumes(sfxMuted = true)
        audio.sfx.landing()
        audio.setVolumes(sfxMuted = false)
        output.setState(OutputState.Suspended)
        audio.sfx.landing()
        assertEquals(0, audio.getState().sfxCounts.landing)
        output.setState(OutputState.Running)
        audio.sfx.landing()
        assertEquals(1, audio.getState().sfxCounts.landing)
    }

    @Test
    fun effectsAreIgnoredWhileTheOutputIsNotRunning() {
        val audio = make()
        audio.unlock()
        output.setState(OutputState.Interrupted)
        audio.sfx.hoof("walk")
        assertEquals(0L, audio.engineOrFail().voicesStarted)
    }

    // ---- volume and mute ----

    @Test
    fun setVolumesTakesEffectAndMutingKeepsTheVolume() {
        val audio = make(AudioSettings(musicVolume = 0.5, sfxVolume = 0.5))
        audio.unlock()
        val engine = audio.engineOrFail()
        assertEquals(0.25, engine.musicChannelGain, 1e-9)
        audio.setVolumes(musicVolume = 0.8)
        output.pump(0.4)
        assertEquals(0.64, engine.musicChannelGain, 1e-3)
        audio.setVolumes(musicMuted = true)
        output.pump(0.4)
        assertEquals(0.0, engine.musicChannelGain, 1e-3)
        assertEquals(0.25, engine.sfxChannelGain, 1e-3)
        audio.setVolumes(musicMuted = false)
        output.pump(0.4)
        assertEquals(0.64, engine.musicChannelGain, 1e-3)
    }

    @Test
    fun mutingTheMusicStopsTheMelodyAndUnmutingStartsItAgain() {
        val audio = make()
        audio.setMusicWanted(true)
        audio.unlock()
        assertTrue(audio.getState().musicPlaying)
        audio.setVolumes(musicMuted = true)
        assertFalse(audio.getState().musicPlaying)
        audio.setVolumes(musicMuted = false)
        assertTrue(audio.getState().musicPlaying)
    }

    @Test
    fun aMusicVolumeOfZeroKeepsTheMelodyRunning() {
        val audio = make()
        audio.setMusicWanted(true)
        audio.unlock()
        audio.setVolumes(musicVolume = 0.0)
        assertTrue(audio.getState().musicPlaying)
    }

    // ---- music should run ----

    @Test
    fun theMelodyFollowsSetMusicWanted() {
        val audio = make()
        audio.unlock()
        assertFalse(audio.getState().musicPlaying)
        audio.setMusicWanted(true)
        assertTrue(audio.getState().musicPlaying)
        assertTrue(audio.getState().musicWanted)
        audio.setMusicWanted(false)
        assertFalse(audio.getState().musicPlaying)
    }

    @Test
    fun theMelodySchedulesNotesContinuouslyOverTime() {
        val audio = make()
        audio.setMusicWanted(true)
        audio.unlock()
        var last = audio.engineOrFail().voicesStarted
        var grew = 0
        repeat(40) {
            output.pump(0.1)
            val now = audio.engineOrFail().voicesStarted
            if (now > last) grew++
            last = now
        }
        assertTrue(grew > 10, "grew $grew times")
    }

    @Test
    fun inTheBackgroundTheMusicStopsAndTheOutputIsSuspendedOnReturnItRunsAgain() {
        val audio = make()
        audio.setMusicWanted(true)
        audio.unlock()
        audio.setHidden(true)
        assertFalse(audio.getState().musicPlaying)
        scheduler.advance(200)
        assertEquals(1, output.suspendCalls)
        assertFalse(audio.getState().running)
        audio.sfx.takeoff()
        audio.setHidden(false)
        assertEquals(1, output.resumeCalls)
        assertTrue(audio.getState().musicPlaying)
        assertTrue(audio.getState().running)
        assertEquals(0, audio.getState().sfxCounts.takeoff)
    }

    @Test
    fun theOutputIsOnlySuspendedAfterTheFadeOut() {
        val audio = make()
        audio.unlock()
        audio.setHidden(true)
        scheduler.advance(100)
        assertEquals(0, output.suspendCalls)
        scheduler.advance(30)
        assertEquals(1, output.suspendCalls)
    }

    @Test
    fun returningBeforeTheSuspendDelayCancelsTheSuspend() {
        val audio = make()
        audio.unlock()
        audio.setHidden(true)
        scheduler.advance(50)
        audio.setHidden(false)
        scheduler.advance(500)
        assertEquals(0, output.suspendCalls)
        assertTrue(audio.getState().running)
    }

    @Test
    fun theBackgroundSilencesTheMasterAndReturningRestoresIt() {
        val audio = make()
        audio.unlock()
        audio.setHidden(true)
        output.pump(0.2)
        assertEquals(0.0, audio.engineOrFail().masterGain, 1e-3)
        audio.setHidden(false)
        output.pump(0.2)
        assertEquals(MASTER_LEVEL, audio.engineOrFail().masterGain, 1e-3)
    }

    @Test
    fun backgroundWithoutAMusicRequestStartsNoMusicOnReturn() {
        val audio = make()
        audio.unlock()
        audio.setHidden(true)
        audio.setHidden(false)
        assertFalse(audio.getState().musicPlaying)
    }

    @Test
    fun aRequestMadeInTheBackgroundIsAppliedOnReturn() {
        val audio = make()
        audio.unlock()
        audio.setHidden(true)
        audio.setMusicWanted(true)
        assertFalse(audio.getState().musicPlaying)
        audio.setHidden(false)
        assertTrue(audio.getState().musicPlaying)
    }

    @Test
    fun unlockInTheBackgroundStartsNoSound() {
        val audio = make()
        audio.setMusicWanted(true)
        audio.setHidden(true)
        audio.unlock()
        assertFalse(audio.getState().musicPlaying)
        scheduler.advance(200)
        assertEquals(1, output.suspendCalls)
    }

    // ---- unlock requests (the mobile counterpart of the gesture listeners) ----

    private class UnlockLog : UnlockListener {
        val events = mutableListOf<Boolean>()
        val needed get() = events.lastOrNull() == true

        override fun onUnlockNeeded(needed: Boolean) {
            events += needed
        }
    }

    @Test
    fun anInstalledListenerIsToldThatAnUnlockIsNeededAndNotAnymoreAfterTheInteraction() {
        val audio = make()
        val log = UnlockLog()
        audio.installUnlock(log)
        assertEquals(listOf(true), log.events)
        assertEquals(0, outputs.size)
        audio.onUserInteraction()
        assertEquals(1, outputs.size)
        assertEquals(listOf(true, false), log.events)
    }

    @Test
    fun removingTheListenerIsHarmlessAndTellsItThatNothingIsNeeded() {
        val audio = make()
        val log = UnlockLog()
        val remove = audio.installUnlock(log)
        remove()
        assertEquals(listOf(true, false), log.events)
        audio.onUserInteraction() // works without a listener as well
        assertEquals(1, outputs.size)
        remove()
    }

    @Test
    fun anInterruptionAfterUnlockingRequestsAnUnlockAgainAndTheNextInteractionResumes() {
        val audio = make()
        audio.setMusicWanted(true)
        val log = UnlockLog()
        audio.installUnlock(log)
        audio.onUserInteraction()
        assertFalse(log.needed)
        // e.g. a phone call or the screen lock on iOS
        output.setState(OutputState.Interrupted)
        assertTrue(log.needed)
        audio.onUserInteraction()
        assertEquals(1, output.resumeCalls)
        assertEquals(OutputState.Running, output.state)
        assertFalse(log.needed)
        assertTrue(audio.getState().musicPlaying)
    }

    @Test
    fun aSuspendedOutputRequestsAnUnlockAsWell() {
        val audio = make()
        val log = UnlockLog()
        audio.installUnlock(log)
        audio.onUserInteraction()
        assertFalse(log.needed)
        output.setState(OutputState.Suspended)
        assertTrue(log.needed)
    }

    @Test
    fun theUnlockStaysRequestedWhileAResumeIsRefusedAndEndsOnceItWorks() {
        val audio = make()
        val log = UnlockLog()
        audio.installUnlock(log)
        audio.onUserInteraction()
        output.setState(OutputState.Interrupted)
        output.refuseResume = true
        audio.onUserInteraction()
        assertEquals(1, output.resumeCalls)
        assertTrue(log.needed)
        output.refuseResume = false
        audio.onUserInteraction()
        assertEquals(2, output.resumeCalls)
        assertFalse(log.needed)
    }

    @Test
    fun noUnlockIsRequestedWhileInTheBackgroundBecauseTheAudioSuspendsItself() {
        val audio = make()
        val log = UnlockLog()
        audio.installUnlock(log)
        audio.onUserInteraction()
        audio.setHidden(true)
        scheduler.advance(200)
        assertEquals(OutputState.Suspended, output.state)
        assertFalse(log.needed)
    }

    @Test
    fun returningToTheAppWithARefusedResumeWaitsForTheNextInteraction() {
        val audio = make()
        val log = UnlockLog()
        audio.installUnlock(log)
        audio.onUserInteraction()
        audio.setHidden(true)
        scheduler.advance(200)
        output.refuseResume = true
        audio.setHidden(false)
        assertEquals(OutputState.Suspended, output.state)
        assertTrue(log.needed)
        output.refuseResume = false
        audio.onUserInteraction()
        assertEquals(OutputState.Running, output.state)
        assertFalse(log.needed)
    }

    @Test
    fun theRemoveFunctionEndsTheRequestsForGood() {
        val audio = make()
        val log = UnlockLog()
        val remove = audio.installUnlock(log)
        audio.onUserInteraction()
        remove()
        val events = log.events.size
        output.setState(OutputState.Interrupted)
        assertEquals(events, log.events.size)
    }

    @Test
    fun afterDisposeAStateChangeRequestsNothing() {
        val audio = make()
        val log = UnlockLog()
        audio.installUnlock(log)
        audio.onUserInteraction()
        val out = output
        audio.dispose()
        val events = log.events.size
        out.setState(OutputState.Interrupted)
        assertEquals(events, log.events.size)
    }

    @Test
    fun installingANewListenerReleasesTheOldOne() {
        val audio = make()
        val first = UnlockLog()
        val second = UnlockLog()
        audio.installUnlock(first)
        audio.installUnlock(second)
        assertEquals(listOf(true, false), first.events)
        assertEquals(listOf(true), second.events)
    }

    @Test
    fun noUnlockIsRequestedWhenAudioFailed() {
        val audio = Audio(AudioSettings(), AudioPlatform(null, scheduler))
        val log = UnlockLog()
        audio.unlock()
        audio.installUnlock(log)
        assertEquals(emptyList(), log.events)
    }

    // ---- dispose ----

    @Test
    fun disposeStopsTheMelodyClosesTheOutputAndTurnsEverythingIntoANoOp() {
        val audio = make()
        audio.setMusicWanted(true)
        audio.unlock()
        audio.dispose()
        assertTrue(output.closed)
        assertFalse(audio.getState().musicPlaying)
        audio.sfx.hoof("walk")
        audio.setMusicWanted(true)
        audio.unlock()
        audio.setVolumes(sfxVolume = 1.0)
        audio.setHidden(true)
        audio.setPaused(true)
        audio.dispose()
        assertEquals(1, outputs.size)
        scheduler.advance(1000)
        assertEquals(0, output.suspendCalls)
        assertEquals(0, audio.getState().sfxCounts.hoof)
    }

    @Test
    fun disposeCancelsAPendingSuspend() {
        val audio = make()
        audio.unlock()
        audio.setHidden(true)
        audio.dispose()
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun theStateReflectsTheFlagsOfTheFacade() {
        val audio = make()
        audio.unlock()
        audio.setMusicWanted(true)
        audio.setHidden(true)
        audio.setPaused(true)
        val state = audio.getState()
        assertTrue(state.unlocked)
        assertTrue(state.musicWanted)
        assertTrue(state.hidden)
        assertTrue(state.paused)
        assertFalse(state.failed)
        assertNotEquals(AudioState(), state)
    }
}
