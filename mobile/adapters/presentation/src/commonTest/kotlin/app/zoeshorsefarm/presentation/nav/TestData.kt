package app.zoeshorsefarm.presentation.nav

import app.zoeshorsefarm.application.FinishedParams
import app.zoeshorsefarm.domain.course.Faults
import app.zoeshorsefarm.domain.course.RideResult

/** Params of a finished ride for the tests (course 1, 2 stars, one knockdown). */
fun finishedParams(
    courseId: Int = 1,
    stars: Int = 2,
    timeCs: Int = 4827,
    knockdowns: Int = 1,
    refusals: Int = 0,
    timeFaults: Int = 0,
    isNewBest: Boolean = false,
    unlockedCourse: Int? = null,
    awarded: List<String> = emptyList(),
): FinishedParams =
    FinishedParams(
        courseId = courseId,
        result =
            RideResult(
                courseId = courseId,
                timeCs = timeCs,
                faults = Faults(knockdowns, refusals, timeFaults, knockdowns * 4 + refusals * 4 + timeFaults),
                stars = stars,
                cleanOxer = false,
                cleanCombination = false,
            ),
        isNewBest = isNewBest,
        unlockedCourse = unlockedCourse,
        awarded = awarded,
    )
