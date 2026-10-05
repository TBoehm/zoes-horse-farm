package app.zoeshorsefarm.presentation.nav

import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.i18n.I18n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A screen model that records what the navigator does to it. */
private class RecordingScreen(
    val label: String,
    val log: MutableList<String>,
    override val music: Boolean = false,
    override val musicDelayMs: Long = 0,
    override val redirect: Route? = null,
    override val rerenderOnLang: Boolean = true,
) : ScreenModel {
    override fun onShow() {
        log += "show $label"
    }

    override fun onCover() {
        log += "cover $label"
    }

    override fun destroy() {
        log += "destroy $label"
    }
}

class AppNavigatorTest {
    private val i18n = I18n()
    private val nav = AppNavigator(i18n)
    private val log = mutableListOf<String>()
    private val changes = mutableListOf<ScreenChange>()

    init {
        nav.onScreen { changes += it }
        nav.register("menu") { RecordingScreen("menu", log, music = true) }
        nav.register("settings") { route ->
            RecordingScreen("settings#${(route as Route.Settings).fromPause}", log, music = !route.fromPause)
        }
        nav.register("ride") { RecordingScreen("ride", log, rerenderOnLang = false) }
        nav.register("results") { RecordingScreen("results", log, music = true, musicDelayMs = 1500) }
        nav.register("prestart") { route ->
            val id = (route as Route.Prestart).courseId
            RecordingScreen("prestart$id", log, redirect = if (id > 1) Route.CourseSelect else null)
        }
        nav.register("courseSelect") { RecordingScreen("courseSelect", log, music = true) }
    }

    @Test
    fun startsWithoutAScreen() {
        assertNull(nav.current)
        assertEquals(emptyList(), nav.stack)
        assertNull(nav.currentModel)
    }

    @Test
    fun goReplacesTheWholeStackAndAnnouncesTheScreen() {
        nav.go(Route.Menu)
        nav.push(Route.Settings(fromPause = false))
        log.clear()
        changes.clear()
        nav.go(Route.CourseSelect)
        assertEquals("courseSelect", nav.current?.name)
        assertEquals(listOf("courseSelect"), nav.stack)
        assertEquals(listOf("destroy settings#false", "destroy menu", "show courseSelect"), log)
        assertEquals(1, changes.size)
        assertEquals(ScreenChange(Route.CourseSelect, true, 0, listOf("courseSelect")), changes[0])
    }

    @Test
    fun pushKeepsTheScreenBelowAndCoversIt() {
        nav.go(Route.Menu)
        log.clear()
        nav.push(Route.Settings(fromPause = true))
        assertEquals(listOf("menu", "settings"), nav.stack)
        assertEquals(listOf("cover menu", "show settings#true"), log)
        assertEquals(false, changes.last().music)
        assertEquals(listOf("menu", "settings"), changes.last().stack)
    }

    @Test
    fun popRemovesTheTopScreenAndShowsTheOneBelowAgain() {
        nav.go(Route.Menu)
        nav.push(Route.Settings(fromPause = false))
        log.clear()
        nav.pop()
        assertEquals("menu", nav.current?.name)
        assertEquals(listOf("destroy settings#false", "show menu"), log)
    }

    @Test
    fun popOnTheLastScreenDoesNothing() {
        nav.go(Route.Menu)
        log.clear()
        changes.clear()
        nav.pop()
        assertEquals(listOf("menu"), nav.stack)
        assertEquals(emptyList(), log)
        assertEquals(emptyList(), changes)
    }

    @Test
    fun theMusicDelayOfTheScreenIsPartOfTheAnnouncement() {
        nav.go(Route.Menu)
        nav.go(Route.Results(finishedParams()))
        assertEquals(true, changes.last().music)
        assertEquals(1500L, changes.last().musicDelayMs)
    }

    @Test
    fun aScreenThatRedirectsIsNotShownTheTargetIsOnGo() {
        nav.go(Route.Menu)
        log.clear()
        changes.clear()
        nav.go(Route.Prestart(2))
        assertEquals(listOf("courseSelect"), nav.stack)
        assertEquals(listOf("destroy menu", "destroy prestart2", "show courseSelect"), log)
        assertEquals(1, changes.size)
    }

    @Test
    fun aScreenThatRedirectsOnPushIsRemovedAndTheTargetReplacesTheStack() {
        nav.go(Route.Menu)
        log.clear()
        nav.push(Route.Prestart(3))
        assertEquals(listOf("courseSelect"), nav.stack)
        assertEquals(listOf("cover menu", "destroy prestart3", "destroy menu", "show courseSelect"), log)
    }

    @Test
    fun theFactoryGetsTheRouteWithItsParameters() {
        var seen: Route? = null
        nav.register("badges") {
            seen = it
            RecordingScreen("badges", log)
        }
        nav.go(Route.Badges)
        assertSame(Route.Badges, seen)
    }

    @Test
    fun anUnregisteredScreenIsAnError() {
        assertFailsWith<IllegalStateException> { nav.go(Route.MyHorse) }
    }

    @Test
    fun aLanguageChangeRebuildsTheScreensExceptTheOnesThatKeepTheirState() {
        nav.go(Route.Menu)
        nav.push(Route.Settings(fromPause = false))
        val menuBefore = nav.routes.size
        val topBefore = nav.currentModel
        log.clear()
        changes.clear()
        i18n.setLang(Language.EN)
        assertEquals(listOf("destroy menu", "destroy settings#false", "show settings#false").sorted(), log.sorted())
        assertTrue(nav.currentModel !== topBefore)
        assertEquals(menuBefore, nav.routes.size)
        assertEquals(1, changes.size)
    }

    @Test
    fun aRideKeepsItsModelWhenTheLanguageChanges() {
        nav.go(Route.Ride(app.zoeshorsefarm.application.modes.RideModeId.FREE, null))
        val model = nav.currentModel
        log.clear()
        i18n.setLang(Language.EN)
        assertSame(model, nav.currentModel)
        assertEquals(emptyList(), log.filter { it.startsWith("destroy") })
    }

    @Test
    fun rotateBlockedIsHandedToTheListeners() {
        val seen = mutableListOf<Boolean>()
        val off = nav.onRotateBlocked { seen += it }
        nav.emitRotateBlocked(true)
        nav.emitRotateBlocked(false)
        off()
        nav.emitRotateBlocked(true)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun disposeDestroysTheScreensAndStopsListeningToTheLanguage() {
        nav.go(Route.Menu)
        log.clear()
        nav.dispose()
        assertEquals(listOf("destroy menu"), log)
        log.clear()
        i18n.setLang(Language.EN)
        assertEquals(emptyList(), log)
    }

    @Test
    fun routesKnowTheirStableScreenName() {
        assertEquals("menu", Route.Menu.name)
        assertEquals("namePrompt", Route.NamePrompt.name)
        assertEquals("controlsHelp", Route.ControlsHelp(fromPause = true).name)
        assertEquals("settings", Route.Settings(fromPause = false).name)
        assertEquals("courseSelect", Route.CourseSelect.name)
        assertEquals("prestart", Route.Prestart(1).name)
        assertEquals("results", Route.Results(finishedParams()).name)
        assertEquals("myHorse", Route.MyHorse.name)
        assertEquals("badges", Route.Badges.name)
    }

    @Test
    fun aSessionScreenNameBecomesARoute() {
        assertEquals(Route.Menu, routeForScreenName("menu"))
        assertEquals(Route.CourseSelect, routeForScreenName("courseSelect"))
        assertFailsWith<IllegalArgumentException> { routeForScreenName("nowhere") }
    }

    @Test
    fun theStartSequenceGivesTheRoutesOfTheStartScreens() {
        assertEquals(
            Route.NamePrompt,
            app.zoeshorsefarm.application.StartScreen.NAME_PROMPT
                .toRoute(),
        )
        assertEquals(
            Route.ControlsHelp(fromPause = false),
            app.zoeshorsefarm.application.StartScreen.CONTROLS_HELP
                .toRoute(),
        )
        assertEquals(
            Route.Menu,
            app.zoeshorsefarm.application.StartScreen.MENU
                .toRoute(),
        )
    }
}
