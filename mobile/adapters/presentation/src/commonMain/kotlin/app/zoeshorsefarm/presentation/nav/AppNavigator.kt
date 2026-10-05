package app.zoeshorsefarm.presentation.nav

import app.zoeshorsefarm.i18n.I18n

/**
 * Announcement after every change of the screen stack (web: the app's `screen` event): the new top
 * [route] (null when the stack is empty), its music wish and delay, and the names of the stack.
 */
data class ScreenChange(
    val route: Route?,
    val music: Boolean,
    val musicDelayMs: Long,
    val stack: List<String>,
) {
    val name: String? get() = route?.name
}

/**
 * The screen stack of the app (web: `createApp` in `app.js`): screens are registered by name with a
 * factory, [go] replaces the whole stack, [push] puts a screen on top, [pop] removes the top one.
 * A model can redirect instead of being shown (a locked course). When the language changes the
 * models are rebuilt, except those that keep their state (a ride).
 *
 * The UI shows [currentModel] and reacts to [onScreen]; every action of the screens goes through here.
 * Not thread safe: call it from the UI thread only.
 */
class AppNavigator(
    private val i18n: I18n,
) {
    private class Entry(
        val route: Route,
    ) {
        var model: ScreenModel? = null
    }

    private val factories = HashMap<String, (Route) -> ScreenModel>()
    private val entries = ArrayList<Entry>()
    private val screenListeners = LinkedHashSet<(ScreenChange) -> Unit>()
    private val rotateListeners = LinkedHashSet<(Boolean) -> Unit>()
    private val stopLangListener = i18n.onLangChange { rebuildForLanguage() }

    /** Registers the factory of a screen; [name] is [Route.name]. */
    fun register(
        name: String,
        factory: (Route) -> ScreenModel,
    ) {
        factories[name] = factory
    }

    /** The route of the top screen, or null. */
    val current: Route? get() = entries.lastOrNull()?.route

    /** The model of the top screen, or null. */
    val currentModel: ScreenModel? get() = entries.lastOrNull()?.model

    /** The routes of the stack, bottom first. */
    val routes: List<Route> get() = entries.map { it.route }

    /** The screen names of the stack, bottom first. */
    val stack: List<String> get() = entries.map { it.route.name }

    private fun mount(entry: Entry) {
        val factory = checkNotNull(factories[entry.route.name]) { "No screen registered for '${entry.route.name}'" }
        entry.model = factory(entry.route)
    }

    private fun unmount(entry: Entry) {
        entry.model?.destroy()
        entry.model = null
    }

    private fun changed() {
        val top = entries.lastOrNull()
        val model = top?.model
        model?.onShow()
        val change = ScreenChange(top?.route, model?.music ?: false, model?.musicDelayMs ?: 0, stack)
        for (listener in screenListeners.toList()) listener(change)
    }

    /** Replaces the whole stack with one screen. */
    fun go(route: Route) {
        while (entries.isNotEmpty()) unmount(entries.removeAt(entries.lastIndex))
        val entry = Entry(route)
        entries.add(entry)
        mount(entry)
        val redirect = entry.model?.redirect
        if (redirect != null) go(redirect) else changed()
    }

    /** Puts a screen on top of the current one (e.g. settings from the pause menu). */
    fun push(route: Route) {
        entries.lastOrNull()?.model?.onCover()
        val entry = Entry(route)
        entries.add(entry)
        mount(entry)
        val redirect = entry.model?.redirect
        if (redirect != null) {
            entries.removeAt(entries.lastIndex)
            unmount(entry)
            go(redirect)
        } else {
            changed()
        }
    }

    /** Removes the top screen; the last screen stays. */
    fun pop() {
        if (entries.size <= 1) return
        unmount(entries.removeAt(entries.lastIndex))
        changed()
    }

    /** Calls [listener] after every change of the stack; returns the function that unsubscribes. */
    fun onScreen(listener: (ScreenChange) -> Unit): () -> Unit {
        screenListeners.add(listener)
        return { screenListeners.remove(listener) }
    }

    /** The rotate notice blocks (true) or releases the game; a ride pauses when blocked. */
    fun emitRotateBlocked(blocked: Boolean) {
        for (listener in rotateListeners.toList()) listener(blocked)
    }

    fun onRotateBlocked(listener: (Boolean) -> Unit): () -> Unit {
        rotateListeners.add(listener)
        return { rotateListeners.remove(listener) }
    }

    private fun rebuildForLanguage() {
        for (entry in entries.toList()) {
            val old = entry.model
            if (old != null && old.rerenderOnLang) {
                mount(entry)
                old.destroy()
            }
        }
        changed()
    }

    /** Leaves every screen and stops listening to the language. */
    fun dispose() {
        stopLangListener()
        while (entries.isNotEmpty()) unmount(entries.removeAt(entries.lastIndex))
    }
}
