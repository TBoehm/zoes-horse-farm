package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.CrashGuard
import app.zoeshorsefarm.scene.texture.BlockTextRasterizer
import app.zoeshorsefarm.scene.texture.TextRasterizer
import app.zoeshorsefarm.view3d.ErrorLog
import app.zoeshorsefarm.view3d.Scheduler
import app.zoeshorsefarm.view3d.quality.DeviceInfo
import app.zoeshorsefarm.view3d.quality.StartupCrash
import kotlin.time.TimeSource

// What the engine needs from its host (web: `app`, `inputMode`, `app.services.*` and the globals the
// engine read: `window`, `navigator`, `performance`).

/** Size of the drawing surface: logical size (dp) and pixels per dp of the screen. */
data class ViewSize(
    val cssWidth: Double,
    val cssHeight: Double,
    val devicePixelRatio: Double = 1.0,
)

/** The game's own clock in seconds (web: `performance.now() / 1000`); only read for events, not per frame. */
fun interface SecondsClock {
    fun nowSeconds(): Double
}

private val clockOrigin = TimeSource.Monotonic.markNow()

/** Seconds since the program started, from the monotonic clock. */
val MonotonicSeconds: SecondsClock = SecondsClock { clockOrigin.elapsedNow().inWholeMilliseconds / MS_PER_SECOND }

private const val MS_PER_SECOND = 1000.0

/**
 * The frame of the running screen (web: the function given to `engine.run`). [dt] is the game time
 * step (never above the simulation's maximum), [rawDt] the real time since the last frame.
 */
fun interface FrameHandler {
    fun frame(
        dt: Double,
        rawDt: Double,
    )
}

/**
 * Told when the engine starts or stops needing frames (see [Engine.wantsFrames]): the host starts
 * its display link when it becomes true and may stop it when it becomes false.
 */
fun interface DemandListener {
    fun onDemandChanged(wantsFrames: Boolean)
}

/**
 * Test feed for the frame times of the graphics automatic (web: `__zhfTest.setFrameFeed`): each
 * measured frame is replaced by [repeat] frames of [dt] seconds; [fedSeconds] counts the fed time.
 */
class FrameFeed(
    var repeat: Int,
    var dt: Double,
) {
    var fedSeconds: Double = 0.0
}

/**
 * Settings of an [Engine].
 *
 * @property view the size of the drawing surface now (later: [Engine.setViewSize])
 * @property device facts for the GPU memory budget (the quality module's [DeviceInfo])
 * @property gpuBudgetOverrideMB replaces the estimated budget (tests)
 * @property contextAntialias what the backend really has, null = what [planStartup] chooses
 * @property startupCrash the crash guard's finding at the start (the debug box's "last change")
 * @property crashGuard the crash guard (blocked levels), null in a bare test setup
 * @property capTo30Fps battery lever: draw at most 30 frames per second (default off)
 * @property scheduler timer for the shader-compile hold; null = driven by the frame loop
 * @property clock seconds since the app started, for the debug box and the context-loss rule
 * @property log where errors of the frame loop go (rate limited), null = print
 */
class EngineConfig(
    val view: ViewSize,
    val device: DeviceInfo = DeviceInfo(),
    val gpuBudgetOverrideMB: Int? = null,
    val contextAntialias: Boolean? = null,
    val startupCrash: StartupCrash? = null,
    val crashGuard: CrashGuard? = null,
    val capTo30Fps: Boolean = false,
    val textRasterizer: TextRasterizer = BlockTextRasterizer,
    val scheduler: Scheduler? = null,
    val clock: SecondsClock = MonotonicSeconds,
    val log: ErrorLog? = null,
)
