package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.presentation.ManualScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BadgeToastStackingTest {
    @Test
    fun showsEveryToastInItsOwnSlotWhileThereIsRoom() {
        assertEquals(listOf(ToastSlot(0, true), ToastSlot(1, true)), toastSlots(2, 3))
    }

    @Test
    fun keepsToastsBeyondTheLimitWaitingHiddenUntilASlotIsFree() {
        assertEquals(listOf(true, true, false, false), toastSlots(4, 2).map { it.visible })
    }

    @Test
    fun closesTheGapWhenAToastIsGoneTheRestMovesDownByRecomputing() {
        assertEquals(listOf(ToastSlot(0, true)), toastSlots(1, 2))
        assertEquals(emptyList(), toastSlots(0, 2))
    }

    @Test
    fun putsEachToastAboveThePreviousOnesWithASmallGap() {
        assertEquals(listOf(0.0, 50.0, 100.0), stackOffsets(listOf(44.0, 44.0, 60.0), 6.0))
    }

    @Test
    fun hasNoOffsetsWithoutToasts() {
        assertEquals(emptyList(), stackOffsets(emptyList()))
    }

    @Test
    fun isCentredInTheGapBetweenTheTouchControlsAndLimitedToItsWidth() {
        // joystick ends at 154, the buttons start at 370 (568 px wide phone)
        assertEquals(ToastGap(x = 262.0, maxWidth = 200.0), toastGap(154.0, 370.0, margin = 8.0, minWidth = 100.0))
    }

    @Test
    fun neverGetsNarrowerThanTheMinimumWidth() {
        assertEquals(120.0, toastGap(200.0, 260.0, margin = 8.0, minWidth = 120.0).maxWidth)
    }
}

class BadgeToastQueueTest {
    private val i18n = I18n().apply { setLang(Language.EN) }
    private val scheduler = ManualScheduler()
    private var height = 800
    private val queue = BadgeToastQueue(i18n, scheduler.ui, viewportHeight = { height })

    @Test
    fun aToastNamesTheBadgeAndShowsItsEmblem() {
        assertTrue(queue.show("firstJump"))
        val toast = queue.toasts.single()
        assertEquals("firstJump", toast.badgeId)
        assertEquals("Badge: First Jump!", toast.text)
        assertEquals(badgeIcon("firstJump"), toast.icon)
        assertTrue(toast.visible)
        assertFalse(toast.leaving)
    }

    @Test
    fun anUnknownBadgeGivesNoToast() {
        assertFalse(queue.show("nope"))
        assertTrue(queue.toasts.isEmpty())
    }

    @Test
    fun aToastLeavesShortlyBeforeItEndsAndThenDisappears() {
        queue.show("firstJump")
        scheduler.advance(2799)
        assertFalse(queue.toasts.single().leaving)
        scheduler.advance(1)
        assertTrue(queue.toasts.single().leaving)
        scheduler.advance(399)
        assertEquals(1, queue.toasts.size)
        scheduler.advance(1)
        assertTrue(queue.toasts.isEmpty())
    }

    @Test
    fun atMostThreeToastsShowAtOnceTheFourthWaits() {
        listOf("firstJump", "jumpMouse", "clean", "busy").forEach(queue::show)
        assertEquals(listOf(true, true, true, false), queue.toasts.map { it.visible })
    }

    @Test
    fun aWaitingToastStartsItsTimerOnlyWhenItBecomesVisible() {
        listOf("firstJump", "jumpMouse", "clean", "busy").forEach(queue::show)
        // the first starts leaving: its slot is free and the waiting toast moves up
        scheduler.advance(2800)
        assertEquals(listOf(true, true, true, true), queue.toasts.map { it.visible })
        assertTrue(queue.toasts.first().leaving)
        // the others end at 3200, the late one has its own full time
        scheduler.advance(400)
        assertEquals(listOf("busy"), queue.toasts.map { it.badgeId })
        scheduler.advance(2399)
        assertFalse(queue.toasts.single().leaving)
        scheduler.advance(1)
        assertTrue(queue.toasts.single().leaving)
        scheduler.advance(400)
        assertTrue(queue.toasts.isEmpty())
    }

    @Test
    fun aLowScreenShowsOnlyTwoToasts() {
        height = 520
        listOf("firstJump", "jumpMouse", "clean").forEach(queue::show)
        assertEquals(listOf(true, true, false), queue.toasts.map { it.visible })
    }

    @Test
    fun turningTheDeviceCanChangeTheNumberOfVisibleToasts() {
        listOf("firstJump", "jumpMouse", "clean").forEach(queue::show)
        height = 400
        queue.relayout()
        assertEquals(listOf(true, true, false), queue.toasts.map { it.visible })
    }

    @Test
    fun theUiIsToldAboutEveryChange() {
        var told = 0
        queue.changes.listen { told++ }
        queue.show("firstJump")
        assertEquals(1, told)
        scheduler.advance(2800)
        assertEquals(2, told)
        scheduler.advance(400)
        assertEquals(3, told)
    }

    @Test
    fun aCustomDurationIsUsed() {
        val quick = BadgeToastQueue(i18n, scheduler.ui, viewportHeight = { 800 }, durationMs = 1000)
        quick.show("busy")
        scheduler.advance(1000)
        assertTrue(quick.toasts.isEmpty())
    }

    @Test
    fun disposeDropsAllToastsAndTimers() {
        queue.show("firstJump")
        queue.dispose()
        assertTrue(queue.toasts.isEmpty())
        assertEquals(0, scheduler.pending)
    }
}
