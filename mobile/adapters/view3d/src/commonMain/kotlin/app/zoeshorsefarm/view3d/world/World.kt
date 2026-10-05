package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.RideAid
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.domain.sim.Zone
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.DirectionalLight
import app.zoeshorsefarm.scene.graph.EnvironmentLight
import app.zoeshorsefarm.scene.graph.Fog
import app.zoeshorsefarm.scene.graph.HemisphereLight
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Sprite
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.graph.collectGpuObjects
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.MaterialParams
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Frustum
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Sphere
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.render.RenderBackend
import app.zoeshorsefarm.scene.texture.BlockTextRasterizer
import app.zoeshorsefarm.scene.texture.TextRasterizer
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.CourseLines
import app.zoeshorsefarm.view3d.DetailHold
import app.zoeshorsefarm.view3d.PADDOCK
import app.zoeshorsefarm.view3d.SITE
import app.zoeshorsefarm.view3d.createWind
import app.zoeshorsefarm.view3d.horse.GrazingHorses
import app.zoeshorsefarm.view3d.horse.PaddockArea
import app.zoeshorsefarm.view3d.horse.createGrazingHorses
import app.zoeshorsefarm.view3d.isOnArenaSand
import app.zoeshorsefarm.view3d.quality.MEDIUM_PRESET
import app.zoeshorsefarm.view3d.quality.MERGED_STAGE_ID
import app.zoeshorsefarm.view3d.quality.MaterialKind
import app.zoeshorsefarm.view3d.quality.QualityPreset
import app.zoeshorsefarm.view3d.quality.QualityStageId
import app.zoeshorsefarm.view3d.quality.ShadowCasters
import app.zoeshorsefarm.view3d.quality.TextureInfo
import app.zoeshorsefarm.view3d.quality.sameMaterialStage
import app.zoeshorsefarm.view3d.releaseNow
import app.zoeshorsefarm.view3d.setWindPatch
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

// The 3D world: lights, sky, arena, environment, obstacles and markings.
// Quality levels can be switched at runtime (the governor downgrades), stage by stage.

private const val SHADOW_HALF = 24.0 // half extent of the shadow camera (m)
private const val SUN_DISTANCE = 90.0
private const val ENV_INTENSITY = 0.8
private const val FLOOR_COLOR = 0x5d6e3e // green ground for the lower hemisphere of the sky light

// The horses are only animated while the paddock (a sphere around it) is in view
private val PADDOCK_SPHERE_RADIUS = hypot(PADDOCK.width, PADDOCK.depth) / 2 + 3

// Grazing horses in the paddock: different coats, a fixed seed (the same picture every time)
private val GRAZING_COATS = listOf(Coat.GREY, Coat.CHESTNUT)
private const val GRAZING_SEED = 31

// Weakest footfall that raises hoof dust (a step at the walk does not)
private const val DUST_MIN_STRENGTH = 0.3
private const val DUST_EDGE_MARGIN = 0.2 // m, no dust right at the fence

/** The aid parameters that are applied to the marker now (compared field by field, nothing is allocated). */
private class AidState {
    var shown = false
        private set
    private var elementId: String? = null
    private var dir = 0
    private var hasZone = false
    private var near = 0.0
    private var far = 0.0

    fun matches(
        id: String,
        direction: Int,
        zone: Zone?,
    ): Boolean = shown && elementId == id && dir == direction && sameZone(zone)

    private fun sameZone(zone: Zone?): Boolean =
        if (zone == null) !hasZone else hasZone && near == zone.near && far == zone.far

    fun remember(
        id: String,
        direction: Int,
        zone: Zone?,
    ) {
        shown = true
        elementId = id
        dir = direction
        hasZone = zone != null
        near = if (zone != null) zone.near else 0.0
        far = if (zone != null) zone.far else 0.0
    }

    fun forget() {
        shown = false
    }
}

/**
 * The 3D world on the scene model; the renderer ([backend]) is only asked for its limits (the
 * anisotropy) and told whether shadows are on (`shadowsEnabled`, part of every program). [release]
 * frees GPU objects that the world replaces while it runs (see `GpuEpoch`). [textRasterizer] draws
 * the texts (number boards, signs).
 * [quality] is the preset to start with.
 */
@Suppress("TooManyFunctions") // the facade of the view: one function per operation of the spec
class World(
    private val backend: RenderBackend,
    quality: QualityPreset = MEDIUM_PRESET,
    private val release: (GpuObject?) -> Unit = ::releaseNow,
    textRasterizer: TextRasterizer = BlockTextRasterizer,
) {
    val scene = Scene().also { it.background = Color(SkyColors.HORIZON) }
    private val pairs = ArrayList<MaterialPair>()
    private val materialFactory: MaterialFactory = { _, params ->
        val pair = MaterialPair(StandardMaterial(params), LambertMaterial(params), params.normalMap)
        pairs.add(pair)
        pair
    }

    // lights
    private val sunDirection = Vec3(-0.52, 0.74, -0.42).normalize()
    private val hemi = HemisphereLight(0xdde9f7, 0x7a6c50, 1.0)
    private val sun = DirectionalLight(0xfff0dc, 2.7)
    private val shadowFocus = Vec3(0.0, 0.0, 0.0)

    private val sky = Sky(sunDirection)

    // one wind moves the trees, the grass, the flowers, the bunting and the animals of all of them
    private val wind = createWind()
    private val arena = Arena(materialFactory, SITE.path, SITE.pathFence, wind)
    private val environment = Environment(materialFactory, wind = wind, release = release)
    private val obstacles = Obstacles(materialFactory, release, wind, textRasterizer)
    private val lines = CourseLinesView(materialFactory, release, textRasterizer)
    private val aid = AidMarker()

    // hoof dust over the sand; the pool follows the level (none on low)
    private val dust = Dust(DustQuality.LOW, release)

    // grazing horses of the paddock: built when a level has them (see syncGrazing)
    private var grazing: GrazingHorses? = null
    private var grazingCount = 0

    // image-based light from the sky (built lazily, freed on "low", rebuilt after a lost device)
    private var envLight: EnvironmentLight? = null

    // The preset each stage group has reached: a level change is applied stage by stage (see
    // QualityStages.kt), so the groups can be at different levels for a short time. Meshes that
    // are added later (obstacles, lines) get the state of the groups, not of a target level.
    private var shadowsPreset = MEDIUM_PRESET
    private var materialsPreset = MEDIUM_PRESET
    private var densityPreset = MEDIUM_PRESET

    // The optional details (flowers, tufts, birds, bunting, boxes, props) are not drawn while the
    // materials stage and the density stage are at different levels: the shaders of everything
    // visible change in between, and a detail that appears or disappears one stage later would be
    // compiled twice (see DetailHold.kt). Code that decides the visibility itself restores them
    // first (density stage, new obstacles) and calls applyMeshes after it.
    private val detailHold = DetailHold()

    // The meshes of the optional details whose GPU buffers were given back after they were hidden
    private val freedDetails = HashSet<Node>()

    // scratch objects of the view test (see paddockInView)
    private val viewMatrix = Mat4()
    private val viewFrustum = Frustum()
    private val paddockSphere = Sphere(Vec3(PADDOCK.x, 0.0, PADDOCK.z), PADDOCK_SPHERE_RADIUS)

    // The sun never moves: its light-space axes and the scratch vectors are made once
    private val lightFwd = sunDirection.clone().negate()
    private val lightRight = Vec3().crossVectors(lightFwd, Node.DEFAULT_UP).normalize()
    private val lightUp = Vec3().crossVectors(lightRight, lightFwd).normalize()
    private val snapped = Vec3()

    // approach direction per element (from setAid) so poles fall in jump direction
    private val approachDirs = HashMap<String, Int>()

    // aid parameters currently applied to the marker
    private val aidState = AidState()

    /**
     * What the engine compiles after a stage: the visible objects only. A walk of the whole scene
     * would also build the programs of every hidden mesh, i.e. the flowers, birds and flower boxes
     * of a level that does not show them. The root answers the walk with the visible objects, plus
     * the hoof dust of a level that has it (its points are hidden between two puffs, and the first
     * puff should not stall the frame with a compile). Pass it as the root to compile, with the real
     * scene as the target: `backend.compile(world.compileRoot, camera, world.scene)`.
     */
    val compileRoot: Traversable =
        object : Traversable {
            override fun traverse(callback: (Node) -> Unit) {
                scene.traverseVisible(callback)
                if (densityPreset.hoofDust) {
                    for (o in dust.obj.children) if (!o.visible) callback(o)
                }
            }

            // the lights are the target's (the real scene's), so there is nothing to add here
            // (they would count twice and the programs would be built for two sets of lights,
            // which the real render never uses)
            override fun traverseVisible(callback: (Node) -> Unit) = Unit
        }

    init {
        sun.shadow.camera.left = -SHADOW_HALF
        sun.shadow.camera.right = SHADOW_HALF
        sun.shadow.camera.top = SHADOW_HALF
        sun.shadow.camera.bottom = -SHADOW_HALF
        sun.shadow.camera.near = 10.0
        sun.shadow.camera.far = SUN_DISTANCE + 60
        sun.shadow.camera.updateProjectionMatrix()
        sun.shadow.bias = -0.0005
        sun.shadow.normalBias = 0.03
        scene.add(hemi)
        scene.add(sun, sun.target)
        scene.add(sky.group)
        scene.add(arena.group)
        scene.add(environment.group)
        scene.add(obstacles.group)
        scene.add(lines.group)
        scene.add(aid.mesh)
        scene.add(dust.obj)
        setQuality(quality)
        updateShadowCamera()
    }

    // --- GPU bookkeeping ------------------------------------------------------------------------

    private fun buildEnvironmentMap(): EnvironmentLight =
        envLight
            ?: EnvironmentLight(
                zenith = Color(SkyColors.ZENITH),
                horizon = Color(SkyColors.HORIZON),
                ground = Color(SkyColors.GROUND),
                sunColor = Color(SkyColors.SUN),
                sunDirection = sunDirection,
                floorColor = Color(FLOOR_COLOR),
            ).also { envLight = it }

    private fun disposeEnvironmentMap() {
        release(envLight)
        envLight = null
    }

    private fun disposeShadowMap() {
        val map = sun.shadow.map ?: return
        release(map)
        sun.shadow.map = null
    }

    /** All meshes with material pair and shadow role. */
    private fun managed(): List<ManagedMesh> =
        (arena.meshes + environment.meshes + obstacles.meshes + lines.meshes).filter { it.mesh != null }

    /**
     * Frees the GPU programs of materials (rule 4). A material keeps every program it has ever been
     * drawn with until it is disposed, so a switch to other shaders (material type, wind code, fog,
     * shadows) that only sets `needsUpdate` would hold the old and the new programs at the same
     * time. A disposed material is no problem to use again: its program is built anew with the
     * next compile.
     *
     * `needsUpdate` comes first: after a lost device `release` skips the dispose of the materials
     * that were on the lost device (see `GpuEpoch`), and the backend only notices a changed program
     * key, wind code or normal map through the material's version. After a dispose the extra
     * version bump does nothing.
     */
    private fun freePrograms(materials: Collection<Material>) {
        for (material in materials.toSet()) {
            material.needsUpdate = true
            release(material)
        }
    }

    private fun pairMaterials(): List<Material> = pairs.flatMap { listOf(it.standard, it.lambert) }

    /** Every material of the scene, also those of the horses, the rider and the dust. */
    private fun sceneMaterials(): Set<Material> {
        val found = LinkedHashSet<Material>(pairMaterials())
        scene.traverse { node ->
            when (node) {
                is Mesh -> node.forEachMaterial { found.add(it) }
                is Points -> found.add(node.material)
                is Sprite -> found.add(node.material)
                else -> Unit
            }
        }
        return found
    }

    /**
     * Gives the GPU buffers of detail meshes that are hidden for good back (flowers, birds, tufts,
     * flower boxes ... of a level that does not have them): hiding a mesh does not free its
     * geometry, and an instanced mesh keeps its instance buffers. A mesh that is only held back
     * for a moment (see DetailHold.kt) keeps them; one that is shown again is uploaded again.
     */
    private fun freeHiddenDetails() {
        for (e in managed()) {
            val mesh = e.mesh
            if (e.detail && mesh != null) freeIfHidden(mesh)
        }
    }

    private fun freeIfHidden(mesh: Mesh) {
        if (mesh.visible || detailHold.has(mesh)) {
            freedDetails.remove(mesh)
        } else if (freedDetails.add(mesh)) {
            release(mesh.geometry)
            if (mesh is InstancedMesh) release(mesh)
        }
    }

    private fun applyMeshes() {
        val lambert = materialsPreset.material == MaterialKind.LAMBERT
        val shadows = shadowsPreset.shadows
        val casters = shadowsPreset.shadowCasters
        for (e in managed()) {
            val mesh = e.mesh ?: continue
            mesh.material = if (lambert) e.mats.lambert else e.mats.standard
            val cast =
                shadows &&
                    (if (e.shadow == ShadowRole.ALL) casters == ShadowCasters.ALL else e.shadow == ShadowRole.OBSTACLES)
            mesh.castShadow = cast
            mesh.receiveShadow = shadows && e.shadow != ShadowRole.NONE
        }
        detailHold.sync(
            managed().filter { it.detail }.mapNotNull { it.mesh },
            sameMaterialStage(materialsPreset, densityPreset),
        )
        freeHiddenDetails()
    }

    // --- Stages ---------------------------------------------------------------------------------
    // Each stage reads what is really there and changes only that, so a stage can be repeated and
    // an interrupted switch can continue from any state.

    /** Shadow pass: the map is freed when it is not used (2048^2 depth target on "high"). */
    private fun applyShadowStage(p: QualityPreset) {
        shadowsPreset = p
        // the shadow code is part of every program: the old ones go before the new ones are built
        if (backend.shadowsEnabled != p.shadows) freePrograms(sceneMaterials())
        backend.shadowsEnabled = p.shadows
        sun.castShadow = p.shadows
        if (!p.shadows) {
            disposeShadowMap()
        } else if (sun.shadow.mapSize.x != p.shadowMapSize.toDouble()) {
            // the backend makes a new map on the first shadow pass
            sun.shadow.mapSize.set(p.shadowMapSize.toDouble(), p.shadowMapSize.toDouble())
            disposeShadowMap()
        }
        applyMeshes()
    }

    /**
     * Everything that changes shader programs: material type, normal maps, fog on/off, the wind
     * code and the environment light. `gpu = false` (the device is lost) leaves the light to
     * [restoreAfterContextLoss].
     */
    private fun applyMaterialStage(
        p: QualityPreset,
        gpu: Boolean,
    ) {
        // What changes, read from what is really there. Whatever is given back goes first (the
        // environment light, the programs of the shaders that are left), then the new state is
        // set up; nothing is built before the old is gone (rule 4).
        val stale = LinkedHashSet<Material>()
        val lambertNow = materialsPreset.material == MaterialKind.LAMBERT
        if (lambertNow != (p.material == MaterialKind.LAMBERT)) stale.addAll(pairMaterials())
        stale.addAll(retireSceneState(p))
        stale.addAll(retireWindAndNormalMaps(p))
        materialsPreset = p
        freePrograms(stale)
        applyMeshes()
        applyLights(p, gpu)
    }

    /**
     * Fog and the environment light are part of the program of every material in the scene: when
     * one of them comes or goes, the whole scene's materials are stale. What goes is detached here.
     */
    private fun retireSceneState(p: QualityPreset): Set<Material> {
        val stale = LinkedHashSet<Material>()
        if (!p.envMap && scene.environment != null) {
            stale.addAll(sceneMaterials())
            scene.environment = null
            disposeEnvironmentMap() // after it is detached; built again when going back up
        }
        if (p.fog == null && scene.fog != null) {
            stale.addAll(sceneMaterials())
            scene.fog = null
        }
        if (p.fog != null && scene.fog == null) stale.addAll(sceneMaterials())
        if (p.envMap && scene.environment == null) stale.addAll(sceneMaterials())
        return stale
    }

    /** The wind code of the scenery (high only) and the normal maps (the Lambert variant has none). */
    private fun retireWindAndNormalMaps(p: QualityPreset): Set<Material> {
        val stale = LinkedHashSet<Material>()
        // without the wind code the plain program is used again
        for (m in pairMaterials()) if (setWindPatch(m, p.wind)) stale.add(m)
        for (pair in pairs) {
            val normalMap = if (p.normalMaps) pair.normalMap else null
            if (pair.standard.normalMap !== normalMap) {
                pair.standard.normalMap = normalMap
                stale.add(pair.standard)
            }
        }
        return stale
    }

    /** Sky light (more of it without an environment light) and the fog object. */
    private fun applyLights(
        p: QualityPreset,
        gpu: Boolean,
    ) {
        if (p.envMap) {
            if (gpu) scene.environment = buildEnvironmentMap()
            scene.environmentIntensity = ENV_INTENSITY
            hemi.intensity = 0.6
        } else {
            hemi.intensity = 1.5
        }

        // fog: a new Fog object makes the backend rebuild every material, so only add it here (the
        // distances are the density stage's business)
        val fog = p.fog
        if (fog != null && scene.fog == null) scene.fog = Fog(SkyColors.HORIZON, fog.near, fog.far)
    }

    /**
     * Scenery: instance counts, geometry detail, the details of a level (flowers, birds, butterflies,
     * bunting, paddock, flower boxes, wind) and the fog distances. Meshes that appear here bring
     * their own shader programs; the engine compiles after this stage.
     */
    private fun applyDensityStage(p: QualityPreset) {
        detailHold.restore() // the code below decides what is visible
        densityPreset = p
        val fog = p.fog
        scene.fog?.let {
            if (fog != null) {
                it.near = fog.near
                it.far = fog.far
            }
        }
        environment.setDensity(
            p.envDensity,
            p.grassTufts,
            p.envDetail,
            EnvDetails(flowers = p.flowers, birds = p.birds, butterflies = p.butterflies, wind = p.wind),
        )
        arena.setDetail(p.decor)
        obstacles.setDecor(p.planters)
        val dustLevel =
            when {
                !p.hoofDust -> DustQuality.LOW
                p.level == GraphicsLevel.HIGH -> DustQuality.HIGH
                else -> DustQuality.MEDIUM
            }
        dust.setQuality(dustLevel)
        syncGrazing(p)
        applyMeshes() // holds the details back if the materials stage is not at this level yet
    }

    /**
     * The grazing horses of a level: made when the level has them, thrown away (and their GPU
     * objects released) when it has none, rebuilt when their number changes, otherwise only their
     * geometry detail follows the level.
     */
    private fun syncGrazing(p: QualityPreset) {
        val wanted = p.grazingHorses
        if (grazing != null && wanted != grazingCount) {
            grazing?.dispose()
            grazing = null
        }
        grazingCount = wanted
        if (wanted <= 0) return
        val existing = grazing
        if (existing != null) {
            existing.setQuality(p)
            return
        }
        val area =
            PaddockArea(PADDOCK.x, PADDOCK.z, PADDOCK.width, PADDOCK.depth, PADDOCK.rotation, planPaddockKeepOut())
        val horses =
            createGrazingHorses(
                area = area,
                quality = p,
                count = wanted,
                coats = GRAZING_COATS,
                rng = createRng(GRAZING_SEED),
                release = release,
            )
        scene.add(horses.group)
        grazing = horses
    }

    private fun paddockInView(camera: Camera?): Boolean {
        if (camera == null) return true
        viewMatrix.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse)
        return viewFrustum.setFromProjectionMatrix(viewMatrix).intersectsSphere(paddockSphere)
    }

    /**
     * Applies the world's anisotropic filtering of a level. Only call it while nothing is drawn (at
     * the start of a ride): the backend reads `texture.anisotropy` only when a texture is uploaded,
     * so a change needs `needsUpdate` and uploads the texture again, which is the most expensive
     * part of a level change. Textures whose value already fits are not touched.
     */
    fun syncAnisotropy(preset: QualityPreset) {
        val anisotropy = min(preset.anisotropy, backend.capabilities.maxAnisotropy)
        for (t in arena.textures + environment.textures) {
            if (t.anisotropy != anisotropy) {
                t.anisotropy = anisotropy
                t.needsUpdate = true
            }
        }
    }

    /**
     * One stage of a level change (`shadows`, `materials`, `shadowsAndMaterials`, `density`, see
     * QualityStages.kt; other ids are the engine's). The pixel ratio is the engine's business:
     * changing it clears the drawing buffer, which must not happen before the new shaders are
     * ready. Horse and rider are the engine's, too.
     */
    fun applyQualityStage(
        id: String,
        preset: QualityPreset,
    ) {
        // the merged stage is the shadow stage and the material stage in one go
        if (id == QualityStageId.SHADOWS.id || id == MERGED_STAGE_ID) applyShadowStage(preset)
        if (id == QualityStageId.MATERIALS.id || id == MERGED_STAGE_ID) applyMaterialStage(preset, true)
        if (id == QualityStageId.DENSITY.id) applyDensityStage(preset)
    }

    /**
     * Switches to a level in one go (creation, a level change while no ride is drawing, a lost
     * device): every stage and the anisotropy. Only what really differs is touched.
     */
    fun setQuality(
        next: QualityPreset,
        gpu: Boolean = true,
    ) {
        applyShadowStage(next)
        applyMaterialStage(next, gpu)
        applyDensityStage(next)
        syncAnisotropy(next)
    }

    /**
     * After a lost and restored graphics device. The backend recreates its own GPU state
     * (programs, textures, buffers of geometries, instanced meshes and the shadow-map target) from
     * the CPU data on the next render. Not restorable is what only lived on the GPU: the
     * environment light is the result of a render pass, so it comes back empty and must be built
     * again. The old one is only forgotten, not disposed (`release` knows that, see `GpuEpoch`).
     * The same goes for the shadow map, which the backend makes anew on the next shadow pass.
     */
    fun restoreAfterContextLoss() {
        disposeEnvironmentMap()
        disposeShadowMap()
        if (materialsPreset.envMap) scene.environment = buildEnvironmentMap()
    }

    private fun updateShadowCamera() {
        // snap to the shadow map texel grid (no shimmering while following)
        val size = if (sun.shadow.mapSize.x != 0.0) sun.shadow.mapSize.x else 1024.0
        val texel = SHADOW_HALF * 2 / size
        val r = shadowFocus.dot(lightRight)
        val u = shadowFocus.dot(lightUp)
        snapped
            .copy(shadowFocus)
            .addScaledVector(lightRight, floor(r / texel + 0.5) * texel - r)
            .addScaledVector(lightUp, floor(u / texel + 0.5) * texel - u)
        sun.target.position.copy(snapped)
        sun.position.copy(snapped).addScaledVector(sunDirection, SUN_DISTANCE)
        sun.target.updateMatrixWorld()
    }

    // --- Content --------------------------------------------------------------------------------

    /** Sets the obstacles of the course (an empty list clears them). */
    fun setObstacles(
        list: List<Obstacle>,
        flags: Boolean = false,
    ) {
        detailHold.restore()
        obstacles.setObstacles(list, flags)
        approachDirs.clear()
        aid.hide()
        aidState.forget()
        applyMeshes()
    }

    /** rails: element id to the state of its rails (true = up); fallDirs: optional element id to +-1. */
    fun syncRails(
        rails: Map<String, BooleanArray>?,
        dt: Double = 0.0,
        fallDirs: Map<String, Int>? = null,
    ) {
        obstacles.syncRails(rails, dt, fallDirs, approachDirs)
    }

    fun highlight(
        elementId: String?,
        number: Int? = null,
    ) {
        obstacles.highlight(elementId, number)
    }

    /** Shows the take-off aid of the ride (null hides it); the aid object of the session is read, not kept. */
    fun setAid(aid: RideAid?) {
        if (aid == null) setAid(null, 1, null) else setAid(aid.elementId, aid.dir, aid.zone)
    }

    /**
     * Shows the take-off aid: a band on the sand in front of the front rail of the element [elementId]
     * (null hides it). Called every frame with equal values most of the time: nothing is allocated
     * and nothing is placed then.
     */
    fun setAid(
        elementId: String?,
        dir: Int,
        zone: Zone?,
    ) {
        if (elementId == null) {
            if (aidState.shown) aid.hide()
            aidState.forget()
            return
        }
        // skip the placement when nothing changed
        if (aidState.matches(elementId, dir, zone)) return
        val element = obstacles.getElement(elementId)
        if (element == null) {
            aid.hide()
            aidState.forget()
            return
        }
        approachDirs[elementId] = if (dir < 0) -1 else 1
        aid.set(element, dir, zone)
        aidState.remember(elementId, dir, zone)
    }

    fun setLines(params: CourseLines?) {
        lines.set(params)
        applyMeshes()
    }

    fun setFinishMarked(on: Boolean) {
        lines.setFinishMarked(on)
    }

    /** Size and kind of the textures the world uploads, for the GPU memory estimate. */
    fun textureSizes(): List<TextureInfo> {
        val sizes = LinkedHashMap<Texture, TextureInfo>()
        for (pair in pairs) {
            pair.standard.map?.let { sizes[it] = TextureInfo(it.width, it.height, normal = false) }
            pair.normalMap?.let { sizes[it] = TextureInfo(it.width, it.height, normal = true) }
        }
        return sizes.values.toList()
    }

    fun setShadowFocus(
        x: Double,
        z: Double,
    ) {
        shadowFocus.set(x, 0.0, z)
        updateShadowCamera()
    }

    /**
     * A footfall at the world position (x, y, z) of the sand: hoof dust, if the footfall is strong
     * enough (not at the walk), on the sand of the arena (not on grass or the path) and the level
     * has dust. [strength]: 0..1 (see the horse's footfalls).
     */
    fun emitHoofDust(
        x: Double,
        y: Double,
        z: Double,
        strength: Double,
    ) {
        if (strength < DUST_MIN_STRENGTH || !isOnArenaSand(x, z, DUST_EDGE_MARGIN)) return
        dust.emit(x, y, z, strength)
    }

    fun update(
        dt: Double,
        camera: Camera?,
    ) {
        dust.update(dt)
        grazing?.let { if (paddockInView(camera)) it.update(dt) }
        sky.update(dt, camera)
        environment.update(dt)
        obstacles.update(dt, camera)
        lines.update(dt)
        aid.update(dt)
    }

    /** Everything that has GPU resources now; handed to `GpuEpoch.contextLost`. */
    fun gpuObjects(): List<GpuObject> = collectGpuObjects(scene, listOfNotNull(envLight))

    fun dispose() {
        grazing?.dispose()
        grazing = null
        dust.dispose()
        obstacles.dispose()
        lines.dispose()
        aid.dispose()
        sky.dispose()
        val textures = LinkedHashSet<Texture>()
        scene.traverse { node ->
            if (node is InstancedMesh) node.dispose()
            when (node) {
                is Mesh -> node.geometry.dispose()
                is Points -> node.geometry.dispose()
                else -> Unit
            }
        }
        for (pair in pairs) {
            for (m in listOf(pair.standard, pair.lambert)) {
                textures.addAll(m.textures())
                m.dispose()
            }
        }
        textures.forEach { it.dispose() }
        disposeEnvironmentMap()
        disposeShadowMap()
    }
}
