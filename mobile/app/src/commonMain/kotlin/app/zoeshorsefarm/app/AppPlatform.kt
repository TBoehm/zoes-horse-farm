package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.Clock
import app.zoeshorsefarm.application.Rng
import app.zoeshorsefarm.audio.AudioPlatform
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.DeviceInfoSource
import app.zoeshorsefarm.platform.SystemClock
import app.zoeshorsefarm.platform.SystemDeviceInfo
import app.zoeshorsefarm.presentation.LocalTimeZone
import app.zoeshorsefarm.presentation.UtcTimeZone
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.scene.texture.BlockTextRasterizer
import app.zoeshorsefarm.scene.texture.TextRasterizer
import app.zoeshorsefarm.storage.KeyValueBackend
import app.zoeshorsefarm.view3d.engine.MonotonicSeconds
import app.zoeshorsefarm.view3d.engine.SecondsClock
import kotlin.random.Random

// Everything a platform shell (Android, iOS, a test) provides to the composition root.

/**
 * The drawing surface of the shell in its platform form (Android `Surface`, iOS `CAMetalLayer`). The
 * app never looks inside: it hands it to [SurfaceBackend.attach], and the [RenderBackendFactory] of the
 * same platform knows the concrete type.
 */
interface PlatformSurface

/** Size of the surface in physical pixels and its density (pixels per dp / point). */
data class SurfaceSize(
    val widthPx: Int,
    val heightPx: Int,
    val density: Double,
) {
    /** Logical width in dp. */
    val widthDp: Double get() = widthPx / density

    /** Logical height in dp. */
    val heightDp: Double get() = heightPx / density
}

/**
 * A render backend that lives on a platform surface. Filament in the app, a fake in the tests. All
 * calls come from the render thread (the thread that calls `ZoesHorseFarmApp.onFrame`).
 */
interface SurfaceBackend {
    /** The scene model's backend the engine draws through. */
    val backend: RenderBackend

    /**
     * The surface exists (again): connect it. After a [detach] the backend reports "context restored"
     * to its listeners; the very first attach is just the start.
     */
    fun attach(
        surface: PlatformSurface,
        size: SurfaceSize,
    )

    /** The surface changed its size or density. */
    fun resize(size: SurfaceSize)

    /** The surface goes away (Android `surfaceDestroyed`): stop drawing, report "context lost". */
    fun detach()
}

/**
 * Creates the render backend once the shell has a surface. [antialias] is the answer of
 * `planStartup` (the memory budget decides); the Filament factory turns it into `msaaSamples`, the
 * engine itself sets the tone mapping (`configureRenderer`). Returns null when the device has no
 * graphics backend ("no 3D" notice).
 */
fun interface RenderBackendFactory {
    fun create(antialias: Boolean): SurfaceBackend?
}

/**
 * What a platform shell hands to `ZoesHorseFarmApp`. The defaults are the platform independent
 * ones; the shell passes what it really has.
 *
 * @param keyValueBackend where the save game lives (iOS: UserDefaults); null = nothing is saved, the
 *   game runs from memory and shows the "not saved" note
 * @param audio the PCM output factory and the timer of the audio service (`createPlatformAudio()`)
 * @param renderBackends creates the backend on the shell's surface (Filament on the devices)
 * @param deviceInfo memory, cores, screen and GPU name for the graphics budget (read once per engine)
 * @param clock wall clock: badge dates, crash guard
 * @param timeZone UTC offset of the player's zone for the badge dates
 * @param textRasterizer real fonts for the signs in the 3D world (the block font is the fallback)
 * @param secondsClock monotonic seconds since the app started: error log time, context loss rule
 * @param logSink every error the app catches goes here as one line too (Logcat, NSLog)
 * @param preferredLanguages language tags of the system in order, for the start language
 * @param appVersion version text of the build (`versionName` / `CFBundleShortVersionString`), null = dev
 * @param inputDevice phones and tablets are TOUCH; a tablet with a hardware keyboard may be HYBRID
 * @param initialAppState normally FOREGROUND
 * @param capTo30Fps battery lever: draw at most 30 frames per second (see `ZoesHorseFarmApp.setCapTo30Fps`)
 * @param debug the debug box of the ride (`ZoesHorseFarmApp.debugText`) is switched on
 * @param random the random source of the ride (web: `Math.random`)
 * @param shutdown called once by `ZoesHorseFarmApp.dispose` after everything else (e.g. the Filament
 *   material compiler's `shutdown()`)
 */
class AppPlatform(
    val keyValueBackend: KeyValueBackend?,
    val audio: AudioPlatform,
    val renderBackends: RenderBackendFactory,
    val deviceInfo: DeviceInfoSource = SystemDeviceInfo,
    val clock: Clock = SystemClock,
    val timeZone: LocalTimeZone = UtcTimeZone,
    val textRasterizer: TextRasterizer = BlockTextRasterizer,
    val secondsClock: SecondsClock = MonotonicSeconds,
    val logSink: (String) -> Unit = {},
    val preferredLanguages: List<String?> = emptyList(),
    val appVersion: String? = null,
    val inputDevice: DeviceClass = DeviceClass.TOUCH,
    val initialAppState: AppState = AppState.FOREGROUND,
    val capTo30Fps: Boolean = false,
    val debug: Boolean = false,
    val random: Rng = { Random.nextDouble() },
    val shutdown: () -> Unit = {},
)
