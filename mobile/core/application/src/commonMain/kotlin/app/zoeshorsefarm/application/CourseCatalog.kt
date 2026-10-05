package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.course.COURSES
import app.zoeshorsefarm.domain.course.Course
import app.zoeshorsefarm.domain.course.courseById
import app.zoeshorsefarm.domain.progress.Progress

// Course catalog for the selection screen: which courses are open, stars and best results
// (rules 35, 36, 37). The unlock rule itself lives in domain/progress (applyFinishedRide).

/** Faults and time (hundredths) of the best ride of a course. */
data class BestResult(
    val faults: Int,
    val timeCs: Int,
)

/** One line of the course selection: [open] = can be started, [best] null = never finished. */
data class CourseListing(
    val id: Int,
    val obstacleCount: Int,
    val open: Boolean,
    val stars: Int,
    val best: BestResult?,
)

private fun isOpen(
    progress: Progress,
    id: Int,
) = id <= progress.unlocked

fun listCourses(store: Store): List<CourseListing> {
    val progress = store.get(ProgressSection)
    return COURSES.map { course ->
        val entry = progress.courses[course.id.toString()]
        CourseListing(
            id = course.id,
            obstacleCount = course.obstacles.size,
            open = isOpen(progress, course.id),
            stars = entry?.stars ?: 0,
            best = entry?.let { BestResult(it.faults, it.timeCs) },
        )
    }
}

/** A course can be started when it exists and is open. */
fun canStart(
    store: Store,
    id: Int,
): Boolean = COURSES.any { it.id == id } && isOpen(store.get(ProgressSection), id)

/** The following course if it exists and is open, else null. */
fun nextCourse(
    store: Store,
    id: Int,
): Int? = (id + 1).takeIf { canStart(store, it) }

/** Course data (obstacles, start/finish, allowed time) for the prestart map. */
fun getCourse(id: Int): Course = courseById(id)

/** Course data for a course id given as text (a route parameter); the first course for anything unknown. */
fun getCourse(id: String?): Course = courseById(id)
