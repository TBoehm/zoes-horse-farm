package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.CylinderGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.IcosahedronGeometry
import app.zoeshorsefarm.scene.geometry.OctahedronGeometry
import app.zoeshorsefarm.scene.geometry.PlaneGeometry
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.material.MaterialParams
import app.zoeshorsefarm.scene.material.Side
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.GeometryBuilder
import app.zoeshorsefarm.view3d.PartTransform
import app.zoeshorsefarm.view3d.boxOnGround
import app.zoeshorsefarm.view3d.jitterVertices
import app.zoeshorsefarm.view3d.patchBunting
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

// Decoration of the arena and the paddock: bunting along the fence (fluttering in the wind), flower
// pots at the gate and the props in the paddock (field shelter, water trough, hay rack). Two
// meshes, hidden on the levels without `decor`.

// Direction the wind blows towards, for the side the pennants lean to
private val WIND_DIRECTION = Vec2(1.0, 0.4).normalize()
private const val STRING_COLOR = 0xe8e4d8
private const val STRING_WIDTH = 0.016
private const val STRING_PIECES = 4 // straight pieces per string

private fun rgb(hex: Int): DoubleArray {
    val c = Color(hex)
    return doubleArrayOf(c.r, c.g, c.b)
}

/**
 * Geometry of the whole bunting: first the strings, then the pennants (every second one first).
 * Drawing the first `stringVertices + 3 * n` vertices gives the strings and the first n pennants.
 */
class BuntingGeometry(
    val geometry: Geometry,
    val stringVertices: Int,
    val pennantCount: Int,
    val coreCount: Int,
)

private class BuntingArrays {
    val positions = ArrayList<Double>()
    val colors = ArrayList<Double>()
    val flutter = ArrayList<Double>()

    fun push(
        point: DoubleArray,
        color: DoubleArray,
        push3: DoubleArray,
    ) {
        point.forEach { positions.add(it) }
        color.forEach { colors.add(it) }
        push3.forEach { flutter.add(it) }
    }
}

private fun pointOn(
    s: BuntingString,
    t: Double,
): DoubleArray =
    doubleArrayOf(
        s.a.x + (s.b.x - s.a.x) * t,
        s.a.y - s.sag * 4 * t * (1 - t),
        s.a.z + (s.b.z - s.a.z) * t,
    )

/** Strings: thin vertical ribbons along the sagging curve. */
private fun addStrings(
    out: BuntingArrays,
    plan: BuntingPlan,
) {
    val still = doubleArrayOf(0.0, 0.0, 0.0)
    val stringColor = rgb(STRING_COLOR)
    for (s in plan.strings) {
        for (i in 0 until STRING_PIECES) {
            val p0 = pointOn(s, i.toDouble() / STRING_PIECES)
            val p1 = pointOn(s, (i + 1).toDouble() / STRING_PIECES)
            val q0 = doubleArrayOf(p0[0], p0[1] - STRING_WIDTH, p0[2])
            val q1 = doubleArrayOf(p1[0], p1[1] - STRING_WIDTH, p1[2])
            for (tri in listOf(listOf(p0, q0, p1), listOf(p1, q0, q1))) {
                for (point in tri) out.push(point, stringColor, still)
            }
        }
    }
}

/** Pennants: a triangle hanging from the string, flutters across its plane. */
private fun addPennants(
    out: BuntingArrays,
    plan: BuntingPlan,
) {
    val still = doubleArrayOf(0.0, 0.0, 0.0)
    for (p in plan.pennants) {
        val hw = p.width / 2
        val normal = Vec2(-p.tz, p.tx)
        val sign = if (normal.dot(WIND_DIRECTION) >= 0) 1.0 else -1.0
        val wave = doubleArrayOf(normal.x * sign, 0.0, normal.y * sign)
        val color = rgb(p.color)
        out.push(doubleArrayOf(p.x - p.tx * hw, p.y, p.z - p.tz * hw), color, still)
        out.push(doubleArrayOf(p.x + p.tx * hw, p.y, p.z + p.tz * hw), color, still)
        out.push(doubleArrayOf(p.x, p.y - p.length, p.z), color, wave)
    }
}

internal fun buildBuntingGeometry(plan: BuntingPlan = planBunting()): BuntingGeometry {
    val out = BuntingArrays()
    addStrings(out, plan)
    val stringVertices = out.positions.size / 3
    addPennants(out, plan)
    val geometry = Geometry()
    geometry.setAttribute("position", FloatAttribute(out.positions, 3))
    geometry.setAttribute("color", FloatAttribute(out.colors, 3))
    geometry.setAttribute("aFlutter", FloatAttribute(out.flutter, 3))
    geometry.computeVertexNormals()
    geometry.computeBoundingSphere()
    return BuntingGeometry(geometry, stringVertices, plan.pennants.size, plan.coreCount)
}

/** Number of vertices to draw for a share of the pennants (0 = nothing, below 1 = every second). */
internal fun buntingDrawCount(
    bunting: BuntingGeometry,
    share: Double,
): Int {
    if (!(share > 0)) return 0
    val pennants = if (share >= 1) bunting.pennantCount else bunting.coreCount
    return bunting.stringVertices + pennants * 3
}

// --- Static decoration ---------------------------------------------------------------------------

/** Adds parts in the local frame of a prop, moved to its place and turned about Y. */
private class PropAdder(
    private val builder: GeometryBuilder,
    x: Double,
    z: Double,
    rotation: Double,
) {
    private val frame =
        Mat4().compose(
            Vec3(x, 0.0, z),
            Quat().setFromAxisAngle(Node.DEFAULT_UP, rotation),
            Vec3(1.0, 1.0, 1.0),
        )

    fun add(
        geometry: Geometry,
        color: Int,
        t: PartTransform = PartTransform(),
        jitter: Double = 0.0,
        rng: (() -> Double)? = null,
    ): Geometry {
        val local =
            Mat4().compose(
                Vec3(t.x, t.y, t.z),
                Quat().setFromEuler(Euler(t.rx, t.ry, t.rz)),
                Vec3(t.sx, t.sy, t.sz),
            )
        return builder.add(geometry, color, Mat4().multiplyMatrices(frame, local), jitter, rng)
    }
}

/** A blob of leaves in a pot: place and radius (for scale 1). */
private class Foliage(
    val x: Double,
    val y: Double,
    val z: Double,
    val r: Double,
)

private val POT_FOLIAGE =
    listOf(
        Foliage(0.0, 0.5, 0.0, 0.19),
        Foliage(0.12, 0.44, 0.06, 0.13),
        Foliage(-0.1, 0.45, -0.08, 0.14),
    )

private val POT_BLOSSOMS =
    listOf(
        doubleArrayOf(0.05, 0.66, 0.08),
        doubleArrayOf(-0.1, 0.6, 0.05),
        doubleArrayOf(0.12, 0.58, -0.07),
        doubleArrayOf(-0.04, 0.63, -0.12),
        doubleArrayOf(0.0, 0.7, 0.0),
    )

private fun addPot(
    builder: GeometryBuilder,
    pot: PotSpot,
    rng: () -> Double,
) {
    val add = PropAdder(builder, pot.x, pot.z, rng() * PI * 2)
    val s = pot.scale
    val body = CylinderGeometry(0.2 * s, 0.14 * s, 0.34 * s, 9, 1, true)
    body.translate(0.0, 0.17 * s, 0.0)
    add.add(body, 0xb8643b, jitter = 0.1, rng = rng)
    add.add(CylinderGeometry(0.215 * s, 0.215 * s, 0.05 * s, 9), 0xa5562f, PartTransform(y = 0.335 * s))
    add.add(CylinderGeometry(0.18 * s, 0.18 * s, 0.02 * s, 9), 0x3b2c20, PartTransform(y = 0.36 * s))
    // foliage and blossoms
    for (leaf in POT_FOLIAGE) {
        val ico = IcosahedronGeometry(leaf.r * s, 0)
        jitterVertices(ico, leaf.r * s * 0.3, rng)
        add.add(ico, 0x477a33, PartTransform(x = leaf.x * s, y = leaf.y * s, z = leaf.z * s), jitter = 0.15, rng = rng)
    }
    for ((fx, fy, fz) in POT_BLOSSOMS.map { it.toList() }) {
        add.add(OctahedronGeometry(0.055 * s), pot.flower, PartTransform(x = fx * s, y = fy * s, z = fz * s))
    }
}

/** Open field shelter: back and side walls, posts, a sloping roof. Opening towards local +Z. */
private fun addShelter(
    builder: GeometryBuilder,
    spot: PropSpot,
) {
    val add = PropAdder(builder, spot.x, spot.z, spot.rotation)
    val wood = 0x8a6a4a
    val dark = 0x6e5238
    val w = 4.4
    val d = 2.8
    val back = 2.5
    val front = 2.9
    // back wall and low side walls of boards
    add.add(boxOnGround(w, 2.2, 0.1), wood, PartTransform(z = -d / 2))
    for (sx in listOf(-1.0, 1.0)) add.add(boxOnGround(0.1, 1.9, d), wood, PartTransform(x = sx * w / 2))
    // posts at the front corners and the middle
    for (sx in listOf(-1.0, 0.0, 1.0)) {
        add.add(boxOnGround(0.16, front - 0.2, 0.16), dark, PartTransform(x = sx * (w - 0.2) / 2, z = d / 2 - 0.1))
    }
    // roof: slopes down to the back
    val slope = atan2(front - back, d)
    val length = hypot(d + 0.8, front - back)
    add.add(
        BoxGeometry(w + 0.7, 0.12, length),
        0x5a3a2c,
        PartTransform(y = (front + back) / 2 + 0.05, z = 0.0, rx = -slope),
    )
    // straw on the floor
    add.add(boxOnGround(w - 0.5, 0.06, d - 0.5), 0xc9ad66, PartTransform(z = -0.05))
}

private fun addTrough(
    builder: GeometryBuilder,
    spot: PropSpot,
) {
    val add = PropAdder(builder, spot.x, spot.z, spot.rotation)
    add.add(boxOnGround(2.0, 0.6, 0.6), 0x8a9099)
    val water = PlaneGeometry(1.85, 0.45)
    water.rotateX(-PI / 2)
    add.add(water, 0x3d6f8a, PartTransform(y = 0.55))
}

private fun addRack(
    builder: GeometryBuilder,
    spot: PropSpot,
) {
    val add = PropAdder(builder, spot.x, spot.z, spot.rotation)
    val wood = 0x7a5c44
    for (sx in listOf(-1.0, 1.0)) {
        add.add(boxOnGround(0.12, 1.1, 0.12), wood, PartTransform(x = sx * 0.8, z = 0.3))
        add.add(boxOnGround(0.12, 1.1, 0.12), wood, PartTransform(x = sx * 0.8, z = -0.3))
    }
    add.add(boxOnGround(1.8, 0.1, 0.7), wood, PartTransform(y = 0.7))
    add.add(boxOnGround(1.6, 0.4, 0.55), 0xc9ad66, PartTransform(y = 0.78))
}

/**
 * The decoration of arena and paddock. [setDetail] takes the share of the bunting that is drawn
 * (0 hides the whole decoration, below 1 draws every second pennant).
 */
class ArenaDecor(
    materialFactory: MaterialFactory,
    wind: Wind,
    seed: Int = 11,
) {
    val group = Group().also { it.name = "arena-decor" }
    val meshes: List<ManagedMesh>
    private val bunting: BuntingGeometry
    private val buntingMesh: Mesh
    private val decorMesh: Mesh

    init {
        val rng = createRng(seed + 29)
        val buntingMats =
            materialFactory("bunting", MaterialParams(vertexColors = true, roughness = 0.85, side = Side.DOUBLE))
        patchBunting(buntingMats.standard, wind)
        patchBunting(buntingMats.lambert, wind)
        bunting = buildBuntingGeometry()
        buntingMesh = Mesh(bunting.geometry, buntingMats.standard)
        buntingMesh.name = "bunting"
        buntingMesh.frustumCulled = false // spans the whole arena; one draw call either way

        val builder = GeometryBuilder()
        for (pot in planPots()) addPot(builder, pot, rng)
        val props = planPaddockProps()
        addShelter(builder, props.shelter)
        addTrough(builder, props.trough)
        addRack(builder, props.rack)
        val decorMats = materialFactory("decor", MaterialParams(vertexColors = true, roughness = 0.85))
        decorMesh = Mesh(builder.build(), decorMats.standard)
        decorMesh.name = "decor-props"

        for (mesh in listOf(buntingMesh, decorMesh)) {
            mesh.visible = false
            group.add(mesh)
        }
        meshes =
            listOf(
                ManagedMesh(buntingMesh, buntingMats, ShadowRole.NONE, detail = true),
                ManagedMesh(decorMesh, decorMats, ShadowRole.ALL, detail = true),
            )
    }

    fun setDetail(share: Double) {
        val count = buntingDrawCount(bunting, share)
        buntingMesh.visible = count > 0
        bunting.geometry.setDrawRange(0, count)
        decorMesh.visible = share > 0
    }
}
