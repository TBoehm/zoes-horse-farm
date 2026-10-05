package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.CameraMode
import app.zoeshorsefarm.application.CrashGuard
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.HorseSection
import app.zoeshorsefarm.application.PreviousRun
import app.zoeshorsefarm.application.RenderInfo
import app.zoeshorsefarm.application.RenderLease
import app.zoeshorsefarm.application.Rng
import app.zoeshorsefarm.application.modes.CourseLines
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.platform.AppState
import app.zoeshorsefarm.platform.ErrorLog
import app.zoeshorsefarm.platform.describeError
import app.zoeshorsefarm.presentation.AppContext
import app.zoeshorsefarm.presentation.UiTask
import app.zoeshorsefarm.presentation.nav.Route
import app.zoeshorsefarm.presentation.nav.ScreenChange
import app.zoeshorsefarm.presentation.nav.ScreenModel
import app.zoeshorsefarm.presentation.ride.FrameResult
import app.zoeshorsefarm.presentation.ride.RideEnginePort
import app.zoeshorsefarm.presentation.ride.RideScreenModel
import app.zoeshorsefarm.presentation.ride.createRideScreen
import app.zoeshorsefarm.view3d.CONTEXT_RESTORE_TIMEOUT_MS
import app.zoeshorsefarm.view3d.engine.DebugBox
import app.zoeshorsefarm.view3d.engine.DebugError
import app.zoeshorsefarm.view3d.engine.Engine
import app.zoeshorsefarm.view3d.engine.EngineConfig
import app.zoeshorsefarm.view3d.engine.FrameHandler
import app.zoeshorsefarm.view3d.engine.ViewSize
import app.zoeshorsefarm.view3d.engine.planStartup
import app.zoeshorsefarm.view3d.quality.StartupCrash
import app.zoeshorsefarm.view3d.quality.canHintLowerLevel

/**
 * The engine side of the ride screen: implements [RideEnginePort] for `RideScreenModel` and does what
 * the web's `ride-screen.js` did with the engine (world, horse, camera, frame loop, crash guard lease,
 * audio hoof beat). Created and owned by `ZoesHorseFarmApp`; not thread safe (render/UI thread only).
 *
 * The 3D side is built lazily: the first ride that has a surface creates the render backend and the
 * [Engine] (like `getEngine` in the web app) and keeps them for the next rides. While there is no
 * surface (the shell has not sent one yet, or the backend cannot be created) the device counts as
 * lost: the ride starts paused with the "graphics lost" note and goes on when the surface arrives.
 */
@Suppress("TooManyFunctions") // the port plus the surface and screen events the app forwards
internal class EngineBridge(
    private val ctx: AppContext,
    private val platform: AppPlatform,
    private val crashGuard: CrashGuard,
    private val previousRun: PreviousRun,
    private val errorLog: ErrorLog,
    private val rng: Rng,
    private val onNoGraphics: () -> Unit,
) : RideEnginePort {
    /** One backend with its engine, from the first ride until [teardownStack]. */
    private class Stack(
        val surface: SurfaceBackend,
        val engine: Engine,
        val debugBox: DebugBox?,
    ) {
        val subscriptions = ArrayList<() -> Unit>()
    }

    private var surface: PlatformSurface? = null
    private var size: SurfaceSize? = null
    private var stack: Stack? = null
    private var backendFailed = false
    private var visible = ctx.lifecycle.state == AppState.FOREGROUND

    // the ride that is on screen (or covered by the settings), null between rides
    private var model: RideScreenModel? = null
    private var modelSubscription: (() -> Unit)? = null
    private var lease: RenderLease? = null
    private var leaseInfo = RenderInfo(GraphicsLevel.LOW, auto = true)
    private var autoGraphics = ctx.settings.get().graphicsAuto

    // what the model asked for before there was an engine or a model to apply it to
    private var cameraMode: CameraMode = ctx.settings.get().camera
    private var lines: CourseLines? = null
    private var startLabel = ""
    private var finishLabel = ""
    private var restartPending = false
    private var reportedLost = false

    private val lostListeners = ArrayList<() -> Unit>()
    private val restoredListeners = ArrayList<() -> Unit>()
    private var watchdog: UiTask? = null
    private val frameHandler = FrameHandler { dt, rawDt -> onEngineFrame(dt, rawDt) }
    private val settingsSubscription = ctx.settings.onChange { autoGraphics = it.graphicsAuto }
    private val lifecycleSubscription =
        ctx.lifecycle.onChange {
            visible = it == AppState.FOREGROUND
            stack?.engine?.setVisible(visible)
        }

    /** The text of the debug box, null unless `AppPlatform.debug` is on and a ride built the engine. */
    val debugText: String? get() = stack?.debugBox?.text

    // ---- screens ----

    /** The factory of the ride route: the model of the screen, wired to the engine when it is a ride. */
    fun createRide(route: Route.Ride): ScreenModel {
        val screen = createRideScreen(ctx, route, rng, this)
        if (screen is RideScreenModel) attach(screen)
        return screen
    }

    /** The screen stack changed: when no ride is left in it, the engine stops drawing it. */
    fun onScreenChanged(change: ScreenChange) {
        if (model != null && Route.Ride.NAME !in change.stack) leaveRide()
    }

    private fun attach(next: RideScreenModel) {
        detachRide()
        model = next
        modelSubscription = next.changes.listen { stack?.engine?.setPaused(next.paused) }
        startRide()
    }

    // The ride is on screen and (maybe) there is an engine: put the world in the state of the ride, mark
    // the drawing for the crash guard and start the frame loop (web: the end of createRideScreen).
    private fun startRide() {
        val ride = model ?: return
        val current = ensureStack() ?: return
        val engine = current.engine
        engine.beginRide(ride.session.obstacles, ride.session.flags)
        if (restartPending) applyRestart(engine, ride)
        lease?.release()
        lease = crashGuard.markRendering(RenderInfo(engine.level, autoGraphics))
        engine.run(frameHandler)
        engine.setPaused(ride.paused) // after run: a new run never starts paused by itself
    }

    // the engine stops drawing this ride; what the model said about it stays until the ride is left
    private fun detachRide() {
        modelSubscription?.invoke()
        modelSubscription = null
        model = null
        lease?.release()
        lease = null
        stack?.engine?.run(null)
    }

    private fun leaveRide() {
        detachRide()
        restartPending = false
        lines = null
    }

    // One frame of the running engine (web: `frame` of the ride screen): the model steps the ride, the
    // engine shows it. Also runs while the ride is paused, the scene is still drawn then.
    private fun onEngineFrame(
        dt: Double,
        rawDt: Double,
    ) {
        val ride = model ?: return
        val current = stack ?: return
        val engine = current.engine
        leaseFrame(engine.level)
        current.debugBox?.frame(rawDt)
        when (ride.frame(dt, rawDt)) {
            FrameResult.PAUSED -> {
                engine.governorFrame(rawDt, measuring = false)
            }

            FrameResult.STOP -> {
                // the ride was paused or left by this frame: nothing more to do
            }

            FrameResult.RUNNING -> {
                engine.updateRide(dt, ride.view)
                engine.governorFrame(rawDt, measuring = visible, busy = ride.busy)
            }
        }
        engine.setPaused(ride.paused)
    }

    private fun leaseFrame(level: GraphicsLevel) {
        val current = lease ?: return
        if (leaseInfo.level != level || leaseInfo.auto != autoGraphics) leaseInfo = RenderInfo(level, autoGraphics)
        current.frame(leaseInfo)
    }

    /** One display frame: the engine runs its frame loop while a ride is on screen. */
    fun frame(nowSeconds: Double) {
        stack?.engine?.frame(nowSeconds)
    }

    /** The ride model that is attached to the engine, for the keyboard. */
    val rideModel: RideScreenModel? get() = model

    // ---- surface ----

    fun onSurfaceCreated(
        surface: PlatformSurface,
        size: SurfaceSize,
    ) {
        this.surface = surface
        this.size = size
        val current = stack
        if (current != null) {
            current.surface.attach(surface, size)
            current.engine.setViewSize(size.widthDp, size.heightDp, size.density)
        } else if (model != null) {
            startRide() // a ride was waiting for the surface
        }
    }

    fun onSurfaceResized(size: SurfaceSize) {
        this.size = size
        val current = stack ?: return
        current.surface.resize(size)
        current.engine.setViewSize(size.widthDp, size.heightDp, size.density)
    }

    fun onSurfaceDestroyed() {
        surface = null
        stack?.surface?.detach()
    }

    // ---- building and tearing down the 3D side ----

    private fun qualityDevice() = platform.deviceInfo.read().toQualityDeviceInfo()

    /** The engine, built on first use; null while there is no surface or no backend. */
    private fun ensureStack(): Stack? {
        stack?.let { return it }
        val target = surface
        val area = size
        val built = if (target == null || area == null || backendFailed) null else build(target, area)
        if (built != null) {
            stack = built
            applyKnownState(built.engine)
            if (reportedLost) {
                reportedLost = false
                for (listener in restoredListeners.toList()) listener()
            }
        }
        return built
    }

    @Suppress("TooGenericExceptionCaught") // the backend or the engine may fail in any way: 3D is off then
    private fun build(
        target: PlatformSurface,
        area: SurfaceSize,
    ): Stack? {
        val device = qualityDevice()
        val view = ViewSize(area.widthDp, area.heightDp, area.density)
        // antialiasing is a property of the backend and the budget decides it, so ask before it exists
        val plan = planStartup(ctx.settings.get().graphicsLevel, view, device)
        var backend: SurfaceBackend? = null
        return try {
            backend = platform.renderBackends.create(plan.antialiasChosen)
            if (backend == null) {
                backendFailed = true
                onNoGraphics()
                return null
            }
            backend.attach(target, area)
            val engine = Engine(backend.backend, ctx.settings, engineConfig(view, device, plan.contextAntialias))
            Stack(backend, engine, debugBoxOf(engine)).also { subscribe(it) }
        } catch (error: Throwable) {
            logError("Cannot start the 3D view", error)
            backendFailed = true
            runCatching { backend?.backend?.dispose() }
            onNoGraphics()
            null
        }
    }

    private fun engineConfig(
        view: ViewSize,
        device: app.zoeshorsefarm.view3d.quality.DeviceInfo,
        antialias: Boolean,
    ) = EngineConfig(
        view = view,
        device = device,
        contextAntialias = antialias,
        startupCrash = startupCrashOf(previousRun),
        crashGuard = crashGuard,
        textRasterizer = platform.textRasterizer,
        clock = platform.secondsClock,
        log = ::logError,
    )

    private fun debugBoxOf(engine: Engine): DebugBox? =
        if (!platform.debug) {
            null
        } else {
            DebugBox(
                diagnostics = engine::diagnostics,
                errors = { errorLog.entries.map { DebugError(it.atS, it.message) } },
                t = { key, params -> ctx.i18n.t(key, params) },
                lastCrash = crashGuard.lastCrash(),
                version = ctx.version,
            )
        }

    private fun subscribe(built: Stack) {
        val engine = built.engine
        built.subscriptions +=
            engine.onContextLost { for (listener in lostListeners.toList()) listener() }
        built.subscriptions +=
            engine.onContextRestored { for (listener in restoredListeners.toList()) listener() }
        // the footfalls of the horse are the hoof beat; the model keeps quiet while paused
        engine.horse.onFootfall = { gait, _ -> model?.onFootfall(gait.id) }
        engine.setVisible(visible)
    }

    // what the model said before the engine existed
    private fun applyKnownState(engine: Engine) {
        engine.setCameraMode(cameraMode)
        if (lines != null) engine.showLines(lines, startLabel, finishLabel)
    }

    private fun applyRestart(
        engine: Engine,
        ride: RideScreenModel,
    ) {
        restartPending = false
        val horse = ctx.store.get(HorseSection)
        engine.restartRide(Appearance(horse.coat, horse.marking), ride.session.view.horse)
    }

    private fun teardownStack() {
        val old = stack ?: return
        stack = null
        old.subscriptions.forEach { it() }
        old.engine.horse.onFootfall = null
        guarded("Cannot free the 3D view") { old.engine.dispose() }
        guarded("Cannot free the render backend") { old.surface.backend.dispose() }
    }

    @Suppress("TooGenericExceptionCaught") // freeing must go on whatever one step throws
    private inline fun guarded(
        message: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: Throwable) {
            logError(message, error)
        }
    }

    private fun logError(
        message: String,
        error: Throwable,
    ) {
        errorLog.add("$message: ${describeError(error)}")
        platform.logSink("$message\n${error.stackTraceToString()}")
    }

    // ---- RideEnginePort ----

    override val graphicsLevel: GraphicsLevel get() = stack?.engine?.level ?: ctx.settings.get().graphicsLevel

    // Asking builds the engine if the surface is there: a ride that starts must not see a "lost" device
    // only because nothing has drawn yet.
    override val contextLost: Boolean
        get() {
            val current = ensureStack()
            if (current == null) reportedLost = true
            return current?.engine?.contextLost ?: true
        }

    override fun onContextLost(listener: () -> Unit): () -> Unit {
        lostListeners.add(listener)
        return { lostListeners.remove(listener) }
    }

    override fun onContextRestored(listener: () -> Unit): () -> Unit {
        restoredListeners.add(listener)
        return { restoredListeners.remove(listener) }
    }

    override fun canHintLowerLevel(auto: Boolean): Boolean = canHintLowerLevel(auto, graphicsLevel)

    // The countdown runs on the UI scheduler (the app's frame loop ticks it), so it also works while
    // there is no engine at all.
    override fun startRestoreWatchdog(onTimeout: () -> Unit) {
        watchdog?.cancel()
        watchdog = ctx.scheduler.postDelayed(CONTEXT_RESTORE_TIMEOUT_MS) { onTimeout() }
    }

    override fun cancelRestoreWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    override fun setCameraMode(mode: CameraMode) {
        cameraMode = mode
        ensureStack()?.engine?.setCameraMode(mode)
    }

    override fun toggleCamera(): CameraMode {
        val next = stack?.engine?.toggleCamera() ?: return cameraMode
        cameraMode = next
        return next
    }

    override fun showLines(
        lines: CourseLines?,
        startLabel: String,
        finishLabel: String,
    ) {
        this.lines = lines
        this.startLabel = startLabel
        this.finishLabel = finishLabel
        stack?.engine?.showLines(lines, startLabel, finishLabel)
    }

    override fun onRideRestarted() {
        val ride = model
        val current = stack
        if (ride == null || current == null) {
            restartPending = true // applied when the ride is attached to the engine
        } else {
            applyRestart(current.engine, ride)
        }
    }

    override fun interruptMeasuring() {
        stack?.engine?.interruptMeasuring()
    }

    override fun lowFpsHintFrame(
        rawDt: Double,
        measuring: Boolean,
    ): Boolean = stack?.engine?.lowFpsHintFrame(rawDt, measuring) ?: false

    override fun takeGraphicsHint(): Boolean = stack?.engine?.takeGraphicsHint() ?: false

    override fun takeCrashHint(): Boolean = crashGuard.takeHint()

    // The web reloads the page; here the backend and the engine are built again on the same surface
    override fun reloadGraphics() {
        val ride = model
        detachRide()
        teardownStack()
        backendFailed = false
        reportedLost = true
        restartPending = true // the new engine knows neither the look of the horse nor its pose
        if (ride != null) attach(ride) else ensureStack()
    }

    /** The app ends: the engine, the backend and the listeners go. */
    fun dispose() {
        detachRide()
        teardownStack()
        cancelRestoreWatchdog()
        settingsSubscription()
        lifecycleSubscription()
        lostListeners.clear()
        restoredListeners.clear()
    }
}

private fun startupCrashOf(run: PreviousRun): StartupCrash =
    when (run) {
        PreviousRun.Clean -> StartupCrash(crashed = false)
        is PreviousRun.Crashed -> StartupCrash(crashed = true, auto = run.auto, level = run.level)
    }
