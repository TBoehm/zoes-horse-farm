package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.Marking
import app.zoeshorsefarm.domain.progress.Progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSchemaTest {
    private fun clean(raw: Map<String, Any?>) = SettingsSection.sanitize(raw)

    private val badVolumes =
        listOf(-0.1, 1.01, 7, Double.NaN, Double.POSITIVE_INFINITY, "0.8", null, true, emptyMap<String, Any?>())
    private val badFlags = listOf("true", 1, 0, null, emptyMap<String, Any?>(), emptyList<Any?>())

    // graphics settings fields (rule 4)

    @Test
    fun firstStartIsAutomaticAtTheLowestLevel() {
        val defaults = SettingsSection.defaults()
        assertTrue(defaults.graphicsAuto)
        assertEquals(AUTO_START_LEVEL, defaults.graphicsLevel)
        assertEquals(GraphicsLevel.LOW, AUTO_START_LEVEL)
    }

    @Test
    fun aSavedAutomaticLevelIsKeptForTheNextStart() {
        for (level in GRAPHICS_LEVELS) {
            val settings = clean(mapOf("graphicsAuto" to true, "graphicsLevel" to level.id))
            assertTrue(settings.graphicsAuto)
            assertEquals(level, settings.graphicsLevel)
        }
    }

    @Test
    fun aMissingOrInvalidLevelFallsBackToTheStartLevel() {
        for (level in listOf("ultra", 3, null)) {
            assertEquals(AUTO_START_LEVEL, clean(mapOf("graphicsLevel" to level)).graphicsLevel, "level = $level")
        }
        assertEquals(AUTO_START_LEVEL, clean(emptyMap()).graphicsLevel)
    }

    @Test
    fun theLanguageFallsBackToTheEnvironmentLanguage() {
        assertEquals(Language.DE, SettingsSection.defaults(SaveEnv(Language.DE)).lang)
        assertEquals(Language.EN, SettingsSection.defaults(SaveEnv()).lang)
        assertEquals(Language.DE, SettingsSection.sanitize(mapOf("lang" to "fr"), SaveEnv(Language.DE)).lang)
        assertEquals(Language.EN, SettingsSection.sanitize(mapOf("lang" to "en"), SaveEnv(Language.DE)).lang)
    }

    @Test
    fun cameraAndAidsHaveTheirDefaultsAndRepairInvalidValues() {
        val defaults = SettingsSection.defaults()
        assertEquals(CameraMode.FOLLOW, defaults.camera)
        assertTrue(defaults.aidFree)
        assertFalse(defaults.aidCourse)
        assertEquals(CameraMode.RIDER, clean(mapOf("camera" to "rider")).camera)
        assertEquals(CameraMode.FOLLOW, clean(mapOf("camera" to "birdseye")).camera)
        assertTrue(clean(mapOf("aidFree" to "yes")).aidFree)
    }

    // sound settings fields

    @Test
    fun soundDefaultsToHalfVolumeNotMuted() {
        val defaults = SettingsSection.defaults()
        assertEquals(0.5, defaults.musicVolume)
        assertFalse(defaults.musicMuted)
        assertEquals(0.5, defaults.sfxVolume)
        assertFalse(defaults.sfxMuted)
    }

    @Test
    fun soundFieldsAreFilledInForAnOldSaveWithoutThem() {
        val settings = clean(mapOf("lang" to "de"))
        assertEquals(0.5, settings.musicVolume)
        assertFalse(settings.musicMuted)
        assertEquals(0.5, settings.sfxVolume)
        assertFalse(settings.sfxMuted)
    }

    @Test
    fun soundFieldsKeepValidValuesIncludingTheLimits() {
        val limits = clean(mapOf("musicVolume" to 0, "musicMuted" to true, "sfxVolume" to 1, "sfxMuted" to true))
        assertEquals(0.0, limits.musicVolume)
        assertTrue(limits.musicMuted)
        assertEquals(1.0, limits.sfxVolume)
        assertTrue(limits.sfxMuted)
        val inner = clean(mapOf("musicVolume" to 0.3, "sfxVolume" to 0.8))
        assertEquals(0.3, inner.musicVolume)
        assertEquals(0.8, inner.sfxVolume)
    }

    @Test
    fun volumesFallBackToHalfForBadValues() {
        for (bad in badVolumes) {
            val settings = clean(mapOf("musicVolume" to bad, "sfxVolume" to bad))
            assertEquals(0.5, settings.musicVolume, "bad = $bad")
            assertEquals(0.5, settings.sfxVolume, "bad = $bad")
        }
    }

    @Test
    fun muteFlagsFallBackToNotMutedForBadValues() {
        for (bad in badFlags) {
            val settings = clean(mapOf("musicMuted" to bad, "sfxMuted" to bad))
            assertFalse(settings.musicMuted, "bad = $bad")
            assertFalse(settings.sfxMuted, "bad = $bad")
        }
    }

    @Test
    fun soundFieldsAreCleanedOneByOne() {
        val settings = clean(mapOf("musicVolume" to 9, "musicMuted" to true, "sfxVolume" to 0.2))
        assertEquals(0.5, settings.musicVolume)
        assertTrue(settings.musicMuted)
        assertEquals(0.2, settings.sfxVolume)
    }

    @Test
    fun soundSettingsSurviveDeleteProgress() {
        // rule 48 resets progress only
        val store =
            FakeStore(
                settings = Settings(musicVolume = 0.1, musicMuted = true, sfxVolume = 0.9, sfxMuted = true),
                progress = Progress(jumps = 12, finishedRides = 3, unlocked = 3),
            )
        resetProgress(store)
        assertEquals(Settings(musicVolume = 0.1, musicMuted = true, sfxVolume = 0.9, sfxMuted = true), store.settings)
        assertEquals(0, store.progress.jumps)
        assertEquals(0, store.progress.finishedRides)
        assertEquals(1, store.progress.unlocked)
    }

    // fps display setting field

    @Test
    fun fpsDisplayDefaultsToOff() {
        assertFalse(SettingsSection.defaults().showFps)
    }

    @Test
    fun fpsDisplayIsFilledInForAnOldSaveWithoutIt() {
        // backward compatible, rule 47
        val settings = clean(mapOf("lang" to "de", "camera" to "rider"))
        assertFalse(settings.showFps)
        assertEquals(CameraMode.RIDER, settings.camera)
    }

    @Test
    fun fpsDisplayKeepsAValidValue() {
        assertTrue(clean(mapOf("showFps" to true)).showFps)
        assertFalse(clean(mapOf("showFps" to false)).showFps)
    }

    @Test
    fun fpsDisplayFallsBackToOffForBadValues() {
        for (bad in badFlags) assertFalse(clean(mapOf("showFps" to bad)).showFps, "bad = $bad")
    }

    // controls help flag

    @Test
    fun controlsHelpIsOffByDefaultAndInAnOldSaveWithoutIt() {
        // rule 56
        assertFalse(SettingsSection.defaults().controlsHelpSeen)
        assertFalse(clean(mapOf("lang" to "de")).controlsHelpSeen)
    }

    @Test
    fun controlsHelpKeepsASavedTrueAndRepairsAWrongType() {
        assertTrue(clean(mapOf("controlsHelpSeen" to true)).controlsHelpSeen)
        assertFalse(clean(mapOf("controlsHelpSeen" to "yes")).controlsHelpSeen)
    }

    // the other sections

    @Test
    fun theHorseSectionRepairsFieldByField() {
        val horse =
            HorseSection.sanitize(mapOf("coat" to "purple", "marking" to "blaze", "name" to "  x", "nameAnswered" to 1))
        assertEquals(Coat.BAY, horse.coat)
        assertEquals(Marking.BLAZE, horse.marking)
        assertNull(horse.name)
        assertFalse(horse.nameAnswered)
    }

    @Test
    fun theHorseSectionKeepsAValidName() {
        val horse = HorseSection.sanitize(mapOf("name" to "Blitz", "nameAnswered" to true, "coat" to "grey"))
        assertEquals("Blitz", horse.name)
        assertTrue(horse.nameAnswered)
        assertEquals(Coat.GREY, horse.coat)
        assertNull(HorseSection.sanitize(mapOf("name" to "x".repeat(17))).name)
    }

    @Test
    fun theHorseDefaultsAreTheDefaultAppearanceWithoutAName() {
        assertEquals(HorseProfile(), HorseSection.defaults())
        assertNull(HorseSection.defaults().name)
    }

    @Test
    fun theProgressSectionUsesTheDomainSanitizer() {
        assertEquals(Progress(), ProgressSection.defaults())
        assertEquals(3, ProgressSection.sanitize(mapOf("unlocked" to 3)).unlocked)
        assertEquals(1, ProgressSection.sanitize("junk").unlocked)
    }

    @Test
    fun cameraModesKeepTheirIds() {
        assertEquals(listOf("follow", "rider"), CAMERA_MODES.map { it.id })
        assertEquals(CameraMode.RIDER, CameraMode.fromId("rider"))
        assertNull(CameraMode.fromId("birdseye"))
        assertNull(CameraMode.fromId(null))
    }

    @Test
    fun cleanSanitizesTheResultOfAChange() {
        val bad = Settings(musicVolume = 7.0)
        assertEquals(0.5, SettingsSection.clean(bad).musicVolume)
    }
}
