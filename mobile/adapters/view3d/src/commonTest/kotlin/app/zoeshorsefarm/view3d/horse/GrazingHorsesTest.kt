package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.quality.Detail
import app.zoeshorsefarm.view3d.quality.presetFor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val AREA = PaddockArea(x = 40.0, z = -20.0, width = 22.0, depth = 14.0, rotation = -0.4)

private fun meshesOf(group: Node): List<Mesh> {
    val list = ArrayList<Mesh>()
    group.traverse { if (it is Mesh) list.add(it) }
    return list
}

class GrazingHorsesTest {
    @Test
    fun `builds cheap horses with no rider and no tack and one draw call each and no shadows`() {
        val paddock = createGrazingHorses(AREA, GraphicsLevel.HIGH, count = 3, rng = createRng(1))
        assertEquals(3, paddock.group.children.size)
        val meshes = meshesOf(paddock.group)
        assertEquals(3, meshes.size)
        for (m in meshes) {
            assertEquals("horse-body", m.name)
            assertEquals(false, m.castShadow)
            assertEquals(true, m.frustumCulled)
            assertGreater(m.boundingSphere!!.radius, 1.5)
        }
        paddock.dispose()
    }

    @Test
    fun `gives the horses different coats and the same ones for the same seed`() {
        val coats = listOf(Coat.GREY, Coat.CHESTNUT)
        for (seed in 1..6) {
            val (a, b) = dealCoats(coats, 2, createRng(seed))
            assertNotEquals(a, b)
            assertEquals(listOf(a, b), dealCoats(coats, 2, createRng(seed)))
        }
        // more horses than coats: the coats start over
        assertEquals(listOf("a", "b", "a"), dealCoats(listOf("a", "b"), 3) { 0.0 })
    }

    @Test
    fun `uses the low horse model on low and medium and the medium model on high`() {
        fun tri(q: GraphicsLevel): Int {
            val p = createGrazingHorses(AREA, q, count = 1)
            val n = meshesOf(p.group)[0].geometry.indexCount / 3
            p.dispose()
            return n
        }
        assertEquals(tri(GraphicsLevel.LOW), tri(GraphicsLevel.MEDIUM))
        assertTrue(tri(GraphicsLevel.HIGH) > tri(GraphicsLevel.MEDIUM))
        // the presets of the quality module are accepted too
        val preset = presetFor(GraphicsLevel.MEDIUM).copy(characterDetail = Detail.HIGH)
        val p = createGrazingHorses(AREA, preset, count = 1)
        assertEquals(tri(GraphicsLevel.HIGH), meshesOf(p.group)[0].geometry.indexCount / 3)
        p.dispose()
    }

    @Test
    fun `the horses stand at their spots before the first update and stay in the paddock`() {
        val paddock = createGrazingHorses(AREA, GraphicsLevel.LOW, count = 2, rng = createRng(3))
        val horses = paddock.group.children
        val start = horses.map { it.position.clone() }
        for (p in start) assertTrue(insideArea(AREA, p.x, p.z, GRAZING.margin - 1))
        var headDown = 0
        var walking = 0
        var frames = 0
        val last = start.map { it.clone() }
        val head = horses.map { it.getObjectByName("head")!! }
        val v = Vec3()
        var t = 0.0
        while (t < 400) {
            paddock.update(1.0 / 30)
            frames++
            horses.forEachIndexed { i, h ->
                val p = h.position
                // the group origin is ahead of the body centre: at most bodyOffset plus the tolerance
                assertTrue(insideArea(AREA, p.x, p.z, GRAZING.margin - GRAZING.bodyOffset - 0.3))
                assertEquals(0.0, p.y)
                h.updateMatrixWorld(true)
                if (head[i].getWorldPosition(v).y < 0.9) headDown++
                if (p.distanceTo(last[i]) * 30 > 0.2) walking++
                last[i].copy(p)
            }
            t += 1.0 / 30
        }
        assertGreater(headDown.toDouble() / (frames * 2), 0.5)
        assertGreater(walking.toDouble(), 30.0)
        assertTrue(horses.withIndex().any { (i, h) -> h.position.distanceTo(start[i]) > 1 })
        paddock.dispose()
    }

    @Test
    fun `the head goes down to the grass`() {
        val paddock = createGrazingHorses(AREA, GraphicsLevel.LOW, count = 1, rng = createRng(5))
        val horse = paddock.group.children[0]
        val head = horse.getObjectByName("head")!!
        val v = Vec3()
        var lowest = Double.POSITIVE_INFINITY
        var highest = 0.0
        var t = 0.0
        while (t < 60) {
            paddock.update(1.0 / 30)
            horse.updateMatrixWorld(true)
            // the muzzle: in the head frame, below and in front of the poll
            val y = v.set(0.0, -0.5, 0.35).applyMatrix4(head.matrixWorld).y
            lowest = minOf(lowest, y)
            highest = max(highest, y)
            t += 1.0 / 30
        }
        assertTrue(lowest < 0.6, "lowest $lowest")
        assertTrue(highest > 1.0, "highest $highest") // it looks up now and then
        paddock.dispose()
    }

    @Test
    fun `is deterministic for a seed`() {
        fun run(): List<Pair<Double, Double>> {
            val p = createGrazingHorses(AREA, GraphicsLevel.LOW, count = 2, rng = createRng(9))
            var t = 0.0
            while (t < 120) {
                p.update(1.0 / 30)
                t += 1.0 / 30
            }
            val out = p.group.children.map { it.position.x to it.position.z }
            p.dispose()
            return out
        }
        assertEquals(run(), run())
    }

    @Test
    fun `releases every geometry and material through the release hook`() {
        val released = ArrayList<GpuObject?>()
        val paddock = createGrazingHorses(AREA, GraphicsLevel.MEDIUM, count = 2, release = { released.add(it) })
        val before = meshesOf(paddock.group).map { it.geometry to it.material }
        paddock.setQuality(GraphicsLevel.HIGH)
        for ((g, m) in before) {
            assertTrue(released.any { it === g })
            assertTrue(released.any { it === m })
        }
        val current = meshesOf(paddock.group).map { it.geometry to it.material }
        paddock.dispose()
        // the final dispose goes through the hook, too (objects of a lost context are not freed)
        for ((g, m) in current) {
            assertTrue(released.any { it === g })
            assertTrue(released.any { it === m })
        }
        assertNull(paddock.group.parent)
    }

    @Test
    fun `draw calls are one per horse and nothing else`() {
        val paddock = createGrazingHorses(AREA, GraphicsLevel.MEDIUM, count = 4)
        assertEquals(4, meshesOf(paddock.group).size)
        paddock.dispose()
    }
}

private class Extent {
    var front = Double.NEGATIVE_INFINITY
    var back = Double.NEGATIVE_INFINITY
    var side = 0.0
    var radius = 0.0
}

/** Extent of the skinned body in the frame of the group (+Z forward) over a series of poses. */
private fun measure(level: GraphicsLevel): Extent {
    val horse = createHorse(quality = level, rider = false, tack = false, rng = createRng(4))
    val body = horse.group.getObjectByName("horse-body") as SkinnedMesh
    val v = Vec3()
    val ext = Extent()

    fun scan() {
        horse.group.updateMatrixWorld(true)
        val n = body.geometry.position.count
        for (i in 0 until n) {
            body.getVertexPosition(i, v)
            v.applyMatrix4(body.matrixWorld)
            ext.front = max(ext.front, v.z)
            ext.back = max(ext.back, -v.z)
            ext.side = max(ext.side, abs(v.x))
            ext.radius = max(ext.radius, hypot(v.x, v.z + GRAZING.bodyOffset))
        }
    }

    fun run(
        state: Horse,
        graze: Double,
        seconds: Int,
    ) {
        var t = 0.0
        while (t < seconds) {
            horse.update(1.0 / 30, state, graze)
            if (round(t * 30).toInt() % 15 == 0) scan()
            t += 1.0 / 30
        }
    }
    run(Horse(gait = Gait.HALT), 1.0, 40)
    run(Horse(gait = Gait.HALT), 0.0, 40)
    run(Horse(gait = Gait.WALK, speed = GRAZING.walkSpeed, turnRate = 0.3), 0.0, 20)
    run(Horse(gait = Gait.HALT, turnRate = GRAZING.turnRate), 0.0, 20)
    horse.dispose()
    return ext
}

class PaddockHorseExtentTest {
    private fun check(level: GraphicsLevel) {
        val e = measure(level)
        assertTrue(e.front <= HORSE_EXTENT.front, "$level front ${e.front}")
        assertTrue(e.back <= HORSE_EXTENT.back, "$level back ${e.back}")
        assertTrue(e.side <= HORSE_EXTENT.side, "$level side ${e.side}")
        assertTrue(e.radius <= GRAZING.bodyRadius, "$level radius ${e.radius}")
        // and the numbers are not wildly generous (the model would shrink and nobody would notice)
        assertTrue(e.front > HORSE_EXTENT.front - 0.08, "$level front ${e.front}")
        assertTrue(e.back > HORSE_EXTENT.back - 0.08, "$level back ${e.back}")
    }

    @Test
    fun `low horse nose and tail and sides and the circle around the body centre`() = check(GraphicsLevel.LOW)

    @Test
    fun `medium horse nose and tail and sides and the circle around the body centre`() = check(GraphicsLevel.MEDIUM)
}
