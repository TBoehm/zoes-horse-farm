package app.zoeshorsefarm.scene.geometry

import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Builders with the same vertex and index layout (and triangle counts) as the three.js r186
// geometries of the same name. They are functions named like the classes so that
// `new THREE.BoxGeometry(1, 2, 3)` ports to `BoxGeometry(1.0, 2.0, 3.0)`.

private fun finish(
    vertices: FloatBuf,
    normals: FloatBuf,
    uvs: FloatBuf,
    indices: IntBuf?,
    geometry: Geometry,
): Geometry {
    if (indices != null) geometry.setIndex(indices.toIntArray())
    geometry.setAttribute("position", vertices.toAttribute(3))
    geometry.setAttribute("normal", normals.toAttribute(3))
    geometry.setAttribute("uv", uvs.toAttribute(2))
    return geometry
}

/** Box with 24 vertices and 12 triangles (one group per face when segments are 1). */
fun BoxGeometry(
    width: Double = 1.0,
    height: Double = 1.0,
    depth: Double = 1.0,
    widthSegments: Int = 1,
    heightSegments: Int = 1,
    depthSegments: Int = 1,
): Geometry {
    val geometry = Geometry()
    val indices = IntBuf()
    val vertices = FloatBuf()
    val normals = FloatBuf()
    val uvs = FloatBuf()
    var numberOfVertices = 0
    var groupStart = 0

    // u, v, w are the axis numbers (0 = x, 1 = y, 2 = z) of the plane's width, height and depth
    fun buildPlane(
        u: Int,
        v: Int,
        w: Int,
        udir: Int,
        vdir: Int,
        planeWidth: Double,
        planeHeight: Double,
        planeDepth: Double,
        gridX: Int,
        gridY: Int,
        materialIndex: Int,
    ) {
        val segmentWidth = planeWidth / gridX
        val segmentHeight = planeHeight / gridY
        val widthHalf = planeWidth / 2
        val heightHalf = planeHeight / 2
        val depthHalf = planeDepth / 2
        val gridX1 = gridX + 1
        val gridY1 = gridY + 1
        var vertexCounter = 0
        var groupCount = 0
        val vector = DoubleArray(3)
        for (iy in 0 until gridY1) {
            val y = iy * segmentHeight - heightHalf
            for (ix in 0 until gridX1) {
                val x = ix * segmentWidth - widthHalf
                vector[u] = x * udir
                vector[v] = y * vdir
                vector[w] = depthHalf
                vertices.add(vector[0], vector[1], vector[2])
                vector[u] = 0.0
                vector[v] = 0.0
                vector[w] = if (planeDepth > 0) 1.0 else -1.0
                normals.add(vector[0], vector[1], vector[2])
                uvs.add(ix.toDouble() / gridX)
                uvs.add(1 - (iy.toDouble() / gridY))
                vertexCounter += 1
            }
        }
        for (iy in 0 until gridY) {
            for (ix in 0 until gridX) {
                val a = numberOfVertices + ix + gridX1 * iy
                val b = numberOfVertices + ix + gridX1 * (iy + 1)
                val c = numberOfVertices + (ix + 1) + gridX1 * (iy + 1)
                val d = numberOfVertices + (ix + 1) + gridX1 * iy
                indices.add(a, b, d)
                indices.add(b, c, d)
                groupCount += 6
            }
        }
        geometry.addGroup(groupStart, groupCount, materialIndex)
        groupStart += groupCount
        numberOfVertices += vertexCounter
    }

    buildPlane(2, 1, 0, -1, -1, depth, height, width, depthSegments, heightSegments, 0) // px
    buildPlane(2, 1, 0, 1, -1, depth, height, -width, depthSegments, heightSegments, 1) // nx
    buildPlane(0, 2, 1, 1, 1, width, depth, height, widthSegments, depthSegments, 2) // py
    buildPlane(0, 2, 1, 1, -1, width, depth, -height, widthSegments, depthSegments, 3) // ny
    buildPlane(0, 1, 2, 1, -1, width, height, depth, widthSegments, heightSegments, 4) // pz
    buildPlane(0, 1, 2, -1, -1, width, height, -depth, widthSegments, heightSegments, 5) // nz
    return finish(vertices, normals, uvs, indices, geometry)
}

/** Plane in the XY plane facing +Z. */
fun PlaneGeometry(
    width: Double = 1.0,
    height: Double = 1.0,
    widthSegments: Int = 1,
    heightSegments: Int = 1,
): Geometry {
    val widthHalf = width / 2
    val heightHalf = height / 2
    val gridX1 = widthSegments + 1
    val gridY1 = heightSegments + 1
    val segmentWidth = width / widthSegments
    val segmentHeight = height / heightSegments
    val indices = IntBuf()
    val vertices = FloatBuf()
    val normals = FloatBuf()
    val uvs = FloatBuf()
    for (iy in 0 until gridY1) {
        val y = iy * segmentHeight - heightHalf
        for (ix in 0 until gridX1) {
            val x = ix * segmentWidth - widthHalf
            vertices.add(x, -y, 0.0)
            normals.add(0.0, 0.0, 1.0)
            uvs.add(ix.toDouble() / widthSegments)
            uvs.add(1 - (iy.toDouble() / heightSegments))
        }
    }
    for (iy in 0 until heightSegments) {
        for (ix in 0 until widthSegments) {
            val a = ix + gridX1 * iy
            val b = ix + gridX1 * (iy + 1)
            val c = (ix + 1) + gridX1 * (iy + 1)
            val d = (ix + 1) + gridX1 * iy
            indices.add(a, b, d)
            indices.add(b, c, d)
        }
    }
    return finish(vertices, normals, uvs, indices, Geometry())
}

/** Cylinder along Y (top at +height/2) with optional caps; groups: 0 torso, 1 top cap, 2 bottom cap. */
@Suppress("CyclomaticComplexMethod") // direct port of three.js CylinderGeometry
fun CylinderGeometry(
    radiusTop: Double = 1.0,
    radiusBottom: Double = 1.0,
    height: Double = 1.0,
    radialSegments: Int = 32,
    heightSegments: Int = 1,
    openEnded: Boolean = false,
    thetaStart: Double = 0.0,
    thetaLength: Double = PI * 2,
): Geometry {
    val geometry = Geometry()
    val indices = IntBuf()
    val vertices = FloatBuf()
    val normals = FloatBuf()
    val uvs = FloatBuf()
    var index = 0
    val indexArray = ArrayList<IntArray>()
    val halfHeight = height / 2
    var groupStart = 0

    fun generateTorso() {
        val normal = Vec3()
        var groupCount = 0
        val slope = (radiusBottom - radiusTop) / height
        for (y in 0..heightSegments) {
            val indexRow = IntArray(radialSegments + 1)
            val v = y.toDouble() / heightSegments
            val radius = v * (radiusBottom - radiusTop) + radiusTop
            for (x in 0..radialSegments) {
                val u = x.toDouble() / radialSegments
                val theta = u * thetaLength + thetaStart
                val sinTheta = sin(theta)
                val cosTheta = cos(theta)
                vertices.add(radius * sinTheta, -v * height + halfHeight, radius * cosTheta)
                normal.set(sinTheta, slope, cosTheta).normalize()
                normals.add(normal.x, normal.y, normal.z)
                uvs.add(u, 1 - v)
                indexRow[x] = index++
            }
            indexArray.add(indexRow)
        }
        for (x in 0 until radialSegments) {
            for (y in 0 until heightSegments) {
                val a = indexArray[y][x]
                val b = indexArray[y + 1][x]
                val c = indexArray[y + 1][x + 1]
                val d = indexArray[y][x + 1]
                if (radiusTop > 0 || y != 0) {
                    indices.add(a, b, d)
                    groupCount += 3
                }
                if (radiusBottom > 0 || y != heightSegments - 1) {
                    indices.add(b, c, d)
                    groupCount += 3
                }
            }
        }
        geometry.addGroup(groupStart, groupCount, 0)
        groupStart += groupCount
    }

    fun generateCap(top: Boolean) {
        val centerIndexStart = index
        var groupCount = 0
        val radius = if (top) radiusTop else radiusBottom
        val sign = if (top) 1.0 else -1.0
        for (x in 1..radialSegments) {
            vertices.add(0.0, halfHeight * sign, 0.0)
            normals.add(0.0, sign, 0.0)
            uvs.add(0.5, 0.5)
            index++
        }
        val centerIndexEnd = index
        for (x in 0..radialSegments) {
            val u = x.toDouble() / radialSegments
            val theta = u * thetaLength + thetaStart
            val cosTheta = cos(theta)
            val sinTheta = sin(theta)
            vertices.add(radius * sinTheta, halfHeight * sign, radius * cosTheta)
            normals.add(0.0, sign, 0.0)
            uvs.add((cosTheta * 0.5) + 0.5, (sinTheta * 0.5 * sign) + 0.5)
            index++
        }
        for (x in 0 until radialSegments) {
            val c = centerIndexStart + x
            val i = centerIndexEnd + x
            if (top) indices.add(i, i + 1, c) else indices.add(i + 1, i, c)
            groupCount += 3
        }
        geometry.addGroup(groupStart, groupCount, if (top) 1 else 2)
        groupStart += groupCount
    }

    generateTorso()
    if (!openEnded) {
        if (radiusTop > 0) generateCap(true)
        if (radiusBottom > 0) generateCap(false)
    }
    return finish(vertices, normals, uvs, indices, geometry)
}

/** Cone along Y with the tip at +height/2 (a cylinder with a top radius of 0). */
fun ConeGeometry(
    radius: Double = 1.0,
    height: Double = 1.0,
    radialSegments: Int = 32,
    heightSegments: Int = 1,
    openEnded: Boolean = false,
    thetaStart: Double = 0.0,
    thetaLength: Double = PI * 2,
): Geometry = CylinderGeometry(0.0, radius, height, radialSegments, heightSegments, openEnded, thetaStart, thetaLength)

/** UV sphere; the poles are single rows of degenerate-free triangles. */
fun SphereGeometry(
    radius: Double = 1.0,
    widthSegments: Int = 32,
    heightSegments: Int = 16,
    phiStart: Double = 0.0,
    phiLength: Double = PI * 2,
    thetaStart: Double = 0.0,
    thetaLength: Double = PI,
): Geometry {
    val wSeg = max(3, widthSegments)
    val hSeg = max(2, heightSegments)
    val thetaEnd = min(thetaStart + thetaLength, PI)
    var index = 0
    val grid = ArrayList<IntArray>()
    val normal = Vec3()
    val indices = IntBuf()
    val vertices = FloatBuf()
    val normals = FloatBuf()
    val uvs = FloatBuf()
    for (iy in 0..hSeg) {
        val verticesRow = IntArray(wSeg + 1)
        val v = iy.toDouble() / hSeg
        val theta = thetaStart + v * thetaLength
        val y = radius * cos(theta)
        val ringRadius = sqrt(radius * radius - y * y)
        var uOffset = 0.0
        if (iy == 0 && thetaStart == 0.0) {
            uOffset = 0.5 / wSeg
        } else if (iy == hSeg && thetaEnd == PI) {
            uOffset = -0.5 / wSeg
        }
        for (ix in 0..wSeg) {
            val u = ix.toDouble() / wSeg
            val phi = phiStart + u * phiLength
            val vx = -ringRadius * cos(phi)
            val vz = ringRadius * sin(phi)
            vertices.add(vx, y, vz)
            normal.set(vx, y, vz).normalize()
            normals.add(normal.x, normal.y, normal.z)
            uvs.add(u + uOffset, 1 - v)
            verticesRow[ix] = index++
        }
        grid.add(verticesRow)
    }
    for (iy in 0 until hSeg) {
        for (ix in 0 until wSeg) {
            val a = grid[iy][ix + 1]
            val b = grid[iy][ix]
            val c = grid[iy + 1][ix]
            val d = grid[iy + 1][ix + 1]
            if (iy != 0 || thetaStart > 0) indices.add(a, b, d)
            if (iy != hSeg - 1 || thetaEnd < PI) indices.add(b, c, d)
        }
    }
    return finish(vertices, normals, uvs, indices, Geometry())
}

/** Flat-shaded (detail 0) or smooth (detail > 0) polyhedron, non-indexed like in three.js. */
private fun PolyhedronGeometry(
    vertices: DoubleArray,
    indices: IntArray,
    radius: Double,
    detail: Int,
): Geometry = PolyhedronBuilder(vertices, indices).build(radius, detail)

/** The steps of three.js `PolyhedronGeometry`: subdivide, project onto the sphere, generate and fix the UVs. */
private class PolyhedronBuilder(
    private val vertices: DoubleArray,
    private val indices: IntArray,
) {
    private val vertexBuffer = DoubleBuf()
    private val uvBuffer = DoubleBuf()

    fun build(
        radius: Double,
        detail: Int,
    ): Geometry {
        subdivide(detail)
        applyRadius(radius)
        generateUVs()
        val geometry = Geometry()
        geometry.setAttribute("position", vertexBuffer.toAttribute(3))
        geometry.setAttribute("normal", vertexBuffer.toAttribute(3))
        geometry.setAttribute("uv", uvBuffer.toAttribute(2))
        if (detail == 0) geometry.computeVertexNormals() else geometry.normalizeNormals()
        return geometry
    }

    private fun subdivide(detail: Int) {
        val a = Vec3()
        val b = Vec3()
        val c = Vec3()
        var i = 0
        while (i < indices.size) {
            getVertexByIndex(indices[i], a)
            getVertexByIndex(indices[i + 1], b)
            getVertexByIndex(indices[i + 2], c)
            subdivideFace(a, b, c, detail)
            i += 3
        }
    }

    private fun getVertexByIndex(
        i: Int,
        vertex: Vec3,
    ) {
        val stride = i * 3
        vertex.x = vertices[stride]
        vertex.y = vertices[stride + 1]
        vertex.z = vertices[stride + 2]
    }

    private fun pushVertex(vertex: Vec3) = vertexBuffer.add(vertex.x, vertex.y, vertex.z)

    private fun subdivideFace(
        a: Vec3,
        b: Vec3,
        c: Vec3,
        detail: Int,
    ) {
        val cols = detail + 1
        val v = ArrayList<Array<Vec3>>()
        for (i in 0..cols) {
            val rows = cols - i
            val aj = a.clone().lerp(c, i.toDouble() / cols)
            val bj = b.clone().lerp(c, i.toDouble() / cols)
            v.add(Array(rows + 1) { j -> if (j == 0 && i == cols) aj else aj.clone().lerp(bj, j.toDouble() / rows) })
        }
        for (i in 0 until cols) {
            for (j in 0 until 2 * (cols - i) - 1) {
                val k = j / 2
                if (j % 2 == 0) {
                    pushVertex(v[i][k + 1])
                    pushVertex(v[i + 1][k])
                    pushVertex(v[i][k])
                } else {
                    pushVertex(v[i][k + 1])
                    pushVertex(v[i + 1][k + 1])
                    pushVertex(v[i + 1][k])
                }
            }
        }
    }

    private fun applyRadius(radius: Double) {
        val vertex = Vec3()
        var i = 0
        while (i < vertexBuffer.size) {
            vertex.set(vertexBuffer[i], vertexBuffer[i + 1], vertexBuffer[i + 2]).normalize().multiplyScalar(radius)
            vertexBuffer[i] = vertex.x
            vertexBuffer[i + 1] = vertex.y
            vertexBuffer[i + 2] = vertex.z
            i += 3
        }
    }

    private fun generateUVs() {
        val vertex = Vec3()
        var i = 0
        while (i < vertexBuffer.size) {
            vertex.set(vertexBuffer[i], vertexBuffer[i + 1], vertexBuffer[i + 2])
            uvBuffer.add(azimuth(vertex) / 2 / PI + 0.5, 1 - (inclination(vertex) / PI + 0.5))
            i += 3
        }
        correctUVs()
        correctSeam()
    }

    private fun azimuth(vector: Vec3): Double = atan2(vector.z, -vector.x)

    private fun inclination(vector: Vec3): Double =
        atan2(-vector.y, sqrt((vector.x * vector.x) + (vector.z * vector.z)))

    private fun correctUV(
        uv: Vec2,
        stride: Int,
        vector: Vec3,
        azimuth: Double,
    ) {
        if (azimuth < 0 && uv.x == 1.0) uvBuffer[stride] = uv.x - 1
        if (vector.x == 0.0 && vector.z == 0.0) uvBuffer[stride] = azimuth / 2 / PI + 0.5
    }

    private fun correctUVs() {
        val a = Vec3()
        val b = Vec3()
        val c = Vec3()
        val centroid = Vec3()
        val uvA = Vec2()
        val uvB = Vec2()
        val uvC = Vec2()
        var i = 0
        var j = 0
        while (i < vertexBuffer.size) {
            a.set(vertexBuffer[i], vertexBuffer[i + 1], vertexBuffer[i + 2])
            b.set(vertexBuffer[i + 3], vertexBuffer[i + 4], vertexBuffer[i + 5])
            c.set(vertexBuffer[i + 6], vertexBuffer[i + 7], vertexBuffer[i + 8])
            uvA.set(uvBuffer[j], uvBuffer[j + 1])
            uvB.set(uvBuffer[j + 2], uvBuffer[j + 3])
            uvC.set(uvBuffer[j + 4], uvBuffer[j + 5])
            centroid
                .copy(a)
                .add(b)
                .add(c)
                .divideScalar(3.0)
            val azi = azimuth(centroid)
            correctUV(uvA, j, a, azi)
            correctUV(uvB, j + 2, b, azi)
            correctUV(uvC, j + 4, c, azi)
            i += 9
            j += 6
        }
    }

    private fun correctSeam() {
        var i = 0
        while (i < uvBuffer.size) {
            val x0 = uvBuffer[i]
            val x1 = uvBuffer[i + 2]
            val x2 = uvBuffer[i + 4]
            val maxX = max(x0, max(x1, x2))
            val minX = min(x0, min(x1, x2))
            if (maxX > 0.9 && minX < 0.1) {
                if (x0 < 0.2) uvBuffer[i] = uvBuffer[i] + 1
                if (x1 < 0.2) uvBuffer[i + 2] = uvBuffer[i + 2] + 1
                if (x2 < 0.2) uvBuffer[i + 4] = uvBuffer[i + 4] + 1
            }
            i += 6
        }
    }
}

private val ICOSAHEDRON_T = (1 + sqrt(5.0)) / 2

private val ICOSAHEDRON_VERTICES =
    doubleArrayOf(
        -1.0,
        ICOSAHEDRON_T,
        0.0,
        1.0,
        ICOSAHEDRON_T,
        0.0,
        -1.0,
        -ICOSAHEDRON_T,
        0.0,
        1.0,
        -ICOSAHEDRON_T,
        0.0,
    ) +
        doubleArrayOf(
            0.0,
            -1.0,
            ICOSAHEDRON_T,
            0.0,
            1.0,
            ICOSAHEDRON_T,
            0.0,
            -1.0,
            -ICOSAHEDRON_T,
            0.0,
            1.0,
            -ICOSAHEDRON_T,
        ) +
        doubleArrayOf(
            ICOSAHEDRON_T,
            0.0,
            -1.0,
            ICOSAHEDRON_T,
            0.0,
            1.0,
            -ICOSAHEDRON_T,
            0.0,
            -1.0,
            -ICOSAHEDRON_T,
            0.0,
            1.0,
        )

private val ICOSAHEDRON_INDICES =
    intArrayOf(0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11) +
        intArrayOf(1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8) +
        intArrayOf(3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9) +
        intArrayOf(4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1)

fun IcosahedronGeometry(
    radius: Double = 1.0,
    detail: Int = 0,
): Geometry = PolyhedronGeometry(ICOSAHEDRON_VERTICES, ICOSAHEDRON_INDICES, radius, detail)

fun OctahedronGeometry(
    radius: Double = 1.0,
    detail: Int = 0,
): Geometry {
    val vertices =
        doubleArrayOf(1.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0)
    val indices = intArrayOf(0, 2, 4, 0, 4, 3, 0, 3, 5, 0, 5, 2, 1, 2, 5, 1, 5, 3, 1, 3, 4, 1, 4, 2)
    return PolyhedronGeometry(vertices, indices, radius, detail)
}

fun TetrahedronGeometry(
    radius: Double = 1.0,
    detail: Int = 0,
): Geometry {
    val vertices = doubleArrayOf(1.0, 1.0, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0, -1.0)
    val indices = intArrayOf(2, 1, 0, 0, 3, 2, 1, 3, 0, 2, 3, 1)
    return PolyhedronGeometry(vertices, indices, radius, detail)
}

/** Torus in the XY plane around Z. */
fun TorusGeometry(
    radius: Double = 1.0,
    tube: Double = 0.4,
    radialSegments: Int = 12,
    tubularSegments: Int = 48,
    arc: Double = PI * 2,
    thetaStart: Double = 0.0,
    thetaLength: Double = PI * 2,
): Geometry {
    val indices = IntBuf()
    val vertices = FloatBuf()
    val normals = FloatBuf()
    val uvs = FloatBuf()
    val center = Vec3()
    val vertex = Vec3()
    val normal = Vec3()
    for (j in 0..radialSegments) {
        val v = thetaStart + (j.toDouble() / radialSegments) * thetaLength
        for (i in 0..tubularSegments) {
            val u = i.toDouble() / tubularSegments * arc
            vertex.x = (radius + tube * cos(v)) * cos(u)
            vertex.y = (radius + tube * cos(v)) * sin(u)
            vertex.z = tube * sin(v)
            vertices.add(vertex.x, vertex.y, vertex.z)
            center.x = radius * cos(u)
            center.y = radius * sin(u)
            normal.subVectors(vertex, center).normalize()
            normals.add(normal.x, normal.y, normal.z)
            uvs.add(i.toDouble() / tubularSegments)
            uvs.add(j.toDouble() / radialSegments)
        }
    }
    for (j in 1..radialSegments) {
        for (i in 1..tubularSegments) {
            val a = (tubularSegments + 1) * j + i - 1
            val b = (tubularSegments + 1) * (j - 1) + i - 1
            val c = (tubularSegments + 1) * (j - 1) + i
            val d = (tubularSegments + 1) * j + i
            indices.add(a, b, d)
            indices.add(b, c, d)
        }
    }
    return finish(vertices, normals, uvs, indices, Geometry())
}

/** Disc (triangle fan) in the XY plane facing +Z. */
fun CircleGeometry(
    radius: Double = 1.0,
    segments: Int = 32,
    thetaStart: Double = 0.0,
    thetaLength: Double = PI * 2,
): Geometry {
    val seg = max(3, segments)
    val indices = IntBuf()
    val vertices = DoubleBuf()
    val normals = FloatBuf()
    val uvs = FloatBuf()
    vertices.add(0.0, 0.0, 0.0)
    normals.add(0.0, 0.0, 1.0)
    uvs.add(0.5, 0.5)
    var i = 3
    for (s in 0..seg) {
        val segment = thetaStart + s.toDouble() / seg * thetaLength
        vertices.add(radius * cos(segment), radius * sin(segment), 0.0)
        normals.add(0.0, 0.0, 1.0)
        uvs.add((vertices[i] / radius + 1) / 2, (vertices[i + 1] / radius + 1) / 2)
        i += 3
    }
    for (k in 1..seg) indices.add(k, k + 1, 0)
    val geometry = Geometry()
    geometry.setIndex(indices.toIntArray())
    geometry.setAttribute("position", vertices.toAttribute(3))
    geometry.setAttribute("normal", normals.toAttribute(3))
    geometry.setAttribute("uv", uvs.toAttribute(2))
    return geometry
}

/** Triangulated flat shapes (with holes) in the XY plane, facing +Z; UVs are the world XY. */
fun ShapeGeometry(
    shapes: List<Shape>,
    curveSegments: Int = 12,
): Geometry {
    val geometry = Geometry()
    val indices = IntBuf()
    val vertices = FloatBuf()
    val normals = FloatBuf()
    val uvs = FloatBuf()
    var groupStart = 0
    var groupCount = 0

    fun addShape(shape: Shape) {
        val indexOffset = vertices.size / 3
        var shapeVertices: MutableList<Vec2> = shape.getPoints(curveSegments).toMutableList()
        val shapeHoles: MutableList<MutableList<Vec2>> =
            shape
                .getPointsHoles(curveSegments)
                .map { it.toMutableList() }
                .toMutableList()
        if (!ShapeUtils.isClockWise(shapeVertices)) shapeVertices = shapeVertices.reversed().toMutableList()
        for (i in shapeHoles.indices) {
            if (ShapeUtils.isClockWise(shapeHoles[i])) shapeHoles[i] = shapeHoles[i].reversed().toMutableList()
        }
        val faces = ShapeUtils.triangulateShape(shapeVertices, shapeHoles)
        for (hole in shapeHoles) shapeVertices = (shapeVertices + hole).toMutableList()
        for (vertex in shapeVertices) {
            vertices.add(vertex.x, vertex.y, 0.0)
            normals.add(0.0, 0.0, 1.0)
            uvs.add(vertex.x, vertex.y)
        }
        for (face in faces) {
            indices.add(face[0] + indexOffset, face[1] + indexOffset, face[2] + indexOffset)
            groupCount += 3
        }
    }

    if (shapes.size == 1) {
        addShape(shapes[0])
    } else {
        for (i in shapes.indices) {
            addShape(shapes[i])
            geometry.addGroup(groupStart, groupCount, i)
            groupStart += groupCount
            groupCount = 0
        }
    }
    return finish(vertices, normals, uvs, indices, geometry)
}

fun ShapeGeometry(
    shape: Shape,
    curveSegments: Int = 12,
): Geometry = ShapeGeometry(listOf(shape), curveSegments)
