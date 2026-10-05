package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.CameraMode
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.RideView
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.application.modes.CourseLines
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.domain.horse.DEFAULT_APPEARANCE
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.view3d.CONTEXT_RESTORE_TIMEOUT_MS
import app.zoeshorsefarm.view3d.CameraRig
import app.zoeshorsefarm.view3d.ErrorReporter
import app.zoeshorsefarm.view3d.GpuEpoch
import app.zoeshorsefarm.view3d.LineLabels
import app.zoeshorsefarm.view3d.RenderGate
import app.zoeshorsefarm.view3d.RestoreWatchdog
import app.zoeshorsefarm.view3d.Scheduler
import app.zoeshorsefarm.view3d.TickScheduler
import app.zoeshorsefarm.view3d.horse.HorseView
import app.zoeshorsefarm.view3d.horse.createHorse
import app.zoeshorsefarm.view3d.printingLog
import app.zoeshorsefarm.view3d.quality.LowFpsHint
import app.zoeshorsefarm.view3d.quality.canHintLowerLevel
import app.zoeshorsefarm.view3d.watchContextLoss
import app.zoeshorsefarm.view3d.world.World
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import app.zoeshorsefarm.view3d.CourseLines as WorldLines

// 3D engine (web: `engine.js`): one world, one horse with rider, one camera, shared across all
// rides, drawn through the scene `RenderBackend`.

// Longest time the frame loop waits for the shaders of a new quality level (or of a restored
// device) before it draws anyway.
private const val COMPILE_HOLD_MAX_MS = 2500L

// the first frame after a start or a pause has no predecessor: assume a 60 Hz display
private const val FIRST_FRAME_DT = 1.0 / 60

// The 30 fps battery lever: a frame comes only when this much time has passed, less a margin for the
// jitter of the display clock (at 60, 90, 120 and 144 Hz every second to fifth refresh is drawn).
private const val CAPPED_FRAME_S = 1.0 / 30
private const val CAP_JITTER_S = 0.004
private const val MS_PER_SECOND = 1000.0

// camera of the web app
private const val CAMERA_FOV = 58.0
private const val CAMERA_ASPECT = 16.0 / 9
private const val CAMERA_NEAR = 0.1
private const val CAMERA_FAR = 900.0

/**
 * The engine. The platform drives the frame loop (Compose, `CADisplayLink`, `Choreographer`): it
 * calls [frame] with the time of every display frame while [run] has a handler. Everything that
 * draws (world, horse, camera, shadows, governor) lives behind it; [backend] is the only way to the
 * GPU.
 *
 * Not thread safe: build, update and render on one thread (see the scene module).
 *
 * @param backend the renderer; the host created it, the engine configures it ([configureRenderer])
 * @param settings the application settings service (the only writer of the settings section)
 */
@Suppress("TooManyFunctions") // the facade of the view: one small function per operation of the spec
class Engine(
    val backend: RenderBackend,
    private val settings: SettingsService,
    config: EngineConfig,
) {
    private val clock = config.clock
    private val reportError = ErrorReporter(config.log ?: printingLog)
    private val guard = Guard(reportError)
    private val ownScheduler: TickScheduler? = if (config.scheduler == null) TickScheduler() else null

    /** The timer of the engine: the one of the host, or the one the frame loop drives. */
    val scheduler: Scheduler = config.scheduler ?: checkNotNull(ownScheduler)

    private val gate = RenderGate(scheduler)
    private val plan =
        planStartup(
            settings.get().graphicsLevel,
            config.view,
            config.device,
            config.gpuBudgetOverrideMB,
            config.contextAntialias,
        )

    /** What the host's backend has to be created with: antialiasing is decided by the memory budget. */
    val antialias: Boolean = plan.contextAntialias

    /** Size of the drawing surface and the way it follows the view and the pixel-ratio cap. */
    val sizing =
        RenderSizing(backend, ViewMetrics(config.view.cssWidth, config.view.cssHeight, config.view.devicePixelRatio))

    val camera = PerspectiveCamera(CAMERA_FOV, CAMERA_ASPECT, CAMERA_NEAR, CAMERA_FAR)
    val cameraRig = CameraRig(camera)

    // Objects that lived on the GPU of a lost device are never released with GPU calls (rule 4)
    private val gpuEpoch = GpuEpoch()
    val world: World
    val horse: HorseView

    // contact points of the footfalls in the world, one vector for all of them
    private val footPoint = Vec3()
    private val scratchSize = Vec2()
    private val lowFpsHint = LowFpsHint()
    private val controller: QualityController
    private val contextLostListeners = ArrayList<() -> Unit>()
    private val contextRestoredListeners = ArrayList<() -> Unit>()

    // context loss bookkeeping (debug box)
    private var lostCount = 0
    private var restoredCount = 0
    private var lostAtS: Double? = null
    private var restoredAtS: Double? = null
    private var graphicsHintPending = false

    // Devices are often lost while the app is in the background (Android app switch): that says
    // nothing about the load of the game, so the loss handler needs to know when the app last went to
    // the background or came back (levelAfterContextLoss).
    private var visible = true
    private var visibilityChangedAtS = Double.NEGATIVE_INFINITY

    // frame loop
    private var handler: FrameHandler? = null
    private var hasLast = false
    private var last = 0.0
    private var hasTick = false
    private var lastTick = 0.0
    private var tickCarryMs = 0.0
    private var redraw = true
    private var demand = false
    private val diag = EngineDiagnostics()

    /** True while the ride is paused: the scene is drawn once and then not again until something changes. */
    var paused: Boolean = false
        private set

    /** Battery lever: at most 30 frames per second (change it with [setCapTo30Fps]). */
    var capTo30Fps: Boolean = config.capTo30Fps
        private set

    /** Told when [wantsFrames] changes; the host may stop its display link while it is false. */
    var demandListener: DemandListener? = null

    private val host =
        object : QualityHost {
            override val world get() = this@Engine.world
            override val horse get() = this@Engine.horse
            override val sizing get() = this@Engine.sizing
            override val guard get() = this@Engine.guard
            override val crashGuard = config.crashGuard
            override val running get() = handler != null
            override val contextLost get() = this@Engine.contextLost

            override fun precompile() = this@Engine.precompile()

            override fun resize(force: Boolean) = this@Engine.resize(force)

            override fun requestRedraw() = this@Engine.requestRedraw()
        }

    private val contextWatch =
        watchContextLoss(
            backend,
            onLost = ::handleContextLost,
            onRestored = ::handleContextRestored,
            log = config.log ?: printingLog,
        )

    init {
        configureRenderer(backend)
        // The first picture is not there yet, so the level's pixel ratio can be applied right away.
        // Later changes wait for the render gate (see QualityController.applyQuality).
        sizing.setMaxPixelRatio(plan.firstPreset.pixelRatio)
        world = World(backend, plan.firstPreset, gpuEpoch::release, config.textRasterizer)
        horse =
            createHorse(
                coat = DEFAULT_APPEARANCE.coat,
                marking = DEFAULT_APPEARANCE.marking,
                quality = GraphicsLevel.fromId(plan.firstPreset.characterDetail.id) ?: plan.firstPreset.level,
                release = gpuEpoch::release,
            )
        world.scene.add(horse.group)
        controller = QualityController(host, settings, plan, config.startupCrash)
        if (!contextWatch.lost) precompile() // the shaders of the first level, before the first frame
    }

    // --- state ---------------------------------------------------------------------------------

    /** The graphics level in use (its name; the picture is fitted to the memory budget). */
    val level: GraphicsLevel get() = controller.level

    /** true while the graphics device is lost (the ride is paused by the ride screen). */
    val contextLost: Boolean get() = contextWatch.lost

    /** true while a frame loop is running (the surface is shown only then). */
    val running: Boolean get() = handler != null

    /** true while a level change is still being applied (stages, pixel ratio, shader compile). */
    val settling: Boolean get() = controller.stagesPending > 0 || controller.hasPendingPixelRatio || gate.blocked

    /**
     * Does the engine want [frame] calls? False while nothing runs, and while the ride is paused and
     * the one frame of the pause was drawn: the host may stop its display link then (battery) and
     * restarts it when [demandListener] says so.
     */
    val wantsFrames: Boolean
        get() =
            handler != null &&
                (!paused || redraw || controller.stagesPending > 0 || controller.hasPendingPixelRatio || gate.blocked)

    private fun notifyDemand() {
        val now = wantsFrames
        if (now == demand) return
        demand = now
        demandListener?.onDemandChanged(now)
    }

    /** Something visible changed: draw a frame even while the ride is paused. */
    fun requestRedraw() {
        redraw = true
        notifyDemand()
    }

    /**
     * The ride is paused (or goes on): a paused ride draws one frame and then stops drawing until
     * something visible changes (a level stage, the size, a new language on the signs). Cheap to
     * call every frame.
     */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        redraw = true // the last picture of the pause menu
        if (!value) hasLast = false // no hidden time passed while nothing was drawn
        notifyDemand()
    }

    /** Switches the 30-frames-per-second battery lever. The graphics automatic does not measure while it is on. */
    fun setCapTo30Fps(on: Boolean) {
        if (capTo30Fps == on) return
        capTo30Fps = on
        interruptMeasuring()
    }

    /** The size of the surface changed (rotation, split screen, window): fitted at once. */
    fun setViewSize(
        cssWidth: Double,
        cssHeight: Double,
        devicePixelRatio: Double = sizing.view.devicePixelRatio,
    ) {
        sizing.view.set(cssWidth, cssHeight, devicePixelRatio)
        guard.run("resize") { resize(false) }
    }

    /** The app is in the foreground (true) or not; a loss near this change says nothing about the game. */
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        visibilityChangedAtS = clock.nowSeconds()
    }

    private fun resize(force: Boolean) {
        val changed = sizing.resize(camera)
        if (changed || force) {
            camera.aspect = max(sizing.view.cssWidth, 1.0) / max(sizing.view.cssHeight, 1.0)
            camera.updateProjectionMatrix()
        }
        if (changed) redraw = true
    }

    // --- shaders -------------------------------------------------------------------------------

    /**
     * Compiles the shaders for the current scene state without blocking: the frame loop waits (at
     * most [COMPILE_HOLD_MAX_MS]) and the last picture stays on screen. Only what is visible is
     * compiled (`world.compileRoot`): the programs of hidden details would take GPU memory for
     * nothing (rule 4).
     */
    private fun precompile() {
        guard.run("shader precompile") {
            gate.hold(COMPILE_HOLD_MAX_MS) { done ->
                backend.compile(world.compileRoot, camera, world.scene, done)
            }
        }
    }

    // --- context loss --------------------------------------------------------------------------

    /** Calls [listener] when the device is lost; returns the function that unsubscribes. */
    fun onContextLost(listener: () -> Unit): () -> Unit {
        contextLostListeners.add(listener)
        return { contextLostListeners.remove(listener) }
    }

    /** Calls [listener] when the device is back; returns the function that unsubscribes. */
    fun onContextRestored(listener: () -> Unit): () -> Unit {
        contextRestoredListeners.add(listener)
        return { contextRestoredListeners.remove(listener) }
    }

    // The device took the graphics memory away (typical on phones and tablets, e.g. under memory
    // pressure or when the app was in the background). We announce the loss so that the ride pauses,
    // rebuild what only lived on the GPU and announce the restore.
    private fun handleContextLost() {
        lostCount += 1
        lostAtS = clock.nowSeconds()
        // first of all: what is on the GPU now is gone, whatever the fallback below rebuilds
        guard.run("context loss bookkeeping") { gpuEpoch.contextLost(world.gpuObjects()) }
        guard.run("context loss fallback") {
            graphicsHintPending =
                controller.onContextLost(visible, clock.nowSeconds() - visibilityChangedAtS)
        }
        for (listener in contextLostListeners.toList()) listener()
    }

    private fun handleContextRestored() {
        restoredCount += 1
        restoredAtS = clock.nowSeconds()
        gpuEpoch.contextRestored()
        guard.run("context restore") {
            world.restoreAfterContextLoss()
            resize(true)
        }
        precompile()
        requestRedraw()
        for (listener in contextRestoredListeners.toList()) listener()
    }

    /**
     * True once after a context loss with a manual level above low: the ride screen shows the hint
     * to pick a lower level (rule 4). Reading it clears it.
     */
    fun takeGraphicsHint(): Boolean {
        val pending = graphicsHintPending
        graphicsHintPending = false
        return pending
    }

    /** True once after an unexpected end of the previous run (crash guard) that calls for the hint. */
    fun takeCrashHint(): Boolean = host.crashGuard?.takeHint() ?: false

    /**
     * Does the "level too high" hint make sense for the level in use? Only for a manual level that has
     * a lower one to pick (rule 4).
     */
    fun canHintLowerLevel(auto: Boolean): Boolean = canHintLowerLevel(auto, level)

    private var restoreWatchdog: RestoreWatchdog? = null

    /**
     * Starts the countdown for a device that does not come back ([CONTEXT_RESTORE_TIMEOUT_MS] unless
     * [timeoutMs] says otherwise); [onTimeout] fires once if [cancelRestoreWatchdog] was not called
     * first and asks the player to restart the 3D view. A second start restarts it. Runs on the
     * engine's timer, so the frame loop has to run.
     */
    fun startRestoreWatchdog(
        timeoutMs: Long = CONTEXT_RESTORE_TIMEOUT_MS,
        onTimeout: () -> Unit,
    ) {
        restoreWatchdog?.cancel()
        restoreWatchdog = RestoreWatchdog(scheduler, timeoutMs, onTimeout).also { it.start() }
    }

    /** The device came back (or the ride was left): stops the countdown. */
    fun cancelRestoreWatchdog() {
        restoreWatchdog?.cancel()
        restoreWatchdog = null
    }

    // --- the frame loop ------------------------------------------------------------------------

    /**
     * Starts ([handler]) or stops (null) the frame loop. Nothing of the ride is on screen yet, so a
     * start is the time to finish a switch that was still in stages, fit the level to the window and
     * give the textures the anisotropy of the level.
     */
    fun run(handler: FrameHandler?) {
        this.handler = handler
        hasLast = false
        redraw = true
        if (handler != null) {
            controller.onRunStart()
            resize(true)
        }
        notifyDemand()
    }

    private fun advanceTimers(now: Double) {
        val own = ownScheduler
        if (own != null && hasTick) {
            val ms = (now - lastTick) * MS_PER_SECOND + tickCarryMs
            val whole = floor(ms)
            tickCarryMs = ms - whole
            if (whole >= 1.0) own.advance(whole.toLong())
        }
        lastTick = now
        hasTick = true
    }

    /**
     * One display frame at [nowSeconds] (any monotonic clock of the host). Does nothing without a
     * running handler. A frame waits while shaders compile (the last picture stays, no hidden time
     * passes); with the 30 fps lever on, frames that come too early are skipped.
     */
    fun frame(nowSeconds: Double) {
        val frameHandler = handler ?: return
        advanceTimers(nowSeconds)
        if (capTo30Fps && hasLast && nowSeconds - last < CAPPED_FRAME_S - CAP_JITTER_S) return
        val rawDt = if (hasLast) nowSeconds - last else FIRST_FRAME_DT
        val dt = if (hasLast) min(TUNING.sim.maxDt, rawDt) else FIRST_FRAME_DT
        last = nowSeconds
        hasLast = true
        // shaders are compiling: keep the last picture, do not simulate (no hidden time passes)
        if (gate.blocked) return
        // drawing happens right below in this frame, so the cleared buffer is never shown
        if (controller.takePendingPixelRatio()) redraw = true
        // each step on its own: a failing step is logged and the others still run
        guard.run("resize") { resize(false) }
        guard.run("frame") { frameHandler.frame(dt, rawDt) }
        drawAndAdvance()
        notifyDemand()
    }

    // A paused ride draws only when something changed; the next quality stage follows a drawn frame.
    private fun drawAndAdvance() {
        if (!paused || redraw || controller.stagesPending > 0) {
            if (!contextWatch.lost) guard.run("render") { backend.render(world.scene, camera) }
            redraw = false
        }
        if (controller.stagesPending > 0 && !contextWatch.lost) {
            guard.run("quality stage") { controller.advanceStages() }
        }
    }

    // --- the graphics automatic and the hint ---------------------------------------------------

    private fun effectiveMeasuring(value: Boolean): Boolean =
        value && !capTo30Fps // 30 fps would look like a slow device

    /**
     * One frame for the level automatic (rule 4): [measuring] = riding and the app visible, [busy] = a
     * jump or an approach is in progress (the upgrade never starts a step then). Call it with
     * `measuring = false` while the ride is paused.
     */
    fun governorFrame(
        rawDt: Double,
        measuring: Boolean,
        busy: Boolean = false,
    ) = controller.frame(rawDt, effectiveMeasuring(measuring), busy)

    /** Frames around a stage, a pause, a menu or a restored device are no measurement. */
    fun interruptMeasuring() {
        controller.interrupt()
        lowFpsHint.interrupt()
    }

    /**
     * One frame of the "level too high" hint (rule 4): true exactly once per ride when a manual level
     * is too high for the device. [measuring] is false while nothing may be concluded from the frames.
     */
    fun lowFpsHintFrame(
        rawDt: Double,
        measuring: Boolean,
    ): Boolean = lowFpsHint.frame(rawDt, effectiveMeasuring(measuring))

    /** Test feed for the frame times of the automatic (web: `__zhfTest.setFrameFeed`). */
    var frameFeed: FrameFeed?
        get() = controller.frameFeed
        set(value) {
            controller.frameFeed = value
        }

    // --- the ride ------------------------------------------------------------------------------

    /** A ride begins: the obstacles of the course and no highlight, aid or finish mark yet. */
    fun beginRide(
        obstacles: List<Obstacle>,
        flags: Boolean,
    ) {
        world.setObstacles(obstacles, flags)
        world.highlight(null)
        world.setAid(null)
        world.setFinishMarked(false)
        requestRedraw()
    }

    /**
     * The ride was (re)started: dress the horse with the saved [appearance], put it on the start pose
     * [start], snap the camera and forget the running measurements ("Start again" is a new ride,
     * rule 39).
     */
    fun restartRide(
        appearance: Appearance,
        start: Horse,
    ) {
        horse.setAppearance(appearance)
        placeHorse(start)
        cameraRig.snap()
        controller.interrupt()
        lowFpsHint.reset()
        requestRedraw()
    }

    /** Sets the camera mode saved in the settings (the start of a ride). */
    fun setCameraMode(mode: CameraMode) {
        cameraRig.setMode(mode)
        requestRedraw()
    }

    /** The camera button: switches the camera and returns the new mode (the caller saves it). */
    fun toggleCamera(): CameraMode {
        val mode = cameraRig.toggle()
        requestRedraw()
        return mode
    }

    /** Start and finish lines with their translated labels (null lines: free mode, nothing drawn). */
    fun showLines(
        lines: CourseLines?,
        startLabel: String,
        finishLabel: String,
    ) {
        world.setLines(
            if (lines == null) null else WorldLines(lines.start, lines.finish, LineLabels(startLabel, finishLabel)),
        )
        requestRedraw()
    }

    /** Puts the horse object where the simulation has it. */
    fun placeHorse(state: Horse) {
        horse.group.position.set(state.x, state.y, state.z)
        horse.group.rotation.y = state.heading
    }

    /**
     * Raises hoof dust for the footfalls of the last horse update. Call it after the horse object has
     * been placed for the frame; the world decides where and how strong (sand only).
     */
    fun emitHoofDust() {
        val falls = horse.footfalls
        for (i in falls.indices) {
            val ev = falls[i]
            horse.footfallWorld(ev, footPoint)
            world.emitHoofDust(footPoint.x, footPoint.y, footPoint.z, ev.strength)
        }
    }

    /**
     * The 3D part of a running ride frame: animates the horse, places it, raises dust, syncs poles,
     * highlight, aid and finish mark, moves the shadow focus and the camera and updates the world.
     * Call it once per frame while the ride runs (not while paused), after the session stepped.
     * Allocation free.
     */
    fun updateRide(
        dt: Double,
        view: RideView,
    ) {
        val state = view.horse
        horse.update(dt, state)
        placeHorse(state)
        emitHoofDust() // after placeHorse: the footfalls are placed with the object
        world.syncRails(view.rails, dt, view.fallDirs)
        world.setShadowFocus(state.x, state.z)
        val highlight = view.highlight
        world.highlight(highlight?.elementId, highlight?.number)
        world.setFinishMarked(view.finishMarked)
        world.setAid(view.aid)
        cameraRig.update(dt, state, horse.earAnchor)
        world.update(dt, camera)
    }

    // --- diagnostics ---------------------------------------------------------------------------

    /** Values for the debug box; the same object each time (do not keep it). */
    fun diagnostics(): EngineDiagnostics {
        val d = diag
        if (d.gpu.isEmpty() && !contextWatch.lost) d.gpu = backend.gpuDescription
        d.budgetGpu = backendBudgetGpu
        d.level = controller.level
        d.auto = controller.auto
        d.devicePixelRatio = sizing.view.devicePixelRatio
        d.pixelRatio = backend.pixelRatio
        backend.getDrawingBufferSize(scratchSize)
        d.bufferWidth = scratchSize.x.toInt()
        d.bufferHeight = scratchSize.y.toInt()
        d.maxTextureSize = backend.capabilities.maxTextureSize
        d.contextLost = lostCount
        d.contextRestored = restoredCount
        d.lostAtS = lostAtS
        d.restoredAtS = restoredAtS
        d.stagesPending = controller.stagesPending
        val fit = controller.fitInfo
        d.gpuEstimateMB = fit.estimateMB
        d.gpuBudgetMB = plan.budgetMB.toDouble()
        d.ratioCap = fit.capped.pixelRatio
        d.shadowCap = fit.capped.shadowMapSize
        d.sceneryCapped = fit.capped.scenery
        d.antialias = plan.contextAntialias
        d.antialiasDropped = plan.antialiasWanted && !plan.antialiasChosen // the budget said no
        d.blockedLevels = host.crashGuard?.blockedLevels() ?: emptyList()
        d.leftLevels = controller.leftLevels()
        d.lastChange = controller.lastChange
        return d
    }

    private val backendBudgetGpu: String = config.device.rendererName.orEmpty()

    /** Stops listening and frees the world and the horse (the backend belongs to the host). */
    fun dispose() {
        run(null)
        cancelRestoreWatchdog()
        controller.dispose()
        contextWatch.stop()
        horse.dispose()
        world.dispose()
    }
}
