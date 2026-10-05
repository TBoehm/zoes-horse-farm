package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.Usage
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.material.MaterialParams
import app.zoeshorsefarm.scene.material.Side
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.Butterfly
import app.zoeshorsefarm.view3d.FlightPose
import app.zoeshorsefarm.view3d.Flock
import app.zoeshorsefarm.view3d.PatchAnchor
import app.zoeshorsefarm.view3d.birdPose
import app.zoeshorsefarm.view3d.butterflyPose
import app.zoeshorsefarm.view3d.jsRound
import app.zoeshorsefarm.view3d.patchWings
import app.zoeshorsefarm.view3d.planButterflies
import app.zoeshorsefarm.view3d.planFlocks
import kotlin.math.max
import kotlin.math.min

// Animals of the meadow: flocks of birds circling in the sky and butterflies over the flower
// patches. Both are small instanced meshes; the wings beat in the vertex shader, the paths come
// from FlightPaths.kt and only the instance matrices are written per frame (a few dozen).

// Technical values (look, no game play)
private const val FLOCKS = 4
private const val BIRDS_PER_FLOCK = 6
private const val BUTTERFLIES = 14

private const val BIRD_BODY = 0x2b2927
private const val BIRD_WING = 0x3b3835
private const val BIRD_TIP = 0x1f1d1c
private val BUTTERFLY_COLORS = listOf(0xf6f2e4, 0xf3d54a, 0xee8a2e, 0x8fb8f0, 0xf08fb0)

private const val BIRD_WING_RATE = 14.0
private const val BIRD_WING_AMPLITUDE = 0.6
private const val BIRD_GLIDE = 0.85
private const val BUTTERFLY_WING_RATE = 26.0
private const val BUTTERFLY_WING_AMPLITUDE = 1.7

private class Triangles {
    val positions = ArrayList<Double>()
    val colors = ArrayList<Double>()

    /** Pushes one triangle with a colour per vertex. */
    fun tri(
        a: DoubleArray,
        b: DoubleArray,
        c: DoubleArray,
        ca: DoubleArray,
        cb: DoubleArray,
        cc: DoubleArray,
    ) {
        for (p in listOf(a, b, c)) p.forEach { positions.add(it) }
        for (col in listOf(ca, cb, cc)) col.forEach { colors.add(it) }
    }

    fun finish(): Geometry {
        val g = Geometry()
        g.setAttribute("position", FloatAttribute(positions, 3))
        g.setAttribute("color", FloatAttribute(colors, 3))
        g.computeVertexNormals()
        g.computeBoundingSphere()
        return g
    }
}

private fun rgb(hex: Int): DoubleArray {
    val c = Color(hex)
    return doubleArrayOf(c.r, c.g, c.b)
}

private fun v(
    x: Double,
    y: Double,
    z: Double,
) = doubleArrayOf(x, y, z)

/**
 * A bird seen from the side and above (7 triangles): slim body, short tail and two swept wings
 * along X, about 1.1 m across. Flies towards +Z.
 */
fun buildBirdGeometry(): Geometry {
    val out = Triangles()
    val body = rgb(BIRD_BODY)
    val wing = rgb(BIRD_WING)
    val tip = rgb(BIRD_TIP)
    // body: a flat diamond with a ridge (4 triangles)
    val nose = v(0.0, 0.0, 0.32)
    val tail = v(0.0, 0.0, -0.26)
    val left = v(-0.05, 0.0, 0.02)
    val right = v(0.05, 0.0, 0.02)
    val top = v(0.0, 0.05, 0.05)
    out.tri(nose, left, top, body, body, body)
    out.tri(nose, top, right, body, body, body)
    out.tri(tail, top, left, body, body, body)
    out.tri(tail, right, top, body, body, body)
    // wings: swept back triangles, tips darker
    out.tri(v(-0.04, 0.01, 0.16), v(-0.56, 0.02, -0.16), v(-0.04, 0.01, -0.12), wing, tip, wing)
    out.tri(v(0.04, 0.01, 0.16), v(0.04, 0.01, -0.12), v(0.56, 0.02, -0.16), wing, wing, tip)
    // tail fan
    out.tri(v(-0.07, 0.0, -0.2), v(0.07, 0.0, -0.2), v(0.0, 0.0, -0.4), wing, wing, tip)
    return out.finish()
}

/** A butterfly (5 triangles): two pairs of wings along X and a thin body, 0.14 m across. */
fun buildButterflyGeometry(): Geometry {
    val out = Triangles()
    val white = doubleArrayOf(1.0, 1.0, 1.0)
    val dark = rgb(0x2a2420)
    for (s in listOf(-1.0, 1.0)) {
        // forward wing and hind wing
        out.tri(v(0.0, 0.0, 0.01), v(s * 0.075, 0.005, 0.05), v(s * 0.055, 0.0, -0.005), white, white, white)
        out.tri(v(0.0, 0.0, 0.0), v(s * 0.055, 0.0, -0.005), v(s * 0.04, 0.0, -0.06), white, white, white)
    }
    out.tri(v(-0.008, 0.0, 0.05), v(0.008, 0.0, 0.05), v(0.0, 0.0, -0.07), dark, dark, dark)
    return out.finish()
}

private val Z_AXIS = Vec3(0.0, 0.0, 1.0)

/**
 * The animals. [setDetail] takes the shares of the birds and the butterflies that are drawn (0
 * hides the mesh); [anchors] are the patches the butterflies hover over.
 */
class Wildlife(
    materialFactory: MaterialFactory,
    wind: Wind,
    anchors: List<PatchAnchor> = emptyList(),
    seed: Int = 11,
) {
    val group = Group().also { it.name = "wildlife" }
    private val flocks: List<Flock>
    private val butterflies: List<Butterfly>
    private val birdTotal = FLOCKS * BIRDS_PER_FLOCK
    private val birdMesh: InstancedMesh
    private val butterflyMesh: InstancedMesh
    val meshes: List<ManagedMesh>

    private val pose = FlightPose()
    private val m = Mat4()
    private val q = Quat()
    private val qRoll = Quat()
    private val p = Vec3()
    private val s = Vec3()
    private var time = 0.0

    init {
        val rng = createRng(seed + 13)
        flocks = planFlocks(rng, FLOCKS, BIRDS_PER_FLOCK)
        butterflies = planButterflies(rng, anchors, BUTTERFLIES)

        val birdMats =
            materialFactory("birds", MaterialParams(vertexColors = true, roughness = 0.9, side = Side.DOUBLE))
        patchWings(birdMats.standard, wind, BIRD_WING_RATE, BIRD_WING_AMPLITUDE, BIRD_GLIDE)
        patchWings(birdMats.lambert, wind, BIRD_WING_RATE, BIRD_WING_AMPLITUDE, BIRD_GLIDE)
        val butterflyMats =
            materialFactory("butterflies", MaterialParams(vertexColors = true, roughness = 0.8, side = Side.DOUBLE))
        patchWings(butterflyMats.standard, wind, BUTTERFLY_WING_RATE, BUTTERFLY_WING_AMPLITUDE)
        patchWings(butterflyMats.lambert, wind, BUTTERFLY_WING_RATE, BUTTERFLY_WING_AMPLITUDE)

        birdMesh = InstancedMesh(buildBirdGeometry(), birdMats.standard, birdTotal)
        birdMesh.name = "birds"
        butterflyMesh = InstancedMesh(buildButterflyGeometry(), butterflyMats.standard, max(1, butterflies.size))
        butterflyMesh.name = "butterflies"
        val c = Color()
        butterflies.indices.forEach { i ->
            c.set(BUTTERFLY_COLORS[i % BUTTERFLY_COLORS.size]).multiplyScalar(0.92 + rng() * 0.16)
            butterflyMesh.setColorAt(i, c)
        }
        butterflyMesh.instanceColor?.needsUpdate = true
        for (mesh in listOf(birdMesh, butterflyMesh)) {
            mesh.instanceMatrix.setUsage(Usage.DYNAMIC_DRAW)
            mesh.frustumCulled = false // the instances move; their matrices change every frame
            mesh.count = 0
            mesh.visible = false
            group.add(mesh)
        }
        meshes =
            listOf(
                ManagedMesh(birdMesh, birdMats, ShadowRole.NONE, detail = true),
                ManagedMesh(butterflyMesh, butterflyMats, ShadowRole.NONE, detail = true),
            )
    }

    private fun writeBirds() {
        for (i in 0 until birdMesh.count) {
            // bird k of flock f is instance k * FLOCKS + f: a smaller share thins every flock
            val flock = flocks[i % FLOCKS]
            birdPose(flock, i / FLOCKS, time, pose)
            q.setFromAxisAngle(Node.DEFAULT_UP, pose.heading)
            qRoll.setFromAxisAngle(Z_AXIS, pose.roll)
            q.multiply(qRoll)
            p.set(pose.x, pose.y, pose.z)
            s.setScalar(1.15 + ((i * 0.618) % 1) * 0.4)
            birdMesh.setMatrixAt(i, m.compose(p, q, s))
        }
        birdMesh.instanceMatrix.needsUpdate = true
    }

    private fun writeButterflies() {
        for (i in 0 until butterflyMesh.count) {
            butterflyPose(butterflies[i], time, pose)
            q.setFromAxisAngle(Node.DEFAULT_UP, pose.heading)
            p.set(pose.x, pose.y, pose.z)
            s.setScalar(0.9 + ((i * 0.37) % 1) * 0.3)
            butterflyMesh.setMatrixAt(i, m.compose(p, q, s))
        }
        butterflyMesh.instanceMatrix.needsUpdate = true
    }

    fun setDetail(
        birds: Double = 0.0,
        butterflies: Double = 0.0,
    ) {
        birdMesh.count = jsRound(birdTotal * min(1.0, max(0.0, birds)))
        birdMesh.visible = birdMesh.count > 0
        butterflyMesh.count = jsRound(this.butterflies.size * min(1.0, max(0.0, butterflies)))
        butterflyMesh.visible = butterflyMesh.count > 0
        writeBirds()
        writeButterflies()
    }

    fun update(dt: Double) {
        time += dt
        if (birdMesh.visible) writeBirds()
        if (butterflyMesh.visible) writeButterflies()
    }
}
