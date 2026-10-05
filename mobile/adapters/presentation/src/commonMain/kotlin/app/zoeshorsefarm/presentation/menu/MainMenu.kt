package app.zoeshorsefarm.presentation.menu

import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.displayName
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenModel

// Main menu with an extensible entry list (rules 53, 54): feature areas register entries, the order
// follows `order`.

/**
 * One entry of the main menu: selecting it replaces the screen with [target]. [visible] can hide it.
 */
data class MenuEntry(
    val id: String,
    val order: Int,
    val labelKey: String,
    val target: Route,
    val visible: () -> Boolean = { true },
)

/** The entries of the main menu, sorted by `order`; registering an id again replaces its entry. */
class MenuRegistry {
    private val list = ArrayList<MenuEntry>()

    val entries: List<MenuEntry> get() = list.toList()

    fun register(entry: MenuEntry) {
        list.removeAll { it.id == entry.id }
        list.add(entry)
        list.sortBy { it.order }
    }
}

/** The entries of the game: courses, free riding, my horse, badges, controls help, settings. */
fun defaultMenuRegistry(): MenuRegistry =
    MenuRegistry().apply {
        register(MenuEntry("courses", 10, "menu.courses", Route.CourseSelect))
        register(MenuEntry("free", 20, "menu.free", Route.Ride()))
        register(MenuEntry("horse", 30, "menu.horse", Route.MyHorse))
        register(MenuEntry("badges", 40, "menu.badges", Route.Badges))
        register(MenuEntry("help", 45, "menu.help", Route.ControlsHelp(fromPause = false)))
        register(MenuEntry("settings", 50, "menu.settings", Route.Settings(fromPause = false)))
    }

/** A button of the menu. */
class MenuItem internal constructor(
    val id: String,
    val label: String,
    private val onSelect: () -> Unit,
) {
    fun select() = onSelect()
}

class MainMenuModel(
    private val ctx: AppContext,
    registry: MenuRegistry = defaultMenuRegistry(),
) : ScreenModel {
    private val entries = registry.entries.filter { it.visible() }

    override val music = true

    val title: String get() = ctx.t("app.title")
    val subtitle: String get() = ctx.t("app.subtitle")

    /** "{name} is waiting for you!" with the horse's own name or the language default. */
    val greeting: String
        get() {
            val name = displayName(ctx.store.get(HorseSection), ctx.t("horse.defaultName"))
            return ctx.t("menu.greeting", mapOf("name" to name))
        }

    /** The buttons in order; the labels follow the language at the time of reading. */
    val items: List<MenuItem>
        get() = entries.map { e -> MenuItem(e.id, ctx.t(e.labelKey)) { ctx.navigator.go(e.target) } }
}
