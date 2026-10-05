package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.progress.addJump
import app.zoeshorsefarm.domain.progress.applyFinishedRide
import app.zoeshorsefarm.domain.progress.checkInstantBadges
import app.zoeshorsefarm.domain.progress.checkRideEndBadges
import app.zoeshorsefarm.domain.progress.resetProgress as resetProgressData

// Progress use cases on top of the store port: count a jump, finish a ride, delete progress.
// The rules live in domain/progress; this service only sequences them and persists the result.

/**
 * Counts one jump over an obstacle (rule 40), saves it at once and awards instant badges (rule 49).
 * @return ids of the badges awarded by this jump
 */
fun recordJump(
    store: Store,
    clock: Clock,
): List<String> {
    var awarded = emptyList<String>()
    store.update(ProgressSection) { progress ->
        val checked = checkInstantBadges(addJump(progress), clock.nowIso())
        awarded = checked.awarded
        checked.progress
    }
    return awarded
}

/** Scores a FINISHED ride (never pass aborted rides, rule 40) and awards end-of-ride badges. */
fun finishRide(
    store: Store,
    clock: Clock,
    result: RideResult,
): FinishOutcome {
    var outcome = FinishOutcome(isNewBest = false, unlockedCourse = null, awarded = emptyList())
    store.update(ProgressSection) { progress ->
        val applied = applyFinishedRide(progress, result)
        val badges = checkRideEndBadges(applied.progress, result, clock.nowIso())
        outcome = FinishOutcome(applied.isNewBest, applied.unlockedCourse, badges.awarded)
        badges.progress
    }
    return outcome
}

/** "Delete progress" (rule 48): resets only the progress fields, nothing else in the save game. */
fun resetProgress(store: Store) {
    store.update(ProgressSection) { progress -> resetProgressData(progress) }
}
