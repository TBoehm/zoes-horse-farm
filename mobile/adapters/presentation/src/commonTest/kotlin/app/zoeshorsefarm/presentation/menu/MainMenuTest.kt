package app.zoeshorsefarm.presentation.menu

import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.presentation.TestApp
import app.zoeshorsefarm.presentation.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainMenuTest {
    private val app = TestApp()

    @Test
    fun theDefaultEntriesFollowTheirOrder() {
        val registry = defaultMenuRegistry()
        assertEquals(
            listOf("courses", "free", "horse", "badges", "help", "settings"),
            registry.entries.map { it.id },
        )
        assertEquals(listOf(10, 20, 30, 40, 45, 50), registry.entries.map { it.order })
        assertEquals(
            listOf("menu.courses", "menu.free", "menu.horse", "menu.badges", "menu.help", "menu.settings"),
            registry.entries.map { it.labelKey },
        )
    }

    @Test
    fun everyLabelKeyHasATextInBothLanguages() {
        val model = MainMenuModel(app.ctx)
        for (lang in Language.entries) {
            app.i18n.setLang(lang)
            for (item in model.items) assertTrue(item.label.isNotEmpty(), "${item.id} in ${lang.id}")
        }
    }

    @Test
    fun registeringAnIdAgainReplacesTheEntryAndKeepsTheOrderSorted() {
        val registry = MenuRegistry()
        registry.register(MenuEntry("b", 20, "menu.free", Route.Menu))
        registry.register(MenuEntry("a", 10, "menu.courses", Route.Menu))
        registry.register(MenuEntry("b", 5, "menu.horse", Route.Badges))
        assertEquals(listOf("b", "a"), registry.entries.map { it.id })
        assertEquals(Route.Badges, registry.entries.first().target)
    }

    @Test
    fun anEntryWithAFalseVisibleCheckIsLeftOut() {
        val registry = MenuRegistry()
        registry.register(MenuEntry("shown", 1, "menu.free", Route.Menu))
        registry.register(MenuEntry("hidden", 2, "menu.horse", Route.Menu, visible = { false }))
        assertEquals(listOf("shown"), MainMenuModel(app.ctx, registry).items.map { it.id })
    }

    @Test
    fun selectingAnEntryReplacesTheScreenWithItsTarget() {
        app.registerStubs("menu", "courseSelect", "ride", "settings", "controlsHelp", "myHorse", "badges")
        val model = MainMenuModel(app.ctx)
        app.navigator.go(Route.Menu)
        model.items.first { it.id == "courses" }.select()
        assertEquals(Route.CourseSelect, app.navigator.current)
        model.items.first { it.id == "free" }.select()
        assertEquals(Route.Ride(), app.navigator.current)
        model.items.first { it.id == "help" }.select()
        assertEquals(Route.ControlsHelp(fromPause = false), app.navigator.current)
        model.items.first { it.id == "settings" }.select()
        assertEquals(Route.Settings(fromPause = false), app.navigator.current)
    }

    @Test
    fun greetsTheHorseByTheDefaultNameOrItsOwn() {
        app.i18n.setLang(Language.EN)
        assertEquals("Flash is waiting for you!", MainMenuModel(app.ctx).greeting)
        app.store.update(HorseSection) { it.copy(name = "Zora", nameAnswered = true) }
        assertEquals("Zora is waiting for you!", MainMenuModel(app.ctx).greeting)
    }

    @Test
    fun showsTitleAndSubtitleAndWantsTheMusic() {
        app.i18n.setLang(Language.EN)
        val model = MainMenuModel(app.ctx)
        assertEquals("Zoe's Horse Farm", model.title)
        assertEquals("Show Jumping", model.subtitle)
        assertTrue(model.music)
    }
}
