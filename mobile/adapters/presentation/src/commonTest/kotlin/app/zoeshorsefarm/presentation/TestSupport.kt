package app.zoeshorsefarm.presentation

import app.zoeshorsefarm.application.RideSound
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.ManualClock
import app.zoeshorsefarm.audio.Cancellable
import app.zoeshorsefarm.audio.Scheduler
import app.zoeshorsefarm.i18n.I18n
import app.zoeshorsefarm.platform.InputMode
import app.zoeshorsefarm.platform.ManualAppLifecycle
import app.zoeshorsefarm.presentation.audio.SoundPort
import app.zoeshorsefarm.presentation.nav.AppNavigator
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.profile.BadgeToastQueue

/** A timer that only fires when the test advances time. */
class ManualScheduler : Scheduler {
    private class Task(
        val at: Long,
        val action: () -> Unit,
    ) : Cancellable {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    private val tasks = mutableListOf<Task>()
    private var now = 0L

    val pending: Int get() = tasks.count { !it.cancelled }

    override fun postDelayed(
        delayMillis: Long,
        task: () -> Unit,
    ): Cancellable = Task(now + delayMillis, task).also { tasks += it }

    /** Lets [ms] milliseconds pass and runs the tasks that fall due, in order. */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            tasks.removeAll { it.cancelled }
            val next = tasks.filter { it.at <= target }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            now = maxOf(now, next.at)
            next.action()
        }
        now = target
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
) {
    val store = FakeStore()
    val settings = SettingsService(store)
    val i18n = I18n().apply { setLang(app.zoeshorsefarm.application.Language.EN) }
    val clock = ManualClock()
    val scheduler = ManualScheduler()
    val sound = RecordingSound()
    val inputMode = if (touch) InputMode.mobile() else InputMode(app.zoeshorsefarm.platform.DeviceClass.KEYBOARD)
    val navigator = AppNavigator(i18n)
    val lifecycle = ManualAppLifecycle()
    val badgeToasts = BadgeToastQueue(i18n, scheduler, viewportHeight = { 800 })
    val ctx =
        AppContext(
            store = store,
            settings = settings,
            inputMode = inputMode,
            clock = clock,
            i18n = i18n,
            navigator = navigator,
            sound = sound,
            scheduler = scheduler,
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
