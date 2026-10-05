package app.zoeshorsefarm.scene.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Polygon triangulation: a port of mapbox `earcut` (the version three.js r186 ships), 2D only, so
 * the triangles of a shape come out in exactly the same order as in the web app.
 */
object Earcut {
    private class Node(
        val i: Int,
        val x: Double,
        val y: Double,
    ) {
        var prev: Node? = null
        var next: Node? = null
        var z: Int = 0
        var prevZ: Node? = null
        var nextZ: Node? = null
        var steiner: Boolean = false
    }

    // The linked ring is always closed, so prev/next are never null once a node is linked.
    private val Node.nx: Node get() = next ?: error("unlinked node")
    private val Node.pv: Node get() = prev ?: error("unlinked node")

    /**
     * `data` is `[x0, y0, x1, y1, ...]` (outer ring first); `holeIndices` are the vertex indices
     * (not array offsets) where each hole starts. Returns triangle vertex indices.
     */
    fun triangulate(
        data: DoubleArray,
        holeIndices: IntArray = IntArray(0),
    ): IntArray {
        val dim = 2
        val hasHoles = holeIndices.isNotEmpty()
        val outerLen = if (hasHoles) holeIndices[0] * dim else data.size
        val first = linkedList(data, 0, outerLen, dim, true) ?: return IntArray(0)
        val triangles = ArrayList<Int>()
        if (first.next === first.prev) return IntArray(0)
        var outerNode: Node = first
        var minX = 0.0
        var minY = 0.0
        var invSize = 0.0
        if (hasHoles) outerNode = eliminateHoles(data, holeIndices, outerNode, dim)
        if (data.size > 80 * dim) {
            minX = data[0]
            minY = data[1]
            var maxX = minX
            var maxY = minY
            var i = dim
            while (i < outerLen) {
                val x = data[i]
                val y = data[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += dim
            }
            invSize = max(maxX - minX, maxY - minY)
            invSize = if (invSize != 0.0) 32767 / invSize else 0.0
        }
        earcutLinked(outerNode, triangles, minX, minY, invSize, 0)
        return triangles.toIntArray()
    }

    private fun linkedList(
        data: DoubleArray,
        start: Int,
        end: Int,
        dim: Int,
        clockwise: Boolean,
    ): Node? {
        var last: Node? = null
        if (clockwise == (signedArea(data, start, end, dim) > 0)) {
            var i = start
            while (i < end) {
                last = insertNode(i / dim, data[i], data[i + 1], last)
                i += dim
            }
        } else {
            var i = end - dim
            while (i >= start) {
                last = insertNode(i / dim, data[i], data[i + 1], last)
                i -= dim
            }
        }
        if (last != null && equals(last, last.nx)) {
            removeNode(last)
            last = last.next
        }
        return last
    }

    private fun filterPoints(
        start: Node?,
        endIn: Node? = null,
    ): Node? {
        if (start == null) return null
        var end: Node = endIn ?: start
        var p: Node = start
        var again: Boolean
        do {
            again = false
            if (!p.steiner && (equals(p, p.nx) || area(p.pv, p, p.nx) == 0.0)) {
                removeNode(p)
                p = p.pv
                end = p
                if (p === p.next) break
                again = true
            } else {
                p = p.nx
            }
        } while (again || p !== end)
        return end
    }

    private fun earcutLinked(
        earIn: Node?,
        triangles: MutableList<Int>,
        minX: Double,
        minY: Double,
        invSize: Double,
        pass: Int,
    ) {
        var ear = earIn ?: return
        if (pass == 0 && invSize != 0.0) indexCurve(ear, minX, minY, invSize)
        var stop = ear
        while (ear.prev !== ear.next) {
            val prev = ear.pv
            val next = ear.nx
            if (if (invSize != 0.0) isEarHashed(ear, minX, minY, invSize) else isEar(ear)) {
                triangles.add(prev.i)
                triangles.add(ear.i)
                triangles.add(next.i)
                removeNode(ear)
                ear = next.nx
                stop = next.nx
                continue
            }
            ear = next
            if (ear === stop) {
                if (pass == 0) {
                    earcutLinked(filterPoints(ear), triangles, minX, minY, invSize, 1)
                } else if (pass == 1) {
                    val cured = cureLocalIntersections(filterPoints(ear) ?: return, triangles)
                    earcutLinked(cured, triangles, minX, minY, invSize, 2)
                } else if (pass == 2) {
                    splitEarcut(ear, triangles, minX, minY, invSize)
                }
                break
            }
        }
    }

    private fun isEar(ear: Node): Boolean {
        val a = ear.pv
        val b = ear
        val c = ear.nx
        if (area(a, b, c) >= 0) return false
        val ax = a.x
        val bx = b.x
        val cx = c.x
        val ay = a.y
        val by = b.y
        val cy = c.y
        val x0 = min(ax, min(bx, cx))
        val y0 = min(ay, min(by, cy))
        val x1 = max(ax, max(bx, cx))
        val y1 = max(ay, max(by, cy))
        var p = c.nx
        while (p !== a) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 &&
                pointInTriangleExceptFirst(ax, ay, bx, by, cx, cy, p.x, p.y) &&
                area(p.pv, p, p.nx) >= 0
            ) {
                return false
            }
            p = p.nx
        }
        return true
    }

    private fun isEarHashed(
        ear: Node,
        minX: Double,
        minY: Double,
        invSize: Double,
    ): Boolean {
        val a = ear.pv
        val b = ear
        val c = ear.nx
        if (area(a, b, c) >= 0) return false
        val ax = a.x
        val bx = b.x
        val cx = c.x
        val ay = a.y
        val by = b.y
        val cy = c.y
        val x0 = min(ax, min(bx, cx))
        val y0 = min(ay, min(by, cy))
        val x1 = max(ax, max(bx, cx))
        val y1 = max(ay, max(by, cy))
        val minZ = zOrder(x0, y0, minX, minY, invSize)
        val maxZ = zOrder(x1, y1, minX, minY, invSize)
        var p = ear.prevZ
        var n = ear.nextZ
        while (p != null && p.z >= minZ && n != null && n.z <= maxZ) {
            if (inEarBox(p, x0, y0, x1, y1, a, c) &&
                pointInTriangleExceptFirst(ax, ay, bx, by, cx, cy, p.x, p.y) &&
                area(p.pv, p, p.nx) >= 0
            ) {
                return false
            }
            p = p.prevZ
            if (inEarBox(n, x0, y0, x1, y1, a, c) &&
                pointInTriangleExceptFirst(ax, ay, bx, by, cx, cy, n.x, n.y) &&
                area(n.pv, n, n.nx) >= 0
            ) {
                return false
            }
            n = n.nextZ
        }
        while (p != null && p.z >= minZ) {
            if (inEarBox(p, x0, y0, x1, y1, a, c) &&
                pointInTriangleExceptFirst(ax, ay, bx, by, cx, cy, p.x, p.y) &&
                area(p.pv, p, p.nx) >= 0
            ) {
                return false
            }
            p = p.prevZ
        }
        while (n != null && n.z <= maxZ) {
            if (inEarBox(n, x0, y0, x1, y1, a, c) &&
                pointInTriangleExceptFirst(ax, ay, bx, by, cx, cy, n.x, n.y) &&
                area(n.pv, n, n.nx) >= 0
            ) {
                return false
            }
            n = n.nextZ
        }
        return true
    }

    private fun inEarBox(
        p: Node,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        a: Node,
        c: Node,
    ): Boolean = p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && p !== a && p !== c

    private fun cureLocalIntersections(
        startIn: Node,
        triangles: MutableList<Int>,
    ): Node? {
        var start = startIn
        var p: Node = start
        do {
            val a = p.pv
            val b = p.nx.nx
            if (!equals(a, b) && intersects(a, p, p.nx, b) && locallyInside(a, b) && locallyInside(b, a)) {
                triangles.add(a.i)
                triangles.add(p.i)
                triangles.add(b.i)
                removeNode(p)
                removeNode(p.nx)
                start = b
                p = b
            }
            p = p.nx
        } while (p !== start)
        return filterPoints(p)
    }

    private fun splitEarcut(
        start: Node,
        triangles: MutableList<Int>,
        minX: Double,
        minY: Double,
        invSize: Double,
    ) {
        var a = start
        do {
            var b = a.nx.nx
            while (b !== a.prev) {
                if (a.i != b.i && isValidDiagonal(a, b)) {
                    var c = splitPolygon(a, b)
                    a = filterPoints(a, a.nx) ?: return
                    c = filterPoints(c, c.nx) ?: return
                    earcutLinked(a, triangles, minX, minY, invSize, 0)
                    earcutLinked(c, triangles, minX, minY, invSize, 0)
                    return
                }
                b = b.nx
            }
            a = a.nx
        } while (a !== start)
    }

    private fun eliminateHoles(
        data: DoubleArray,
        holeIndices: IntArray,
        outerNodeIn: Node,
        dim: Int,
    ): Node {
        var outerNode = outerNodeIn
        val queue = ArrayList<Node>()
        val len = holeIndices.size
        for (i in 0 until len) {
            val start = holeIndices[i] * dim
            val end = if (i < len - 1) holeIndices[i + 1] * dim else data.size
            val list = linkedList(data, start, end, dim, false) ?: continue
            if (list === list.next) list.steiner = true
            queue.add(getLeftmost(list))
        }
        queue.sortWith { a, b -> compareXYSlope(a, b) }
        for (node in queue) outerNode = eliminateHole(node, outerNode)
        return outerNode
    }

    private fun compareXYSlope(
        a: Node,
        b: Node,
    ): Int {
        var result = a.x - b.x
        if (result == 0.0) {
            result = a.y - b.y
            if (result == 0.0) {
                val aSlope = (a.nx.y - a.y) / (a.nx.x - a.x)
                val bSlope = (b.nx.y - b.y) / (b.nx.x - b.x)
                result = aSlope - bSlope
            }
        }
        // JS treats a NaN comparator result as 0
        return if (result > 0) {
            1
        } else if (result < 0) {
            -1
        } else {
            0
        }
    }

    private fun eliminateHole(
        hole: Node,
        outerNode: Node,
    ): Node {
        val bridge = findHoleBridge(hole, outerNode) ?: return outerNode
        val bridgeReverse = splitPolygon(bridge, hole)
        filterPoints(bridgeReverse, bridgeReverse.nx)
        return filterPoints(bridge, bridge.nx) ?: outerNode
    }

    private fun findHoleBridge(
        hole: Node,
        outerNode: Node,
    ): Node? {
        var p = outerNode
        val hx = hole.x
        val hy = hole.y
        var qx = Double.NEGATIVE_INFINITY
        var m: Node? = null
        if (equals(hole, p)) return p
        do {
            if (equals(hole, p.nx)) {
                return p.nx
            } else if (hy <= p.y && hy >= p.nx.y && p.nx.y != p.y) {
                val x = p.x + (hy - p.y) * (p.nx.x - p.x) / (p.nx.y - p.y)
                if (x <= hx && x > qx) {
                    qx = x
                    m = if (p.x < p.nx.x) p else p.nx
                    if (x == hx) return m
                }
            }
            p = p.nx
        } while (p !== outerNode)
        var best = m ?: return null
        val stop = best
        val mx = best.x
        val my = best.y
        var tanMin = Double.POSITIVE_INFINITY
        p = best
        do {
            if (hx >= p.x && p.x >= mx && hx != p.x &&
                pointInTriangle(if (hy < my) hx else qx, hy, mx, my, if (hy < my) qx else hx, hy, p.x, p.y)
            ) {
                val tan = abs(hy - p.y) / (hx - p.x)
                if (locallyInside(p, hole) &&
                    (
                        tan < tanMin ||
                            (tan == tanMin && (p.x > best.x || (p.x == best.x && sectorContainsSector(best, p))))
                    )
                ) {
                    best = p
                    tanMin = tan
                }
            }
            p = p.nx
        } while (p !== stop)
        return best
    }

    private fun sectorContainsSector(
        m: Node,
        p: Node,
    ): Boolean = area(m.pv, m, p.pv) < 0 && area(p.nx, m, m.nx) < 0

    private fun indexCurve(
        start: Node,
        minX: Double,
        minY: Double,
        invSize: Double,
    ) {
        var p: Node = start
        do {
            if (p.z == 0) p.z = zOrder(p.x, p.y, minX, minY, invSize)
            p.prevZ = p.prev
            p.nextZ = p.next
            p = p.nx
        } while (p !== start)
        val last = p.prevZ ?: error("unlinked node")
        last.nextZ = null
        p.prevZ = null
        sortLinked(p)
    }

    private fun sortLinked(listIn: Node): Node? {
        var list: Node? = listIn
        var numMerges: Int
        var inSize = 1
        do {
            var p = list
            list = null
            var tail: Node? = null
            numMerges = 0
            while (p != null) {
                numMerges++
                var q: Node? = p
                var pSize = 0
                for (i in 0 until inSize) {
                    pSize++
                    q = q?.nextZ
                    if (q == null) break
                }
                var qSize = inSize
                while (pSize > 0 || (qSize > 0 && q != null)) {
                    val e: Node
                    if (pSize != 0 && (qSize == 0 || q == null || (p ?: error("empty run")).z <= q.z)) {
                        e = p ?: error("empty run")
                        p = e.nextZ
                        pSize--
                    } else {
                        e = q ?: error("empty run")
                        q = e.nextZ
                        qSize--
                    }
                    if (tail != null) tail.nextZ = e else list = e
                    e.prevZ = tail
                    tail = e
                }
                p = q
            }
            tail?.nextZ = null
            inSize *= 2
        } while (numMerges > 1)
        return list
    }

    private fun zOrder(
        xIn: Double,
        yIn: Double,
        minX: Double,
        minY: Double,
        invSize: Double,
    ): Int {
        var x = ((xIn - minX) * invSize).toInt()
        var y = ((yIn - minY) * invSize).toInt()
        x = (x or (x shl 8)) and 0x00FF00FF
        x = (x or (x shl 4)) and 0x0F0F0F0F
        x = (x or (x shl 2)) and 0x33333333
        x = (x or (x shl 1)) and 0x55555555
        y = (y or (y shl 8)) and 0x00FF00FF
        y = (y or (y shl 4)) and 0x0F0F0F0F
        y = (y or (y shl 2)) and 0x33333333
        y = (y or (y shl 1)) and 0x55555555
        return x or (y shl 1)
    }

    private fun getLeftmost(start: Node): Node {
        var p: Node = start
        var leftmost = start
        do {
            if (p.x < leftmost.x || (p.x == leftmost.x && p.y < leftmost.y)) leftmost = p
            p = p.nx
        } while (p !== start)
        return leftmost
    }

    private fun pointInTriangle(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        cx: Double,
        cy: Double,
        px: Double,
        py: Double,
    ): Boolean =
        (cx - px) * (ay - py) >= (ax - px) * (cy - py) &&
            (ax - px) * (by - py) >= (bx - px) * (ay - py) &&
            (bx - px) * (cy - py) >= (cx - px) * (by - py)

    private fun pointInTriangleExceptFirst(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        cx: Double,
        cy: Double,
        px: Double,
        py: Double,
    ): Boolean = !(ax == px && ay == py) && pointInTriangle(ax, ay, bx, by, cx, cy, px, py)

    private fun isValidDiagonal(
        a: Node,
        b: Node,
    ): Boolean {
        if (a.nx.i == b.i || a.pv.i == b.i || intersectsPolygon(a, b)) return false
        // locally visible and not creating opposite-facing sectors
        val visible =
            locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b) &&
                (area(a.pv, a, b.pv) != 0.0 || area(a, b.pv, b) != 0.0)
        // special zero-length case
        val zeroLength = equals(a, b) && area(a.pv, a, a.nx) > 0 && area(b.pv, b, b.nx) > 0
        return visible || zeroLength
    }

    private fun area(
        p: Node,
        q: Node,
        r: Node,
    ): Double = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)

    private fun equals(
        p1: Node,
        p2: Node,
    ): Boolean = p1.x == p2.x && p1.y == p2.y

    private fun intersects(
        p1: Node,
        q1: Node,
        p2: Node,
        q2: Node,
    ): Boolean {
        val o1 = sign(area(p1, q1, p2))
        val o2 = sign(area(p1, q1, q2))
        val o3 = sign(area(p2, q2, p1))
        val o4 = sign(area(p2, q2, q1))
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(p1, p2, q1)) return true
        if (o2 == 0 && onSegment(p1, q2, q1)) return true
        if (o3 == 0 && onSegment(p2, p1, q2)) return true
        if (o4 == 0 && onSegment(p2, q1, q2)) return true
        return false
    }

    private fun onSegment(
        p: Node,
        q: Node,
        r: Node,
    ): Boolean = q.x <= max(p.x, r.x) && q.x >= min(p.x, r.x) && q.y <= max(p.y, r.y) && q.y >= min(p.y, r.y)

    private fun sign(num: Double): Int =
        if (num > 0) {
            1
        } else if (num < 0) {
            -1
        } else {
            0
        }

    private fun intersectsPolygon(
        a: Node,
        b: Node,
    ): Boolean {
        var p = a
        do {
            if (p.i != a.i && p.nx.i != a.i && p.i != b.i && p.nx.i != b.i && intersects(p, p.nx, a, b)) return true
            p = p.nx
        } while (p !== a)
        return false
    }

    private fun locallyInside(
        a: Node,
        b: Node,
    ): Boolean =
        if (area(a.pv, a, a.nx) < 0) {
            area(a, b, a.nx) >= 0 && area(a, a.pv, b) >= 0
        } else {
            area(a, b, a.pv) < 0 || area(a, a.nx, b) < 0
        }

    private fun middleInside(
        a: Node,
        b: Node,
    ): Boolean {
        var p = a
        var inside = false
        val px = (a.x + b.x) / 2
        val py = (a.y + b.y) / 2
        do {
            if (((p.y > py) != (p.nx.y > py)) && p.nx.y != p.y &&
                (px < (p.nx.x - p.x) * (py - p.y) / (p.nx.y - p.y) + p.x)
            ) {
                inside = !inside
            }
            p = p.nx
        } while (p !== a)
        return inside
    }

    private fun splitPolygon(
        a: Node,
        b: Node,
    ): Node {
        val a2 = Node(a.i, a.x, a.y)
        val b2 = Node(b.i, b.x, b.y)
        val an = a.nx
        val bp = b.pv
        a.next = b
        b.prev = a
        a2.next = an
        an.prev = a2
        b2.next = a2
        a2.prev = b2
        bp.next = b2
        b2.prev = bp
        return b2
    }

    private fun insertNode(
        i: Int,
        x: Double,
        y: Double,
        last: Node?,
    ): Node {
        val p = Node(i, x, y)
        if (last == null) {
            p.prev = p
            p.next = p
        } else {
            p.next = last.next
            p.prev = last
            last.nx.prev = p
            last.next = p
        }
        return p
    }

    private fun removeNode(p: Node) {
        p.nx.prev = p.prev
        p.pv.next = p.next
        p.prevZ?.nextZ = p.nextZ
        p.nextZ?.prevZ = p.prevZ
    }

    private fun signedArea(
        data: DoubleArray,
        start: Int,
        end: Int,
        dim: Int,
    ): Double {
        var sum = 0.0
        var j = end - dim
        var i = start
        while (i < end) {
            sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1])
            j = i
            i += dim
        }
        return sum
    }
}
