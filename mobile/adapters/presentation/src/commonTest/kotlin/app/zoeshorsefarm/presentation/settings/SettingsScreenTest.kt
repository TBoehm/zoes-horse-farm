package app.zoeshorsefarm.presentation.settings

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.application.SoundChannel
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SettingsScreenTest {
    private val app = TestApp()

    private fun screen(fromPause: Boolean = false) = SettingsScreenModel(app.ctx, fromPause)

    private fun SettingsScreenModel.rows(id: String) = (sections.first { it.id == id } as SettingsBlock.Rows).rows

    private fun SettingsScreenModel.choice(id: String) = rows(id).filterIsInstance<ChoiceRow>().first()

    @Test
    fun hasTheSectionsInTheOrderOfTheWebApp() {
        assertEquals(
            listOf("language", "graphics", "aid", "audio", "reset", "version"),
            screen().sections.map { it.id },
        )
        assertEquals(listOf(10, 20, 30, 40, 90, 99), screen().sections.map { it.order })
    }

    @Test
    fun theDeleteProgressSectionIsOnlyThereFromTheMainMenu() {
        assertEquals(
            listOf("language", "graphics", "aid", "audio", "version"),
            screen(fromPause = true).sections.map { it.id },
        )
    }

    @Test
    fun theMusicPlaysOnlyWhenOpenedFromTheMainMenu() {
        assertTrue(screen().music)
        assertFalse(screen(fromPause = true).music)
    }

    @Test
    fun backGoesToTheMenuOrPopsBackToThePauseMenu() {
        app.registerStubs("menu", "ride", "settings")
        app.navigator.go(Route.Menu)
        app.navigator.push(Route.Settings())
        screen().back()
        assertEquals(Route.Menu, app.navigator.current)
        assertEquals(listOf("menu"), app.navigator.stack)

        app.navigator.go(Route.Ride())
        app.navigator.push(Route.Settings(fromPause = true))
        screen(fromPause = true).back()
        assertEquals(Route.Ride(), app.navigator.current)
    }

    @Test
    fun theLanguageChoiceOffersBothLanguagesAndSwitchesSettingsAndTexts() {
        val model = screen()
        val row = model.choice("language")
        assertTrue(row.wide)
        assertEquals(listOf("de", "en"), row.options.map { it.id })
        assertEquals(listOf("Deutsch", "English"), row.options.map { it.label })
        assertEquals("en", row.selected)
        row.select("de")
        assertEquals(Language.DE, app.settings.get().lang)
        assertEquals(Language.DE, app.i18n.lang)
        assertEquals("Sprache", row.label)
    }

    @Test
    fun theGraphicsChoiceStartsOnAutomaticAndPicksALevel() {
        val row = screen().choice("graphics")
        assertEquals(listOf("auto", "low", "medium", "high"), row.options.map { it.id })
        assertEquals("auto", row.selected)
        assertTrue(row.wide)
        row.select("medium")
        assertEquals(GraphicsLevel.MEDIUM, app.settings.get().graphicsLevel)
        assertFalse(app.settings.get().graphicsAuto)
        assertEquals("medium", row.selected)
    }

    @Test
    fun automaticGraphicsStartAtLowAgainAndTellTheListeners() {
        var told = 0
        app.settings.onAutoSelected { told++ }
        val row = screen().choice("graphics")
        row.select("high")
        row.select("auto")
        assertTrue(app.settings.get().graphicsAuto)
        assertEquals(GraphicsLevel.LOW, app.settings.get().graphicsLevel)
        assertEquals("auto", row.selected)
        assertEquals(1, told)
    }

    @Test
    fun theFrameRateSwitchIsOwnRowAndChangesTheSetting() {
        val toggle = screen().rows("graphics").filterIsInstance<ToggleRow>().single()
        assertEquals("showFps", toggle.name)
        assertTrue(toggle.wide)
        assertFalse(toggle.on)
        toggle.toggle()
        assertTrue(app.settings.get().showFps)
        assertTrue(toggle.on)
    }

    @Test
    fun theJumpAidSwitchesStandForFreeRidingAndCourses() {
        val rows = screen().rows("aid").filterIsInstance<ToggleRow>()
        assertEquals(listOf("aidFree", "aidCourse"), rows.map { it.name })
        assertEquals(listOf(true, false), rows.map { it.on })
        rows[0].toggle()
        rows[1].toggle()
        assertFalse(app.settings.get().aidFree)
        assertTrue(app.settings.get().aidCourse)
    }

    @Test
    fun theVolumeRowsShowPercentAndKeepTheVolumeWhenMuted() {
        val volumes = screen().rows("audio").filterIsInstance<VolumeRow>()
        assertEquals(listOf(SoundChannel.MUSIC, SoundChannel.SFX), volumes.map { it.channel })
        val music = volumes[0]
        assertEquals(50, music.percent)
        assertTrue(music.soundOn)
        music.setPercent(80)
        assertEquals(0.8, app.settings.get().musicVolume)
        assertEquals(80, music.percent)
        music.setSoundOn(false)
        assertTrue(app.settings.get().musicMuted)
        assertEquals(0.8, app.settings.get().musicVolume)
        assertFalse(music.soundOn)
        assertEquals("Music", music.label)
        assertEquals("Music volume", music.sliderLabel)
        assertEquals("Music on", music.switchLabel)
    }

    @Test
    fun aVolumeIsLimitedToTheSliderRangeInSteps() {
        val music = screen().rows("audio").filterIsInstance<VolumeRow>().first()
        music.setPercent(130)
        assertEquals(100, music.percent)
        music.setPercent(-5)
        assertEquals(0, music.percent)
        assertEquals(0, VolumeRow.MIN_PERCENT)
        assertEquals(100, VolumeRow.MAX_PERCENT)
        assertEquals(5, VolumeRow.STEP_PERCENT)
    }

    @Test
    fun theVersionLineShowsTheBuildVersion() {
        val section = screen().sections.last()
        assertIs<SettingsBlock.Version>(section)
        assertEquals("Version 1.2.3", section.text)
    }

    @Test
    fun titleAndLabelsFollowTheLanguage() {
        app.i18n.setLang(Language.DE)
        val model = screen()
        assertEquals("Einstellungen", model.title)
        assertEquals("Grafik", model.choice("graphics").label)
        assertEquals(
            "Automatisch",
            model
                .choice("graphics")
                .options
                .first()
                .label,
        )
    }

    @Test
    fun theScreenTellsTheUiWhenTheSettingsChange() {
        val model = screen()
        var told = 0
        model.changes.listen { told++ }
        app.settings.setShowFps(true)
        assertEquals(1, told)
        model.destroy()
        app.settings.setShowFps(false)
        assertEquals(1, told)
    }

    @Test
    fun theResetSectionDeletesTheProgressOnlyAfterTheConfirmation() {
        app.store.update(ProgressSection) { it.copy(jumps = 42) }
        val reset = (screen().sections.first { it.id == "reset" } as SettingsBlock.Reset).model
        assertFalse(reset.confirming)
        reset.request()
        assertTrue(reset.confirming)
        assertEquals(42, app.store.progress.jumps)
        reset.confirm()
        assertFalse(reset.confirming)
        assertEquals(0, app.store.progress.jumps)
    }
}
