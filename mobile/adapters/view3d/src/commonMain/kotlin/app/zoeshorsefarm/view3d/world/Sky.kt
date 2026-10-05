package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.SphereGeometry
import app.zoeshorsefarm.scene.graph.Camera
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.Side
import app.zoeshorsefarm.scene.material.SkyMaterial
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.MathUtils
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.createCloudAtlas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Sky: gradient dome with sun (the backend's `sky` program) and a few soft clouds (one draw call).

/** Colours of the sky (sRGB hex). */
object SkyColors {
    const val ZENITH = 0x3f7fcf
    const val HORIZON = 0xcfe2ee
    const val GROUND = 0xb8c7bf
    const val SUN = 0xfff1d6
}

private val DEFAULT_SUN_DIRECTION = Vec3(-0.45, 0.62, -0.64)
private const val DEFAULT_RADIUS = 420.0
private const val DEFAULT_CLOUD_COUNT = 9
private const val DEFAULT_SEED = 3
private const val CLOUD_RING = 0.86 // clouds sit on a ring at this share of the dome radius
private const val CLOUD_DRIFT = 0.002 // rad/s

/** Quads on a ring facing the centre; the texture atlas has 4 variants. */
private fun buildCloudGeometry(
    radius: Double,
    cloudCount: Int,
    seed: Int,
): Geometry {
    val rng = createRng(seed)
    val positions = ArrayList<Double>()
    val uvs = ArrayList<Double>()
    val indices = ArrayList<Int>()
    val r = radius * CLOUD_RING
    val center = Vec3()
    val toCenter = Vec3()
    val right = Vec3()
    val up = Vec3()
    val corner = Vec3()
    val yAxis = Vec3(0.0, 1.0, 0.0)
    for (i in 0 until cloudCount) {
        val az = i.toDouble() / cloudCount * PI * 2 + rng() * 0.5
        val el = MathUtils.degToRad(7 + rng() * 16)
        val w = 70 + rng() * 80
        val h = w * 0.5
        center.set(cos(el) * sin(az) * r, sin(el) * r, cos(el) * cos(az) * r)
        toCenter.copy(center).negate().normalize()
        right.crossVectors(yAxis, toCenter).normalize()
        up.crossVectors(toCenter, right).normalize()
        val base = positions.size / 3
        for ((sx, sy) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
            corner.copy(center).addScaledVector(right, sx * w / 2).addScaledVector(up, sy * h / 2)
            positions.add(corner.x)
            positions.add(corner.y)
            positions.add(corner.z)
        }
        val k = floor(rng() * 4).toInt()
        val u0 = (k % 2) * 0.5
        val v0 = 0.5 - (k / 2) * 0.5
        uvs.addAll(listOf(u0, v0, u0 + 0.5, v0, u0 + 0.5, v0 + 0.5, u0, v0 + 0.5))
        indices.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
    }
    val geometry = Geometry()
    geometry.setAttribute("position", FloatAttribute(positions, 3))
    geometry.setAttribute("uv", FloatAttribute(uvs, 2))
    geometry.setIndex(indices)
    return geometry
}

/**
 * [sunDirection]: direction towards the sun (normalized on the way in). The [group] follows the
 * camera so the sky looks infinite.
 */
class Sky(
    sunDirection: Vec3? = null,
    radius: Double = DEFAULT_RADIUS,
    cloudCount: Int = DEFAULT_CLOUD_COUNT,
    seed: Int = DEFAULT_SEED,
) {
    val group = Group().also { it.name = "sky" }
    val sunDirection: Vec3 = (sunDirection ?: DEFAULT_SUN_DIRECTION).clone().normalize()
    val horizonColor = Color(SkyColors.HORIZON)

    private val domeMaterial =
        SkyMaterial(SkyColors.ZENITH, SkyColors.HORIZON, SkyColors.GROUND, SkyColors.SUN, this.sunDirection)
    val dome = Mesh(SphereGeometry(radius, 32, 16), domeMaterial)

    private val cloudGeometry = buildCloudGeometry(radius, cloudCount, seed)
    private val cloudMaterial =
        BasicMaterial(
            map = createCloudAtlas(),
            transparent = true,
            depthWrite = false,
            fog = false,
            side = Side.DOUBLE,
        )
    val clouds = Mesh(cloudGeometry, cloudMaterial)

    init {
        dome.frustumCulled = false
        dome.renderOrder = -10
        group.add(dome)
        clouds.frustumCulled = false
        clouds.renderOrder = -9
        group.add(clouds)
    }

    fun update(
        dt: Double,
        camera: Camera?,
    ) {
        if (camera != null) group.position.copy(camera.position)
        clouds.rotation.y += dt * CLOUD_DRIFT
    }

    fun dispose() {
        dome.geometry.dispose()
        domeMaterial.dispose()
        cloudGeometry.dispose()
        cloudMaterial.map?.dispose()
        cloudMaterial.dispose()
    }
}
