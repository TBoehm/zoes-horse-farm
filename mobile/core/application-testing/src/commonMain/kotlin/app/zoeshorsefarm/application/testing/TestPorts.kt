package app.zoeshorsefarm.application.testing

import app.zoeshorsefarm.application.Clock
import app.zoeshorsefarm.application.CrashGuardSection
import app.zoeshorsefarm.application.CrashGuardState
import app.zoeshorsefarm.application.HorseProfile
import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.application.Rng
import app.zoeshorsefarm.application.Section
import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.application.SettingsSection
import app.zoeshorsefarm.application.Store
import app.zoeshorsefarm.domain.progress.Progress
import app.zoeshorsefarm.domain.sim.createRng

// Test doubles for the application ports: store, clock and rng (tests/support/test-ports.js).

/**
 * In-memory [Store]. Like the real store it sanitizes every change; unlike it, nothing is saved.
 * Open so a test can count calls (override [get], [updateThrough], [onChange]).
 */
open class FakeStore(
    settings: Settings = Settings(),
    horse: HorseProfile = HorseProfile(),
    progress: Progress = Progress(),
    crashGuard: CrashGuardState = CrashGuardState(),
) : Store {
    private val data = HashMap<String, Any>()
    private val listeners = HashMap<String, MutableList<(Any) -> Unit>>()

    init {
        set(SettingsSection, settings)
        set(HorseSection, horse)
        set(ProgressSection, progress)
        set(CrashGuardSection, crashGuard)
    }

    val settings: Settings get() = get(SettingsSection)
    val horse: HorseProfile get() = get(HorseSection)
    val progress: Progress get() = get(ProgressSection)
    val crashGuard: CrashGuardState get() = get(CrashGuardSection)

    /**
     * Replaces a section without sanitizing and without telling the listeners (a test sets up
     * state, or simulates another tab).
     */
    fun <T : Any> set(
        section: Section<T>,
        value: T,
    ) {
        data[section.name] = value
    }

    @Suppress("UNCHECKED_CAST") // the value under a section name was stored by a call with that section
    override fun <T : Any> get(section: Section<T>): T = data.getOrPut(section.name) { section.defaults() } as T

    private fun <T : Any> write(
        section: Section<T>,
        change: (T) -> T,
    ): T {
        val next = section.clean(change(get(section)))
        data[section.name] = next
        for (listener in listeners[section.name].orEmpty().toList()) listener(next)
        return next
    }

    override fun <T : Any> update(
        section: Section<T>,
        change: (T) -> T,
    ): T = write(section, change)

    /** Same as [update]; a test simulates another tab by calling [set] before the call. */
    override fun <T : Any> updateThrough(
        section: Section<T>,
        change: (T) -> T,
    ): T = write(section, change)

    override fun <T : Any> onChange(
        section: Section<T>,
        listener: (T) -> Unit,
    ): () -> Unit {
        @Suppress("UNCHECKED_CAST") // only values of this section are delivered under its name
        val wrapper: (Any) -> Unit = { listener(it as T) }
        listeners.getOrPut(section.name) { ArrayList() }.add(wrapper)
        return { listeners[section.name]?.remove(wrapper) }
    }
}

/** Time of the badge dates in the tests. */
const val FIXED_ISO = "2026-01-02T03:04:05.000Z"

/** A clock that always tells the same ISO time ([nowMs] stays at [ms]). */
class FixedClock(
    private val iso: String = FIXED_ISO,
    private val ms: Long = 0,
) : Clock {
    override fun nowMs(): Long = ms

    override fun nowIso(): String = iso
}

/** A clock that only moves when the test says so; [nowIso] follows [nowMs]. */
class ManualClock(
    var ms: Long = 0,
) : Clock {
    override fun nowMs(): Long = ms

    fun advance(deltaMs: Long) {
        ms += deltaMs
    }
}

/** The domain's seeded random source (mulberry32). */
fun seededRng(seed: Int = 1): Rng = createRng(seed)
