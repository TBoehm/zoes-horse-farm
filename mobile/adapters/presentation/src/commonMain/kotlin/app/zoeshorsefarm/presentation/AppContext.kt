package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.Clock
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.application.Store
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.platform.AppLifecycle
import app.zoeshorsefarm.platform.InputMode
import app.zoeshorsefarm.presentation.audio.SilentSound
import app.zoeshorsefarm.presentation.audio.SoundPort
import app.zoeshorsefarm.presentation.nav.AppNavigator
import app.zoeshorsefarm.presentation.profile.BadgeToastQueue

/**
 * Everything the screen models share (web: the `ctx` of `createApp`): the save game, the settings
 * service (the only way to change settings), the input mode, texts, navigation and sound.
 *
 * @param scheduler delayed tasks (music delay, toasts). The UI calls `scheduler.tick()` on every frame of
 *   its own clock, so the tasks run on the UI thread (the models are not thread safe). Never pass the
 *   scheduler of the audio module here.
 * @param timeZone the player's time zone, for the dates of the badges (default UTC)
 * @param lifecycle foreground/background of the app (the ride pauses and the sound mutes in the background)
 * @param badgeToasts the toasts of awarded badges, shown above every screen
 * @param version the build's version text (see `appVersionOf` in `:adapters:platform`)
 */
class AppContext(
    val store: Store,
    val settings: SettingsService,
    val inputMode: InputMode,
    val clock: Clock,
    val i18n: I18n,
    val navigator: AppNavigator,
    val scheduler: UiScheduler,
    val lifecycle: AppLifecycle,
    val badgeToasts: BadgeToastQueue,
    val version: String,
    val sound: SoundPort = SilentSound,
    val timeZone: LocalTimeZone = UtcTimeZone,
) {
    /** Shortcut for `i18n.t(key, params)`. */
    fun t(
        key: String,
        params: Map<String, Any?>? = null,
    ): String = i18n.t(key, params)
}
