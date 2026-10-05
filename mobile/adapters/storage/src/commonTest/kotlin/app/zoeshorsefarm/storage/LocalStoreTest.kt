package app.zoeshorsefarm.storage

import app.zoeshorsefarm.application.CrashGuard
import app.zoeshorsefarm.application.CrashGuardSection
import app.zoeshorsefarm.application.Field
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.HEARTBEAT_INTERVAL_MS
import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.Language
import app.zoeshorsefarm.application.LevelDecision
import app.zoeshorsefarm.application.PreviousRun
import app.zoeshorsefarm.application.ProgressSection
import app.zoeshorsefarm.application.RenderInfo
import app.zoeshorsefarm.application.SAVE_SECTIONS
import app.zoeshorsefarm.application.SAVE_VERSION
import app.zoeshorsefarm.application.SaveEnv
import app.zoeshorsefarm.application.SettingsSection
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.application.testing.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val env = SaveEnv(defaultLang = Language.DE)

private fun memory(initial: Map<String, String> = emptyMap()) = MemoryKeyValueBackend(initial)

private fun store(
    backend: KeyValueBackend?,
    session: KeyValueBackend? = memory(),
    noticeMarker: NoticeMarker? = null,
) = LocalStore(backend, session, env, noticeMarker)

private fun LocalStore.setLang(lang: Language) = update(SettingsSection) { it.copy(lang = lang) }

private fun LocalStore.number(
    section: MapSection,
    key: String,
): Int = (get(section)[key] as Number).toInt()

class LoadingSaveDataTest {
    @Test
    fun `uses initial values for empty storage with the start language from env`() {
        assertEquals(Language.DE, store(memory()).get(SettingsSection).lang)
    }

    @Test
    fun `starts with broken JSON without crashing`() {
        val backend = memory(mapOf(SAVE_KEY to "{broken"))
        assertEquals(Language.DE, store(backend).get(SettingsSection).lang)
    }

    @Test
    fun `keeps readable values and resets invalid ones`() {
        val backend =
            memory(
                mapOf(SAVE_KEY to """{"version":1,"settings":{"lang":"en","extra":7},"horse":5}"""),
            )
        val store = store(backend)
        assertEquals(Language.EN, store.get(SettingsSection).lang)
        assertEquals(7L, store.get(SettingsSection).unknown["extra"])
        assertEquals(HorseSection.defaults(env), store.get(HorseSection))

        val bad = memory(mapOf(SAVE_KEY to """{"settings":{"lang":"fr"}}"""))
        assertEquals(Language.DE, store(bad).get(SettingsSection).lang)
    }

    @Test
    fun `accepts non-objects as save data`() {
        for (text in listOf("42", "\"x\"", "null", "[1,2]", "")) {
            val store = store(memory(mapOf(SAVE_KEY to text)))
            assertEquals(Language.DE, store.get(SettingsSection).lang, text)
        }
    }

    @Test
    fun `loads older saves with missing sections without loss`() {
        val backend = memory(mapOf(SAVE_KEY to """{"settings":{"lang":"en"}}"""))
        val testArea = numberSection("testArea", "level", 9.0, 1.0)
        val store = store(backend)
        assertEquals(1.0, store.get(testArea)["level"])
        assertEquals(Language.EN, store.get(SettingsSection).lang)
    }

    @Test
    fun `an unreadable backend counts as no save`() {
        val throwing =
            object : KeyValueBackend {
                override fun getString(key: String): String? = throw IllegalStateException("denied")

                override fun setString(
                    key: String,
                    value: String,
                ) = Unit

                override fun remove(key: String) = Unit
            }
        assertEquals(Language.DE, store(throwing).get(SettingsSection).lang)
    }
}

class SavingSaveDataTest {
    @Test
    fun `saves immediately on change`() {
        val backend = memory()
        store(backend).setLang(Language.EN)
        assertEquals("en", savedTree(backend)!!.at("settings", "lang"))
        assertEquals(Language.EN, store(backend).get(SettingsSection).lang)
    }

    @Test
    fun `returns the sanitized section and notifies listeners with it`() {
        val store = store(memory())
        val seen = mutableListOf<Double>()
        store.onChange(SettingsSection) { seen += it.musicVolume }
        val result = store.update(SettingsSection) { it.copy(musicVolume = 7.0) }
        assertEquals(SettingsSection.defaults(env).musicVolume, result.musicVolume)
        assertEquals(listOf(result.musicVolume), seen)
    }

    @Test
    fun `unsubscribing stops the notifications`() {
        val store = store(memory())
        var calls = 0
        val off = store.onChange(SettingsSection) { calls += 1 }
        store.setLang(Language.EN)
        off()
        store.setLang(Language.DE)
        assertEquals(1, calls)
    }

    @Test
    fun `preserves unknown sections and fields unchanged`() {
        val backend =
            memory(
                mapOf(
                    SAVE_KEY to
                        """{"stable":{"horses":[{"name":"Luna"}]},"version":3,""" +
                        """"settings":{"lang":"de","futureFlag":true}}""",
                ),
            )
        store(backend).setLang(Language.EN)
        val saved = savedTree(backend)!!
        assertEquals(mapOf("horses" to listOf(mapOf("name" to "Luna"))), saved["stable"])
        assertEquals(true, saved.at("settings", "futureFlag"))
        assertEquals(3L, saved["version"])
    }

    @Test
    fun `writes at least the current save version`() {
        val backend = memory(mapOf(SAVE_KEY to """{"version":0}"""))
        store(backend).setLang(Language.EN)
        assertEquals(SAVE_VERSION.toLong(), savedTree(backend)!!["version"])
        val none = memory()
        store(none).setLang(Language.EN)
        assertEquals(SAVE_VERSION.toLong(), savedTree(none)!!["version"])
    }

    @Test
    fun `a new section adds data without deleting existing data`() {
        val backend = memory(mapOf(SAVE_KEY to """{"settings":{"lang":"en"},"other":{"a":1}}"""))
        val breeding = numberSection("breeding", "foals", 99.0, 0.0)
        val store = store(backend)
        store.update(breeding) { it + ("foals" to 2) }
        val saved = savedTree(backend)!!
        assertEquals(2L, saved.at("breeding", "foals"))
        assertEquals("en", saved.at("settings", "lang"))
        assertEquals(mapOf("a" to 1L), saved["other"])
    }

    @Test
    fun `flush writes the initial values on first start`() {
        val backend = memory()
        val store = store(backend)
        assertNull(backend.getString(SAVE_KEY))
        assertTrue(store.flush())
        val saved = savedTree(backend)!!
        assertEquals(SAVE_SECTIONS.map { it.name }.toSet() + "version", saved.keys)
        assertEquals("de", saved.at("settings", "lang"))
    }

    @Test
    fun `every section survives a restart`() {
        val backend = memory()
        val first = store(backend)
        first.update(SettingsSection) { it.copy(lang = Language.EN, showFps = true, musicVolume = 0.25) }
        first.update(HorseSection) { it.copy(name = "Luna", nameAnswered = true) }
        first.update(CrashGuardSection) { it.copy(lastSeen = 99, blockedLevels = listOf(GraphicsLevel.HIGH)) }
        first.update(ProgressSection) { it }
        val second = store(backend)
        for (section in SAVE_SECTIONS) assertEquals(first.get(section), second.get(section), section.name)
        assertEquals("Luna", second.get(HorseSection).name)
    }
}

class SectionsExtendedLaterTest {
    @Test
    fun `returns new fields even without a prior update`() {
        val store = store(memory())
        val first = numberSection("lateArea", "a", 9.0, 1.0)
        assertEquals(mapOf("a" to 1.0), store.get(first))
        val extended =
            MapSection("lateArea", mapOf("a" to Field.number(0.0, 9.0, 1.0), "b" to Field.bool(true)))
        assertEquals(mapOf("a" to 1.0, "b" to true), store.get(extended))
    }

    @Test
    fun `an unknown section is kept in memory and in the save`() {
        val backend = memory(mapOf(SAVE_KEY to """{"future":{"x":1}}"""))
        val store = store(backend)
        store.setLang(Language.EN)
        assertEquals(mapOf("x" to 1L), savedTree(backend)!!["future"])
    }
}

class SeveralTabsTest {
    private val tabProgress = numberSection("tabProgress", "jumps", 999.0, 0.0)

    private fun open(backend: KeyValueBackend?) = store(backend)

    private fun guardStore(
        store: LocalStore,
        clock: ManualClock,
    ) = CrashGuard(
        store = store,
        settings = SettingsService(store),
        clock = clock,
        decide = { _, _ -> LevelDecision(GraphicsLevel.LOW, persist = false, hint = false) },
    )

    @Test
    fun `keeps what another tab saved and does not pull it into memory`() {
        val backend = memory()
        val a = open(backend)
        val b = open(backend)
        b.update(tabProgress) { it + ("jumps" to 42) }
        a.updateThrough(CrashGuardSection) { it.copy(lastSeen = 7) }
        val saved = savedTree(backend)!!
        assertEquals(42L, saved.at("tabProgress", "jumps"))
        assertEquals(7L, saved.at("crashGuard", "lastSeen"))
        assertEquals(0, a.number(tabProgress, "jumps"))
        assertEquals(7L, a.get(CrashGuardSection).lastSeen)
    }

    @Test
    fun `gives the change the stored section and returns the sanitized result`() {
        val backend = memory()
        val a = open(backend)
        val b = open(backend)
        b.updateThrough(CrashGuardSection) { it.copy(blockedLevels = listOf(GraphicsLevel.HIGH)) }
        val result = a.updateThrough(CrashGuardSection) { it.copy(lastSeen = -5) }
        assertEquals(listOf(GraphicsLevel.HIGH), result.blockedLevels)
        assertEquals(0L, result.lastSeen)
        assertEquals(listOf("high"), savedTree(backend)!!.at("crashGuard", "blockedLevels"))
    }

    @Test
    fun `keeps unsaved in-memory changes of other sections when a save failed before`() {
        val backend = SwitchableBackend()
        val a = open(backend)
        a.update(tabProgress) { it + ("jumps" to 5) }
        a.setLang(Language.EN)
        backend.failing = false
        a.updateThrough(CrashGuardSection) { it.copy(lastSeen = 9) }
        assertEquals(5, a.number(tabProgress, "jumps"))
        assertEquals(Language.EN, a.get(SettingsSection).lang)
        assertEquals(9L, a.get(CrashGuardSection).lastSeen)
        // the next regular save persists the unsaved parts too
        a.update(tabProgress) { it + ("jumps" to 6) }
        assertEquals("en", savedTree(backend)!!.at("settings", "lang"))
    }

    @Test
    fun `emits no change event for other sections even when another tab changed them`() {
        val backend = memory()
        val a = open(backend)
        val b = open(backend)
        b.setLang(Language.EN)
        b.update(tabProgress) { it + ("jumps" to 8) }
        val changed = mutableListOf<String>()
        a.onChange(SettingsSection) { changed += "settings" }
        a.onChange(tabProgress) { changed += "tabProgress" }
        a.onChange(CrashGuardSection) { changed += "crashGuard" }
        a.updateThrough(CrashGuardSection) { it.copy(lastSeen = 1) }
        assertEquals(listOf("crashGuard"), changed)
        assertEquals(Language.DE, a.get(SettingsSection).lang)
    }

    @Test
    fun `preserves unknown sections and falls back to memory when the storage has no save`() {
        val backend = memory(mapOf(SAVE_KEY to """{"future":{"x":1}}"""))
        val a = open(backend)
        a.updateThrough(CrashGuardSection) { it.copy(lastSeen = 2) }
        assertEquals(mapOf("x" to 1L), savedTree(backend)!!["future"])
        backend.setString(SAVE_KEY, "{broken")
        a.update(tabProgress) { it + ("jumps" to 3) }
        backend.remove(SAVE_KEY)
        a.updateThrough(CrashGuardSection) { it.copy(lastSeen = 4) }
        val saved = savedTree(backend)!!
        assertEquals(3L, saved.at("tabProgress", "jumps"))
        assertEquals(4L, saved.at("crashGuard", "lastSeen"))
    }

    @Test
    fun `keeps the new value in memory when the storage is missing or full per rule 46`() {
        var failed = 0
        val full = open(FailingBackend())
        full.onSaveFailed { failed += 1 }
        full.updateThrough(CrashGuardSection) { it.copy(lastSeen = 6) }
        assertEquals(6L, full.get(CrashGuardSection).lastSeen)
        assertFalse(full.canSave)
        assertEquals(1, failed)
        val none = open(null)
        assertEquals(3L, none.updateThrough(CrashGuardSection) { it.copy(lastSeen = 3) }.lastSeen)
        assertFalse(none.canSave)
    }

    @Test
    fun `a crash guard heartbeat in one tab keeps the progress another tab saved`() {
        val backend = memory()
        val a = open(backend)
        val b = open(backend)
        val clock = ManualClock(1_000_000)
        val guard = guardStore(a, clock)
        val lease = guard.markRendering(RenderInfo(GraphicsLevel.MEDIUM, auto = true))
        b.update(tabProgress) { it + ("jumps" to 42) }
        clock.advance(HEARTBEAT_INTERVAL_MS)
        lease.frame(RenderInfo(GraphicsLevel.MEDIUM, auto = true))
        val saved = savedTree(backend)!!
        assertEquals(42L, saved.at("tabProgress", "jumps"))
        assertEquals(true, saved.at("crashGuard", "rendering"))
        assertEquals(clock.nowMs(), saved.at("crashGuard", "lastSeen"))
        lease.release()
        // a second tab that starts now sees a clean save (no crash)
        val second = guardStore(open(backend), clock)
        assertEquals(PreviousRun.Clean, second.checkPreviousRun())
    }

    @Test
    fun `a heartbeat after a failed save keeps the progress and settings of this tab and emits no settings change`() {
        val backend = SwitchableBackend()
        val a = open(backend)
        val clock = ManualClock(1_000_000)
        val guard = guardStore(a, clock)
        val lease = guard.markRendering(RenderInfo(GraphicsLevel.MEDIUM, auto = true))
        a.update(tabProgress) { it + ("jumps" to 11) } // the save fails: memory only
        var settingsChanges = 0
        a.onChange(SettingsSection) { settingsChanges += 1 }
        backend.failing = false
        clock.advance(HEARTBEAT_INTERVAL_MS)
        lease.frame(RenderInfo(GraphicsLevel.MEDIUM, auto = true))
        assertEquals(11, a.number(tabProgress, "jumps"))
        assertEquals(0, settingsChanges)
    }

    @Test
    fun `an overwrite by a stale tab is not made permanent by a heartbeat`() {
        val backend = memory()
        val a = open(backend)
        val b = open(backend)
        b.setLang(Language.EN)
        var settingsChanges = 0
        a.onChange(SettingsSection) { settingsChanges += 1 }
        val clock = ManualClock(1_000_000)
        val lease = guardStore(a, clock).markRendering(RenderInfo(GraphicsLevel.MEDIUM, auto = true))
        clock.advance(HEARTBEAT_INTERVAL_MS)
        lease.frame(RenderInfo(GraphicsLevel.MEDIUM, auto = true))
        assertEquals("en", savedTree(backend)!!.at("settings", "lang"))
        assertEquals(Language.DE, a.get(SettingsSection).lang)
        assertEquals(0, settingsChanges)
    }
}

class SavingNotPossibleTest {
    @Test
    fun `stays usable and reports canSave false`() {
        val store = store(FailingBackend())
        assertFalse(store.canSave)
        store.setLang(Language.EN)
        assertEquals(Language.EN, store.get(SettingsSection).lang)
    }

    @Test
    fun `a failed save emits saveFailed and recovers when saving works again`() {
        val backend = SwitchableBackend()
        val store = store(backend)
        var failed = 0
        store.onSaveFailed { failed += 1 }
        store.setLang(Language.EN)
        assertEquals(1, failed)
        assertFalse(store.canSave)
        backend.failing = false
        assertTrue(store.flush())
        assertTrue(store.canSave)
        assertEquals("en", savedTree(backend)!!.at("settings", "lang"))
    }

    @Test
    fun `shows the notice once per session and not again after a reload`() {
        val session = memory()
        val a = store(FailingBackend(), session)
        assertTrue(a.shouldShowSaveNotice())
        assertFalse(a.shouldShowSaveNotice())
        val reloaded = store(FailingBackend(), session)
        assertFalse(reloaded.shouldShowSaveNotice())
        val newSession = store(FailingBackend(), memory())
        assertTrue(newSession.shouldShowSaveNotice())
    }

    @Test
    fun `shows no notice when saving works`() {
        assertFalse(store(memory(), memory()).shouldShowSaveNotice())
    }

    @Test
    fun `works without any storage and shows only once even after a reload`() {
        val marker = FlagMarker()
        val store = store(null, null, marker)
        assertFalse(store.canSave)
        assertTrue(store.shouldShowSaveNotice())
        assertFalse(store.shouldShowSaveNotice())
        val reloaded = store(null, null, marker)
        assertFalse(reloaded.shouldShowSaveNotice())
    }

    @Test
    fun `uses the fallback marker when the session storage throws`() {
        val marker = FlagMarker()
        val session = FailingBackend()
        val a = store(FailingBackend(), session, marker)
        assertTrue(a.shouldShowSaveNotice())
        val b = store(FailingBackend(), session, marker)
        assertFalse(b.shouldShowSaveNotice())
    }
}
