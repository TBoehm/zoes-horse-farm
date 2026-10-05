package app.zoeshorsefarm.application

import app.zoeshorsefarm.application.modes.AidTarget
import app.zoeshorsefarm.application.modes.CourseMode
import app.zoeshorsefarm.application.modes.FreeMode
import app.zoeshorsefarm.application.modes.ModeHost
import app.zoeshorsefarm.application.modes.RideFinish
import app.zoeshorsefarm.application.modes.RideFrame
import app.zoeshorsefarm.application.modes.RideMode
import app.zoeshorsefarm.application.modes.RideModeId
import app.zoeshorsefarm.application.modes.RideStart
import app.zoeshorsefarm.application.testing.FIXED_ISO
import app.zoeshorsefarm.application.testing.FakeHost
import app.zoeshorsefarm.application.testing.FakeStore
import app.zoeshorsefarm.application.testing.FixedClock
import app.zoeshorsefarm.application.testing.seededRng
import app.zoeshorsefarm.domain.course.FREE_LAYOUT
import app.zoeshorsefarm.domain.course.Faults
import app.zoeshorsefarm.domain.course.RideResult
import app.zoeshorsefarm.domain.course.RunPhase
import app.zoeshorsefarm.domain.course.courseById
import app.zoeshorsefarm.domain.progress.CourseBest
import app.zoeshorsefarm.domain.progress.Progress
import app.zoeshorsefarm.domain.sim.COMBI_DISTANCE
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.GallopEndReason
import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Pose
import app.zoeshorsefarm.domain.sim.RefusalReason
import app.zoeshorsefarm.domain.sim.SimApproach
import app.zoeshorsefarm.domain.sim.SimEvent
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.domain.sim.TUNING
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.domain.sim.Zone
import app.zoeshorsefarm.domain.sim.approachInfo
import app.zoeshorsefarm.domain.sim.zoneForElement
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal const val STEP_DT = 1.0 / 60

/** One cross in the middle of the arena; the horse starts at the given distance in front of it. */
internal val cross = Element("c", ElementKind.CROSS, height = 0.45, spread = 0.0, x = 0.0, z = 0.0, rot = 0.0)
internal val crossObstacles = listOf(Obstacle(number = null, elements = listOf(cross), directed = false))

/** A free mode with parts replaced, like the JS tests spread `createFreeMode()` with overrides. */
internal class TestMode(
    private val base: RideMode = FreeMode(),
    override val obstacles: List<Obstacle> = base.obstacles,
    private val start: () -> RideStart = { base.startPose() },
    private val eventsHandler: ((List<SimEvent>, ModeHost) -> Unit)? = null,
    private val updateHandler: ((Double, RideFrame, ModeHost) -> RideFinish?)? = null,
    private val aidHandler: ((SimApproach?, Settings) -> AidTarget?)? = null,
    private val restartHandler: (() -> Unit)? = null,
) : RideMode by base {
    override fun startPose() = start()

    override fun onRestart() {
        restartHandler?.invoke()
        base.onRestart()
    }

    override fun onEvents(
        events: List<SimEvent>,
        host: ModeHost,
    ) {
        val handler = eventsHandler
        if (handler != null) handler(events, host) else base.onEvents(events, host)
    }

    override fun update(
        dt: Double,
        frame: RideFrame,
        host: ModeHost,
    ): RideFinish? {
        val handler = updateHandler
        return if (handler != null) handler(dt, frame, host) else base.update(dt, frame, host)
    }

    override fun aidTarget(
        approach: SimApproach?,
        settings: Settings,
    ): AidTarget? {
        val handler = aidHandler
        return if (handler != null) handler(approach, settings) else base.aidTarget(approach, settings)
    }
}

@Suppress("LongParameterList") // mirrors the optional overrides of the JS test helper
internal fun crossMode(
    distance: Double,
    speed: Double = 0.0,
    gallop: Boolean = false,
    obstacles: List<Obstacle> = crossObstacles,
    aid: ((SimApproach?, Settings) -> AidTarget?)? = null,
    onEvents: ((List<SimEvent>, ModeHost) -> Unit)? = null,
    start: (() -> RideStart)? = null,
) = TestMode(
    obstacles = obstacles,
    start = start ?: { RideStart(Pose(0.0, -distance, 0.0), speed, gallop) },
    aidHandler = aid,
    eventsHandler = onEvents,
)

internal class Setup(
    val store: FakeStore,
    val session: RideSession,
)

internal fun setup(
    mode: RideMode = FreeMode(),
    settings: Settings = Settings(),
    progress: Progress = Progress(),
    rng: Rng = seededRng(1),
): Setup {
    val store = FakeStore(settings = settings, progress = progress)
    return Setup(store, RideSession(mode, store, FixedClock(FIXED_ISO), rng))
}

internal class Collected {
    val events = mutableListOf<SimEvent>()
    val commands = mutableListOf<RideCommand>()
}

/** Steps until `done(out)` or the time is up; returns all commands and events collected. */
internal fun run(
    session: RideSession,
    maxT: Double = 8.0,
    done: (StepResult) -> Boolean = { false },
    input: (RideView) -> SimInput,
): Collected {
    val all = Collected()
    var t = 0.0
    while (t < maxT) {
        val out = session.step(STEP_DT, input(session.view))
        all.events.addAll(out.events)
        all.commands.addAll(out.commands)
        if (done(out)) break
        t += STEP_DT
    }
    return all
}

internal fun run(
    session: RideSession,
    input: SimInput,
    maxT: Double = 8.0,
    done: (StepResult) -> Boolean = { false },
) = run(session, maxT, done) { input }

/** Presses Space once, in the first step, and then rides on until the stop condition. */
internal fun pressOnce(
    session: RideSession,
    done: (StepResult) -> Boolean,
): Collected {
    var pressed = false
    return run(session, done = done) {
        val press = !pressed
        pressed = true
        SimInput(jump = press)
    }
}

internal val landedIn: (StepResult) -> Boolean = { out -> out.events.any { it is SimEvent.Landed } }
internal val railDownIn: (StepResult) -> Boolean = { out -> out.events.any { it is SimEvent.RailDown } }
internal val refusalIn: (StepResult) -> Boolean = { out -> out.events.any { it is SimEvent.Refusal } }

internal inline fun <reified T> List<*>.ofType() = filterIsInstance<T>()

internal fun Collected.sounds() = commands.ofType<RideCommand.Sound>().map { it.sound }

internal class Jumped(
    val ctx: Setup,
    val out: Collected,
)

/** Trot at the cross and press Space in the takeoff zone so it is jumped. */
internal fun jumpTheCross(
    progress: Progress = Progress(),
    rng: Rng = seededRng(1),
): Jumped {
    val z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING)
    val mode = crossMode(distance = z.near + 0.3, speed = TUNING.speeds.trotMax)
    val ctx = setup(mode, progress = progress, rng = rng)
    return Jumped(ctx, pressOnce(ctx.session, landedIn))
}

/** Jumps the cross very close to the takeoff limit with a rng that always knocks the rail. */
internal fun knockSetup(): Setup {
    val z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING)
    val mode = crossMode(distance = z.reach - 0.05, speed = TUNING.speeds.trotMax)
    return setup(mode, rng = { 0.0 })
}

internal fun freeNearCross() = TestMode(obstacles = crossObstacles, start = { RideStart(Pose(0.0, -10.0, 0.0)) })

internal val result =
    RideResult(
        courseId = 1,
        timeCs = 5000,
        faults = Faults(knockdowns = 0, refusals = 0, timeFaults = 0, total = 0),
        stars = 3,
        cleanOxer = false,
        cleanCombination = false,
    )

internal fun finishingMode() = TestMode(updateHandler = { _, _, _ -> RideFinish("results", result, 1) })

internal val a = Element("a", ElementKind.VERTICAL, height = 0.7, spread = 0.0, x = 0.0, z = 0.0, rot = 0.0)
internal val b = a.copy(id = "b", z = COMBI_DISTANCE)

internal fun comboMode() =
    TestMode(
        obstacles = listOf(Obstacle(null, listOf(a, b), directed = false)),
        start = { RideStart(Pose(0.0, -14.0, 0.0), TUNING.speeds.canterMedium, gallop = true) },
    )

/** Canter input: Space for each element in turn once its distance is below `aim(zone)`. */
internal fun pressInTurn(
    elements: List<Element>,
    aim: (Zone) -> Double = { (it.near + it.far) / 2 },
): (RideView) -> SimInput {
    val pressed = mutableSetOf<String>()
    return { view ->
        val horse = view.horse
        val el = elements.firstOrNull { it.id !in pressed }
        val info = el?.let { approachInfo(it, horse, TUNING.approachDistance) }
        val ready = el != null && info != null && horse.jump == null
        var input = SimInput(gallop = true)
        if (ready && info.approaching && info.distance <= aim(zoneForElement(el, horse.speed, TUNING))) {
            pressed.add(el.id)
            input = SimInput(gallop = true, jump = true)
        }
        input
    }
}

internal fun knockFrom(dir: Int): RideSession {
    val z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING)
    val reach = z.reach - 0.05
    val mode =
        crossMode(
            distance = reach,
            speed = TUNING.speeds.trotMax,
            start = { RideStart(Pose(0.0, -dir * reach, if (dir > 0) 0.0 else PI), TUNING.speeds.trotMax) },
        )
    val session = setup(mode, rng = { 0.0 }).session
    pressOnce(session, railDownIn)
    return session
}
