package app.zoeshorsefarm.presentation.nav

import app.zoeshorsefarm.application.FinishedParams
import app.zoeshorsefarm.application.RideCommand
import app.zoeshorsefarm.application.StartScreen
import app.zoeshorsefarm.application.modes.RideModeId

/**
 * A screen with its parameters (web: `app.go(name, params)`). [name] is the stable screen name the
 * application layer and the screen registry use.
 */
sealed interface Route {
    val name: String

    data object Menu : Route {
        override val name = "menu"
    }

    data object NamePrompt : Route {
        override val name = "namePrompt"
    }

    /** [fromPause]: opened on top of the paused ride, "Got it" pops back to the pause menu (rule 56). */
    data class ControlsHelp(
        val fromPause: Boolean = false,
    ) : Route {
        override val name get() = NAME

        companion object {
            const val NAME = "controlsHelp"
        }
    }

    /** [fromPause]: opened from the pause menu (rules 38, 48, 51). */
    data class Settings(
        val fromPause: Boolean = false,
    ) : Route {
        override val name get() = NAME

        companion object {
            const val NAME = "settings"
        }
    }

    data object CourseSelect : Route {
        override val name = "courseSelect"
    }

    data class Prestart(
        val courseId: Int,
    ) : Route {
        override val name get() = NAME

        companion object {
            const val NAME = "prestart"
        }
    }

    /** A free ride ([RideModeId.FREE]) or a ride of a course. */
    data class Ride(
        val mode: RideModeId = RideModeId.FREE,
        val courseId: Int? = null,
    ) : Route {
        override val name get() = NAME

        companion object {
            const val NAME = "ride"
        }
    }

    /** The results of a finished course ride (what the ride session reported). */
    data class Results(
        val finished: FinishedParams,
    ) : Route {
        override val name get() = NAME

        companion object {
            const val NAME = "results"
        }
    }

    data object MyHorse : Route {
        override val name = "myHorse"
    }

    data object Badges : Route {
        override val name = "badges"
    }
}

/**
 * The route of a screen name without parameters, as the ride modes name their quit screen
 * (`quitScreen` of a mode: "menu" or "courseSelect").
 * @throws IllegalArgumentException for a name that needs parameters or does not exist
 */
fun routeForScreenName(name: String): Route =
    when (name) {
        Route.Menu.name -> Route.Menu
        Route.NamePrompt.name -> Route.NamePrompt
        Route.CourseSelect.name -> Route.CourseSelect
        Route.MyHorse.name -> Route.MyHorse
        Route.Badges.name -> Route.Badges
        else -> throw IllegalArgumentException("No parameterless screen named '$name'")
    }

/** The screen of a step of the start sequence (name question, controls help, menu). */
fun StartScreen.toRoute(): Route =
    when (this) {
        StartScreen.NAME_PROMPT -> Route.NamePrompt
        StartScreen.CONTROLS_HELP -> Route.ControlsHelp(fromPause = false)
        StartScreen.MENU -> Route.Menu
    }

/** Where the end of a course ride leads: the results screen with the saved outcome. */
fun RideCommand.Finished.toRoute(): Route =
    when (screen) {
        Route.Results.NAME -> Route.Results(params)
        else -> routeForScreenName(screen)
    }
