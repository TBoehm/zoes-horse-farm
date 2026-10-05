package app.zoeshorsefarm.presentation.courses

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.modes.CourseMode
import app.zoeshorsefarm.domain.course.RunPhase
import app.zoeshorsefarm.i18n.I18n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CourseHudTest {
    private val i18n = I18n().apply { setLang(Language.EN) }
    private val hud = CourseHudPresenter(i18n)

    private fun riding(
        timeCs: Int = 4827,
        faults: Int = 0,
        overTime: Boolean = false,
        nextLabel: String = "1",
        missingHint: Int? = null,
    ) = hud.render(RunPhase.RIDING, timeCs, allowedS = 62, faults, overTime, nextLabel, missingHint)

    @Test
    fun beforeTheStartOnlyAllowedTimeAndNextShowTogetherWithTheStartHint() {
        val state = hud.render(RunPhase.PRESTART, 0, 62, 0, false, "1", null)
        assertFalse(state.timeVisible)
        assertFalse(state.faultsVisible)
        assertEquals("62 s", state.allowedText)
        assertEquals("1", state.nextText)
        assertEquals("Ride over the start line", state.notice)
    }

    @Test
    fun whileRidingTimeAndFaultsAreShown() {
        val state = riding(timeCs = 7520, faults = 4)
        assertTrue(state.timeVisible)
        assertTrue(state.faultsVisible)
        assertEquals("01:15.20", state.timeText)
        assertEquals("4", state.faultsText)
        assertNull(state.notice)
    }

    @Test
    fun theTimeWarnsWhenTheAllowedTimeIsOver() {
        assertFalse(riding(overTime = false).timeWarning)
        assertTrue(riding(overTime = true).timeWarning)
    }

    @Test
    fun theNextObstacleShowsItsLabelOrTheFinish() {
        assertEquals("3b", riding(nextLabel = "3b").nextText)
        assertEquals("Finish", riding(nextLabel = "finish").nextText)
    }

    @Test
    fun aMissingObstacleHintNamesTheObstacle() {
        assertEquals("Jump 4 is still missing!", riding(missingHint = 4).notice)
    }

    @Test
    fun theTimeUsesTheDecimalSeparatorOfTheLanguage() {
        assertEquals("00:48.27", riding().timeText)
        i18n.setLang(Language.DE)
        assertEquals("00:48,27", riding().timeText)
    }

    @Test
    fun hasTheChipLabelsInTheCurrentLanguage() {
        assertEquals(
            listOf("Time", "Allowed", "Faults", "Next"),
            listOf(hud.timeLabel, hud.allowedLabel, hud.faultsLabel, hud.nextLabel),
        )
        i18n.setLang(Language.DE)
        assertEquals("Zeit", hud.timeLabel)
    }

    @Test
    fun anUnchangedModelGivesTheSameStateObjectAgain() {
        val first = riding()
        assertSame(first, riding())
        val changed = riding(faults = 4)
        assertTrue(first !== changed)
    }

    @Test
    fun aLanguageChangeRebuildsTheState() {
        val first = riding()
        i18n.setLang(Language.DE)
        assertTrue(first !== riding())
    }

    @Test
    fun readsTheHudModelOfTheCourseMode() {
        val mode = CourseMode(1)
        val state = hud.update(checkNotNull(mode.hudModel()))
        assertFalse(state.timeVisible)
        assertEquals("Ride over the start line", state.notice)
        assertEquals("1", state.nextText)
    }
}
