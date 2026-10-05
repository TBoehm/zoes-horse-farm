package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.Obstacle
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Usage
import app.zoeshorsefarm.scene.graph.Camera
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
import app.zoeshorsefarm.scene.texture.BlockTextRasterizer
import app.zoeshorsefarm.scene.texture.TextRasterizer
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.GeometryBuilder
import app.zoeshorsefarm.view3d.LabelAtlas
import app.zoeshorsefarm.view3d.LabelItem
import app.zoeshorsefarm.view3d.PLANTER_BOX_HEIGHT
import app.zoeshorsefarm.view3d.POLE_GEOM_LENGTH
import app.zoeshorsefarm.view3d.STAND_X
import app.zoeshorsefarm.view3d.buildPlanterGeometry
import app.zoeshorsefarm.view3d.createLabelAtlas
import app.zoeshorsefarm.view3d.createWind
import app.zoeshorsefarm.view3d.highlightText
import app.zoeshorsefarm.view3d.labelOf
import app.zoeshorsefarm.view3d.patchBlossoms
import app.zoeshorsefarm.view3d.polesOf
import app.zoeshorsefarm.view3d.releaseNow
import app.zoeshorsefarm.view3d.standRows

// Obstacle meshes: stands, striped poles (instanced, visibly falling), fillers, direction flags,
// number boards and the highlight of the obstacle that is due next.

private const val MAX_POLES = 128
private const val MAX_PLANTERS = 128 // flower boxes: two per stand row
private const val PLANTER_OFFSET = 0.12 // box centre outside the centre of the stand (m)

// obstacle colors (pole stripes, stand sections, plank)
private val OBSTACLE_COLORS = listOf(0xc62828, 0x1e56b8, 0x2e7d32, 0xef8f00, 0x6a3fa0, 0x00838f)

// blossom colors of the flower boxes: one per obstacle, so that its boxes match
private val PLANTER_COLORS = listOf(0xe9719f, 0xf2cf2e, 0xd8453b, 0x8a5bc8, 0xf08a3c, 0x4f7fe0)

/** What the manager knows about an element: its obstacle, its label, its colour and its poles by rail. */
private class ElementInfo(
    val element: Element,
    val obstacle: Obstacle,
    val index: Int,
    val label: String?,
    val color: Int,
) {
    /** Poles per rail index (null where no rail has that index); iterated without allocating. */
    val railLists = ArrayList<MutableList<Pole>?>()
}

/** The hash of an element id that seeds its random stream (FNV-1a as in the web app). */
private fun hashId(id: String): Int {
    var h = 2166136261L.toInt()
    for (ch in id) h = (h xor ch.code) * 16777619
    return h
}

/**
 * The obstacles of the course. [materialFactory] makes `{ standard, lambert }` pairs; [wind] moves
 * the flowers of the flower boxes; [textRasterizer] draws the numbers. [setDecor] shows or hides the
 * flower boxes at the stands.
 */
class Obstacles(
    materialFactory: MaterialFactory,
    private val release: (GpuObject?) -> Unit = ::releaseNow,
    wind: Wind = createWind(),
    private val textRasterizer: TextRasterizer = BlockTextRasterizer,
) {
    val group = Group().also { it.name = "obstacles" }
    private val staticMats = materialFactory("obstacle-static", MaterialParams(vertexColors = true, roughness = 0.55))
    private val poleMats = materialFactory("poles", MaterialParams(color = 0xffffff, roughness = 0.45))
    private val boardMats = materialFactory("boards", MaterialParams(roughness = 0.6))
    private val poleGeoms = buildPoleGeometries(10)
    private val whitePoles = InstancedMesh(poleGeoms.white, poleMats.standard, MAX_POLES)
    private val colorPoles = InstancedMesh(poleGeoms.colored, poleMats.standard, MAX_POLES)
    private val planterMats =
        materialFactory("planters", MaterialParams(vertexColors = true, roughness = 0.85, side = Side.DOUBLE))
    private val planterGeometry = buildPlanterGeometry()
    private val planters = InstancedMesh(planterGeometry, planterMats.standard, MAX_PLANTERS)
    private var decor = false

    private var staticMesh: Mesh? = null
    private var boardMesh: Mesh? = null
    private var atlas: LabelAtlas? = null
    private var poles = ArrayList<Pole>()
    private val elements = LinkedHashMap<String, ElementInfo>()
    private val elementList = ArrayList<ElementInfo>()
    private val highlight = Highlight(release, textRasterizer)

    // the highlight that is currently built (compared field by field: no key string per frame)
    private var shownId: String? = null
    private var shownNumber: Int? = null

    /** The managed meshes: static parts, white and coloured poles, number boards, flower boxes. */
    val meshes =
        listOf(
            ManagedMesh(null, staticMats, ShadowRole.OBSTACLES),
            ManagedMesh(whitePoles, poleMats, ShadowRole.OBSTACLES),
            ManagedMesh(colorPoles, poleMats, ShadowRole.OBSTACLES),
            ManagedMesh(null, boardMats, ShadowRole.NONE),
            ManagedMesh(planters, planterMats, ShadowRole.NONE, detail = true),
        )

    private val mat = Mat4()
    private val one = Vec3(1.0, 1.0, 1.0)
    private val wq = Quat()
    private val wp = Vec3()

    init {
        for (m in listOf(whitePoles, colorPoles)) {
            m.count = 0
            m.frustumCulled = false
            m.castShadow = true
            m.receiveShadow = true
            m.instanceMatrix.setUsage(Usage.DYNAMIC_DRAW)
            group.add(m)
        }
        whitePoles.name = "poles-white"
        colorPoles.name = "poles-colored"
        colorPoles.setColorAt(0, Color(1.0, 1.0, 1.0))

        // flower boxes at the feet of the stands: one instanced mesh, kept for the life of the manager
        // (the instances are rewritten with every course); hidden while `decor` is off.
        // The wooden box does not bend, only the plants above it.
        patchBlossoms(planterMats.standard, wind, PLANTER_BOX_HEIGHT)
        patchBlossoms(planterMats.lambert, wind, PLANTER_BOX_HEIGHT)
        planters.name = "planters"
        planters.count = 0
        planters.frustumCulled = false
        planters.setColorAt(0, Color(1.0, 1.0, 1.0))
        planters.receiveShadow = false
        group.add(planters)
        group.add(highlight.group)
    }

    private fun writePole(pole: Pole) {
        val el = pole.element
        wq.setFromAxisAngle(Node.DEFAULT_UP, el.rot)
        wp.copy(pole.pos).applyQuaternion(wq)
        wp.x += el.x
        wp.z += el.z
        wq.multiply(pole.quat)
        one.set(pole.length / POLE_GEOM_LENGTH, 1.0, 1.0)
        mat.compose(wp, wq, one)
        whitePoles.setMatrixAt(pole.index, mat)
        colorPoles.setMatrixAt(pole.index, mat)
    }

    private fun clear() {
        staticMesh?.let {
            group.remove(it)
            release(it.geometry)
        }
        staticMesh = null
        boardMesh?.let {
            group.remove(it)
            release(it.geometry)
            boardMats.standard.map = null
            boardMats.lambert.map = null
        }
        boardMesh = null
        release(atlas?.texture)
        atlas = null
        elements.clear()
        elementList.clear()
        poles = ArrayList()
        whitePoles.count = 0
        colorPoles.count = 0
        planters.count = 0
        planters.visible = false
        highlight.hide()
        shownId = null
        shownNumber = null
    }

    /** One flower box at each foot of every stand row, in the blossom colour of its obstacle. */
    private fun placePlanters(obstacles: List<Obstacle>) {
        var n = 0
        val c = Color()
        for ((oi, obstacle) in obstacles.withIndex()) {
            c.set(PLANTER_COLORS[oi % PLANTER_COLORS.size])
            for (element in obstacle.elements) n = placeElementPlanters(element, c, n)
        }
        planters.count = n
        planters.visible = decor && n > 0
        planters.instanceMatrix.needsUpdate = true
        planters.instanceColor?.needsUpdate = true
    }

    /** The boxes of one element from instance [first] on (up to the limit); returns the next free instance. */
    private fun placeElementPlanters(
        element: Element,
        color: Color,
        first: Int,
    ): Int {
        var n = first
        val base = localMatrix(element)
        val m = Mat4()
        val local = Mat4()
        for (z in standRows(element)) {
            for (sx in listOf(-1.0, 1.0)) {
                if (n >= MAX_PLANTERS) return n
                local.makeTranslation(sx * (STAND_X + PLANTER_OFFSET), 0.0, z)
                m.multiplyMatrices(base, local)
                planters.setMatrixAt(n, m)
                planters.setColorAt(n, color)
                n += 1
            }
        }
        return n
    }

    /** Builds the stands, poles and boards of a course (nothing for an empty list). */
    fun setObstacles(
        obstacles: List<Obstacle>,
        flags: Boolean = false,
    ) {
        clear()
        val builder = GeometryBuilder()
        val labels = ArrayList<Pair<Element, String>>()
        val c = Color()
        obstacles.forEachIndexed { oi, obstacle ->
            val color = OBSTACLE_COLORS[oi % OBSTACLE_COLORS.size]
            obstacle.elements.forEachIndexed { ei, element ->
                val label = labelOf(obstacle, ei)
                addElementStatic(builder, element, color, flags, board = label != null)
                if (label != null) labels.add(element to label)
                val info = ElementInfo(element, obstacle, ei, label, color)
                val rng = createRng(hashId(element.id))
                for (def in polesOf(element)) {
                    if (poles.size >= MAX_POLES) break
                    val pole = Pole(def, element, poles.size, rng)
                    poles.add(pole)
                    writePole(pole)
                    colorPoles.setColorAt(pole.index, c.set(color))
                    if (def.rail >= 0) {
                        while (info.railLists.size <= def.rail) info.railLists.add(null)
                        val list = info.railLists[def.rail] ?: ArrayList<Pole>().also { info.railLists[def.rail] = it }
                        list.add(pole)
                    }
                }
                elements[element.id] = info
                elementList.add(info)
            }
        }
        whitePoles.count = poles.size
        colorPoles.count = poles.size
        placePlanters(obstacles)
        whitePoles.instanceMatrix.needsUpdate = true
        colorPoles.instanceMatrix.needsUpdate = true
        colorPoles.instanceColor?.needsUpdate = true

        val mesh = Mesh(builder.build(), staticMats.standard)
        mesh.name = "obstacle-static"
        mesh.castShadow = true
        mesh.receiveShadow = true
        group.add(mesh)
        staticMesh = mesh
        meshes[STATIC].mesh = mesh
        addBoards(labels)
    }

    /** The number boards of the labelled elements (one atlas for the distinct labels). */
    private fun addBoards(labels: List<Pair<Element, String>>) {
        if (labels.isEmpty()) {
            meshes[BOARDS].mesh = null
            return
        }
        val unique = labels.map { it.second }.distinct()
        val labelAtlas =
            createLabelAtlas(unique.map { LabelItem(it, drawNumber(it)) }, textRasterizer, cellW = 128, cellH = 112)
        atlas = labelAtlas
        val boards = GeometryBuilder()
        for ((element, label) in labels) {
            val rect = checkNotNull(labelAtlas.rects[label]) { "no cell for the label $label" }
            boards.addPainted(makeSignQuad(0.6, 0.5, rect, 0.017), boardMatrix(element))
        }
        boardMats.standard.map = labelAtlas.texture
        boardMats.lambert.map = labelAtlas.texture
        boardMats.standard.needsUpdate = true
        boardMats.lambert.needsUpdate = true
        val mesh = Mesh(boards.build(), boardMats.standard)
        mesh.name = "number-boards"
        group.add(mesh)
        boardMesh = mesh
        meshes[BOARDS].mesh = mesh
    }

    private fun syncElementRails(
        info: ElementInfo,
        states: BooleanArray,
        fallDirs: Map<String, Int>?,
        approachDirs: Map<String, Int>?,
    ) {
        val id = info.element.id
        val lists = info.railLists
        for (railIndex in lists.indices) {
            val list = lists[railIndex] ?: continue
            val up = railIndex >= states.size || states[railIndex]
            for (i in list.indices) {
                val pole = list[i]
                if (up && !pole.isUp) {
                    pole.startRise()
                } else if (!up && pole.isUp) {
                    val side =
                        fallDirs?.get(id)?.takeIf { it != 0 }
                            ?: approachDirs?.get(id)?.takeIf { it != 0 }
                            ?: (if (pole.rng() < 0.5) -1 else 1)
                    pole.startFall(if (side < 0) -1 else 1)
                }
            }
        }
    }

    /**
     * [rails]: element id to the state of its rails (true = up; a missing entry counts as up).
     * [fallDirs] / [approachDirs]: optional element id to the side to fall to along n (+-1; fallDirs
     * wins). Runs every frame: no allocations.
     */
    fun syncRails(
        rails: Map<String, BooleanArray>?,
        dt: Double = 0.0,
        fallDirs: Map<String, Int>? = null,
        approachDirs: Map<String, Int>? = null,
    ) {
        if (rails != null) {
            for (e in elementList.indices) {
                val info = elementList[e]
                val states = rails[info.element.id] ?: continue
                syncElementRails(info, states, fallDirs, approachDirs)
            }
        }
        var dirty = false
        for (i in poles.indices) {
            val pole = poles[i]
            if (pole.step(dt)) {
                writePole(pole)
                dirty = true
            }
        }
        if (dirty) {
            whitePoles.instanceMatrix.needsUpdate = true
            colorPoles.instanceMatrix.needsUpdate = true
        }
    }

    fun getElement(id: String): Element? = elements[id]?.element

    /** Shows or hides the flower boxes at the stands (the detail levels medium and high). */
    fun setDecor(on: Boolean) {
        decor = on
        planters.visible = decor && planters.count > 0
    }

    /** Highlights an element (null: none) with the number [number] on its badge. */
    fun highlight(
        elementId: String?,
        number: Int? = null,
    ) {
        // called every frame: only rebuild the ring and badge when something changed
        if (elementId == shownId && (elementId == null || number == shownNumber)) return
        val info = if (elementId != null) elements[elementId] else null
        if (info == null) {
            highlight.hide()
            shownId = null
            shownNumber = null
            return
        }
        highlight.show(info.element, highlightText(info.obstacle, info.index, number))
        shownId = elementId
        shownNumber = number
    }

    fun update(
        dt: Double,
        camera: Camera?,
    ) {
        highlight.update(dt, camera)
    }

    fun dispose() {
        clear()
        highlight.dispose()
        poleGeoms.white.dispose()
        poleGeoms.colored.dispose()
        whitePoles.dispose()
        colorPoles.dispose()
        planterGeometry.dispose()
        planters.dispose()
    }

    private companion object {
        const val STATIC = 0
        const val BOARDS = 3
    }
}
