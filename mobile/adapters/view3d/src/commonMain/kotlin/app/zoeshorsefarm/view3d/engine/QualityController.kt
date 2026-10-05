package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.CrashGuard
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.view3d.ErrorReporter
import app.zoeshorsefarm.view3d.horse.HorseView
import app.zoeshorsefarm.view3d.quality.BudgetFit
import app.zoeshorsefarm.view3d.quality.QUALITY_STAGE_IDS
import app.zoeshorsefarm.view3d.quality.QualityGovernor
import app.zoeshorsefarm.view3d.quality.QualityPreset
import app.zoeshorsefarm.view3d.quality.QualityStage
import app.zoeshorsefarm.view3d.quality.QualityStageId
import app.zoeshorsefarm.view3d.quality.StageQueue
import app.zoeshorsefarm.view3d.quality.StartupChange
import app.zoeshorsefarm.view3d.quality.StartupCrash
import app.zoeshorsefarm.view3d.quality.UpgradeGovernor
import app.zoeshorsefarm.view3d.quality.UpgradeStep
import app.zoeshorsefarm.view3d.quality.estimateGpuMemoryMB
import app.zoeshorsefarm.view3d.quality.fitPresetToBudget
import app.zoeshorsefarm.view3d.quality.levelAfterContextLoss
import app.zoeshorsefarm.view3d.quality.nextUpgradeLevel
import app.zoeshorsefarm.view3d.quality.planQualityStagesFromState
import app.zoeshorsefarm.view3d.quality.presetFor
import app.zoeshorsefarm.view3d.quality.startupCrashChange
import app.zoeshorsefarm.view3d.quality.upgradeMeasuring
import app.zoeshorsefarm.view3d.world.World

// The graphics level of the engine (web: the quality part of `engine.js`): the level and its fit to
// the GPU memory budget, the staged level change, the two governors of "Automatic" and what the
// engine remembers about them for the debug box.

/** Runs one part of the engine; an exception is logged and never breaks the loop or the input. */
internal class Guard(
    @PublishedApi internal val report: ErrorReporter,
) {
    @Suppress("TooGenericExceptionCaught") // any failure is logged and the loop goes on
    inline fun run(
        where: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: Exception) {
            report(where, error)
        }
    }
}

/** What the controller needs from the engine around it. */
internal interface QualityHost {
    val world: World
    val horse: HorseView
    val sizing: RenderSizing
    val guard: Guard
    val crashGuard: CrashGuard?

    /** A frame loop is running (otherwise nothing is visible and a switch is applied at once). */
    val running: Boolean
    val contextLost: Boolean

    /** Compiles the visible shaders; the loop waits for it. */
    fun precompile()

    fun resize(force: Boolean)

    /** Something visible changed: draw a frame even while the ride is paused. */
    fun requestRedraw()
}

/** The preset's character detail as the level of the horse model. */
private fun characterLevel(preset: QualityPreset): GraphicsLevel =
    GraphicsLevel.fromId(preset.characterDetail.id) ?: preset.level

internal class QualityController(
    private val host: QualityHost,
    private val settings: SettingsService,
    private val plan: StartupPlan,
    startupCrash: StartupCrash?,
) {
    private val world = host.world
    private val horse = host.horse
    private val guard = host.guard
    private val budgetMB = plan.budgetMB.toDouble()

    /** The level in use (its name; the preset is fitted to the budget). */
    var level: GraphicsLevel = plan.level
        private set

    /** The fit of the level that was applied last, for the debug box. */
    var fitInfo: BudgetFit = plan.fit
        private set

    // Level changes are applied stage by stage (QualityStages.kt): `applied` is the level each stage
    // has reached, the queue paces the remaining ones, one per few drawn frames.
    private val applied =
        HashMap<QualityStageId, QualityPreset?>().also { m ->
            for (id in QUALITY_STAGE_IDS) m[id] = plan.firstPreset
        }
    private val stageQueue = StageQueue()

    /** The level's preset fitted to the budget. */
    var targetPreset: QualityPreset = plan.firstPreset
        private set

    // the pixel ratio is applied by the frame loop in the frame that draws again (NaN = none waiting)
    private var pendingPixelRatio = Double.NaN
    private val textures = world.textureSizes()

    /** Levels the automatic stepped down from because of a low frame rate (not to be climbed to again). */
    private val leftByFps = LinkedHashSet<GraphicsLevel>()

    /** Why the automatic changed the level last (debug box). A crash at the previous start already did. */
    var lastChange: LevelChange? =
        if (startupCrashChange(startupCrash) == StartupChange.CRASH) LevelChange(ChangeKind.CRASH) else null
        private set

    /** Frame feed of the browser tests (`__zhfTest.setFrameFeed`): replaces the real frame times. */
    var frameFeed: FrameFeed? = null

    private val downgrade =
        QualityGovernor(
            level = level,
            auto = settings.get().graphicsAuto,
            onChange = { next, fps ->
                leftByFps.add(level) // not to be climbed to again in this session
                lastChange = LevelChange(ChangeKind.DOWN, fps)
                applyQuality(next)
                settings.setAutoLevel(next) // governor downgrade: "Automatic" stays on
            },
        )
    private val upgrade =
        UpgradeGovernor(
            chooseTarget = {
                nextUpgradeLevel(
                    level = level,
                    blocked = host.crashGuard?.blockedLevels() ?: emptyList(),
                    left = leftByFps,
                    fits = ::fitsBudget,
                )
            },
        )
    private val unsubscribe = ArrayList<() -> Unit>()

    init {
        unsubscribe +=
            settings.onChange { s ->
                guard.run("settings change") {
                    if (s.graphicsAuto != downgrade.auto) downgrade.setAuto(s.graphicsAuto)
                    if (s.graphicsLevel != level) {
                        downgrade.setLevel(s.graphicsLevel)
                        applyQuality(s.graphicsLevel)
                    }
                }
            }
        // "Automatic" selected anew: the levels stepped down from may be tried again (the crash guard
        // forgets its blocked levels itself)
        unsubscribe += settings.onAutoSelected { leftByFps.clear() }
    }

    val auto: Boolean get() = downgrade.auto

    /** Number of stages still to come. */
    val stagesPending: Int get() = stageQueue.pending

    val hasPendingPixelRatio: Boolean get() = !pendingPixelRatio.isNaN()

    /** The levels the automatic stepped down from in this session (debug box). */
    fun leftLevels(): List<GraphicsLevel> = leftByFps.toList()

    fun dispose() {
        for (stop in unsubscribe) stop()
        unsubscribe.clear()
    }

    /** The preset of a level, fitted to the budget for the real context and the current size. */
    private fun fitFor(forLevel: GraphicsLevel): QualityPreset {
        fitInfo =
            fitPresetToBudget(
                presetFor(forLevel),
                memoryContext(host.sizing.view, plan.contextAntialias, textures),
                budgetMB,
            )
        return fitInfo.preset
    }

    /** The unreduced estimate of a level for this device against its budget (rule 4, b). */
    private fun fitsBudget(candidate: GraphicsLevel): Boolean =
        estimateGpuMemoryMB(presetFor(candidate), memoryContext(host.sizing.view, plan.contextAntialias, textures)) <=
            budgetMB

    /**
     * Applies one stage for [target]. The pixel ratio is NOT changed here: resizing the drawing
     * buffer clears it, and with the render gate closed (shaders compiling) nothing would be drawn.
     * The new ratio is stored and applied by the frame loop in the very frame that draws again.
     */
    private fun applyStage(
        stage: QualityStage,
        target: QualityPreset,
    ) {
        guard.run("quality stage ${stage.id}") {
            when (stage.id) {
                QualityStageId.PIXEL_RATIO.id -> pendingPixelRatio = target.pixelRatio
                QualityStageId.CHARACTERS.id -> horse.setQuality(characterLevel(target))
                else -> world.applyQualityStage(stage.id, target)
            }
        }
        for (covered in stage.covers) applied[covered] = target
    }

    /** Everything at once, for a switch nobody sees or that nothing is drawn for. */
    private fun applyAllNow(gpu: Boolean) {
        stageQueue.clear()
        targetPreset = fitFor(level)
        guard.run("quality switch") {
            world.setQuality(targetPreset, gpu)
            horse.setQuality(characterLevel(targetPreset))
            if (gpu) {
                pendingPixelRatio = targetPreset.pixelRatio // the frame loop applies it
            } else {
                // The device is lost: nothing is shown, so the drawing buffer can change right away. A
                // restored device is created at the size the surface has by then, so it has to be the
                // new one already.
                pendingPixelRatio = Double.NaN
                host.sizing.setMaxPixelRatio(targetPreset.pixelRatio)
                host.resize(true)
            }
        }
        for (id in QUALITY_STAGE_IDS) applied[id] = targetPreset
        interrupt() // the switch is no measurement
        host.requestRedraw()
    }

    /**
     * Switches to a level. While a ride draws, the change is split into stages spread over several
     * frames, each followed by a shader precompile with the last picture staying on screen (see
     * [advanceStages]). Without a running frame loop (menus) or with a lost device nothing is
     * visible and nothing is drawn, so everything is applied at once.
     */
    private fun applyQuality(next: GraphicsLevel) {
        level = next
        upgrade.noteChange() // every change, up or down, is followed by a cooldown
        if (!host.running || host.contextLost) {
            applyAllNow(gpu = !host.contextLost)
            if (!host.contextLost) host.precompile()
            return
        }
        // every switch, also a manual "high", is checked against the budget
        targetPreset = fitFor(next)
        stageQueue.plan(planQualityStagesFromState(applied, targetPreset))
        host.requestRedraw()
    }

    /** Called after every drawn frame: starts the next stage when its time has come. */
    fun advanceStages() {
        val stage = stageQueue.tick() ?: return
        // the target is read now: a switch during the staging changes where the remaining stages go
        applyStage(stage, targetPreset)
        interrupt() // frames around a stage are slower: not a measurement
        if (stage.compile) host.precompile()
        host.requestRedraw()
    }

    /** The frame loop applies the waiting pixel ratio in the frame that draws again; false if none waits. */
    fun takePendingPixelRatio(): Boolean {
        if (pendingPixelRatio.isNaN()) return false
        val ratio = pendingPixelRatio
        pendingPixelRatio = Double.NaN
        guard.run("pixel ratio") {
            host.sizing.setMaxPixelRatio(ratio)
            host.resize(true)
        }
        return true
    }

    /** Frames around a stage, a pause or a menu are no measurement for either governor. */
    fun interrupt() {
        downgrade.interrupt()
        upgrade.interrupt()
    }

    /** The upgrade governor found room to spare: one level up, saved ("Automatic" stays on). */
    private fun climbTo(step: UpgradeStep) {
        lastChange = LevelChange(ChangeKind.UP, step.fps)
        applyQuality(step.level)
        downgrade.setLevel(step.level) // keeps the downgrade governor in step (no onChange)
        settings.setAutoLevel(step.level)
    }

    private fun step(
        dt: Double,
        measuring: Boolean,
        busy: Boolean,
    ) {
        val before = level
        downgrade.frame(dt, measuring)
        if (level != before) return // stepped down in this frame
        val climb = upgrade.frame(dt, upgradeMeasuring(measuring, downgrade.auto), busy)
        if (climb != null) climbTo(climb)
    }

    /**
     * One real frame for the level automatic: [measuring] = riding and the app visible, [busy] = a
     * jump or approach is in progress (the upgrade never starts a step then). With a [frameFeed] the
     * fed frame times replace the real ones.
     */
    fun frame(
        rawDt: Double,
        measuring: Boolean,
        busy: Boolean,
    ) {
        val feed = frameFeed
        if (feed == null) {
            step(rawDt, measuring, busy)
            return
        }
        for (i in 0 until feed.repeat) {
            step(feed.dt, measuring, busy)
            if (measuring) feed.fedSeconds += feed.dt
        }
    }

    /**
     * The device was lost. A loss in the foreground shows that the device is overloaded: the level
     * goes down right away, while nothing is drawn, so that the restored scene comes back already at
     * the new level and size. Returns true if the player should get the hint to pick a lower level.
     */
    fun onContextLost(
        visible: Boolean,
        sinceVisibilityChangeS: Double,
    ): Boolean {
        val decision = levelAfterContextLoss(settings.get().graphicsAuto, level, visible, sinceVisibilityChangeS)
        val changed = decision.level != level
        // a regular loss at this level: the automatic must not climb back to it (saved)
        if (decision.counted) host.crashGuard?.blockLevel(level)
        level = decision.level
        if (changed) {
            downgrade.setLevel(level)
            upgrade.noteChange()
            lastChange = LevelChange(ChangeKind.LOSS)
        }
        applyAllNow(gpu = false) // also finishes a switch that was still in stages
        if (decision.persist) settings.setAutoLevel(level) // "Automatic" stays on
        return decision.hint
    }

    /**
     * A ride starts drawing: nothing of it is on screen yet, so this is the time to finish a switch
     * that was still in stages, fit the level to the window (its size may have changed since the
     * level was applied) and give the textures the anisotropy of the level (an upload, never done in
     * the middle of a ride).
     */
    fun onRunStart() {
        if (planQualityStagesFromState(applied, fitFor(level)).isNotEmpty()) {
            applyAllNow(gpu = true)
            host.precompile()
        }
        guard.run("anisotropy") { world.syncAnisotropy(targetPreset) }
    }
}
