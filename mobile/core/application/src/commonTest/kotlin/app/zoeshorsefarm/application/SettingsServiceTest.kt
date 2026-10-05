package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FakeStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Values that the web service ignores because they have the wrong type or are not offered (an
// unknown language, level, camera, aid kind or channel; a non-boolean flag) cannot be passed to the
// typed API; the ids a player or a save game may carry are covered by the enums' fromId tests.
// Also not ported: JS "get returns a copy" (Settings is immutable, nothing to copy).
class SettingsServiceTest {
    private class Setup(
        settings: Settings,
    ) {
        val store = FakeStore(settings = settings)
        val service = SettingsService(store)
    }

    private fun setup(settings: Settings = Settings()) = Setup(settings)

    @Test
    fun getReturnsTheSavedSettings() {
        val s = setup(Settings(lang = Language.DE, camera = CameraMode.RIDER))
        assertEquals(Language.DE, s.service.get().lang)
        assertEquals(CameraMode.RIDER, s.service.get().camera)
    }

    // language

    @Test
    fun savesALanguage() {
        val s = setup(Settings(lang = Language.DE))
        s.service.setLang(Language.EN)
        assertEquals(Language.EN, s.store.settings.lang)
    }

    // graphics

    @Test
    fun tellsTheListenersWhenThePlayerSelectsAutomaticGraphics() {
        val s = setup(Settings(graphicsAuto = false, graphicsLevel = GraphicsLevel.HIGH))
        var calls = 0
        val off = s.service.onAutoSelected { calls += 1 }
        s.service.setGraphicsAuto()
        assertEquals(1, calls)
        s.service.setGraphicsLevel(GraphicsLevel.MEDIUM)
        s.service.setAutoLevel(GraphicsLevel.LOW)
        assertEquals(1, calls)
        off()
        s.service.setGraphicsAuto()
        assertEquals(1, calls)
    }

    @Test
    fun automaticStartsAtLowWhateverLevelWasSetBefore() {
        // rule 4
        for (level in GRAPHICS_LEVELS) {
            val s = setup(Settings(graphicsAuto = false, graphicsLevel = level))
            s.service.setGraphicsAuto()
            assertTrue(s.store.settings.graphicsAuto)
            assertEquals(GraphicsLevel.LOW, s.store.settings.graphicsLevel)
        }
    }

    @Test
    fun manualLevelTurnsAutoOffAndSavesTheLevel() {
        val s = setup(Settings(graphicsAuto = true, graphicsLevel = GraphicsLevel.LOW))
        s.service.setGraphicsLevel(GraphicsLevel.HIGH)
        assertFalse(s.store.settings.graphicsAuto)
        assertEquals(GraphicsLevel.HIGH, s.store.settings.graphicsLevel)
    }

    @Test
    fun theGovernorChangesTheLevelAndAutomaticStaysOn() {
        val s = setup(Settings(graphicsAuto = true, graphicsLevel = GraphicsLevel.HIGH))
        s.service.setAutoLevel(GraphicsLevel.MEDIUM)
        assertTrue(s.store.settings.graphicsAuto)
        assertEquals(GraphicsLevel.MEDIUM, s.store.settings.graphicsLevel)
        s.service.setAutoLevel(GraphicsLevel.HIGH)
        assertTrue(s.store.settings.graphicsAuto)
        assertEquals(GraphicsLevel.HIGH, s.store.settings.graphicsLevel)
    }

    // camera

    @Test
    fun savesFollowAndRider() {
        val s = setup(Settings(camera = CameraMode.FOLLOW))
        s.service.setCamera(CameraMode.RIDER)
        assertEquals(CameraMode.RIDER, s.store.settings.camera)
        s.service.setCamera(CameraMode.FOLLOW)
        assertEquals(CameraMode.FOLLOW, s.store.settings.camera)
    }

    // jump aid

    @Test
    fun switchesTheFreeRideAndCourseAidsIndependently() {
        val s = setup(Settings(aidFree = true, aidCourse = false))
        s.service.setAid(AidKind.COURSE, true)
        assertTrue(s.store.settings.aidFree)
        assertTrue(s.store.settings.aidCourse)
        s.service.setAid(AidKind.FREE, false)
        assertFalse(s.store.settings.aidFree)
        assertTrue(s.store.settings.aidCourse)
    }

    // fps display

    @Test
    fun switchesTheFpsDisplayOnAndOff() {
        val s = setup(Settings(showFps = false))
        s.service.setShowFps(true)
        assertTrue(s.store.settings.showFps)
        s.service.setShowFps(false)
        assertFalse(s.store.settings.showFps)
    }

    @Test
    fun theFpsDisplayLeavesTheOtherSettingsAlone() {
        val s = setup(Settings(camera = CameraMode.RIDER, graphicsLevel = GraphicsLevel.LOW))
        s.service.setShowFps(true)
        assertEquals(CameraMode.RIDER, s.store.settings.camera)
        assertEquals(GraphicsLevel.LOW, s.store.settings.graphicsLevel)
    }

    // sound

    @Test
    fun setsTheVolumePerChannel() {
        val s = setup()
        s.service.setVolume(SoundChannel.MUSIC, 0.2)
        s.service.setVolume(SoundChannel.SFX, 0.9)
        assertEquals(0.2, s.store.settings.musicVolume)
        assertEquals(0.9, s.store.settings.sfxVolume)
    }

    @Test
    fun limitsTheVolumeToZeroToOneAndIgnoresNonFiniteNumbers() {
        val s = setup(Settings(musicVolume = 0.4))
        s.service.setVolume(SoundChannel.MUSIC, 7.0)
        assertEquals(1.0, s.store.settings.musicVolume)
        s.service.setVolume(SoundChannel.MUSIC, -1.0)
        assertEquals(0.0, s.store.settings.musicVolume)
        s.service.setVolume(SoundChannel.MUSIC, 0.4)
        s.service.setVolume(SoundChannel.MUSIC, Double.NaN)
        s.service.setVolume(SoundChannel.MUSIC, Double.POSITIVE_INFINITY)
        assertEquals(0.4, s.store.settings.musicVolume)
    }

    @Test
    fun mutesAndUnmutesPerChannel() {
        val s = setup(Settings(musicMuted = false, sfxMuted = false))
        s.service.setMuted(SoundChannel.SFX, true)
        assertTrue(s.store.settings.sfxMuted)
        assertFalse(s.store.settings.musicMuted)
        s.service.setMuted(SoundChannel.SFX, false)
        s.service.setMuted(SoundChannel.MUSIC, true)
        assertFalse(s.store.settings.sfxMuted)
        assertTrue(s.store.settings.musicMuted)
    }

    @Test
    fun theChannelsKeepTheirIdsForTheStore() {
        assertEquals(listOf("music", "sfx"), SoundChannel.entries.map { it.id })
        assertEquals(listOf("free", "course"), AidKind.entries.map { it.id })
    }

    // onChange

    @Test
    fun notifiesWithTheNewSettingsAndStopsAfterUnsubscribe() {
        val s = setup(Settings(camera = CameraMode.FOLLOW))
        val seen = mutableListOf<CameraMode>()
        val stop = s.service.onChange { seen.add(it.camera) }
        s.service.setCamera(CameraMode.RIDER)
        stop()
        s.service.setCamera(CameraMode.FOLLOW)
        assertEquals(listOf(CameraMode.RIDER), seen)
    }

    // controls help

    @Test
    fun markControlsHelpSeenSavesTheFlagAndKeepsTheOtherSettings() {
        val s = setup(Settings(controlsHelpSeen = false, camera = CameraMode.RIDER))
        s.service.markControlsHelpSeen()
        assertTrue(s.store.settings.controlsHelpSeen)
        assertEquals(CameraMode.RIDER, s.store.settings.camera)
        assertTrue(s.service.get().controlsHelpSeen)
    }

    @Test
    fun markControlsHelpSeenIsIdempotent() {
        val s = setup(Settings(controlsHelpSeen = true))
        s.service.markControlsHelpSeen()
        assertTrue(s.store.settings.controlsHelpSeen)
    }

    @Test
    fun unknownFieldsOfANewerSaveSurviveEveryWrite() {
        val future = mapOf("futureField" to listOf(1, 2))
        val s = setup(Settings(unknown = future))
        s.service.setCamera(CameraMode.RIDER)
        s.service.setVolume(SoundChannel.SFX, 0.3)
        assertEquals(future, s.store.settings.unknown)
    }
}
