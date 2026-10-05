package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.render.ContextListener
import app.zoeshorsefarm.scene.render.RenderBackend
import kotlin.time.TimeSource

// Helpers that keep the 3D view playable when things go wrong (rule 4): loss of the graphics
// device, shader compilation stalls after a quality switch and exceptions in the frame loop. Pure
// (the backend and the clock come in as parameters). `collectGpuObjects` of the web file is in the
// scene module.

/** Where errors that were caught go: a message and the error (web app: `console.error`). */
typealias ErrorLog = (message: String, error: Throwable) -> Unit

internal val printingLog: ErrorLog = { message, error -> println("$message\n${error.stackTraceToString()}") }

// How long the ride screen waits for the device to come back before it asks to restart the view
// (some systems stop restoring after repeated losses). A technical value, not a game value.
const val CONTEXT_RESTORE_TIMEOUT_MS = 8000L

/**
 * Watches the render backend for a lost and restored graphics device. Callbacks may throw: the
 * error goes to [log] and the state stays right. Create it with [watchContextLoss].
 */
class ContextLossWatch internal constructor(
    private val backend: RenderBackend,
    private val onLost: (() -> Unit)?,
    private val onRestored: (() -> Unit)?,
    private val log: ErrorLog,
) : ContextListener {
    /** True between the loss and the restore of the device. */
    var lost: Boolean = false
        private set

    override fun onContextLost() {
        lost = true
        safely { onLost?.invoke() }
    }

    override fun onContextRestored() {
        lost = false
        safely { onRestored?.invoke() }
    }

    /** A throwing callback must not break the other listeners of the backend. */
    @Suppress("TooGenericExceptionCaught") // any failure of a callback is only logged
    private fun safely(callback: () -> Unit) {
        try {
            callback()
        } catch (error: Throwable) {
            log("Context loss handler failed", error)
        }
    }

    /** Stops watching. */
    fun stop() = backend.removeContextListener(this)

    internal fun start(): ContextLossWatch {
        backend.addContextListener(this)
        return this
    }
}

/** Starts watching [backend] for loss and restore of the graphics device. */
fun watchContextLoss(
    backend: RenderBackend,
    onLost: (() -> Unit)? = null,
    onRestored: (() -> Unit)? = null,
    log: ErrorLog = printingLog,
): ContextLossWatch = ContextLossWatch(backend, onLost, onRestored, log).start()

/** Frees a GPU object right away (the normal case, as long as no context was ever lost). */
fun releaseNow(obj: GpuObject?) {
    obj?.dispose()
}

/**
 * Tells which GPU objects must not be released with GPU calls any more (rule 4). In the web
 * app three.js registers `dispose` listeners in bookkeeping instances that exist when an object
 * is uploaded; a restored context gets new instances, but the old listeners stay on the objects,
 * so disposing an object that was uploaded before the loss would delete handles of the lost
 * context. The GPU memory went away with the context, so such an object is only forgotten.
 * [contextLost] is called with everything that lived on the GPU at that moment (`collectGpuObjects`),
 * [release] replaces `obj.dispose()` wherever the view frees GPU objects while it runs. Without a
 * loss nothing changes: every object is disposed. While the context is lost no GPU call is made at all.
 * Known limit: an object that survives a loss and is uploaded again stays on the GPU when it is
 * released later (one generation of rebuilt objects per loss), which is the price of never
 * logging a GPU error. The epoch holds on to the objects it marked as long as it lives (one per world).
 */
class GpuEpoch {
    private val stale = HashSet<GpuObject>()

    /** True between a loss and the restore. */
    var lost: Boolean = false
        private set

    fun contextLost(objects: Iterable<GpuObject?> = emptyList()) {
        lost = true
        for (obj in objects) if (obj != null) stale.add(obj)
    }

    fun contextRestored() {
        lost = false
    }

    fun isStale(obj: GpuObject): Boolean = obj in stale

    /** Disposes the object unless its GPU resources belong to a lost context. */
    fun release(obj: GpuObject?) {
        if (obj == null || lost || obj in stale) return
        obj.dispose()
    }
}

/**
 * Holds back the frame loop while shaders compile (after a quality switch or a restored
 * context), so that the app stays responsive instead of stalling inside the first draw call. The
 * gate always opens again: when the work reports that it is done (also on failure) or after
 * `maxMs`.
 */
class RenderGate(
    private val scheduler: Scheduler,
) {
    private var token = 0
    private var timer: ScheduledTask? = null

    /** True while a hold is running. */
    var blocked: Boolean = false
        private set

    private fun open(mine: Int) {
        // a newer hold has taken over
        if (mine != token) return
        blocked = false
        timer?.cancel()
        timer = null
    }

    /**
     * Blocks until [work] calls the `done` function it is given, but at most [maxMs]. [work] starts
     * the compilation; without work (null) nothing is held. A hold replaces an older one. If [work]
     * throws, the gate opens and the exception goes on.
     */
    @Suppress("TooGenericExceptionCaught") // the gate opens on every failure and rethrows
    fun hold(
        maxMs: Long,
        work: ((done: () -> Unit) -> Unit)?,
    ) {
        token += 1
        val mine = token
        timer?.cancel()
        timer = null
        if (work == null) {
            blocked = false
            return
        }
        blocked = true
        timer = scheduler.schedule(maxMs) { open(mine) }
        try {
            work { open(mine) }
        } catch (error: Throwable) {
            open(mine)
            throw error
        }
    }
}

/**
 * Countdown for a context that does not come back: [start] when it is lost, [cancel] when it is
 * restored; [onTimeout] fires once if the time runs out first. A second start restarts it.
 */
class RestoreWatchdog(
    private val scheduler: Scheduler,
    private val timeoutMs: Long = CONTEXT_RESTORE_TIMEOUT_MS,
    private val onTimeout: (() -> Unit)? = null,
) {
    private var timer: ScheduledTask? = null

    fun start() {
        cancel()
        timer =
            scheduler.schedule(timeoutMs) {
                timer = null
                onTimeout?.invoke()
            }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
    }
}

// the same place is logged once per this many milliseconds
private const val ERROR_LOG_INTERVAL_MS = 5000L

private val clockOrigin = TimeSource.Monotonic.markNow()

private fun monotonicNowMs(): Long = clockOrigin.elapsedNow().inWholeMilliseconds

/**
 * Logs errors from a frame loop without flooding the console: the same place is logged once per
 * [intervalMs] (an error thrown every frame would otherwise print 60 lines per second).
 */
class ErrorReporter(
    private val log: ErrorLog = printingLog,
    private val intervalMs: Long = ERROR_LOG_INTERVAL_MS,
    private val now: () -> Long = ::monotonicNowMs,
) {
    private val lastLogged = HashMap<String, Long>()

    operator fun invoke(
        where: String,
        error: Throwable,
    ) {
        val t = now()
        val last = lastLogged[where]
        if (last != null && t - last < intervalMs) return
        lastLogged[where] = t
        log("3D view: $where failed, keeping the loop running", error)
    }
}
