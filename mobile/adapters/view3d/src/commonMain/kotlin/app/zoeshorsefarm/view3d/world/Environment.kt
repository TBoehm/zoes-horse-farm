package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.material.MaterialParams
import app.zoeshorsefarm.scene.material.Side
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.GeometryBuilder
import app.zoeshorsefarm.view3d.SITE
import app.zoeshorsefarm.view3d.butterflyAnchors
import app.zoeshorsefarm.view3d.createGrassTexture
import app.zoeshorsefarm.view3d.createWind
import app.zoeshorsefarm.view3d.instanceCount
import app.zoeshorsefarm.view3d.isBlocked
import app.zoeshorsefarm.view3d.jsRound
import app.zoeshorsefarm.view3d.patchBushWind
import app.zoeshorsefarm.view3d.patchTreeWind
import app.zoeshorsefarm.view3d.patchTuftWind
import app.zoeshorsefarm.view3d.quality.Detail
import app.zoeshorsefarm.view3d.releaseNow
import app.zoeshorsefarm.view3d.scatter
import app.zoeshorsefarm.view3d.terrainHeight
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Surroundings of the riding facility: meadow with hills, trees, bushes, grass tufts and flowers
// (instanced, moving in the wind), birds and butterflies, stable, judges' hut and props. All
// procedural. The random numbers are drawn in the order of the web app, so the scenery is the same.

private const val TUFT_TOTAL = 11000 // grass tufts at full density
private const val BUTTERFLY_PATCHES = 7 // flower patches the butterflies hover over
private const val FOREST_TREES = 520

/** A plant to place: ground position and, when given, its scale (otherwise drawn at random). */
private class Plant(
    val x: Double,
    val z: Double,
    val scale: Double? = null,
)

private fun isBlockedTuft(
    x: Double,
    z: Double,
): Boolean = isBlocked(x, z, -1.3)

/** An instanced mesh whose visible share follows the level, with optional geometry for a low level. */
private class Scalable(
    val mesh: InstancedMesh,
    val total: Int,
    val priority: Int,
) {
    var high: Geometry = mesh.geometry
    var low: Geometry? = null
}

/** Range of the scale of a plant and the tint variation of its colour. */
private class Look(
    val scaleMin: Double = 0.85,
    val scaleMax: Double = 1.3,
    val tint: Double = 0.12,
)

private fun makeInstanced(
    geometry: Geometry,
    material: app.zoeshorsefarm.scene.material.Material,
    items: List<Plant>,
    rng: () -> Double,
    look: Look = Look(),
): InstancedMesh {
    val mesh = InstancedMesh(geometry, material, maxOf(1, items.size))
    val m = Mat4()
    val q = Quat()
    val s = Vec3()
    val p = Vec3()
    val c = Color()
    items.forEachIndexed { i, item ->
        val k = item.scale ?: (look.scaleMin + rng() * (look.scaleMax - look.scaleMin))
        val squash = 0.9 + rng() * 0.2
        p.set(item.x, terrainHeight(item.x, item.z) - 0.05, item.z)
        q.setFromAxisAngle(Node.DEFAULT_UP, rng() * PI * 2)
        s.set(k * squash, k, k * (2 - squash))
        mesh.setMatrixAt(i, m.compose(p, q, s))
        val tint = look.tint
        c.setRGB(1 - tint / 2 + rng() * tint, 1 - tint / 2 + rng() * tint, 1 - tint / 2 + rng() * tint * 0.6)
        mesh.setColorAt(i, c)
    }
    mesh.count = items.size
    mesh.instanceMatrix.needsUpdate = true
    mesh.instanceColor?.needsUpdate = true
    mesh.computeBoundingSphere()
    mesh.userData[USER_TOTAL] = items.size
    return mesh
}

private fun plantsOf(points: List<app.zoeshorsefarm.domain.sim.Vec2>): List<Plant> = points.map { Plant(it.x, it.z) }

private val NEAR_DECIDUOUS =
    listOf(
        Plant(-30.0, -30.0, 1.25),
        Plant(-28.0, 47.0, 1.35),
        Plant(31.0, 43.0, 1.2),
        Plant(33.0, -6.0, 1.4),
        Plant(37.0, -43.0, 1.3),
        Plant(-34.0, -52.0, 1.15),
        Plant(13.0, 53.0, 1.3),
        Plant(-11.0, -53.0, 1.25),
        Plant(40.0, 22.0, 1.1),
    )

private val CONIFER_NEAR =
    listOf(
        Plant(-62.0, 2.0, 1.2),
        Plant(-64.0, 30.0, 1.3),
        Plant(-57.0, 48.0, 1.1),
        Plant(-25.0, -60.0, 1.2),
        Plant(55.0, -40.0, 1.25),
        Plant(28.0, 64.0, 1.15),
    )

/** The shares of the details the density stage sets. */
class EnvDetails(
    val flowers: Double = 0.0,
    val birds: Double = 0.0,
    val butterflies: Double = 0.0,
    /** Trees, bushes, grass and flowers sway. */
    val wind: Boolean = false,
)

/**
 * Environment. [materialFactory] makes `{ standard, lambert }` pairs; [wind] is the wind of the whole
 * world (the arena and the obstacles share it). [release] frees a GPU object that the environment
 * replaces while it runs (see `GpuEpoch`). [setDensity] sets the instance counts per quality level.
 */
class Environment(
    materialFactory: MaterialFactory,
    seed: Int = 11,
    val wind: Wind = createWind(),
    private val release: (GpuObject?) -> Unit = ::releaseNow,
) {
    val group = Group().also { it.name = "environment" }
    val textures: List<Texture>
    val meshes: List<ManagedMesh>
    private val flowers: Meadow
    private val wildlife: Wildlife
    private val tufts: Scalable
    private val scalable: List<Scalable>

    init {
        val rng = createRng(seed)

        // terrain
        val grassMap = createGrassTexture(512)
        val groundMats =
            materialFactory(
                "grass",
                MaterialParams(map = grassMap, vertexColors = true, roughness = 1.0, metalness = 0.0),
            )
        val terrain = Mesh(buildTerrain(rng), groundMats.standard)
        terrain.name = "terrain"
        terrain.receiveShadow = true
        group.add(terrain)

        // trees and bushes: only the standard material sways, the Lambert one (low) stays free of the wind code
        val plantMats = materialFactory("plants", MaterialParams(vertexColors = true, roughness = 0.92))
        patchTreeWind(plantMats.standard, wind)
        val bushMats = materialFactory("bushes", MaterialParams(vertexColors = true, roughness = 0.92))
        patchBushWind(bushMats.standard, wind)

        val alley = ArrayList<Plant>()
        var az = -62.0
        while (az <= 62.0) {
            alley.add(Plant(47.0, az, 1.05 + rng() * 0.2))
            az += 12.4
        }
        val behindStable = ArrayList<Plant>()
        for (i in 0 until 9) {
            val x = -60 - rng() * 12
            behindStable.add(Plant(x, -4.0 + i * 6 + rng() * 3))
        }
        val deciduous = NEAR_DECIDUOUS + alley + behindStable + plantsOf(scatter(rng, 34, 45.0, 125.0, 4.0))
        val deciduousMesh = makeInstanced(deciduousGeometry(rng), plantMats.standard, deciduous, rng, Look(0.9, 1.5))
        deciduousMesh.name = "trees-deciduous"
        val deciduousScalable = Scalable(deciduousMesh, deciduous.size, NEAR_DECIDUOUS.size)
        deciduousScalable.low = deciduousGeometry(rng, 0)

        val coniferGeometry = coniferGeometry(rng)
        val coniferItems = CONIFER_NEAR + plantsOf(scatter(rng, 30, 60.0, 130.0, 4.0))
        val coniferMesh = makeInstanced(coniferGeometry, plantMats.standard, coniferItems, rng, Look(0.9, 1.5))
        coniferMesh.name = "trees-conifer"
        val coniferLow = coniferGeometry(rng, 0)
        val coniferScalable = Scalable(coniferMesh, coniferItems.size, CONIFER_NEAR.size)
        coniferScalable.low = coniferLow

        // forest edge on the hills (no shadows)
        val forestMesh = makeInstanced(coniferLow, plantMats.standard, forestItems(rng), rng)
        forestMesh.name = "forest"
        val forestScalable = Scalable(forestMesh, forestMesh.userData[USER_TOTAL] as Int, FOREST_PRIORITY)

        // bushes
        val bushItems = bushItems(rng, alley)
        val bushMesh =
            makeInstanced(bushGeometry(rng), bushMats.standard, bushItems.items, rng, Look(0.7, 1.4))
        bushMesh.name = "bushes"
        val bushScalable = Scalable(bushMesh, bushItems.items.size, minOf(bushItems.priority, 8))
        bushScalable.low = bushGeometry(rng, 0)

        // grass tufts (medium and high), denser near the fence
        val tuftMats =
            materialFactory("tufts", MaterialParams(vertexColors = true, roughness = 1.0, side = Side.DOUBLE))
        patchTuftWind(tuftMats.standard, wind)
        patchTuftWind(tuftMats.lambert, wind)
        // the places are drawn before the geometry, as in the web app
        val tuftPlaces = tuftItems(rng)
        val tuftMesh = makeInstanced(tuftGeometry(rng), tuftMats.standard, tuftPlaces, rng, Look(tint = 0.25))
        tuftMesh.name = "grass-tufts"
        tufts = Scalable(tuftMesh, tuftMesh.userData[USER_TOTAL] as Int, 0)

        // flowers in patches, birds in the sky and butterflies over the flowers (medium and high)
        flowers = Meadow(materialFactory, wind, seed)
        wildlife = Wildlife(materialFactory, wind, butterflyAnchors(flowers.patches, BUTTERFLY_PATCHES), seed)

        // buildings
        val buildingMats = materialFactory("buildings", MaterialParams(vertexColors = true, roughness = 0.85))
        val b = GeometryBuilder()
        addStable(b)
        addHut(b)
        addProps(b, rng)
        val buildings = Mesh(b.build(), buildingMats.standard)
        buildings.name = "buildings"
        buildings.castShadow = true
        buildings.receiveShadow = true

        for (m in listOf(deciduousMesh, coniferMesh)) {
            m.castShadow = true
            m.receiveShadow = true
        }
        forestMesh.receiveShadow = false
        group.add(deciduousMesh, coniferMesh, forestMesh, bushMesh, tuftMesh, buildings)
        group.add(flowers.mesh, wildlife.group)

        scalable = listOf(deciduousScalable, coniferScalable, forestScalable, bushScalable)
        textures = listOf(grassMap)
        meshes =
            listOf(
                ManagedMesh(terrain, groundMats, ShadowRole.RECEIVE),
                ManagedMesh(deciduousMesh, plantMats, ShadowRole.ALL),
                ManagedMesh(coniferMesh, plantMats, ShadowRole.ALL),
                ManagedMesh(forestMesh, plantMats, ShadowRole.NONE),
                ManagedMesh(bushMesh, bushMats, ShadowRole.NONE),
                ManagedMesh(tuftMesh, tuftMats, ShadowRole.NONE, detail = true),
                ManagedMesh(flowers.mesh, flowers.mats, ShadowRole.NONE, detail = true),
            ) + wildlife.meshes + ManagedMesh(buildings, buildingMats, ShadowRole.ALL)
    }

    /**
     * [density] 0..1 scales trees and bushes above the mandatory instances; [tufts] 0..1;
     * [detail] picks the geometry detail (low or the full models). [details]: the share of the
     * flowers, birds and butterflies and whether plants sway; everything off when not given.
     */
    fun setDensity(
        density: Double,
        tufts: Double,
        detail: Detail = Detail.HIGH,
        details: EnvDetails = EnvDetails(),
    ) {
        for (s in scalable) {
            val lowGeometry = s.low
            if (lowGeometry != null) {
                val next = if (detail == Detail.LOW) lowGeometry else s.high
                // the geometry of the level that is left gives its GPU buffers back right away
                if (next !== s.mesh.geometry) release(s.mesh.geometry)
                s.mesh.geometry = next
            }
            s.mesh.count = instanceCount(s.total, s.priority, density)
        }
        val tuftMesh = this.tufts.mesh
        tuftMesh.count = jsRound(this.tufts.total * tufts)
        tuftMesh.visible = tuftMesh.count > 0
        flowers.setDensity(details.flowers)
        wildlife.setDetail(details.birds, details.butterflies)
        wind.strength = if (details.wind) 1.0 else 0.0
    }

    fun update(dt: Double) {
        wind.time += dt
        wildlife.update(dt)
    }
}

private const val FOREST_PRIORITY = 60

/** Woods on the hills: grouped in bands. */
private fun forestItems(rng: () -> Double): List<Plant> {
    val items = ArrayList<Plant>()
    var i = 0
    while (items.size < FOREST_TREES && i < 6000) {
        i += 1
        val a = rng() * PI * 2
        val r = 135 + rng() * 130
        val x = cos(a) * r
        val z = sin(a) * r
        // grouped in bands so that woods appear
        if (sin(a * 7 + 1.3) + sin(a * 3) * 0.6 < -0.2) continue
        items.add(Plant(x, z, 1.6 + rng() * 1.4))
    }
    return items
}

private class BushItems(
    val items: List<Plant>,
    val priority: Int,
)

private fun bushItems(
    rng: () -> Double,
    alley: List<Plant>,
): BushItems {
    val items = ArrayList<Plant>()
    var z = SITE.stable.z - 14
    while (z <= SITE.stable.z + 14) {
        items.add(Plant(SITE.stable.x - 7, z, 0.9 + rng() * 0.4))
        z += 4.5
    }
    var x = -16.0
    while (x <= 16) {
        val bx = x + rng()
        val bz = -40.5 - rng()
        items.add(Plant(bx, bz, 0.9 + rng() * 0.5))
        x += 3.2
    }
    items.add(Plant(SITE.hut.x + 2.5, SITE.hut.z - 2.8, 1.1))
    items.add(Plant(SITE.hut.x + 2.2, SITE.hut.z + 3.4, 0.9))
    val priority = items.size
    for (p in NEAR_DECIDUOUS + alley) {
        val bx = p.x + 1.5 + rng() * 2
        val bz = p.z + rng() * 2 - 1
        items.add(Plant(bx, bz))
    }
    items.addAll(plantsOf(scatter(rng, 40, 30.0, 110.0, 2.0)))
    return BushItems(items, priority)
}

/** Grass tufts, denser near the fence. */
private fun tuftItems(rng: () -> Double): List<Plant> {
    val items = ArrayList<Plant>()
    var i = 0
    while (items.size < TUFT_TOTAL && i < TUFT_TOTAL * 12) {
        i += 1
        val near = rng() < 0.55
        val x = if (near) (rng() - 0.5) * 2 * 32 else (rng() - 0.5) * 2 * 75
        val z = if (near) (rng() - 0.5) * 2 * 46 else (rng() - 0.5) * 2 * 90
        if (isBlockedTuft(x, z)) continue
        items.add(Plant(x, z, 0.5 + rng() * 0.6))
    }
    return items
}
