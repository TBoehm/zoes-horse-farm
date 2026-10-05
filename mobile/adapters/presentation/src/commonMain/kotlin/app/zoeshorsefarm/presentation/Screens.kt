package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.Rng
import app.zoeshorsefarm.application.Store
import app.zoeshorsefarm.application.nextStartScreen
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.presentation.courses.CourseSelectModel
import app.zoeshorsefarm.presentation.courses.PrestartModel
import app.zoeshorsefarm.presentation.courses.ResultsModel
import app.zoeshorsefarm.presentation.help.ControlsHelpModel
import app.zoeshorsefarm.presentation.menu.MainMenuModel
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.toRoute
import app.zoeshorsefarm.presentation.profile.BadgesModel
import app.zoeshorsefarm.presentation.profile.HorsePreview
import app.zoeshorsefarm.presentation.profile.MyHorseModel
import app.zoeshorsefarm.presentation.profile.NamePromptModel
import app.zoeshorsefarm.presentation.ride.RideEnginePort
import app.zoeshorsefarm.presentation.ride.createRideScreen
import app.zoeshorsefarm.presentation.settings.SettingsScreenModel

/**
 * Registers the model factory of every screen with the navigator of [ctx] (web: the `register*`
 * functions of `main.js` and the screen folders). The ride gets its random source here (the
 * application layer never draws random numbers itself) and the [engine] that draws it; the "My
 * horse" screen dresses the 3D preview through [horsePreview].
 */
fun registerScreens(
    ctx: AppContext,
    rng: Rng,
    engine: RideEnginePort,
    horsePreview: HorsePreview = HorsePreview { },
) {
    val nav = ctx.navigator
    nav.register(Route.Menu.name) { MainMenuModel(ctx) }
    nav.register(Route.NamePrompt.name) { NamePromptModel(ctx) }
    nav.register(Route.ControlsHelp.NAME) { ControlsHelpModel(ctx, (it as Route.ControlsHelp).fromPause) }
    nav.register(Route.Settings.NAME) { SettingsScreenModel(ctx, (it as Route.Settings).fromPause) }
    nav.register(Route.CourseSelect.name) { CourseSelectModel(ctx) }
    nav.register(Route.Prestart.NAME) { PrestartModel(ctx, (it as Route.Prestart).courseId) }
    nav.register(Route.Ride.NAME) { createRideScreen(ctx, it as Route.Ride, rng, engine) }
    nav.register(Route.Results.NAME) { ResultsModel(ctx, (it as Route.Results).finished) }
    nav.register(Route.MyHorse.name) { MyHorseModel(ctx, horsePreview) }
    nav.register(Route.Badges.name) { BadgesModel(ctx) }
}

/** The first screen of the app: the name question, the controls help or the main menu (rules 43, 56). */
fun startRoute(store: Store): Route = nextStartScreen(store).toRoute()
