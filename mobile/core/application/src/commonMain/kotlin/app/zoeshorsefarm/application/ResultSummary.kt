package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.course.KNOCKDOWN_FAULTS
import app.zoeshorsefarm.domain.course.REFUSAL_FAULTS

// Display model of the results screen (rules 32, 33, 36): penalty points per fault kind, badges
// and whether a next course is offered. Texts stay in the UI (keys only).

/** Penalty row of one fault kind: how many, points each, points in total. */
data class FaultRow(
    val count: Int,
    val points: Int,
    val each: Int,
)

data class ResultRows(
    val knockdowns: FaultRow,
    val refusals: FaultRow,
    val timeFaults: Int,
    val total: Int,
)

/** A badge awarded by the ride: its id and the key of its name. */
data class ResultBadge(
    val id: String,
    val nameKey: String,
)

data class ResultSummary(
    val courseId: Int,
    val stars: Int,
    val timeCs: Int,
    val isNewBest: Boolean,
    val unlockedCourse: Int?,
    val rows: ResultRows,
    val badges: List<ResultBadge>,
    val nextCourse: Int?,
)

/** [finished] = the params of the `Finished` command of the ride session. */
fun summarizeResult(
    store: Store,
    finished: FinishedParams,
): ResultSummary {
    val faults = finished.result.faults
    return ResultSummary(
        courseId = finished.courseId,
        stars = finished.result.stars,
        timeCs = finished.result.timeCs,
        isNewBest = finished.isNewBest,
        unlockedCourse = finished.unlockedCourse,
        rows =
            ResultRows(
                knockdowns = FaultRow(faults.knockdowns, faults.knockdowns * KNOCKDOWN_FAULTS, KNOCKDOWN_FAULTS),
                refusals = FaultRow(faults.refusals, faults.refusals * REFUSAL_FAULTS, REFUSAL_FAULTS),
                timeFaults = faults.timeFaults,
                total = faults.total,
            ),
        badges = finished.awarded.mapNotNull { id -> describeBadge(id)?.let { ResultBadge(it.id, it.nameKey) } },
        nextCourse = nextCourse(store, finished.courseId),
    )
}
