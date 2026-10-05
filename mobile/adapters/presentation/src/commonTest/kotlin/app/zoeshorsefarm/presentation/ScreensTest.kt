package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.presentation.courses.CourseSelectModel
import app.zoeshorsefarm.presentation.courses.PrestartModel
import app.zoeshorsefarm.presentation.courses.ResultsModel
import app.zoeshorsefarm.presentation.help.ControlsHelpModel
import app.zoeshorsefarm.presentation.menu.MainMenuModel
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.finishedParams
import app.zoeshorsefarm.presentation.profile.BadgesModel
import app.zoeshorsefarm.presentation.profile.MyHorseModel
import app.zoeshorsefarm.presentation.profile.NamePromptModel
import app.zoeshorsefarm.presentation.ride.RideEnginePort
import app.zoeshorsefarm.presentation.ride.RideScreenModel
import app.zoeshorsefarm.presentation.settings.SettingsScreenModel
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScreensTest {
    private val app = TestApp()
    private val appearances = mutableListOf<Appearance>()

    private val engine: RideEnginePort = IdleEngine()

    init {
        app.store.update(ProgressSection) { it.copy(unlocked = 2) }
        registerScreens(app.ctx, seededRng(), engine) { appearances += it }
    }

    private fun modelOf(route: Route): Any {
        app.navigator.go(route)
        return checkNotNull(app.navigator.currentModel)
    }

    private fun assertScreen(
        route: Route,
        type: KClass<*>,
    ) {
        val model = modelOf(route)
        assertTrue(type.isInstance(model), "${route.name}: expected ${type.simpleName} but was $model")
    }

    @Test
    fun everyRouteHasItsScreenModel() {
        assertScreen(Route.Menu, MainMenuModel::class)
        assertScreen(Route.NamePrompt, NamePromptModel::class)
        assertScreen(Route.ControlsHelp(), ControlsHelpModel::class)
        assertScreen(Route.Settings(), SettingsScreenModel::class)
        assertScreen(Route.CourseSelect, CourseSelectModel::class)
        assertScreen(Route.Prestart(1), PrestartModel::class)
        assertScreen(Route.Ride(), RideScreenModel::class)
        assertScreen(Route.Results(finishedParams()), ResultsModel::class)
        assertScreen(Route.MyHorse, MyHorseModel::class)
        assertScreen(Route.Badges, BadgesModel::class)
    }

    @Test
    fun theParametersOfTheRouteReachTheModel() {
        assertIs<SettingsScreenModel>(modelOf(Route.Settings(fromPause = true)))
        assertEquals(false, (app.navigator.currentModel as SettingsScreenModel).music)
        assertEquals(false, (modelOf(Route.ControlsHelp(fromPause = true)) as ControlsHelpModel).music)
        assertEquals(true, (modelOf(Route.ControlsHelp(fromPause = false)) as ControlsHelpModel).music)
    }

    @Test
    fun aLockedCourseNeverShowsItsPrestartOrItsRide() {
        modelOf(Route.Prestart(4))
        assertEquals(Route.CourseSelect, app.navigator.current)
        modelOf(Route.Ride(RideModeId.COURSE, 4))
        assertEquals(Route.CourseSelect, app.navigator.current)
    }

    @Test
    fun anOpenCourseRideStarts() {
        assertIs<RideScreenModel>(modelOf(Route.Ride(RideModeId.COURSE, 2)))
    }

    @Test
    fun theMyHorseScreenDressesThePreviewWhenTheLookChanges() {
        val model = modelOf(Route.MyHorse) as MyHorseModel
        model.coat.select("grey")
        assertEquals(1, appearances.size)
    }

    @Test
    fun theStartRouteFollowsTheStartSequence() {
        assertEquals(Route.NamePrompt, startRoute(app.store))
        app.store.update(HorseSection) { it.copy(nameAnswered = true) }
        assertEquals(Route.ControlsHelp(fromPause = false), startRoute(app.store))
        app.settings.markControlsHelpSeen()
        assertEquals(Route.Menu, startRoute(app.store))
    }

    @Test
    fun theMenuLeadsToTheScreensThatAreRegistered() {
        val menu = modelOf(Route.Menu) as MainMenuModel
        for (item in menu.items) {
            app.navigator.go(Route.Menu)
            item.select()
            assertTrue(app.navigator.current != null, item.id)
        }
    }
}
