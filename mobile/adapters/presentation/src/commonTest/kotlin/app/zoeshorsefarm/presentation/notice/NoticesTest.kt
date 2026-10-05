package app.zoeshorsefarm.presentation.notice

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.InputMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticesTest {
    private val i18n = I18n().apply { setLang(Language.EN) }

    @Test
    fun theNoGraphicsNoticeNamesATitleATextAndAHint() {
        val notice = FullNotice(NoticeKind.NO_3D, i18n)
        assertEquals("no3d", notice.kind.id)
        assertEquals("Oh no!", notice.title)
        assertEquals("Sorry, your device or browser cannot show the game.", notice.text)
        assertEquals("Try another browser or device.", notice.hint)
        assertEquals("🐴", notice.emoji)
    }

    @Test
    fun theStartErrorNoticeIsFriendlyAndTranslated() {
        val notice = FullNotice(NoticeKind.ERROR, i18n)
        assertEquals("Oops, something went wrong!", notice.title)
        i18n.setLang(Language.DE)
        assertEquals("Huch, da ist etwas schiefgelaufen!", notice.title)
    }

    @Test
    fun theRotateNoticeBlocksOnlyInPortraitWithTouchControls() {
        val rotate = RotateNotice(i18n, InputMode.mobile(), width = 800, height = 400)
        assertFalse(rotate.blocked)
        rotate.update(400, 800)
        assertTrue(rotate.blocked)
        rotate.update(400, 400)
        assertFalse(rotate.blocked)
    }

    @Test
    fun withoutTouchModePortraitDoesNotBlock() {
        val rotate = RotateNotice(i18n, InputMode(DeviceClass.KEYBOARD), width = 400, height = 800)
        assertFalse(rotate.blocked)
    }

    @Test
    fun theBlockedStateIsReportedOnlyWhenItChanges() {
        val seen = mutableListOf<Boolean>()
        val rotate = RotateNotice(i18n, InputMode.mobile(), width = 800, height = 400, onBlockedChange = { seen += it })
        rotate.update(400, 800)
        rotate.update(300, 700)
        rotate.update(800, 400)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun aSwitchOfTheInputModeIsEvaluatedAgain() {
        val mode = InputMode(DeviceClass.HYBRID)
        val seen = mutableListOf<Boolean>()
        val rotate = RotateNotice(i18n, mode, width = 400, height = 800, onBlockedChange = { seen += it })
        assertFalse(rotate.blocked)
        mode.onTouch()
        assertTrue(rotate.blocked)
        mode.onKey("KeyW")
        assertFalse(rotate.blocked)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun theRotateNoticeTextFollowsTheLanguage() {
        val rotate = RotateNotice(i18n, InputMode.mobile(), width = 400, height = 800)
        assertEquals("Please turn your device", rotate.title)
        assertEquals("The game only works sideways.", rotate.text)
        i18n.setLang(Language.DE)
        assertEquals("Bitte dreh dein Gerät", rotate.title)
    }

    @Test
    fun disposeStopsListeningToTheInputMode() {
        val mode = InputMode(DeviceClass.HYBRID)
        val rotate = RotateNotice(i18n, mode, width = 400, height = 800)
        rotate.dispose()
        mode.onTouch()
        assertFalse(rotate.blocked)
    }

    @Test
    fun theSaveNoticeShowsUntilItIsDismissed() {
        val notice = SaveNotice(i18n)
        assertFalse(notice.visible)
        notice.show()
        assertTrue(notice.visible)
        assertEquals("Your progress is not being saved right now. You can still play.", notice.text)
        assertEquals("Okay", notice.okLabel)
        notice.dismiss()
        assertFalse(notice.visible)
    }

    @Test
    fun theSaveNoticeTellsTheListenersAboutEveryChange() {
        val notice = SaveNotice(i18n)
        var calls = 0
        val off = notice.onChange { calls++ }
        notice.show()
        notice.show()
        notice.dismiss()
        notice.dismiss()
        off()
        notice.show()
        assertEquals(2, calls)
    }
}
