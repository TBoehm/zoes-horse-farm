package app.zoeshorsefarm.scene.geometry

import app.zoeshorsefarm.scene.math.Vec2
import kotlin.math.abs
import kotlin.math.max

/**
 * Prism from a flat shape, extruded along +Z by `depth` (three.js `ExtrudeGeometry` without bevels
 * and without extrusion paths, which is all the game uses). Non-indexed with flat normals;
 * group 0 is the two lids, group 1 the side walls.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // direct port of three.js ExtrudeGeometry (no bevel)
fun ExtrudeGeometry(
    shapes: List<Shape>,
    depth: Double = 1.0,
    steps: Int = 1,
    curveSegments: Int = 12,
): Geometry {
    val geometry = Geometry()
    val verticesArray = DoubleBuf()
    val uvArray = DoubleBuf()

    fun addShape(shape: Shape) {
        val placeholder = DoubleBuf()
        var vertices: MutableList<Vec2> = shape.getPoints(curveSegments).toMutableList()
        val holes: MutableList<MutableList<Vec2>> =
            shape.getPointsHoles(curveSegments).map { it.toMutableList() }.toMutableList()
        val reverse = !ShapeUtils.isClockWise(vertices)
        if (reverse) {
            vertices = vertices.reversed().toMutableList()
            for (h in holes.indices) {
                if (ShapeUtils.isClockWise(holes[h])) holes[h] = holes[h].reversed().toMutableList()
            }
        }
        mergeOverlappingPoints(vertices)
        holes.forEach { mergeOverlappingPoints(it) }
        val contour = vertices // only the points of the circumference
        for (hole in holes) vertices = (vertices + hole).toMutableList()
        val vlen = vertices.size
        val faces = ShapeUtils.triangulateShape(contour, holes)
        val flen = faces.size

        fun v(
            x: Double,
            y: Double,
            z: Double,
        ) = placeholder.add(x, y, z)

        for (i in 0 until vlen) v(vertices[i].x, vertices[i].y, 0.0)
        for (s in 1..steps) {
            for (i in 0 until vlen) v(vertices[i].x, vertices[i].y, depth / steps * s)
        }

        fun addVertex(index: Int) {
            verticesArray.add(placeholder[index * 3], placeholder[index * 3 + 1], placeholder[index * 3 + 2])
        }

        fun f3(
            a: Int,
            b: Int,
            c: Int,
        ) {
            addVertex(a)
            addVertex(b)
            addVertex(c)
            val next = verticesArray.size / 3
            // world UVs: the XY of the vertex
            for (k in intArrayOf(next - 3, next - 2, next - 1)) {
                uvArray.add(verticesArray[k * 3], verticesArray[k * 3 + 1])
            }
        }

        fun f4(
            a: Int,
            b: Int,
            c: Int,
            d: Int,
        ) {
            addVertex(a)
            addVertex(b)
            addVertex(d)
            addVertex(b)
            addVertex(c)
            addVertex(d)
            val next = verticesArray.size / 3
            val iA = next - 6
            val iB = next - 3
            val iC = next - 2
            val iD = next - 1
            val ax = verticesArray[iA * 3]
            val ay = verticesArray[iA * 3 + 1]
            val az = verticesArray[iA * 3 + 2]
            val bx = verticesArray[iB * 3]
            val by = verticesArray[iB * 3 + 1]
            val bz = verticesArray[iB * 3 + 2]
            val cx = verticesArray[iC * 3]
            val cy = verticesArray[iC * 3 + 1]
            val cz = verticesArray[iC * 3 + 2]
            val dx = verticesArray[iD * 3]
            val dy = verticesArray[iD * 3 + 1]
            val dz = verticesArray[iD * 3 + 2]
            val uvs =
                if (abs(ay - by) < abs(ax - bx)) {
                    doubleArrayOf(ax, 1 - az, bx, 1 - bz, cx, 1 - cz, dx, 1 - dz)
                } else {
                    doubleArrayOf(ay, 1 - az, by, 1 - bz, cy, 1 - cz, dy, 1 - dz)
                }
            // the triangles are (a, b, d) and (b, c, d)
            uvArray.add(uvs[0], uvs[1])
            uvArray.add(uvs[2], uvs[3])
            uvArray.add(uvs[6], uvs[7])
            uvArray.add(uvs[2], uvs[3])
            uvArray.add(uvs[4], uvs[5])
            uvArray.add(uvs[6], uvs[7])
        }

        // lids
        var start = verticesArray.size / 3
        for (face in faces) f3(face[2], face[1], face[0])
        for (face in faces) f3(face[0] + vlen * steps, face[1] + vlen * steps, face[2] + vlen * steps)
        geometry.addGroup(start, verticesArray.size / 3 - start, 0)

        // side walls
        fun sidewalls(
            ring: List<Vec2>,
            layerOffset: Int,
        ) {
            var i = ring.size
            while (--i >= 0) {
                val j = i
                var k = i - 1
                if (k < 0) k = ring.size - 1
                for (s in 0 until steps) {
                    val slen1 = vlen * s
                    val slen2 = vlen * (s + 1)
                    val a = layerOffset + j + slen1
                    val b = layerOffset + k + slen1
                    val c = layerOffset + k + slen2
                    val d = layerOffset + j + slen2
                    f4(a, b, c, d)
                }
            }
        }
        start = verticesArray.size / 3
        var layerOffset = 0
        sidewalls(contour, layerOffset)
        layerOffset += contour.size
        for (hole in holes) {
            sidewalls(hole, layerOffset)
            layerOffset += hole.size
        }
        geometry.addGroup(start, verticesArray.size / 3 - start, 1)
    }

    for (shape in shapes) addShape(shape)
    geometry.setAttribute("position", verticesArray.toAttribute(3))
    geometry.setAttribute("uv", uvArray.toAttribute(2))
    geometry.computeVertexNormals()
    return geometry
}

fun ExtrudeGeometry(
    shape: Shape,
    depth: Double = 1.0,
    steps: Int = 1,
    curveSegments: Int = 12,
): Geometry = ExtrudeGeometry(listOf(shape), depth, steps, curveSegments)

private fun mergeOverlappingPoints(points: MutableList<Vec2>) {
    val threshold = 1e-10
    val thresholdSq = threshold * threshold
    var prevPos = points[0]
    var i = 1
    while (i <= points.size) {
        val currentIndex = i % points.size
        val currentPos = points[currentIndex]
        val dx = currentPos.x - prevPos.x
        val dy = currentPos.y - prevPos.y
        val distSq = dx * dx + dy * dy
        val scalingFactorSqrt = max(max(abs(currentPos.x), abs(currentPos.y)), max(abs(prevPos.x), abs(prevPos.y)))
        val thresholdSqScaled = thresholdSq * scalingFactorSqrt * scalingFactorSqrt
        if (distSq <= thresholdSqScaled) {
            points.removeAt(currentIndex)
            continue
        }
        prevPos = currentPos
        i++
    }
}
