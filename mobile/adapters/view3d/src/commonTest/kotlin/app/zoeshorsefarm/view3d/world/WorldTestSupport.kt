package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.view3d.CourseLines
import app.zoeshorsefarm.view3d.LineLabels
import app.zoeshorsefarm.view3d.quality.QualityPreset
import app.zoeshorsefarm.view3d.quality.presetFor
import kotlin.math.PI

// Shared set-up of the world tests (the helpers of world-budget.test.js and world-stages.test.js).

internal fun el(
    id: String,
    kind: ElementKind,
    height: Double,
    x: Double,
    z: Double,
    rot: Double = 0.0,
    spread: Double = 0.0,
) = Element(id, kind, height, spread, x, z, rot)

private fun obstacle(
    number: Int,
    vararg elements: Element,
) = Obstacle(number, elements.toList(), directed = false)

/** A small course with every kind of element and a combination. */
internal val OBSTACLES =
    listOf(
        obstacle(1, el("a", ElementKind.VERTICAL, 0.8, -10.0, -20.0)),
        obstacle(2, el("b", ElementKind.OXER, 1.0, 10.0, -20.0, 0.0, 1.2)),
        obstacle(3, el("c", ElementKind.CROSS, 0.6, 10.0, 0.0, PI)),
        obstacle(
            4,
            el("d1", ElementKind.VERTICAL, 0.9, -10.0, 8.0),
            el("d2", ElementKind.OXER, 1.0, -10.0, 14.0, 0.0, 1.4),
        ),
        obstacle(5, el("e", ElementKind.OXER, 1.1, 0.0, 25.0, PI / 2, 1.5)),
    )

/** Stand rows: vertical 1, oxer 2, cross 1, vertical 1 + oxer 2, oxer 2 = 9 rows, two stands each. */
internal const val STANDS = 18

internal val LINES =
    CourseLines(
        start = Line(Vec2(-3.0, -30.0), Vec2(3.0, -30.0), Vec2(0.0, 1.0)),
        finish = Line(Vec2(-3.0, 30.0), Vec2(3.0, 30.0), Vec2(0.0, 1.0)),
        labels = LineLabels(start = "S", finish = "F"),
    )

internal val LEVELS = listOf(GraphicsLevel.LOW, GraphicsLevel.MEDIUM, GraphicsLevel.HIGH)

/** The preset of a level without the environment light (the tests look at the scene, not at a picture). */
internal fun testPreset(level: GraphicsLevel): QualityPreset = presetFor(level).copy(envMap = false)

internal fun newCamera() = PerspectiveCamera()

internal fun buildWorld(
    backend: FakeRenderBackend = FakeRenderBackend(),
    quality: QualityPreset = presetFor(GraphicsLevel.LOW),
): World {
    val world = World(backend, quality)
    world.setObstacles(OBSTACLES, flags = true)
    world.setLines(LINES)
    return world
}

private val shownLevel = HashMap<World, GraphicsLevel>() // world to the level the helper has shown last

/**
 * The stages of a level change in the order of the engine: a downgrade goes shadows, materials,
 * density; a climb the other way round.
 */
internal fun showLevel(
    world: World,
    level: GraphicsLevel,
    camera: PerspectiveCamera = newCamera(),
) {
    val preset = testPreset(level)
    val down = level.ordinal < (shownLevel[world] ?: GraphicsLevel.LOW).ordinal
    val order = if (down) listOf("shadows", "materials", "density") else listOf("density", "materials", "shadows")
    for (id in order) world.applyQualityStage(id, preset)
    shownLevel[world] = level
    world.update(0.016, camera)
}

internal fun visibleNames(world: World): Set<String> {
    val names = LinkedHashSet<String>()
    world.scene.traverseVisible { if (it is Mesh && it.name.isNotEmpty()) names.add(it.name) }
    return names
}
