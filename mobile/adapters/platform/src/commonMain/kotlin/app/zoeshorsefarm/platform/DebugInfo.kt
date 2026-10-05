package app.zoeshorsefarm.platform

// Diagnostics for tests on real devices: the debug box in the ride (UI) lists the last errors and
// the device. This file keeps the pure parts: the error texts, the ring of the last errors and the
// device line. The web's `?debug` URL flag and the capture of console and window errors have no
// native counterpart: the app shell decides when the box is on and feeds the log (see [ErrorLog.record]).

// Number of errors the box lists, and the longest message kept (the box is small)
private const val MAX_ERRORS = 5
private const val MAX_MESSAGE_LENGTH = 160
private const val BYTES_PER_MB = 1024L * 1024L

/** Text for anything that can be thrown, logged or passed to an error handler. */
@Suppress("TooGenericExceptionCaught") // a broken toString() of any value must not break the diagnostics
fun describeError(value: Any?): String =
    try {
        if (value is Throwable) {
            val name = value::class.simpleName ?: "Throwable"
            value.message?.let { "$name: $it" } ?: name
        } else {
            value.toString()
        }
    } catch (_: Exception) {
        "unprintable error"
    }

/** One line of the debug box: seconds since the app started and the (shortened) message. */
data class ErrorEntry(
    val atS: Double,
    val message: String,
)

/**
 * Ring of the last errors: [entries] is oldest first; [count] includes the dropped ones. [now]
 * returns seconds since the app started.
 */
class ErrorLog(
    private val max: Int = MAX_ERRORS,
    private val maxLength: Int = MAX_MESSAGE_LENGTH,
    private val now: () -> Double,
) {
    private val items = ArrayDeque<ErrorEntry>()

    var count: Int = 0
        private set

    val entries: List<ErrorEntry> get() = items

    fun add(message: String) {
        count += 1
        val text = if (message.length > maxLength) message.take(maxLength - 1) + "…" else message
        items.addLast(ErrorEntry(now(), text))
        if (items.size > max) items.removeFirst()
    }

    /** Adds the description of anything that was thrown or logged as an error. */
    fun record(error: Any?) = add(describeError(error))
}

/** Memory as whole megabytes ("unknown" for null). */
fun formatMemory(bytes: Long?): String = bytes?.let { "${it / BYTES_PER_MB} MB" } ?: "unknown"

/** The device on one line for the debug box. */
fun describeDevice(info: DeviceInfo): String {
    val memory = info.totalMemoryBytes?.let { formatMemory(it) } ?: "memory unknown"
    val screen =
        if (info.screenWidthPx != null && info.screenHeightPx != null) {
            "${info.screenWidthPx}x${info.screenHeightPx} px"
        } else {
            "screen unknown"
        }
    return listOf(
        memory,
        "${info.cores} cores",
        if (info.touch) "touch" else "no touch",
        screen,
        info.gpuName ?: "GPU unknown",
    ).joinToString(", ")
}
