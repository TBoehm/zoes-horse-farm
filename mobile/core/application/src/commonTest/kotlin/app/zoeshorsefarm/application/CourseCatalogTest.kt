package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.progress.COURSE_COUNT
import app.zoeshorsefarm.domain.progress.COURSE_IDS
import app.zoeshorsefarm.domain.progress.CourseBest
import app.zoeshorsefarm.domain.progress.Progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CourseCatalogTest {
    private fun store(unlocked: Int = 1) = FakeStore(progress = Progress(unlocked = unlocked))

    // course ids

    @Test
    fun theProgressDomainKnowsExactlyTheCoursesThatExist() {
        assertEquals(COURSE_COUNT, COURSES.size)
        assertEquals(COURSES.map { it.id.toString() }, COURSE_IDS)
    }

    // listCourses

    @Test
    fun listsAllCoursesWithObstacleCountOnlyCourse1IsOpenAtTheStart() {
        val list = listCourses(FakeStore())
        assertEquals(COURSES.map { it.id }, list.map { it.id })
        assertEquals(
            CourseListing(id = 1, obstacleCount = COURSES[0].obstacles.size, open = true, stars = 0, best = null),
            list[0],
        )
        assertTrue(list.drop(1).none { it.open })
    }

    @Test
    fun opensEveryCourseUpToTheUnlockedOne() {
        val list = listCourses(store(unlocked = 3))
        assertEquals(listOf(true, true, true, false, false), list.map { it.open })
    }

    @Test
    fun reportsStarsAndBestResultOfACourse() {
        val progress = Progress(unlocked = 2, courses = mapOf("1" to CourseBest(faults = 4, timeCs = 5123, stars = 2)))
        val (first, second) = listCourses(FakeStore(progress = progress))
        assertEquals(2, first.stars)
        assertEquals(BestResult(faults = 4, timeCs = 5123), first.best)
        assertNull(second.best)
        assertEquals(0, second.stars)
    }

    // canStart

    @Test
    fun canStartAllowsOpenCoursesOnly() {
        val store = store(unlocked = 2)
        assertTrue(canStart(store, 1))
        assertTrue(canStart(store, 2))
        assertFalse(canStart(store, 3))
    }

    @Test
    fun canStartRejectsUnknownIds() {
        val store = store(unlocked = 5)
        assertFalse(canStart(store, 0))
        assertFalse(canStart(store, 6))
    }

    // nextCourse

    @Test
    fun returnsTheFollowingCourseWhenItIsOpen() {
        assertEquals(2, nextCourse(store(unlocked = 2), 1))
    }

    @Test
    fun returnsNullWhenTheFollowingCourseIsStillLocked() {
        assertNull(nextCourse(store(unlocked = 2), 2))
    }

    @Test
    fun returnsNullAfterTheLastCourse() {
        assertNull(nextCourse(store(unlocked = 5), COURSES.size))
    }

    // getCourse

    @Test
    fun returnsTheCourseDataForThePrestartScreen() {
        assertSame(COURSES[1], getCourse(2))
        assertSame(COURSES[1], getCourse("2"))
    }
}
