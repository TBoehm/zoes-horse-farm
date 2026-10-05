package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.CrashGuard
import app.zoeshorsefarm.application.CrashGuardState
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.LevelDecision
import app.zoeshorsefarm.application.RideSession
import app.zoeshorsefarm.application.Settings
import app.zoeshorsefarm.application.SettingsService
import app.zoeshorsefarm.application.modes.FreeMode
import app.zoeshorsefarm.application.testing.FIXED_ISO
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.FixedClock
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.view3d.quality.DeviceInfo
import app.zoeshorsefarm.view3d.quality.levelAfterContextLoss

// Shared set-up of the engine tests: an engine on the fake backend with a fake store, a crash guard
// and a clock the test moves by hand.

/** The fake backend whose shader compile can be held back to test the render gate. */
internal class DeferredBackend(
    val fake: FakeRenderBackend = FakeRenderBackend(),
) : RenderBackend by fake {
    var deferCompile = false
    val pending = ArrayList<() -> Unit>()

    override fun compile(
        root: Traversable,
        camera: Camera,
        scene: Scene,
        onComplete: () -> Unit,
    ) {
        if (deferCompile) {
            fake.compile(root, camera, scene)
            pending.add(onComplete)
        } else {
            fake.compile(root, camera, scene, onComplete)
        }
    }

    fun finishCompile() {
        val done = pending.toList()
        pending.clear()
        for (callback in done) callback()
    }
}

internal const val DT = 1.0 / 60

private val BIG_DEVICE = DeviceInfo(totalMemoryGiB = 16.0, isTouch = false, rendererName = "Test GPU")

internal class EngineRig(
    level: GraphicsLevel = GraphicsLevel.LOW,
    auto: Boolean = false,
    budgetMB: Int? = null,
    capTo30Fps: Boolean = false,
    deferCompile: Boolean = false,
    view: ViewSize = ViewSize(800.0, 400.0, 2.0),
    store: FakeStore = FakeStore(Settings(graphicsAuto = auto, graphicsLevel = level)),
    startupCrash: app.zoeshorsefarm.view3d.quality.StartupCrash? = null,
) {
    val store = store
    val backend = DeferredBackend().also { it.deferCompile = deferCompile }
    val settings = SettingsService(store)
    val crashGuard =
        CrashGuard(store, settings, FixedClock(FIXED_ISO), { a, l ->
            val d = levelAfterContextLoss(a, l)
            LevelDecision(d.level, d.persist, d.hint)
        })
    var now = 100.0
    val errors = ArrayList<String>()
    val engine =
        Engine(
            backend,
            settings,
            EngineConfig(
                view = view,
                device = BIG_DEVICE,
                gpuBudgetOverrideMB = budgetMB,
                startupCrash = startupCrash,
                crashGuard = crashGuard,
                capTo30Fps = capTo30Fps,
                clock = { now },
                log = { message, _ -> errors.add(message) },
            ),
        )
    val calls = ArrayList<DoubleArray>()
    var time = 0.0

    /** Starts the frame loop with a handler that records dt and rawDt. */
    fun run() {
        engine.run { dt, raw -> calls.add(doubleArrayOf(dt, raw)) }
    }

    /** [n] display frames of [step] seconds each. */
    fun frames(
        n: Int,
        step: Double = DT,
    ) {
        repeat(n) {
            time += step
            engine.frame(time)
        }
    }

    val fake get() = backend.fake
}

internal fun newSession() = RideSession(FreeMode(), FakeStore(), FixedClock(FIXED_ISO), seededRng(1))
