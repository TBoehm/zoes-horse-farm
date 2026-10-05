package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.horse.COATS
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.MARKINGS
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.math.Sphere
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.quality.QualityPreset
import app.zoeshorsefarm.view3d.releaseNow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// Grazing horses of the paddock (SRT-011): cheap horses without rider and tack that graze, look up
// now and then and walk a few slow steps. The behaviour is in GrazingLogic.kt (pure, tested).
//
//   val paddock = createGrazingHorses(
//       quality = GraphicsLevel.MEDIUM,       // or a QualityPreset (characterDetail)
//       area = PaddockArea(x, z, width, depth, rotation, avoid), // avoid: r = prop radius + bodyRadius
//       count = 2,
//       coats = listOf(Coat.CHESTNUT, Coat.BAY, Coat.GREY),      // coats to pick from (default: all)
//       rng = rng,                            // random numbers (default: seeded)
//       release = epoch::release,             // release hook of the GPU epoch
//       groundY = { x, z -> 0.0 },            // height of the grass (default: flat)
//   )
//   scene.add(paddock.group)
//   paddock.update(dt)                        // every frame
//   paddock.setQuality(GraphicsLevel.HIGH)    // geometry detail of the horses changes
//   paddock.dispose()
//
// A horse is planned by its body centre: the whole horse (nose to tail, any pose, turning on the
// spot) lies within GRAZING.bodyRadius of it, so it stays inside the fence and out of the props.
// The group origin (ground below the forelegs) is GRAZING.bodyOffset ahead of the centre.
//
// Level of detail: low and medium use the "low" horse (one draw call, about 3.2 k triangles each),
// high the "medium" horse (one draw call, about 8.4 k triangles). The horses cast no shadows and are
// culled when they are out of view. Wiring is left to the engine.

/** Detail of the horse model per graphics level (the paddock is far from the camera). */
private fun horseLevel(level: GraphicsLevel): GraphicsLevel =
    when (level) {
        GraphicsLevel.LOW, GraphicsLevel.MEDIUM -> GraphicsLevel.LOW
        GraphicsLevel.HIGH -> GraphicsLevel.MEDIUM
    }

// Bounding sphere of a horse in its own frame (it moves its head down to the grass, so the sphere
// is larger than the standing horse); the skinned mesh is culled with it
private const val BOUNDS_CENTER_Y = 1.0
private const val BOUNDS_RADIUS = 2.3
private const val MIN_SPACING = 2.5 // m between the horses at the start
private const val START_TRIES = 12
private const val STEP_MAX = 0.1 // s, longest time step of the animation
private const val WALK_MIN_SPEED = 0.15 // m/s: below this the horse stands
private const val PULL_BACK = 0.02 // share of the way to the middle per frame when a horse leaves the area
private const val SEED_RANGE = 1e9
private const val DEFAULT_SEED = 31

/**
 * The coats are dealt out one after the other from a random start, so the horses differ (as long
 * as there are enough coats).
 */
fun <T> dealCoats(
    coats: List<T>,
    count: Int,
    rng: () -> Double,
): List<T> {
    val first = floor(rng() * coats.size).toInt()
    return List(count) { i -> coats[(first + i) % coats.size] }
}

/** The grazing horses of a paddock: [group] holds one horse group per horse. */
class GrazingHorses internal constructor(
    startLevel: GraphicsLevel,
    private val area: PaddockArea,
    count: Int,
    coats: List<Coat>,
    private val rng: () -> Double,
    release: (GpuObject?) -> Unit,
    private val groundY: (Double, Double) -> Double,
) {
    val group = Group()
    private val grazers = ArrayList<Grazer>()
    private val horses = ArrayList<HorseView>()
    private var level = horseLevel(startLevel)
    private val state = Horse(gait = Gait.HALT)
    private val back = XZ()

    init {
        group.name = "paddock-horses"
        // start positions: spread over the paddock, apart from each other
        val starts = ArrayList<XZ>()
        repeat(count) { starts.add(pickStart(starts)) }
        val dealt = dealCoats(coats, starts.size, rng)
        starts.forEachIndexed { i, p ->
            val horseRng = createRng(floor(rng() * SEED_RANGE).toInt())
            val horse =
                createHorse(
                    coat = dealt[i],
                    marking = MARKINGS[floor(rng() * MARKINGS.size).toInt()],
                    quality = level,
                    rider = false,
                    tack = false,
                    castShadow = false,
                    release = release,
                    rng = horseRng,
                )
            horse.group.name = "paddock-horse-$i"
            cullByBounds(horse)
            group.add(horse.group)
            horses.add(horse)
            grazers.add(createGrazer(p.x, p.z, (rng() - 0.5) * 2 * PI, rng))
            place(i)
        }
        update(0.0) // (the horses stand at their spots before the first frame)
    }

    private fun randomSpot(): XZ =
        toWorld(
            area,
            (rng() - 0.5) * (area.width - 2 * GRAZING.margin),
            (rng() - 0.5) * (area.depth - 2 * GRAZING.margin),
        )

    private fun isFree(p: XZ): Boolean = area.avoid?.none { hypot(p.x - it.x, p.z - it.z) < it.r } ?: true

    private fun pickStart(starts: List<XZ>): XZ {
        var best: XZ? = null
        var bestGap = -1.0
        var tries = 0
        var spaced = false
        while (tries < START_TRIES && !spaced) {
            tries++
            val p = randomSpot()
            if (!isFree(p)) continue
            var gap = Double.POSITIVE_INFINITY
            for (s in starts) gap = min(gap, hypot(s.x - p.x, s.z - p.z))
            if (gap > bestGap) {
                best = p
                bestGap = gap
            }
            spaced = gap >= MIN_SPACING
        }
        // (no free try: the middle of the paddock, the update moves the horse on)
        return best ?: toWorld(area, 0.0, 0.0)
    }

    /** Puts the group of horse [i] at its grazer: the origin is ahead of the body centre. */
    private fun place(i: Int) {
        val g = grazers[i]
        val x = g.x + sin(g.heading) * GRAZING.bodyOffset
        val z = g.z + cos(g.heading) * GRAZING.bodyOffset
        horses[i].group.position.set(x, groundY(x, z), z)
        horses[i].group.rotation.y = g.heading
    }

    /** Moves and animates the horses by [dt] seconds. */
    fun update(dt: Double) {
        val step = min(dt, STEP_MAX)
        for (i in grazers.indices) {
            val g = grazers[i]
            stepGrazer(g, step, area, grazers, rng)
            // keep the whole horse in the paddock whatever happens
            if (!insideArea(area, g.x, g.z, GRAZING.bodyRadius)) {
                toWorld(area, 0.0, 0.0, back)
                g.x += (back.x - g.x) * PULL_BACK
                g.z += (back.z - g.z) * PULL_BACK
            }
            place(i)
            state.gait = if (g.speed >= WALK_MIN_SPEED) Gait.WALK else Gait.HALT
            state.speed = g.speed
            state.turnRate = g.turnRate
            horses[i].update(step, state, g.graze)
        }
    }

    /** Changes the geometry detail of the horses (low and medium share the low horse). */
    fun setQuality(next: GraphicsLevel) {
        val wanted = horseLevel(next)
        if (wanted == level) return
        level = wanted
        for (horse in horses) horse.setQuality(level)
    }

    /** Same as [setQuality] for a preset: its `characterDetail` decides. */
    fun setQuality(preset: QualityPreset) = setQuality(GraphicsLevel.fromId(preset.characterDetail.id) ?: preset.level)

    fun dispose() {
        for (horse in horses) horse.dispose()
        horses.clear()
        grazers.clear()
        group.removeFromParent()
    }
}

/** Lets the skinned meshes of a horse be culled with a fixed bounding sphere. */
private fun cullByBounds(horse: HorseView) {
    horse.group.traverse {
        if (it is SkinnedMesh) {
            it.frustumCulled = true
            it.boundingSphere = Sphere(Vec3(0.0, BOUNDS_CENTER_Y, 0.0), BOUNDS_RADIUS)
        }
    }
}

/**
 * Creates the horses of a paddock: [count] horses in [area], with coats dealt from [coats], at the
 * detail of [quality]. [groundY] gives the height of the grass.
 */
fun createGrazingHorses(
    area: PaddockArea,
    quality: GraphicsLevel = GraphicsLevel.MEDIUM,
    count: Int = 2,
    coats: List<Coat> = COATS,
    rng: () -> Double = createRng(DEFAULT_SEED),
    release: (GpuObject?) -> Unit = ::releaseNow,
    groundY: (Double, Double) -> Double = { _, _ -> 0.0 },
): GrazingHorses = GrazingHorses(quality, area, count, coats, rng, release, groundY)

/** Like the level variant; the `characterDetail` of the preset decides about the horse model. */
fun createGrazingHorses(
    area: PaddockArea,
    quality: QualityPreset,
    count: Int = 2,
    coats: List<Coat> = COATS,
    rng: () -> Double = createRng(DEFAULT_SEED),
    release: (GpuObject?) -> Unit = ::releaseNow,
    groundY: (Double, Double) -> Double = { _, _ -> 0.0 },
): GrazingHorses =
    GrazingHorses(
        GraphicsLevel.fromId(quality.characterDetail.id) ?: quality.level,
        area,
        count,
        coats,
        rng,
        release,
        groundY,
    )
