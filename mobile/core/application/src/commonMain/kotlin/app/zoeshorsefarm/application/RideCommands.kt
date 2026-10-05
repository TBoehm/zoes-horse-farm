package app.zoeshorsefarm.application

import app.zoeshorsefarm.domain.course.RideResult

// What the ride session asks the UI adapter to do (input, toasts, sound, screen change).

/** The sounds a ride asks for; [id] is the name the audio adapter plays. */
enum class RideSound(
    val id: String,
) {
    TAKEOFF("takeoff"),
    LANDING("landing"),
    RAIL_DOWN("railDown"),
    FINISH_SIGNAL("finishSignal"),
}

/** What a finished ride changed: a new best, a newly unlocked course, the badges it awarded. */
data class FinishOutcome(
    val isNewBest: Boolean,
    val unlockedCourse: Int?,
    val awarded: List<String>,
)

/** Params of the results screen: the ride that ended and what saving it changed. */
data class FinishedParams(
    val courseId: Int,
    val result: RideResult,
    val isNewBest: Boolean,
    val unlockedCourse: Int?,
    val awarded: List<String>,
)

/** A command of the ride session, returned by `restart()` and `step()`. */
sealed interface RideCommand {
    /** The gallop ended without the player's doing: release the gallop input. */
    data object EndGallop : RideCommand

    /** The ride was restarted: release the touch gallop button. */
    data object ResetTouchGallop : RideCommand

    /** Show the feedback with this text key (toast). */
    data class Feedback(
        val key: String,
    ) : RideCommand

    /** Instant badges were awarded by a jump: announce them. */
    data class Badges(
        val ids: List<String>,
    ) : RideCommand

    data class Sound(
        val sound: RideSound,
    ) : RideCommand

    /**
     * A course ride ended and its result is already saved. A `Sound(FINISH_SIGNAL)` comes right
     * before it. [screen] is the screen to go to (the results).
     */
    data class Finished(
        val screen: String,
        val params: FinishedParams,
    ) : RideCommand
}
