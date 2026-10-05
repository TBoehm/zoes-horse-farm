package app.zoeshorsefarm.scene.geometry

import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.math.Vec2
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Compares the builders with three.js r186: the vertex and index counts, the groups and
 * fingerprints (sum and position-weighted sum) of all attributes and of the index.
 */
class PrimitivesTest {
    private class Fp(
        val name: String,
        val vertices: Int,
        val indices: Int,
        val posSum: Double,
        val posW: Double,
        val nrmSum: Double,
        val nrmW: Double,
        val uvSum: Double,
        val uvW: Double,
        val idxW: Double,
        val groups: String,
    )

    private fun weighted(array: FloatArray): Pair<Double, Double> {
        var sum = 0.0
        var w = 0.0
        for (i in array.indices) {
            sum += array[i]
            w += array[i].toDouble() * ((i % 97) + 1)
        }
        return sum to w
    }

    private fun check(
        fp: Fp,
        g: Geometry,
    ) {
        val label = fp.name
        assertEquals(fp.vertices, g.position.count, "$label vertices")
        assertEquals(fp.indices, g.indexCount, "$label indices")
        val (ps, pw) = weighted(g.position.array)
        assertNear(fp.posSum, ps, 1e-9, "$label position sum")
        assertNear(fp.posW, pw, 1e-9, "$label position weighted")
        val nrm = g.getAttribute("normal") as? FloatAttribute
        if (nrm != null) {
            val (ns, nw) = weighted(nrm.array)
            assertNear(fp.nrmSum, ns, 1e-9, "$label normal sum")
            assertNear(fp.nrmW, nw, 1e-9, "$label normal weighted")
        }
        val uv = g.getAttribute("uv") as? FloatAttribute
        if (uv != null) {
            val (us, uw) = weighted(uv.array)
            assertNear(fp.uvSum, us, 1e-9, "$label uv sum")
            assertNear(fp.uvW, uw, 1e-9, "$label uv weighted")
        }
        g.index?.let { idx ->
            var iw = 0.0
            for (i in idx.indices) iw += idx[i].toDouble() * ((i % 89) + 1)
            assertNear(fp.idxW, iw, 1e-9, "$label index weighted")
        }
        assertEquals(
            fp.groups,
            g.groups.joinToString(",") { "${it.start}:${it.count}:${it.materialIndex}" },
            "$label groups",
        )
    }

    private fun tri() = Shape(listOf(Vec2(-0.5, 0.0), Vec2(0.5, 0.0), Vec2(0.0, 1.0)))

    private fun roundedRect(
        hw: Double,
        hd: Double,
        r: Double,
    ): Shape {
        val s = Shape()
        s.moveTo(-hw + r, -hd)
        s.lineTo(hw - r, -hd)
        s.quadraticCurveTo(hw, -hd, hw, -hd + r)
        s.lineTo(hw, hd - r)
        s.quadraticCurveTo(hw, hd, hw - r, hd)
        s.lineTo(-hw + r, hd)
        s.quadraticCurveTo(-hw, hd, -hw, hd - r)
        s.lineTo(-hw, -hd + r)
        s.quadraticCurveTo(-hw, -hd, -hw + r, -hd)
        return s
    }

    @Test
    fun `box`() {
        check(
            Fp(
                "box",
                24,
                36,
                0.0,
                -186.0,
                0.0,
                -144.0,
                24.0,
                576.0,
                10206.0,
                "0:6:0,6:6:1,12:6:2,18:6:3,24:6:4,30:6:5",
            ),
            BoxGeometry(1.0, 2.0, 3.0),
        )
        check(
            Fp(
                "box-seg",
                94,
                312,
                0.0,
                -807.7499995678663,
                0.0,
                -1143.0,
                94.00000047683716,
                4447.083357036114,
                594318.0,
                "0:72:0,72:72:1,144:48:2,192:48:3,240:36:4,276:36:5",
            ),
            BoxGeometry(2.0, 1.0, 1.5, 2, 3, 4),
        )
    }

    @Test
    fun `plane`() {
        check(
            Fp(
                "plane",
                12,
                36,
                0.0,
                -113.99999991059303,
                12.0,
                234.0,
                12.000000089406967,
                128.00000110268593,
                4488.0,
                "",
            ),
            PlaneGeometry(2.0, 3.0, 3, 2),
        )
    }

    @Test
    fun `cylinder and cone`() {
        check(
            Fp(
                "cylinder",
                61,
                144,
                7.499999999999999,
                -1132.2792177200317,
                11.38419958949089,
                5.146563678979874,
                62.00000023841858,
                2469.500011444092,
                129126.0,
                "0:96:0,96:24:1,120:24:2",
            ),
            CylinderGeometry(1.0, 2.0, 3.0, 8, 2),
        )
        check(
            Fp(
                "cylinder-open",
                14,
                36,
                0.9999999999999999,
                -66.58845698833466,
                1.9999999999999998,
                13.823086023330678,
                14.000000029802322,
                172.66666692495346,
                5001.0,
                "0:36:0",
            ),
            CylinderGeometry(0.5, 0.5, 1.0, 6, 1, true),
        )
        check(
            Fp(
                "cylinder-cone",
                39,
                84,
                -12.499999925494194,
                -921.9210286512971,
                -1.5835922062397003,
                -312.1226954013109,
                39.50000030081719,
                1490.7678358964622,
                79485.0,
                "0:63:0,63:21:2",
            ),
            CylinderGeometry(0.0, 1.0, 2.0, 7, 2),
        )
        check(
            Fp(
                "cylinder-arc",
                78,
                216,
                -1.974406755529344,
                -143.80677548516542,
                6.86371298879385,
                451.69981063343585,
                75.10938354334212,
                2847.73299222099,
                254729.0,
                "0:162:0,162:27:1,189:27:2",
            ),
            CylinderGeometry(0.3, 0.6, 1.2, 9, 3, false, 0.5, 4.0),
        )
        check(
            Fp(
                "cone",
                39,
                84,
                -12.499999925494194,
                -921.9210286512971,
                -1.5835922062397003,
                -312.1226954013109,
                39.50000030081719,
                1490.7678358964622,
                79485.0,
                "0:63:0,63:21:2",
            ),
            ConeGeometry(1.0, 2.0, 7, 2),
        )
        check(
            Fp(
                "cone-open",
                10,
                12,
                1.2000000476837154,
                -12.149998247623458,
                9.200000166893005,
                136.600002348423,
                10.0,
                90.0,
                458.0,
                "0:12:0",
            ),
            ConeGeometry(1.2, 0.9, 4, 1, true),
        )
    }

    @Test
    fun `sphere`() {
        check(
            Fp(
                "sphere",
                63,
                240,
                -5.598076105117798,
                -605.84454870224,
                -3.7320507764816284,
                -403.8963661789894,
                63.00000013411045,
                2446.062512129545,
                321771.0,
                "",
            ),
            SphereGeometry(1.5, 8, 6),
        )
        check(
            Fp(
                "sphere-part",
                24,
                90,
                8.517757967114449,
                18.45607414841652,
                8.517757967114449,
                18.45607414841652,
                24.00000035762787,
                496.00000858306885,
                57233.0,
                "",
            ),
            SphereGeometry(1.0, 5, 3, 0.3, 4.0, 0.5, 2.0),
        )
        check(
            Fp(
                "sphere-small",
                12,
                18,
                -0.5000000000000002,
                -58.04903808236122,
                -1.0000000000000004,
                -116.09807616472244,
                11.999999955296516,
                117.33333288133144,
                1125.0,
                "",
            ),
            SphereGeometry(0.5, 2, 1),
        )
    }

    @Test
    fun `polyhedra are non-indexed with flat or smooth normals`() {
        check(
            Fp(
                "icosa0",
                60,
                0,
                0.0,
                63.37907886505127,
                0.0,
                -24.07844987511635,
                63.000000059604645,
                2551.941089808941,
                0.0,
                "",
            ),
            IcosahedronGeometry(1.0, 0),
        )
        check(
            Fp(
                "icosa1",
                240,
                0,
                0.0,
                -408.6591944694519,
                0.0,
                -204.32959723472595,
                246.0000015348196,
                12238.939350031316,
                0.0,
                "",
            ),
            IcosahedronGeometry(2.0, 1),
        )
        check(
            Fp(
                "icosa2",
                540,
                0,
                0.0,
                -45.452374167740345,
                0.0,
                -90.90474833548069,
                548.9999985992908,
                26824.238025166094,
                0.0,
                "",
            ),
            IcosahedronGeometry(0.5, 2),
        )
        check(
            Fp("octa0", 24, 0, 0.0, -144.0, 0.0, -249.4153118133545, 24.0, 570.0, 0.0, ""),
            OctahedronGeometry(1.0, 0),
        )
        check(
            Fp("octa1", 96, 0, 0.0, -315.77205991744995, 0.0, -242.9015827178955, 96.0, 4368.5, 0.0, ""),
            OctahedronGeometry(1.3, 1),
        )
        check(
            Fp(
                "tetra0",
                12,
                0,
                0.0,
                -24.248710870742798,
                0.0,
                -93.53074193000793,
                11.999999821186066,
                144.14903843402863,
                0.0,
                "",
            ),
            TetrahedronGeometry(1.0, 0),
        )
        check(
            Fp(
                "tetra1",
                48,
                0,
                0.0,
                100.50773477554321,
                0.0,
                67.00515651702881,
                47.999999824911356,
                2089.792968634516,
                0.0,
                "",
            ),
            TetrahedronGeometry(1.5, 1),
        )
    }

    @Test
    fun `icosahedron first normal and uv`() {
        val ico = IcosahedronGeometry(1.0, 0)
        assertNear(-0.5773502588272095, ico.normal.getX(0), 1e-7)
        assertNear(0.5773502588272095, ico.normal.getY(0), 1e-7)
        assertNear(0.5881040692329407, ico.uv.getX(0), 1e-7)
        val smooth = IcosahedronGeometry(1.0, 1)
        assertNear(-0.80901700258255, smooth.normal.getX(0), 1e-7)
        assertNear(0.5580698847770691, smooth.uv.getX(0), 1e-7)
    }

    @Test
    fun `torus and circle`() {
        check(
            Fp(
                "torus",
                77,
                360,
                14.5,
                -215.69648447632792,
                0.9999999999999964,
                -30.765907943248823,
                77.00000032037497,
                3338.500017128885,
                646110.0,
                "",
            ),
            TorusGeometry(2.0, 0.5, 6, 10),
        )
        check(
            Fp(
                "torus-arc",
                48,
                210,
                29.735883846879005,
                1028.3224671557546,
                -0.3166760951280594,
                -138.86374606192112,
                48.000000804662704,
                2848.0000421106815,
                207367.0,
                "",
            ),
            TorusGeometry(1.0, 0.3, 5, 7, 3.0, 0.2, 5.0),
        )
        check(
            Fp(
                "circle",
                11,
                27,
                1.9999999999999996,
                -39.18189048767091,
                11.0,
                198.0,
                11.499999992083758,
                120.13635162916034,
                1701.0,
                "",
            ),
            CircleGeometry(2.0, 9),
        )
        check(
            Fp(
                "circle-arc",
                5,
                9,
                -0.3843948096036911,
                -16.710291638970375,
                5.0,
                45.0,
                4.80780261894688,
                22.05501804733649,
                81.0,
                "",
            ),
            CircleGeometry(1.0, 3, 1.0, 3.0),
        )
    }

    @Test
    fun `shape geometry triangulates outlines and holes`() {
        check(Fp("shape-tri", 3, 3, 1.0, 0.5, 3.0, 18.0, 1.0, 1.0, 7.0, ""), ShapeGeometry(tri(), 6))
        check(
            Fp("shape-roundrect", 20, 54, 0.0, 543.75, 20.0, 630.0, 0.0, 362.5, 12646.0, ""),
            ShapeGeometry(roundedRect(2.0, 1.0, 0.5), 4),
        )
        val outer = roundedRect(3.0, 2.0, 0.9)
        val inner = roundedRect(3.0 - 0.25, 2.0 - 0.25, 0.9 - 0.25 * 0.5)
        outer.holes.add(Path(inner.getPoints(6).reversed()))
        val ring = ShapeGeometry(outer, 6)
        ring.rotateX(-PI / 2)
        check(
            Fp("shape-ring", 56, 168, 0.0, 1503.7576322555542, 56.0, 2404.0, 0.0, 2657.6590344905853, 176430.0, ""),
            ring,
        )
    }

    @Test
    fun `shape polyline of the rounded rectangle`() {
        val points = roundedRect(3.0, 2.0, 0.9).getPoints(6)
        assertEquals(29, points.size)
        assertNear(-2.1, points[0].x)
        assertNear(-2.0, points[0].y)
        assertNear(2.3750000000000004, points[2].x)
        assertNear(-1.975, points[2].y)
    }

    @Test
    fun `extrude geometry builds lids and walls`() {
        check(
            Fp(
                "extrude-tri",
                24,
                0,
                10.400000035762787,
                418.00000147521496,
                -0.633436918258667,
                57.879552483558655,
                24.20000010728836,
                729.0000035762787,
                0.0,
                "0:6:0,6:18:1",
            ),
            ExtrudeGeometry(tri(), depth = 0.2),
        )
        check(
            Fp(
                "extrude-steps",
                276,
                0,
                207.0,
                10479.111100196838,
                0.0,
                246.45515072345734,
                48.0,
                1269.0555573701859,
                0.0,
                "0:84:0,84:192:1",
            ),
            ExtrudeGeometry(roundedRect(2.0, 1.0, 0.5), depth = 1.5, steps = 2, curveSegments = 3),
        )
        val outer = roundedRect(3.0, 2.0, 0.9)
        outer.holes.add(Path(roundedRect(2.5, 1.5, 0.6).getPoints(3)))
        check(
            Fp(
                "extrude-hole",
                384,
                0,
                67.66666722297668,
                4936.600077718496,
                0.0,
                453.77616260945797,
                144.46666836738586,
                13448.833396077156,
                0.0,
                "0:192:0,192:192:1",
            ),
            ExtrudeGeometry(outer, depth = 0.4, curveSegments = 3),
        )
    }

    @Test
    fun `merge geometries concatenates attributes and offsets the index`() {
        val box = BoxGeometry(1.0, 1.0, 1.0)
        box.translate(1.0, 2.0, 3.0)
        val cone = ConeGeometry(1.0, 2.0, 5)
        cone.rotateX(0.5)
        val merged = assertNotNull(mergeGeometries(listOf(box, cone), true))
        check(
            Fp(
                "merge-groups",
                47,
                66,
                129.86922496557236,
                5366.792995110154,
                -6.9323743879795074,
                50.683305107057095,
                47.50000002607703,
                2245.940954603255,
                67446.0,
                "0:36:0,36:30:1",
            ),
            merged,
        )
        val flat = assertNotNull(mergeGeometries(listOf(box.toNonIndexed(), cone.toNonIndexed()), false))
        check(
            Fp(
                "merge-nonindexed",
                66,
                0,
                188.85983800888062,
                7928.830215647817,
                -11.252034299075603,
                -1135.4755657613277,
                64.00000000745058,
                2571.145737938583,
                0.0,
                "",
            ),
            flat,
        )
    }

    @Test
    fun `merge fails for incompatible geometries`() {
        val indexed = BoxGeometry()
        val flat = BoxGeometry().toNonIndexed()
        assertNull(mergeGeometries(listOf(indexed, flat)))
        val noUv = BoxGeometry().also { it.deleteAttribute("uv") }
        assertNull(mergeGeometries(listOf(BoxGeometry(), noUv)))
        assertNull(mergeGeometries(emptyList()))
    }

    @Test
    fun `transforms apply to positions and normals and refresh the bounds`() {
        val g = BoxGeometry(1.0, 2.0, 3.0)
        g.rotateY(0.4).translate(1.0, 2.0, 3.0).scale(2.0, 1.0, 0.5)
        check(
            Fp(
                "box-transformed",
                24,
                36,
                132.00000178813934,
                4591.112184405327,
                0.0,
                -83.89335715770721,
                24.0,
                576.0,
                10206.0,
                "0:6:0,6:6:1,12:6:2,18:6:3,24:6:4,30:6:5",
            ),
            g,
        )
        g.computeBoundingSphere()
        val sphere = assertNotNull(g.boundingSphere)
        assertNear(2.0000001192092896, sphere.center.x, 1e-9)
        assertNear(1.5, sphere.center.z, 1e-9)
        assertNear(2.3911116457932406, sphere.radius, 1e-9)
    }

    @Test
    fun `computeVertexNormals gives flat normals for non-indexed and smooth ones for indexed geometry`() {
        val flat = BoxGeometry(1.0, 1.0, 1.0, 2, 2, 2).toNonIndexed()
        flat.computeVertexNormals()
        check(
            Fp(
                "box-nonindexed-normals",
                144,
                0,
                0.0,
                -509.5,
                0.0,
                -528.0,
                144.0,
                6793.0,
                0.0,
                "0:24:0,24:24:1,48:24:2,72:24:3,96:24:4,120:24:5",
            ),
            flat,
        )
        val sphere = SphereGeometry(1.0, 6, 4)
        sphere.deleteAttribute("normal")
        sphere.computeVertexNormals()
        check(
            Fp(
                "sphere-recomputed-normals",
                35,
                108,
                -2.414213538169861,
                -668.0623595714569,
                -1.9699953639419032,
                -440.83459124909814,
                35.000000067055225,
                1011.5000013336539,
                77085.0,
                "",
            ),
            sphere,
        )
    }

    @Test
    fun `draw range limits the triangles`() {
        val g = BoxGeometry()
        assertEquals(12, g.drawnTriangles)
        g.setDrawRange(0, 6)
        assertEquals(2, g.drawnTriangles)
        g.setDrawRange(0, 0)
        assertEquals(0, g.drawnTriangles)
    }
}
