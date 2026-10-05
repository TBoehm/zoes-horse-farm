package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.SettingsSection
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.presentation.help.ControlsHelpModel
import app.zoeshorsefarm.presentation.menu.MainMenuModel
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.notice.NoticeKind
import app.zoeshorsefarm.presentation.profile.NamePromptModel
import app.zoeshorsefarm.presentation.ride.RideScreenModel
import app.zoeshorsefarm.storage.SAVE_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStartTest {
    @Test
    fun anEmptySaveStartsWithTheNameQuestionThenTheControlsHelpThenTheMenu() {
        val rig = AppRig()
        rig.app.start()

        val prompt = rig.app.model<NamePromptModel>()
        prompt.onInput("Blitz")
        assertTrue(prompt.okEnabled)
        prompt.submit()

        rig.app.model<ControlsHelpModel>().done()
        val menu = rig.app.model<MainMenuModel>()

        assertTrue(menu.greeting.contains("Blitz"))
        assertEquals(
            "Blitz",
            rig.app.store
                .get(HorseSection)
                .name,
        )
        assertTrue(
            rig.app.settings
                .get()
                .controlsHelpSeen,
        )
    }

    @Test
    fun theSaveIsWrittenAtTheFirstStartAndTheNextStartGoesStraightToTheMenu() {
        val first = AppRig()
        assertNotNull(first.storage.getString(SAVE_KEY))
        first.app.start()
        first.app.model<NamePromptModel>().skip()
        first.app.model<ControlsHelpModel>().done()

        val second = AppRig(first.storage)
        second.app.start()

        second.app.model<MainMenuModel>()
    }

    @Test
    fun theStartLanguageFollowsTheSystemOnTheFirstStartAndTheSaveAfterwards() {
        val german = AppRig(languages = listOf("de-DE", "en"))
        assertEquals(Language.DE, german.app.i18n.lang)
        german.app.settings.setLang(Language.EN)

        val again = AppRig(german.storage, languages = listOf("de-DE"))

        assertEquals(Language.EN, again.app.i18n.lang)
        assertEquals(
            Language.EN,
            again.app.store
                .get(SettingsSection)
                .lang,
        )
    }

    @Test
    fun startingTwiceShowsTheFirstScreenOnce() {
        val rig = AppRig()
        var changes = 0
        rig.app.navigator.onScreen { changes++ }

        rig.app.start()
        rig.app.start()

        assertEquals(1, changes)
    }

    @Test
    fun theMenuMelodyIsWantedOnTheScreensThatAskForIt() {
        val rig = AppRig()
        rig.app.start()
        assertTrue(
            rig.app.audio
                .getState()
                .musicWanted,
        )

        rig.app.navigator.go(Route.Ride())

        assertFalse(
            rig.app.audio
                .getState()
                .musicWanted,
        )
    }

    @Test
    fun theFirstTouchUnlocksTheSoundAndTheBackgroundMutesIt() {
        val rig = AppRig()
        rig.app.start()
        assertFalse(
            rig.app.audio
                .getState()
                .unlocked,
        )

        rig.app.onTouch()
        assertTrue(
            rig.app.audio
                .getState()
                .unlocked,
        )
        assertTrue(
            rig.app.audio
                .getState()
                .running,
        )

        rig.app.onLifecycle(AppState.BACKGROUND)
        assertTrue(
            rig.app.audio
                .getState()
                .hidden,
        )
        rig.app.onLifecycle(AppState.FOREGROUND)
        assertFalse(
            rig.app.audio
                .getState()
                .hidden,
        )
    }

    @Test
    fun theNoticeOfTheNo3dDeviceReplacesTheUiWhenTheBackendCannotBeCreated() {
        val rig = AppRig()
        rig.backends.available = false
        var fired = 0
        rig.app.noticeChanges.listen { fired++ }
        rig.app.start()
        assertNull(rig.app.notice)

        rig.app.navigator.go(Route.Ride())

        assertEquals(NoticeKind.NO_3D, rig.app.notice?.kind)
        assertEquals(1, fired)
        // the ride waits paused like for a lost device
        assertTrue(rig.app.model<RideScreenModel>().paused)
    }

    @Test
    fun theRotateNoticeBlocksTheRideInPortraitInTouchMode() {
        val rig = AppRig()
        rig.app.start()
        rig.app.navigator.go(Route.Ride())
        val ride = rig.app.model<RideScreenModel>()
        rig.app.onViewportChanged(800, 400)
        assertFalse(rig.app.rotateNotice.blocked)
        assertFalse(ride.paused)

        rig.app.onViewportChanged(400, 800)

        assertTrue(rig.app.rotateNotice.blocked)
        assertTrue(ride.paused)
    }

    @Test
    fun disposeStopsEverythingAndCallsTheShutdownHookOnce() {
        val rig = AppRig(quiet = true)
        rig.app.start()
        rig.app.navigator.go(Route.Ride())
        rig.frames(5)

        rig.app.dispose()
        rig.app.dispose()

        assertEquals(1, rig.shutdowns)
        assertTrue(rig.backends.last.quiet.disposed)
    }
}
