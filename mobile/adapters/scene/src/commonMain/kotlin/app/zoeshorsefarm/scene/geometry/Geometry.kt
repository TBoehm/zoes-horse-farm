package app.zoeshorsefarm.scene.geometry

import app.zoeshorsefarm.scene.GpuResource
import app.zoeshorsefarm.scene.math.Box3
import app.zoeshorsefarm.scene.math.Mat3
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Sphere
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A range of the index (or vertex) list drawn with one material of a material array. */
class GeometryGroup(
    val start: Int,
    val count: Int,
    val materialIndex: Int,
)

/** Which part of the index (or vertices) is drawn (three.js `drawRange`). */
class DrawRange(
    var start: Int = 0,
    var count: Int = Int.MAX_VALUE,
)

/**
 * Mesh data (three.js `BufferGeometry`): named vertex attributes, an optional index, material
 * groups and bounds. Well-known attribute names: `position`, `normal`, `uv`, `color`,
 * `skinIndex`, `skinWeight`; any other name is a custom attribute for a material effect.
 */
open class Geometry : GpuResource() {
    var name: String = ""
    val attributes: MutableMap<String, BufferAttribute> = LinkedHashMap()

    /** Triangle list indices (null = non-indexed); call [markIndexChanged] after editing in place. */
    var index: IntArray? = null
        private set

    /** Incremented whenever the index is replaced or marked changed. */
    var indexVersion: Int = 0
        private set

    /** Incremented whenever an attribute is added, replaced or removed. */
    var structureVersion: Int = 0
        private set

    val groups: MutableList<GeometryGroup> = ArrayList()
    val drawRange: DrawRange = DrawRange()
    var boundingSphere: Sphere? = null
    var boundingBox: Box3? = null
    val userData: MutableMap<String, Any?> = HashMap()

    val indexCount: Int get() = index?.size ?: 0

    fun setAttribute(
        name: String,
        attribute: BufferAttribute,
    ): Geometry {
        attributes[name] = attribute
        structureVersion++
        return this
    }

    fun getAttribute(name: String): BufferAttribute? = attributes[name]

    fun hasAttribute(name: String): Boolean = attributes.containsKey(name)

    fun deleteAttribute(name: String): Geometry {
        attributes.remove(name)
        structureVersion++
        return this
    }

    /** The float attribute `name`; fails if it is missing or not a float attribute. */
    fun float(name: String): FloatAttribute =
        attributes[name] as? FloatAttribute ?: error("geometry has no float attribute '$name'")

    val position: FloatAttribute get() = float("position")
    val normal: FloatAttribute get() = float("normal")
    val uv: FloatAttribute get() = float("uv")
    val color: FloatAttribute get() = float("color")

    fun setIndex(indices: IntArray?): Geometry {
        index = indices
        indexVersion++
        return this
    }

    fun setIndex(indices: List<Int>): Geometry = setIndex(indices.toIntArray())

    fun markIndexChanged() {
        indexVersion++
    }

    fun addGroup(
        start: Int,
        count: Int,
        materialIndex: Int = 0,
    ): Geometry {
        groups.add(GeometryGroup(start, count, materialIndex))
        return this
    }

    fun clearGroups(): Geometry {
        groups.clear()
        return this
    }

    fun setDrawRange(
        start: Int,
        count: Int,
    ): Geometry {
        drawRange.start = start
        drawRange.count = count
        return this
    }

    fun applyMatrix4(matrix: Mat4): Geometry {
        (attributes["position"] as? FloatAttribute)?.let {
            it.applyMatrix4(matrix)
            it.needsUpdate = true
        }
        (attributes["normal"] as? FloatAttribute)?.let {
            it.applyNormalMatrix(Mat3().getNormalMatrix(matrix))
            it.needsUpdate = true
        }
        if (boundingBox != null) computeBoundingBox()
        if (boundingSphere != null) computeBoundingSphere()
        return this
    }

    fun applyQuaternion(q: Quat): Geometry = applyMatrix4(scratch.makeRotationFromQuaternion(q))

    fun rotateX(angle: Double): Geometry = applyMatrix4(scratch.makeRotationX(angle))

    fun rotateY(angle: Double): Geometry = applyMatrix4(scratch.makeRotationY(angle))

    fun rotateZ(angle: Double): Geometry = applyMatrix4(scratch.makeRotationZ(angle))

    fun translate(
        x: Double,
        y: Double,
        z: Double,
    ): Geometry = applyMatrix4(scratch.makeTranslation(x, y, z))

    fun scale(
        x: Double,
        y: Double,
        z: Double,
    ): Geometry = applyMatrix4(scratch.makeScale(x, y, z))

    /** Moves the geometry so that the centre of its bounding box is the origin. */
    fun center(): Geometry {
        computeBoundingBox()
        val offset = Vec3()
        (boundingBox ?: return this).getCenter(offset).negate()
        return translate(offset.x, offset.y, offset.z)
    }

    fun computeBoundingBox() {
        val box = boundingBox ?: Box3().also { boundingBox = it }
        val pos = attributes["position"] as? FloatAttribute
        if (pos == null) {
            box.makeEmpty()
            return
        }
        box.makeEmpty()
        for (i in 0 until pos.count) box.expandByPoint(scratch2.set(pos.getX(i), pos.getY(i), pos.getZ(i)))
    }

    fun computeBoundingSphere() {
        val sphere = boundingSphere ?: Sphere().also { boundingSphere = it }
        val pos = attributes["position"] as? FloatAttribute ?: return
        val center = sphere.center
        scratchBox.makeEmpty()
        for (i in 0 until pos.count) scratchBox.expandByPoint(scratch2.set(pos.getX(i), pos.getY(i), pos.getZ(i)))
        scratchBox.getCenter(center)
        var maxRadiusSq = 0.0
        for (i in 0 until pos.count) {
            scratch2.set(pos.getX(i), pos.getY(i), pos.getZ(i))
            maxRadiusSq = max(maxRadiusSq, center.distanceToSquared(scratch2))
        }
        sphere.radius = sqrt(maxRadiusSq)
    }

    /** Area-weighted vertex normals (smooth when the index shares vertices, flat when it does not). */
    fun computeVertexNormals() {
        val idx = index
        val positionAttribute = attributes["position"] as? FloatAttribute ?: return
        var normalAttribute = attributes["normal"] as? FloatAttribute
        if (normalAttribute == null || normalAttribute.count != positionAttribute.count) {
            normalAttribute = FloatAttribute(FloatArray(positionAttribute.count * 3), 3)
            setAttribute("normal", normalAttribute)
        } else {
            for (i in 0 until normalAttribute.count) normalAttribute.setXYZ(i, 0.0, 0.0, 0.0)
        }
        val pA = Vec3()
        val pB = Vec3()
        val pC = Vec3()
        val nA = Vec3()
        val nB = Vec3()
        val nC = Vec3()
        val cb = Vec3()
        val ab = Vec3()
        if (idx != null) {
            var i = 0
            while (i < idx.size) {
                val vA = idx[i]
                val vB = idx[i + 1]
                val vC = idx[i + 2]
                positionAttribute.getVec3(vA, pA)
                positionAttribute.getVec3(vB, pB)
                positionAttribute.getVec3(vC, pC)
                cb.subVectors(pC, pB)
                ab.subVectors(pA, pB)
                cb.cross(ab)
                normalAttribute.getVec3(vA, nA)
                normalAttribute.getVec3(vB, nB)
                normalAttribute.getVec3(vC, nC)
                nA.add(cb)
                nB.add(cb)
                nC.add(cb)
                normalAttribute.setXYZ(vA, nA.x, nA.y, nA.z)
                normalAttribute.setXYZ(vB, nB.x, nB.y, nB.z)
                normalAttribute.setXYZ(vC, nC.x, nC.y, nC.z)
                i += 3
            }
        } else {
            var i = 0
            while (i < positionAttribute.count) {
                positionAttribute.getVec3(i, pA)
                positionAttribute.getVec3(i + 1, pB)
                positionAttribute.getVec3(i + 2, pC)
                cb.subVectors(pC, pB)
                ab.subVectors(pA, pB)
                cb.cross(ab)
                normalAttribute.setXYZ(i, cb.x, cb.y, cb.z)
                normalAttribute.setXYZ(i + 1, cb.x, cb.y, cb.z)
                normalAttribute.setXYZ(i + 2, cb.x, cb.y, cb.z)
                i += 3
            }
        }
        normalizeNormals()
        normalAttribute.needsUpdate = true
    }

    fun normalizeNormals() {
        val normals = normal
        for (i in 0 until normals.count) {
            scratch2.set(normals.getX(i), normals.getY(i), normals.getZ(i)).normalize()
            normals.setXYZ(i, scratch2.x, scratch2.y, scratch2.z)
        }
    }

    /** Copy without the index: every triangle gets its own vertices (flat shading, per-face colours). */
    fun toNonIndexed(): Geometry {
        val idx = index ?: return this
        val result = Geometry()
        for ((attrName, attribute) in attributes) {
            result.setAttribute(attrName, expand(attribute, idx))
        }
        for (g in groups) result.addGroup(g.start, g.count, g.materialIndex)
        return result
    }

    private fun expand(
        attribute: BufferAttribute,
        indices: IntArray,
    ): BufferAttribute {
        val itemSize = attribute.itemSize
        return when (attribute) {
            is FloatAttribute -> {
                val out = FloatArray(indices.size * itemSize)
                var o = 0
                for (i in indices) {
                    val base = i * itemSize
                    for (j in 0 until itemSize) out[o++] = attribute.array[base + j]
                }
                FloatAttribute(out, itemSize)
            }

            is UShortAttribute -> {
                val out = ShortArray(indices.size * itemSize)
                var o = 0
                for (i in indices) {
                    val base = i * itemSize
                    for (j in 0 until itemSize) out[o++] = attribute.array[base + j]
                }
                UShortAttribute(out, itemSize)
            }
        }
    }

    /** Deep copy (attributes, index, groups, bounds, draw range). The GPU copy is not shared. */
    open fun clone(): Geometry {
        val copy = Geometry()
        copy.name = name
        index?.let { copy.setIndex(it.copyOf()) }
        for ((attrName, attribute) in attributes) copy.setAttribute(attrName, attribute.clone())
        for (g in groups) copy.addGroup(g.start, g.count, g.materialIndex)
        boundingBox?.let { copy.boundingBox = it.clone() }
        boundingSphere?.let { copy.boundingSphere = it.clone() }
        copy.drawRange.start = drawRange.start
        copy.drawRange.count = drawRange.count
        copy.userData.putAll(userData)
        return copy
    }

    /** Number of vertices (position count), 0 without positions. */
    val vertexCount: Int get() = (attributes["position"] as? FloatAttribute)?.count ?: 0

    /** Triangles that are drawn, honouring the index and the draw range (see SceneStats). */
    val drawnTriangles: Int
        get() {
            if (!hasAttribute("position")) return 0
            val total = index?.size ?: vertexCount
            val used = max(0, min(total - drawRange.start, drawRange.count))
            return used / 3
        }

    private companion object {
        val scratch = Mat4()
        val scratch2 = Vec3()
        val scratchBox = Box3()
    }
}
