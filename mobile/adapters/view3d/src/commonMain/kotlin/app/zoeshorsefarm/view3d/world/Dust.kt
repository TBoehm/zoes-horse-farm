package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.Usage
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.material.ShaderMaterial
import app.zoeshorsefarm.scene.material.Uniform
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.MathUtils
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.jsRound
import app.zoeshorsefarm.view3d.releaseNow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Hoof dust: soft puffs that rise from the sand under the hooves and drift away.
//
//   val dust = Dust(DustQuality.MEDIUM, release)    // release: see GpuEpoch
//   scene.add(dust.obj)                              // one Points (one draw call), invisible while idle
//   dust.emit(x, y, z, strength)                     // world position of a footfall, strength 0..1
//   dust.update(dt)                                  // every frame
//   dust.setQuality(DustQuality.HIGH)                // rebuilds the pool (releases the old buffers)
//   dust.dispose()
//
// The caller only emits when the hoof is on the sand (not on grass). Quality: low has no dust at
// all (the object is an empty group: no geometry, no draw call); medium and high use a fixed pool
// (no allocation per frame). The puffs are round soft sprites of the backend's `hoof-dust`
// program (no textures, rule 2).

/** Dust level: the pool size and the share of puffs per footfall. */
enum class DustQuality(
    val pool: Int,
    val emitShare: Double,
) {
    LOW(0, 0.0),
    MEDIUM(36, 0.7),
    HIGH(90, 1.0),
}

// Technical constants (pool size and look); nothing here is a game value.
private const val PUFFS_MIN = 1.0
private const val PUFFS_MAX = 4.0
private const val JITTER = 0.1 // m, start position scatter
private val SPEED = 0.3..1.5 // m/s, outwards (scaled by strength)
private val RISE = 0.5..1.3 // m/s, upwards at the start (scaled by strength)
private val LIFE = 0.5..1.2 // s
private const val SIZE_START = 0.18 // m, grows from the first to the second value
private const val SIZE_END = 0.7
private const val PEAK_ALPHA = 0.6 // peak opacity (scaled by strength)
private const val DRAG = 2.2 // 1/s
private const val BUOYANCY = 0.25 // m/s^2, slow rise of the cloud
private const val GRAVITY = 1.2 // m/s^2, pulls the rising dust back down
private const val MAX_DT = 0.1 // s
private const val MIN_HEIGHT = 0.02 // m, never under the sand
private const val FADE_IN = 0.08 // share of the life
private const val FADE_OUT_POWER = 1.6
private const val DUST_COLOR = 0xe8dcc2 // dry sand dust: lighter than the footing, so that it shows against it
private const val DEFAULT_PIXEL_SCALE = 600.0
private const val DEFAULT_SEED = 21

/**
 * Particle pool as plain arrays (pure; no scene model). `rng` gives numbers in [0, 1).
 * Slot i has position (x, y, z), velocity, age and life; a dead slot has life 0.
 */
class DustPool(
    val capacity: Int,
    private val rng: () -> Double,
    level: DustQuality = DustQuality.HIGH,
) {
    val pos = FloatArray(capacity * 3)
    val vel = FloatArray(capacity * 3)
    val age = FloatArray(capacity)

    /** 0 = free. */
    val life = FloatArray(capacity)

    /** Current size (m). */
    val size = FloatArray(capacity)

    /** Current opacity. */
    val alpha = FloatArray(capacity)

    /** Peak opacity of the puff. */
    private val peak = FloatArray(capacity)

    // ring buffer: the next slot to (re)use
    private var next = 0

    /** Number of living puffs after the last [update]. */
    var alive = 0
        private set

    private val share = level.emitShare

    private fun between(range: ClosedFloatingPointRange<Double>): Double =
        range.start + (range.endInclusive - range.start) * rng()

    /** Starts the puffs of one footfall; the oldest puffs are overwritten when the pool is full. */
    fun emit(
        x: Double,
        y: Double,
        z: Double,
        strength: Double,
    ): Int {
        if (capacity == 0) return 0
        val s = max(0.0, min(1.0, strength))
        if (s <= 0) return 0
        val count = max(1, jsRound((PUFFS_MIN + (PUFFS_MAX - PUFFS_MIN) * s) * share))
        for (c in 0 until count) {
            val i = next
            next = (i + 1) % capacity
            val a = rng() * PI * 2
            val out = between(SPEED) * (0.35 + 0.65 * s)
            pos[i * 3] = (x + (rng() - 0.5) * 2 * JITTER).toFloat()
            pos[i * 3 + 1] = (y + 0.03 + rng() * 0.05).toFloat()
            pos[i * 3 + 2] = (z + (rng() - 0.5) * 2 * JITTER).toFloat()
            vel[i * 3] = (cos(a) * out).toFloat()
            vel[i * 3 + 1] = (between(RISE) * (0.3 + 0.7 * s)).toFloat()
            vel[i * 3 + 2] = (sin(a) * out).toFloat()
            age[i] = 0f
            life[i] = (between(LIFE) * (0.6 + 0.4 * s)).toFloat()
            peak[i] = (PEAK_ALPHA * (0.4 + 0.6 * s)).toFloat()
            size[i] = SIZE_START.toFloat()
            alpha[i] = 0f
        }
        return count
    }

    /** Moves the puffs on; returns the number of living puffs. */
    fun update(dt: Double): Int {
        val h = max(0.0, min(dt, MAX_DT))
        var living = 0
        val damp = exp(-DRAG * h)
        for (i in 0 until capacity) if (advance(i, damp, h)) living++
        alive = living
        return living
    }

    /** Ages one slot; returns true while its puff lives. */
    private fun advance(
        i: Int,
        damp: Double,
        h: Double,
    ): Boolean {
        if (life[i] <= 0) {
            alpha[i] = 0f
            return false
        }
        age[i] = (age[i] + h).toFloat()
        val t = age[i].toDouble() / life[i]
        if (t >= 1) {
            life[i] = 0f
            alpha[i] = 0f
            size[i] = 0f
            return false
        }
        move(i, damp, h, t)
        return true
    }

    private fun move(
        i: Int,
        damp: Double,
        h: Double,
        t: Double,
    ) {
        val k = i * 3
        vel[k] = (vel[k] * damp).toFloat()
        vel[k + 2] = (vel[k + 2] * damp).toFloat()
        vel[k + 1] = (vel[k + 1] * damp - GRAVITY * h * (1 - t) + BUOYANCY * h * t).toFloat()
        pos[k] = (pos[k] + vel[k] * h).toFloat()
        pos[k + 1] = max(MIN_HEIGHT, pos[k + 1] + vel[k + 1] * h).toFloat()
        pos[k + 2] = (pos[k + 2] + vel[k + 2] * h).toFloat()
        size[i] = (SIZE_START + (SIZE_END - SIZE_START) * sqrt(t)).toFloat()
        // quick fade in (no popping into view), long fade out
        alpha[i] = (peak[i] * min(1.0, t / FADE_IN) * (1 - t).pow(FADE_OUT_POWER)).toFloat()
    }
}

private class DustPoints(
    val points: Points,
    val geometry: Geometry,
    val material: ShaderMaterial,
)

private val sizeTmp = Vec2()

private fun dynamicAttribute(
    array: FloatArray,
    itemSize: Int,
): FloatAttribute = FloatAttribute(array, itemSize).also { it.setUsage(Usage.DYNAMIC_DRAW) }

/** Builds the Points object for a pool; the geometry shares the pool's arrays. */
private fun buildPoints(pool: DustPool): DustPoints {
    val geometry = Geometry()
    geometry.setAttribute("position", dynamicAttribute(pool.pos, 3))
    geometry.setAttribute("aSize", dynamicAttribute(pool.size, 1))
    geometry.setAttribute("aAlpha", dynamicAttribute(pool.alpha, 1))
    val pixelScale = Uniform(DEFAULT_PIXEL_SCALE)
    val material =
        ShaderMaterial(
            "hoof-dust",
            mapOf("uColor" to Uniform(Color(DUST_COLOR)), "uPixelScale" to pixelScale),
            transparent = true,
            depthWrite = false,
            fog = false,
        )
    val points = Points(geometry, material)
    points.name = "hoof-dust"
    points.frustumCulled = false
    points.visible = false
    points.renderOrder = 2
    // pixels per metre at distance 1 for the camera that renders (size attenuation)
    var last = DEFAULT_PIXEL_SCALE
    points.onBeforeRender = { backend, _, camera ->
        val h = backend.getDrawingBufferSize(sizeTmp).y
        val scale = if (camera is PerspectiveCamera) h / (2 * tan(MathUtils.degToRad(camera.fov) / 2)) else h
        // the uniform only changes with the surface or the field of view: no boxing per frame
        if (scale != last) {
            last = scale
            pixelScale.value = scale
        }
    }
    return DustPoints(points, geometry, material)
}

/**
 * The dust of a world. [obj] is the group to add to the scene. [release] frees GPU objects that
 * the dust replaces while it runs (see `GpuEpoch`).
 */
class Dust(
    quality: DustQuality = DustQuality.MEDIUM,
    private val release: (GpuObject?) -> Unit = ::releaseNow,
    private val rng: () -> Double = createRng(DEFAULT_SEED),
) {
    val obj = Group().also { it.name = "dust" }
    private var level = quality
    private var pool: DustPool? = null
    private var built: DustPoints? = null

    init {
        build()
    }

    /** Pool size of the current level (0 = off). */
    val capacity: Int get() = pool?.capacity ?: 0

    /** Number of puffs alive (for tests and debugging). */
    val alive: Int
        get() {
            val p = pool ?: return 0
            var n = 0
            for (i in 0 until p.capacity) if (p.life[i] > 0) n++
            return n
        }

    private fun drop() {
        built?.let {
            obj.remove(it.points)
            // through `release`, so that objects of a lost context are not freed with GPU calls
            release(it.geometry)
            release(it.material)
        }
        built = null
    }

    private fun build() {
        drop()
        pool = null
        val capacity = level.pool
        if (capacity <= 0) return // low: no geometry, no draw call
        val p = DustPool(capacity, rng, level)
        pool = p
        built = buildPoints(p).also { obj.add(it.points) }
    }

    fun emit(
        x: Double,
        y: Double,
        z: Double,
        strength: Double = 0.5,
    ) {
        val p = pool ?: return
        p.emit(x, y, z, strength)
        built?.points?.visible = true
    }

    fun update(dt: Double) {
        val p = pool ?: return
        val b = built ?: return
        val living = p.update(dt)
        // nothing to draw: no draw call
        b.points.visible = living > 0
        if (living > 0) {
            b.geometry.float("position").needsUpdate = true
            b.geometry.float("aSize").needsUpdate = true
            b.geometry.float("aAlpha").needsUpdate = true
        }
    }

    fun setQuality(next: DustQuality) {
        if (next == level) return
        level = next
        build()
    }

    fun dispose() {
        drop()
        pool = null
        obj.removeFromParent()
    }
}
