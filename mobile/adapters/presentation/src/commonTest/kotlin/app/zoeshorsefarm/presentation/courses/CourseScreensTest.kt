package app.zoeshorsefarm.presentation.courses

import app.zoeshorsefarm.application.FinishedParams
import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.progress.CourseBest
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.finishedParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseSelectTest {
    private val app = TestApp()

    @Test
    fun listsEveryCourseWithTheFirstOneOpenOnANewSave() {
        val model = CourseSelectModel(app.ctx)
        assertEquals(COURSES.map { it.id }, model.cards.map { it.id })
        assertEquals(listOf(true, false, false, false, false), model.cards.map { it.open })
        assertTrue(model.music)
        assertEquals("Courses", model.title)
    }

    @Test
    fun anOpenCourseShowsItsNumberJumpsStarsAndBestResult() {
        app.store.update(ProgressSection) {
            it.copy(unlocked = 2, courses = mapOf("1" to CourseBest(faults = 4, timeCs = 6001, stars = 2)))
        }
        val cards = CourseSelectModel(app.ctx).cards
        val first = cards[0]
        assertEquals("Course 1", first.title)
        assertEquals("${COURSES[0].obstacles.size} jumps", first.obstaclesText)
        assertEquals(2, first.stars)
        assertEquals("2 of 3 stars", first.starsLabel)
        assertEquals("Best: 4 faults · 01:00.01", first.bestText)
        assertEquals("Not ridden yet", cards[1].bestText)
        assertEquals(0, cards[1].stars)
    }

    @Test
    fun aLockedCourseSaysSoAndHasNoStars() {
        val card = CourseSelectModel(app.ctx).cards[2]
        assertFalse(card.open)
        assertEquals("Locked", card.bestText)
        assertNull(card.starsLabel)
    }

    @Test
    fun theBestTimeUsesTheGermanSeparatorInGerman() {
        app.i18n.setLang(Language.DE)
        app.store.update(ProgressSection) { it.copy(courses = mapOf("1" to CourseBest(0, 4827, 3))) }
        assertEquals("Beste: 0 Fehler · 00:48,27", CourseSelectModel(app.ctx).cards[0].bestText)
    }

    @Test
    fun selectingAnOpenCourseGoesToItsPrestart() {
        app.registerStubs("prestart")
        CourseSelectModel(app.ctx).select(1)
        assertEquals(Route.Prestart(1), app.navigator.current)
    }

    @Test
    fun selectingALockedCourseDoesNothing() {
        app.registerStubs("prestart", "menu")
        app.navigator.go(Route.Menu)
        CourseSelectModel(app.ctx).select(3)
        assertEquals(Route.Menu, app.navigator.current)
    }

    @Test
    fun backGoesToTheMenu() {
        app.registerStubs("menu")
        CourseSelectModel(app.ctx).back()
        assertEquals(Route.Menu, app.navigator.current)
    }
}

class PrestartTest {
    private val app = TestApp()

    @Test
    fun aLockedCourseRedirectsToTheSelectionEvenWhenForced() {
        assertEquals(Route.CourseSelect, PrestartModel(app.ctx, 4).redirect)
        assertEquals(Route.CourseSelect, PrestartModel(app.ctx, 99).redirect)
        assertNull(PrestartModel(app.ctx, 1).redirect)
    }

    @Test
    fun navigatingToALockedPrestartEndsOnTheSelection() {
        app.registerStubs("courseSelect")
        app.navigator.register("prestart") { PrestartModel(app.ctx, (it as Route.Prestart).courseId) }
        app.navigator.go(Route.Prestart(5))
        assertEquals(Route.CourseSelect, app.navigator.current)
    }

    @Test
    fun showsTitleAndAllowedTimeOfTheCourse() {
        val model = PrestartModel(app.ctx, 1)
        assertEquals("Course 1", model.title)
        assertEquals("Time allowed: ${COURSES[0].allowedTimeS} s", model.allowedTimeText)
        assertTrue(model.music)
    }

    @Test
    fun theAidSwitchChangesTheSavedCourseSetting() {
        val toggle = PrestartModel(app.ctx, 1).aid
        assertEquals("aidCourse", toggle.name)
        assertEquals("Take-off helper", toggle.label)
        assertFalse(toggle.on)
        toggle.toggle()
        assertTrue(app.store.settings.aidCourse)
        assertTrue(app.store.settings.aidFree)
    }

    @Test
    fun goPlaysTheStartSignalAndStartsTheCourseRide() {
        app.registerStubs("ride")
        PrestartModel(app.ctx, 1).go()
        assertEquals(listOf("startSignal"), app.sound.calls)
        assertEquals(Route.Ride(RideModeId.COURSE, 1), app.navigator.current)
    }

    @Test
    fun backGoesToTheSelection() {
        app.registerStubs("courseSelect")
        PrestartModel(app.ctx, 1).back()
        assertEquals(Route.CourseSelect, app.navigator.current)
    }

    @Test
    fun theLegendTextsAreTranslatedAndThePlanUsesThem() {
        val model = PrestartModel(app.ctx, 1)
        val plan = assertNotNull(model.plan(520.0, 297.0))
        val labels = plan.shapes.filterIsInstance<PlanLabel>().map { it.text }
        assertTrue("Start" in labels)
        assertTrue("Finish" in labels)
        assertEquals("Course plan with order and jumping direction", model.planLabel)
    }

    @Test
    fun aRedirectingModelHasNoPlan() {
        assertNull(PrestartModel(app.ctx, 4).plan(520.0, 297.0))
    }
}

class ResultsTest {
    private val app = TestApp()

    private fun model(params: FinishedParams = finishedParams()) = ResultsModel(app.ctx, params)

    @Test
    fun theResultsMusicStartsAfterTheFinishSignal() {
        assertTrue(model().music)
        assertEquals(1500L, model().musicDelayMs)
    }

    @Test
    fun showsTheStarsTimeAndThePenaltyRows() {
        val m = model(finishedParams(stars = 2, timeCs = 4827, knockdowns = 2, refusals = 1, timeFaults = 3))
        assertEquals(2, m.stars)
        assertEquals("2 of 3 stars", m.starsLabel)
        assertEquals(
            listOf("time", "knockdowns", "refusals", "timeFaults", "total"),
            m.rows.map { it.field },
        )
        assertEquals(listOf("Time", "Poles down", "Refusals", "Time faults", "Total faults"), m.rows.map { it.label })
        assertEquals("00:48.27", m.rows[0].value)
        assertEquals("2 × 4 = 8", m.rows[1].value)
        assertEquals("1 × 4 = 4", m.rows[2].value)
        assertEquals("3", m.rows[3].value)
        assertEquals("15", m.rows[4].value)
    }

    @Test
    fun aKindOfFaultThatDidNotHappenShowsZero() {
        val m = model(finishedParams(knockdowns = 0, refusals = 0, timeFaults = 0))
        assertEquals("0", m.rows[1].value)
        assertEquals("0", m.rows[2].value)
    }

    @Test
    fun theHorseIsNamedByItsDefaultNameOrItsOwn() {
        assertEquals("Flash and you", model().horseText)
        app.store.update(HorseSection) { it.copy(name = "Zora", nameAnswered = true) }
        assertEquals("Zora and you", model().horseText)
    }

    @Test
    fun aNewBestAndAnUnlockedCourseAreAnnounced() {
        val plain = model()
        assertNull(plain.newBestText)
        assertNull(plain.unlockedText)
        val m = model(finishedParams(isNewBest = true, unlockedCourse = 2))
        assertEquals("New personal best!", m.newBestText)
        assertEquals("Course 2 is now open!", m.unlockedText)
    }

    @Test
    fun newBadgesComeWithNameAndEmblem() {
        val m = model(finishedParams(awarded = listOf("clean", "unknownBadge", "busy")))
        assertEquals(listOf("clean", "busy"), m.badges.map { it.id })
        assertEquals(listOf("Clear Round", "Hard Worker"), m.badges.map { it.name })
        assertTrue(m.badges.all { it.name.isNotEmpty() && it.icon.isNotEmpty() })
        assertEquals("New badges", m.newBadgesTitle)
        assertTrue(model().badges.isEmpty())
    }

    @Test
    fun offersTheNextCourseOnlyWhenItIsOpen() {
        assertNull(model(finishedParams(courseId = 1)).nextCourse)
        app.store.update(ProgressSection) { it.copy(unlocked = 2) }
        assertEquals(2, model(finishedParams(courseId = 1)).nextCourse)
    }

    @Test
    fun theThreeButtonsLeadToTheNextScreens() {
        app.registerStubs("prestart", "courseSelect")
        app.store.update(ProgressSection) { it.copy(unlocked = 2) }
        val m = model(finishedParams(courseId = 1))
        m.again()
        assertEquals(Route.Prestart(1), app.navigator.current)
        m.next()
        assertEquals(Route.Prestart(2), app.navigator.current)
        m.toSelect()
        assertEquals(Route.CourseSelect, app.navigator.current)
        assertEquals(listOf("Again", "Next course", "To courses"), listOf(m.againLabel, m.nextLabel, m.toSelectLabel))
    }

    @Test
    fun nextDoesNothingWithoutANextCourse() {
        app.registerStubs("prestart")
        model(finishedParams(courseId = 1)).next()
        assertNull(app.navigator.current)
    }
}
