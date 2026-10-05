package app.zoeshorsefarm.application

// Ports of the application layer (spec "Ports"): the adapters implement them, the application code
// and the tests get them as parameters.

/** Time source: the badge date and time spans of the crash guard. Game time comes in as `dt`. */
interface Clock {
    /** Milliseconds since 1970-01-01T00:00:00Z. */
    fun nowMs(): Long

    /** The current time as ISO 8601 text with milliseconds and "Z" (the form of `Date.toISOString()`). */
    fun nowIso(): String = isoFromEpochMs(nowMs())
}

/** Random source in [0, 1); the domain never draws random numbers itself. */
typealias Rng = () -> Double

/**
 * The save game as typed sections (sanitized on every write). Values are immutable, so [get]
 * needs no copy. [update] returns the SANITIZED new section, not what `change` returned.
 */
interface Store {
    /** The saved section. */
    fun <T : Any> get(section: Section<T>): T

    /** Changes the section, saves the sanitized result at once and notifies the listeners. */
    fun <T : Any> update(
        section: Section<T>,
        change: (T) -> T,
    ): T

    /**
     * Changes ONE section against what is stored right now and writes only that section through
     * (other and unknown sections stay as stored; the in-memory state of the other sections is not
     * touched). For writers without a user action, such as the crash guard's heartbeat, which must
     * not overwrite what another tab saved meanwhile.
     */
    fun <T : Any> updateThrough(
        section: Section<T>,
        change: (T) -> T,
    ): T

    /** Calls `listener` with the new section after every change; returns the function that unsubscribes. */
    fun <T : Any> onChange(
        section: Section<T>,
        listener: (T) -> Unit,
    ): () -> Unit
}
