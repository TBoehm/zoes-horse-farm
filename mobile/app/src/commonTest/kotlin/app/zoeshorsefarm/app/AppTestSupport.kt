package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.testing.ManualClock
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.audio.AudioPlatform
import app.zoeshorsefarm.audio.Cancellable
import app.zoeshorsefarm.audio.OutputState
import app.zoeshorsefarm.audio.PcmOutput
import app.zoeshorsefarm.audio.PcmRenderer
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.DeviceInfo
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.render.ContextListener
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.scene.render.RenderCapabilities
import app.zoeshorsefarm.scene.render.RenderInfo
import app.zoeshorsefarm.scene.render.ShadowType
import app.zoeshorsefarm.scene.render.ToneMapping
import app.zoeshorsefarm.storage.MemoryKeyValueBackend
import kotlin.test.assertIs

// Doubles and a rig for the tests of the composition root: a fake platform around the real app.

internal class TestSurface : PlatformSurface

internal const val FRAME_S = 1.0 / 60
internal const val SURFACE_WIDTH = 1600
internal const val SURFACE_HEIGHT = 800
internal const val SURFACE_DENSITY = 2.0

/** A backend that draws nothing and allocates nothing (the allocation test measures the app itself). */
internal class QuietBackend : RenderBackend {
    override val info = RenderInfo()
    override val capabilities = RenderCapabilities()
    override val gpuDescription = "quiet"
    override var pixelRatio = 1.0
        private set
    override var toneMapping = ToneMapping.NONE
    override var toneMappingExposure = 1.0
    override var shadowsEnabled = false
    override var shadowType = ShadowType.BASIC
    override var shadowAutoUpdate = true
    private var width = 0
    private var height = 0
    private val listeners = ArrayList<ContextListener>()
    var renders = 0
        private set
    var lost = false
        private set

    override fun setPixelRatio(ratio: Double) {
        pixelRatio = ratio
    }

    override fun setSize(
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
    }

    override fun getSize(target: Vec2): Vec2 = target.set(width.toDouble(), height.toDouble())

    override fun getDrawingBufferSize(target: Vec2): Vec2 = target.set(width * pixelRatio, height * pixelRatio)

    override fun render(
        scene: Scene,
        camera: Camera,
    ) {
        renders++
    }

    override fun compile(
        root: Traversable,
        camera: Camera,
        scene: Scene,
        onComplete: () -> Unit,
    ) = onComplete()

    override fun addContextListener(listener: ContextListener) {
        listeners.add(listener)
    }

    override fun removeContextListener(listener: ContextListener) {
        listeners.remove(listener)
    }

    fun loseContext() {
        lost = true
        for (listener in listeners.toList()) listener.onContextLost()
    }

    fun restoreContext() {
        lost = false
        for (listener in listeners.toList()) listener.onContextRestored()
    }

    var disposed = false
        private set

    override fun dispose() {
        disposed = true
        listeners.clear()
    }
}

/** A surface backend like the Filament one: detaching loses the context, attaching again restores it. */
internal class TestSurfaceBackend(
    override val backend: RenderBackend,
    private val lose: () -> Unit,
    private val restore: () -> Unit,
) : SurfaceBackend {
    var attachCount = 0
    var detachCount = 0
    var resizeCount = 0
    var size: SurfaceSize? = null
    private var detached = false

    override fun attach(
        surface: PlatformSurface,
        size: SurfaceSize,
    ) {
        attachCount++
        this.size = size
        if (detached) restore()
        detached = false
    }

    override fun resize(size: SurfaceSize) {
        resizeCount++
        this.size = size
    }

    override fun detach() {
        detachCount++
        detached = true
        lose()
    }

    val fake: FakeRenderBackend get() = backend as FakeRenderBackend
    val quiet: QuietBackend get() = backend as QuietBackend
}

internal class TestBackends(
    private val quiet: Boolean = false,
) : RenderBackendFactory {
    val created = ArrayList<TestSurfaceBackend>()
    var antialias: Boolean? = null
    var available = true
    var failWith: Throwable? = null

    val last: TestSurfaceBackend get() = created.last()

    override fun create(antialias: Boolean): SurfaceBackend? {
        this.antialias = antialias
        failWith?.let { throw it }
        if (!available) return null
        val made =
            if (quiet) {
                val backend = QuietBackend()
                TestSurfaceBackend(backend, backend::loseContext, backend::restoreContext)
            } else {
                val backend = FakeRenderBackend()
                TestSurfaceBackend(backend, backend::simulateContextLoss, backend::simulateContextRestore)
            }
        created.add(made)
        return made
    }
}

/** A sound output that is always willing to run. */
internal class TestPcmOutput : PcmOutput {
    override val sampleRate = 44100
    override var state = OutputState.Suspended
        private set
    private var listener: ((OutputState) -> Unit)? = null

    override fun setStateListener(listener: ((OutputState) -> Unit)?) {
        this.listener = listener
    }

    override fun start(renderer: PcmRenderer) = set(OutputState.Running)

    override fun resume() = set(OutputState.Running)

    override fun suspend() = set(OutputState.Suspended)

    override fun close() = set(OutputState.Closed)

    private fun set(next: OutputState) {
        state = next
        listener?.invoke(next)
    }
}

internal val BIG_DEVICE = DeviceInfo(8L * 1024 * 1024 * 1024, 8, true, 2400, 1080, "Test GPU")

internal inline fun <reified T : ScreenModel> ZoesHorseFarmApp.model(): T {
    val current = navigator.currentModel
    assertIs<T>(current)
    return current
}

/** The app on a fake platform with a clock the test moves by frames. */
internal class AppRig(
    val storage: MemoryKeyValueBackend = MemoryKeyValueBackend(),
    quiet: Boolean = false,
    languages: List<String?> = listOf("en"),
    debug: Boolean = false,
    withSurface: Boolean = true,
    state: AppState = AppState.FOREGROUND,
    capTo30Fps: Boolean = false,
    inputDevice: DeviceClass = DeviceClass.TOUCH,
    unlockOutput: TestPcmOutput? = null,
) {
    val clock = ManualClock(1_700_000_000_000L)
    val backends = TestBackends(quiet)
    val logged = ArrayList<String>()
    var seconds = 0.0
    var shutdowns = 0
    val platform =
        AppPlatform(
            keyValueBackend = storage,
            audio = AudioPlatform({ unlockOutput ?: TestPcmOutput() }, { _, _ -> Cancellable { } }),
            renderBackends = backends,
            deviceInfo = { BIG_DEVICE },
            clock = clock,
            secondsClock = { seconds },
            logSink = { logged.add(it) },
            preferredLanguages = languages,
            random = seededRng(1),
            debug = debug,
            initialAppState = state,
            capTo30Fps = capTo30Fps,
            inputDevice = inputDevice,
            shutdown = { shutdowns++ },
        )
    val app = (ZoesHorseFarmApp.create(platform) as AppCreation.Created).app
    val surface = TestSurface()

    init {
        if (withSurface) createSurface()
    }

    fun createSurface() = app.onSurfaceCreated(surface, SURFACE_WIDTH, SURFACE_HEIGHT, SURFACE_DENSITY)

    /** One display frame; [wallClock] false keeps the wall clock still (no heartbeat of the crash guard). */
    fun frame(
        step: Double = FRAME_S,
        wallClock: Boolean = true,
    ) {
        seconds += step
        if (wallClock) clock.ms = (seconds * MS_PER_SECOND).toLong() + START_MS
        app.onFrame(seconds)
    }

    fun frames(count: Int) = repeat(count) { frame() }

    private companion object {
        const val MS_PER_SECOND = 1000.0
        const val START_MS = 1_700_000_000_000L
    }
}
