package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Usage
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.ColorSpace
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Reins as a dynamic band (two thin tubes updated every frame; one draw call).

private const val SEG = 10
private const val SIDES_AROUND = 4
private const val RADIUS = 0.006

/** The reins of a horse: [mesh] is one dynamic geometry that [setRein] and [commit] rewrite every frame. */
class Reins {
    private val verts = 2 * (SEG + 1) * SIDES_AROUND
    private val pos = FloatArray(verts * 3)
    private val geo = Geometry()
    val mesh: Mesh
    private val p = Vec3()
    private val tan = Vec3()
    private val n1 = Vec3()
    private val n2 = Vec3()
    private val up = Vec3(0.0, 1.0, 0.0)
    private val va = Vec3()
    private val vb = Vec3()
    private val vc = Vec3()
    private val ab = Vec3()
    private val positionAttr = FloatAttribute(pos, 3).also { it.setUsage(Usage.DYNAMIC_DRAW) }
    private val normalAttr = FloatAttribute(FloatArray(verts * 3), 3)

    init {
        val col = FloatArray(verts * 3)
        val c = Color().setRGB(0.16, 0.09, 0.05, ColorSpace.SRGB)
        for (i in 0 until verts) {
            col[i * 3] = c.r.toFloat()
            col[i * 3 + 1] = c.g.toFloat()
            col[i * 3 + 2] = c.b.toFloat()
        }
        val index = ArrayList<Int>()
        for (r in 0 until 2) {
            val base = r * (SEG + 1) * SIDES_AROUND
            for (i in 0 until SEG) {
                for (j in 0 until SIDES_AROUND) {
                    val a = base + i * SIDES_AROUND + j
                    val b = base + i * SIDES_AROUND + ((j + 1) % SIDES_AROUND)
                    val a1 = a + SIDES_AROUND
                    val b1 = b + SIDES_AROUND
                    index.addAll(listOf(a, a1, b, b, a1, b1))
                }
            }
        }
        geo.setAttribute("position", positionAttr)
        geo.setAttribute("normal", normalAttr)
        geo.setAttribute("color", FloatAttribute(col, 3))
        geo.setIndex(index)
        mesh = Mesh(geo, BasicMaterial())
        mesh.name = "horse-reins"
        mesh.frustumCulled = false
    }

    fun setMaterial(m: Material) {
        mesh.material = m
    }

    /** Rein [s] (0 left, 1 right) from [a] to [b] with sag (m). */
    fun setRein(
        s: Int,
        a: Vec3,
        b: Vec3,
        sag: Double,
    ) {
        val base = s * (SEG + 1) * SIDES_AROUND
        tan.subVectors(b, a).normalize()
        n1.crossVectors(tan, up)
        if (n1.lengthSq() < 1e-6) n1.set(1.0, 0.0, 0.0)
        n1.normalize()
        n2.crossVectors(n1, tan).normalize()
        for (i in 0..SEG) {
            val t = i.toDouble() / SEG
            p.lerpVectors(a, b, t)
            p.y -= sag * 4 * t * (1 - t)
            for (j in 0 until SIDES_AROUND) {
                val ang = (j.toDouble() / SIDES_AROUND) * PI * 2
                val k = (base + i * SIDES_AROUND + j) * 3
                val w = RADIUS * 1.6
                pos[k] = (p.x + n1.x * cos(ang) * w + n2.x * sin(ang) * RADIUS).toFloat()
                pos[k + 1] = (p.y + n1.y * cos(ang) * w + n2.y * sin(ang) * RADIUS).toFloat()
                pos[k + 2] = (p.z + n1.z * cos(ang) * w + n2.z * sin(ang) * RADIUS).toFloat()
            }
        }
    }

    /** Uploads the new positions and recomputes the normals (without allocating). */
    fun commit() {
        positionAttr.needsUpdate = true
        val idx = geo.index ?: return
        val n = normalAttr.array
        n.fill(0f)
        var i = 0
        while (i < idx.size) {
            val ia = idx[i]
            val ib = idx[i + 1]
            val ic = idx[i + 2]
            va.set(pos[ia * 3].toDouble(), pos[ia * 3 + 1].toDouble(), pos[ia * 3 + 2].toDouble())
            vb.set(pos[ib * 3].toDouble(), pos[ib * 3 + 1].toDouble(), pos[ib * 3 + 2].toDouble())
            vc.set(pos[ic * 3].toDouble(), pos[ic * 3 + 1].toDouble(), pos[ic * 3 + 2].toDouble())
            vc.sub(vb)
            ab.subVectors(va, vb)
            vc.cross(ab)
            addNormal(n, ia, vc)
            addNormal(n, ib, vc)
            addNormal(n, ic, vc)
            i += 3
        }
        normalize(n)
        normalAttr.needsUpdate = true
    }

    private fun addNormal(
        n: FloatArray,
        v: Int,
        f: Vec3,
    ) {
        n[v * 3] += f.x.toFloat()
        n[v * 3 + 1] += f.y.toFloat()
        n[v * 3 + 2] += f.z.toFloat()
    }

    private fun normalize(n: FloatArray) {
        for (v in 0 until verts) {
            val x = n[v * 3].toDouble()
            val y = n[v * 3 + 1].toDouble()
            val z = n[v * 3 + 2].toDouble()
            val len = sqrt(x * x + y * y + z * z)
            val l = if (len == 0.0) 1.0 else len
            n[v * 3] = (x / l).toFloat()
            n[v * 3 + 1] = (y / l).toFloat()
            n[v * 3 + 2] = (z / l).toFloat()
        }
    }

    /** Frees the buffers through [release] (see the GPU epoch), right away by default. */
    fun dispose(release: (GpuObject?) -> Unit = { it?.dispose() }) {
        release(geo)
    }
}

fun createReins(): Reins = Reins()
