package app.zoeshorsefarm.presentation.profile

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.application.SettingsSection
import app.zoeshorsefarm.domain.progress.CourseBest
import app.zoeshorsefarm.presentation.TestApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResetSectionTest {
    private val app = TestApp()
    private val model = ResetSectionModel(app.ctx)

    private fun playedSomething() {
        app.store.update(ProgressSection) {
            it.copy(unlocked = 3, jumps = 40, courses = mapOf("1" to CourseBest(0, 5000, 3)))
        }
    }

    @Test
    fun startsWithOnlyTheDeleteButton() {
        assertFalse(model.confirming)
        assertEquals("", model.status)
        assertEquals(ResetFocus.NONE, model.focus)
    }

    @Test
    fun askingShowsTheQuestionAndFocusesTheSafeChoice() {
        model.request()
        assertTrue(model.confirming)
        assertEquals(ResetFocus.CANCEL, model.focus)
    }

    @Test
    fun cancelingKeepsTheProgressAndFocusesTheDeleteButton() {
        playedSomething()
        model.request()
        model.cancel()
        assertFalse(model.confirming)
        assertEquals(ResetFocus.TRIGGER, model.focus)
        assertEquals(40, app.store.progress.jumps)
    }

    @Test
    fun confirmingDeletesOnlyTheProgressAndReportsIt() {
        playedSomething()
        app.settings.setShowFps(true)
        model.request()
        model.confirm()
        assertFalse(model.confirming)
        assertEquals(ResetFocus.TRIGGER, model.focus)
        assertEquals(1, app.store.progress.unlocked)
        assertEquals(0, app.store.progress.jumps)
        assertTrue(
            app.store.progress.courses
                .isEmpty(),
        )
        assertTrue(app.store.get(SettingsSection).showFps)
        assertEquals("Progress deleted.", model.status)
    }

    @Test
    fun askingAgainClearsTheOldStatus() {
        model.request()
        model.confirm()
        model.request()
        assertEquals("", model.status)
    }

    @Test
    fun hasTheTextsOfTheDialog() {
        app.i18n.setLang(Language.EN)
        assertEquals("Delete progress", model.buttonLabel)
        assertEquals("Delete", model.confirmLabel)
        assertEquals("Cancel", model.cancelLabel)
        assertEquals("Really delete everything? Courses, stars and badges will be lost.", model.question)
    }

    @Test
    fun tellsTheUiAboutEveryChange() {
        var told = 0
        model.changes.listen { told++ }
        model.request()
        model.cancel()
        model.request()
        model.confirm()
        assertEquals(4, told)
    }
}
