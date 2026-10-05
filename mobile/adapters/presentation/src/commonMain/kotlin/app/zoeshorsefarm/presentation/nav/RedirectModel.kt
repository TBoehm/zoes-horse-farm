package app.zoeshorsefarm.presentation.nav

/** A screen that is never shown: the navigator goes to [redirect] instead (a guard, e.g. a locked course). */
class RedirectModel(
    override val redirect: Route,
) : ScreenModel
