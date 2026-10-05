package app.zoeshorsefarm.application.modes

import app.zoeshorsefarm.domain.course.COURSES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModesTest {
    @Test
    fun defaultsToTheFreeMode() {
        assertEquals(RideModeId.FREE, createRideMode().id)
        assertEquals(RideModeId.FREE, createRideMode(id = null as String?).id)
    }

    @Test
    fun createsTheCourseModeForTheGivenCourse() {
        val mode = createRideMode(RideModeId.COURSE, courseId = 2)
        assertEquals(RideModeId.COURSE, mode.id)
        assertSame(COURSES[1].obstacles, mode.obstacles)
    }

    @Test
    fun createsAModeFromItsStoredId() {
        assertEquals(RideModeId.COURSE, createRideMode("course", 1).id)
        assertEquals(RideModeId.FREE, createRideMode("free").id)
    }

    @Test
    fun rejectsUnknownModes() {
        val error = assertFailsWith<IllegalArgumentException> { createRideMode("nope") }
        assertTrue(error.message.orEmpty().contains("unknown ride mode", ignoreCase = true))
    }

    @Test
    fun modeIdsKeepTheirStoredNames() {
        assertEquals(listOf("free", "course"), RideModeId.entries.map { it.id })
    }
}
