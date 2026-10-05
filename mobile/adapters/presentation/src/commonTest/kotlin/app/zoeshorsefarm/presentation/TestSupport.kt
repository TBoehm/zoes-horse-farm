package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.RideSound
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.ManualClock
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.InputMode
import app.zoeshorsefarm.platform.ManualAppLifecycle
import app.zoeshorsefarm.presentation.audio.SoundPort
import app.zoeshorsefarm.presentation.nav.AppNavigator
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.profile.BadgeToastQueue

/** The UI scheduler on a manual clock: tasks fire only when the test advances time. */
class ManualScheduler(
    val clock: ManualClock = ManualClock(),
) {
    val ui = UiScheduler(clock)

    val pending: Int get() = ui.pending

    /** Lets [ms] milliseconds pass and runs the tasks that fall due, in order. */
    fun advance(ms: Long) {
        clock.advance(ms)
        ui.tick()
    }
}

/** Records what the screens ask the sound output to do. */
class RecordingSound : SoundPort {
    val calls = mutableListOf<String>()

    override fun setVolumes(
        musicVolume: Double?,
        musicMuted: Boolean?,
        sfxVolume: Double?,
        sfxMuted: Boolean?,
    ) {
        calls += "volumes $musicVolume $musicMuted $sfxVolume $sfxMuted"
    }

    override fun setMusicWanted(wanted: Boolean) {
        calls += "music $wanted"
    }

    override fun setHidden(hidden: Boolean) {
        calls += "hidden $hidden"
    }

    override fun setPaused(paused: Boolean) {
        calls += "paused $paused"
    }

    override fun play(sound: RideSound) {
        calls += "play ${sound.id}"
    }

    override fun hoof(gait: String) {
        calls += "hoof $gait"
    }

    override fun startSignal() {
        calls += "startSignal"
    }
}

/** Everything a screen model needs, with fakes for the ports. */
class TestApp(
    val touch: Boolean = true,
    device: DeviceClass? = null,
) {
    val store = FakeStore()
    val settings = SettingsService(store)
    val i18n = I18n().apply { setLang(app.zoeshorsefarm.application.Language.EN) }
    val scheduler = ManualScheduler()
    val clock = scheduler.clock
    val sound = RecordingSound()
    val inputMode =
        if (device !=
            null
        ) {
            InputMode(device)
        } else if (touch) {
            InputMode.mobile()
        } else {
            InputMode(DeviceClass.KEYBOARD)
        }
    val navigator = AppNavigator(i18n)
    val lifecycle = ManualAppLifecycle()
    val badgeToasts = BadgeToastQueue(i18n, scheduler.ui, viewportHeight = { 800 })
    val ctx =
        AppContext(
            store = store,
            settings = settings,
            inputMode = inputMode,
            clock = clock,
            i18n = i18n,
            navigator = navigator,
            sound = sound,
            scheduler = scheduler.ui,
            lifecycle = lifecycle,
            badgeToasts = badgeToasts,
            version = "1.2.3",
        )

    /** Registers a do-nothing screen for every name in [names] so that navigation can be tested. */
    fun registerStubs(vararg names: String) {
        for (name in names) navigator.register(name) { Stub }
    }

    object Stub : ScreenModel
}
