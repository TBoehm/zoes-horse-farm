package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.mergeGeometries
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.toFixed
import app.zoeshorsefarm.shared.clamp

// Geometry helpers (second half of textures.js): colour parts with vertex colours, transform and
// merge them.

/**
 * Position ([x], [y], [z]), rotation about X, Y, Z in radians ([rx], [ry], [rz], order XYZ) and
 * scale ([sx], [sy], [sz]) of a part.
 */
data class PartTransform(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val rx: Double = 0.0,
    val ry: Double = 0.0,
    val rz: Double = 0.0,
    val sx: Double = 1.0,
    val sy: Double = 1.0,
    val sz: Double = 1.0,
)

private val tmpColor = Color()

/**
 * Colors a geometry uniformly (hex, sRGB) and makes it non-indexed. With a [jitter] every vertex
 * gets its brightness scaled by 1 +- jitter / 2 (taken from [rng]).
 */
private fun paint(
    geometry: Geometry,
    color: Int,
    jitter: Double,
    rng: (() -> Double)?,
): Geometry {
    require(jitter == 0.0 || rng != null) { "a colour jitter needs an rng" }
    val g = geometry.toNonIndexed()
    if (g !== geometry) geometry.dispose()
    val n = g.position.count
    if (!g.hasAttribute("uv")) g.setAttribute("uv", FloatAttribute(FloatArray(n * 2), 2))
    val colors = FloatArray(n * 3)
    tmpColor.set(color)
    for (i in 0 until n) {
        val f = if (jitter != 0.0 && rng != null) 1 + (rng() - 0.5) * jitter else 1.0
        colors[i * 3] = (tmpColor.r * f).toFloat()
        colors[i * 3 + 1] = (tmpColor.g * f).toFloat()
        colors[i * 3 + 2] = (tmpColor.b * f).toFloat()
    }
    g.setAttribute("color", FloatAttribute(colors, 3))
    return g
}

/** Collects colored parts and merges them into one geometry (web app: `createGeometryBuilder()`). */
class GeometryBuilder {
    private val parts = ArrayList<Geometry>()
    private val m = Mat4()
    private val q = Quat()
    private val e = Euler()
    private val s = Vec3()
    private val p = Vec3()

    /** Number of parts collected so far. */
    val count: Int get() = parts.size

    /**
     * Adds a part: [geometry] (taken over), [color] and [transform]. A [jitter] > 0 varies the
     * brightness per vertex and needs an [rng] (numbers in [0, 1)). Returns the stored part.
     */
    fun add(
        geometry: Geometry,
        color: Int,
        transform: PartTransform = PartTransform(),
        jitter: Double = 0.0,
        rng: (() -> Double)? = null,
    ): Geometry {
        val matrix =
            m.compose(
                p.set(transform.x, transform.y, transform.z),
                q.setFromEuler(e.set(transform.rx, transform.ry, transform.rz)),
                s.set(transform.sx, transform.sy, transform.sz),
            )
        return add(geometry, color, matrix, jitter, rng)
    }

    /** Like the above with a transform matrix. */
    fun add(
        geometry: Geometry,
        color: Int,
        matrix: Mat4,
        jitter: Double = 0.0,
        rng: (() -> Double)? = null,
    ): Geometry {
        val g = paint(geometry, color, jitter, rng)
        g.applyMatrix4(matrix)
        parts.add(g)
        return g
    }

    /** Adds an already colored geometry (copied) with an optional [matrix]. */
    fun addPainted(
        geometry: Geometry,
        matrix: Mat4? = null,
    ): Geometry {
        val g = geometry.clone()
        if (matrix != null) g.applyMatrix4(matrix)
        parts.add(g)
        return g
    }

    /** Merges the parts into one geometry (an empty one without parts) and starts over. */
    fun build(): Geometry {
        if (parts.isEmpty()) return Geometry()
        val merged = checkNotNull(mergeGeometries(parts, false)) { "the parts have different attributes" }
        for (g in parts) g.dispose()
        parts.clear()
        merged.computeBoundingSphere()
        return merged
    }
}

/** Box with its bottom at y = 0 (handy for posts, walls). */
fun boxOnGround(
    w: Double,
    h: Double,
    d: Double,
): Geometry = BoxGeometry(w, h, d).translate(0.0, h / 2, 0.0)

/** Randomly displaces vertices by up to [amount] / 2 (organic shapes for tree crowns, bushes). */
fun jitterVertices(
    geometry: Geometry,
    amount: Double,
    rng: () -> Double,
): Geometry {
    val pos = geometry.position
    // move equal positions equally so no holes appear
    val cache = HashMap<String, DoubleArray>()
    for (i in 0 until pos.count) {
        val key = "${pos.getX(i).toFixed(3)},${pos.getY(i).toFixed(3)},${pos.getZ(i).toFixed(3)}"
        val d =
            cache.getOrPut(key) {
                doubleArrayOf((rng() - 0.5) * amount, (rng() - 0.5) * amount, (rng() - 0.5) * amount)
            }
        pos.setXYZ(i, pos.getX(i) + d[0], pos.getY(i) + d[1], pos.getZ(i) + d[2])
    }
    geometry.computeVertexNormals()
    return geometry
}

/** Scales vertex colors by height (darker at the bottom, like ambient occlusion). */
fun shadeByHeight(
    geometry: Geometry,
    minY: Double,
    maxY: Double,
    bottom: Double = 0.55,
    top: Double = 1.1,
): Geometry {
    val pos = geometry.position
    val col = geometry.color
    for (i in 0 until pos.count) {
        val t = clamp((pos.getY(i) - minY) / (maxY - minY), 0.0, 1.0)
        val f = bottom + (top - bottom) * t
        col.setXYZ(i, col.getX(i) * f, col.getY(i) * f, col.getZ(i) * f)
    }
    return geometry
}
