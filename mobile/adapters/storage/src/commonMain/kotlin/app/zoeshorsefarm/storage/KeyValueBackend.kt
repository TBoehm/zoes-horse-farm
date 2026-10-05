package app.zoeshorsefarm.storage

/**
 * Where text is kept between app starts (the port behind [LocalStore]; web: `localStorage`). The
 * methods may throw (storage full, denied, unreadable): the store treats that as "cannot save" or
 * "no save", never as a crash.
 */
interface KeyValueBackend {
    /** The text stored under [key], or null when there is none. */
    fun getString(key: String): String?

    fun setString(
        key: String,
        value: String,
    )

    fun remove(key: String)
}

/** Keeps the entries in memory only: for tests, and as the session backend. */
class MemoryKeyValueBackend(
    initial: Map<String, String> = emptyMap(),
) : KeyValueBackend {
    private val entries = LinkedHashMap(initial)

    override fun getString(key: String): String? = entries[key]

    override fun setString(
        key: String,
        value: String,
    ) {
        entries[key] = value
    }

    override fun remove(key: String) {
        entries.remove(key)
    }
}

/**
 * The session backend of the app: lives exactly as long as the app process (web: `sessionStorage`
 * of a tab), so "once per session" things such as the save notice show again after a restart.
 */
object ProcessSession {
    val backend: KeyValueBackend = MemoryKeyValueBackend()
}

/**
 * A second place for the "notice shown" mark when no session backend works (web: `history.state`).
 * It must outlive a restart of the app's UI but not the process; the default is none.
 */
interface NoticeMarker {
    fun isSet(): Boolean

    fun set()
}
