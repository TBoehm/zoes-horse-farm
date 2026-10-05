package app.zoeshorsefarm.storage

import app.zoeshorsefarm.application.SAVE_SECTIONS
import app.zoeshorsefarm.application.SAVE_VERSION
import app.zoeshorsefarm.application.SaveEnv
import app.zoeshorsefarm.application.Section
import app.zoeshorsefarm.application.Store

// The save game on the device (rules 44-47): save immediately, load robustly, preserve unknown data.
// Port of `local-store.js`. Not thread-safe: use it from the one thread that runs the game logic.

/** Key of the save text in the backend. */
const val SAVE_KEY = "zoes-horse-farm.save"
private const val SESSION_NOTICE_KEY = "zoes-horse-farm.saveNoticeShown"
private const val PROBE_KEY = "$SAVE_KEY.probe"

/** Runs [block]; null when the backend (or anything else it touches) throws: storage must never crash the game. */
@Suppress("TooGenericExceptionCaught") // a backend may throw anything (IOException, platform errors)
private inline fun <T : Any> attempt(block: () -> T?): T? =
    try {
        block()
    } catch (_: Exception) {
        null
    }

/**
 * The [Store] on a [KeyValueBackend].
 *
 * @param backend where the save lives (null: no storage at all, the game runs from memory)
 * @param sessionBackend keeps the "notice shown" mark for the lifetime of the app process
 * @param env environment values for initial values (the start language)
 * @param noticeMarker second place for that mark when the session backend is missing or broken
 */
class LocalStore(
    private val backend: KeyValueBackend?,
    private val sessionBackend: KeyValueBackend? = ProcessSession.backend,
    private val env: SaveEnv = SaveEnv(),
    private val noticeMarker: NoticeMarker? = null,
) : Store {
    private class Slot(
        val section: Section<*>,
        var value: Any,
    )

    private val raw: MutableMap<String, Any?> = readStored() ?: LinkedHashMap()
    private val slots = HashMap<String, Slot>()
    private val saveFailedListeners = LinkedHashSet<() -> Unit>()
    private val changeListeners = HashMap<String, LinkedHashSet<(Any) -> Unit>>()
    private var noticeShownInMemory = false

    /** Whether the last write worked (and the first probe write): false means progress is not kept. */
    var canSave: Boolean = probe()
        private set

    init {
        for (section in SAVE_SECTIONS) slotOf(section)
    }

    private fun readStored(): MutableMap<String, Any?>? {
        val text = attempt { backend?.getString(SAVE_KEY) }
        if (text.isNullOrEmpty()) return null
        return decodeJsonTree(text)?.let { LinkedHashMap(it) }
    }

    private fun probe(): Boolean =
        backend != null &&
            attempt {
                backend.setString(PROBE_KEY, "1")
                backend.remove(PROBE_KEY)
                true
            } == true

    /** Writes [content] (a whole save tree) as the stored save; false when that fails. */
    private fun persist(content: Map<String, Any?>): Boolean {
        if (backend == null) {
            canSave = false
            return false
        }
        // a stored version with a fraction is cut off to a whole number (the save version is an integer)
        val stored = (content["version"] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toLong() ?: 0L
        val saved = content + ("version" to maxOf(stored, SAVE_VERSION.toLong()))
        val written =
            attempt {
                backend.setString(SAVE_KEY, encodeJsonTree(saved))
                true
            } == true
        canSave = written
        if (!written) for (listener in saveFailedListeners.toList()) listener()
        return written
    }

    private fun <T : Any> slotOf(section: Section<T>): Slot {
        val slot = slots[section.name]
        // Sections registered or extended later (e.g. new settings fields) are sanitized anew
        if (slot != null && slot.section === section) return slot
        val value = section.sanitize(raw[section.name], env)
        raw[section.name] = section.toTree(value)
        return Slot(section, value).also { slots[section.name] = it }
    }

    @Suppress("UNCHECKED_CAST") // a slot always holds the value type of the section it was made for
    private fun <T : Any> valueOf(section: Section<T>): T = slotOf(section).value as T

    override fun <T : Any> get(section: Section<T>): T = valueOf(section)

    override fun <T : Any> update(
        section: Section<T>,
        change: (T) -> T,
    ): T {
        val next = section.clean(change(valueOf(section)), env)
        keep(section, next)
        persist(raw)
        notifyChange(section.name, next)
        return next
    }

    /**
     * Changes ONE section against what is stored right now and writes only that section through
     * (see [Store.updateThrough]). `change` gets the stored section (the memory value when the
     * storage has none or cannot be read); in the stored save only this section is replaced, other
     * and unknown sections stay as stored. A failed write keeps the new value in memory only.
     */
    override fun <T : Any> updateThrough(
        section: Section<T>,
        change: (T) -> T,
    ): T {
        val inMemory = valueOf(section)
        val stored = readStored()
        val current =
            if (stored != null &&
                section.name in stored
            ) {
                section.sanitize(stored[section.name], env)
            } else {
                inMemory
            }
        val next = section.clean(change(current), env)
        keep(section, next)
        persist((stored ?: raw) + (section.name to section.toTree(next)))
        notifyChange(section.name, next)
        return next
    }

    private fun <T : Any> keep(
        section: Section<T>,
        value: T,
    ) {
        slotOf(section).value = value
        raw[section.name] = section.toTree(value)
    }

    /** Writes the current state (e.g. so initial values are fixed on first start). */
    fun flush(): Boolean = persist(raw)

    @Suppress("UNCHECKED_CAST") // listeners are registered per section name with that section's type
    private fun notifyChange(
        name: String,
        value: Any,
    ) {
        for (listener in changeListeners[name].orEmpty().toList()) listener(value)
    }

    @Suppress("UNCHECKED_CAST") // the listener of a section only ever gets that section's values
    override fun <T : Any> onChange(
        section: Section<T>,
        listener: (T) -> Unit,
    ): () -> Unit {
        val untyped: (Any) -> Unit = { listener(it as T) }
        changeListeners.getOrPut(section.name) { LinkedHashSet() }.add(untyped)
        return { changeListeners[section.name]?.remove(untyped) }
    }

    /** Calls [listener] whenever a write fails; returns the function that unsubscribes. */
    fun onSaveFailed(listener: () -> Unit): () -> Unit {
        saveFailedListeners.add(listener)
        return { saveFailedListeners.remove(listener) }
    }

    /** true at most once per session, and only when saving is not possible (rule 46). */
    fun shouldShowSaveNotice(): Boolean = !canSave && (firstNoticeInSession() ?: firstNoticeByMarker())

    /** Marks the notice as shown in the session backend: true if it was not, false if it was, null if unusable. */
    private fun firstNoticeInSession(): Boolean? {
        val session = sessionBackend ?: return null
        return attempt {
            if (session.getString(SESSION_NOTICE_KEY) != null) {
                false
            } else {
                session.setString(SESSION_NOTICE_KEY, "1")
                true
            }
        }
    }

    /** No usable session backend: a mark that survives a restart of the UI (JS: history.state). */
    private fun firstNoticeByMarker(): Boolean {
        if (noticeShownInMemory || noticeMarker?.isSet() == true) return false
        noticeShownInMemory = true
        noticeMarker?.set()
        return true
    }
}
