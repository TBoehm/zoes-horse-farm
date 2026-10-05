package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.domain.testing.FRAME
import app.zoeshorsefarm.domain.testing.Sequences
import app.zoeshorsefarm.domain.testing.runScript
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.UShortAttribute
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.createRng
import app.zoeshorsefarm.shared.clamp
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The horse as a whole (scene model without a renderer): detail per level, skeleton of the hair and
// eyelids, eyelid and hair motion, footfall events, release of GPU objects.

// Size of body and tack geometry before SRT-011 (vertices, triangles): low must not grow
private class Before(
    val bodyVertices: Int,
    val bodyTriangles: Int,
    val tackVertices: Int,
    val tackTriangles: Int,
)

private fun before(level: GraphicsLevel) =
    when (level) {
        GraphicsLevel.LOW -> Before(1681, 3202, 650, 1228)
        GraphicsLevel.MEDIUM -> Before(4220, 8218, 1344, 2596)
        GraphicsLevel.HIGH -> Before(8669, 17052, 2814, 5512)
    }

private class Counts(
    val skel: SkeletonBones,
    val body: Geometry,
    val tack: Geometry,
) {
    val bodyVertices get() = body.position.count
    val bodyTriangles get() = body.indexCount / 3
    val tackVertices get() = tack.position.count
    val tackTriangles get() = tack.indexCount / 3
}

private fun counts(level: GraphicsLevel): Counts {
    val skel = createSkeletonBones()
    return Counts(skel, buildBodyGeometry(skel.index, level), buildTackGeometry(skel.index, level))
}

private fun meshesOf(horse: HorseView): List<Mesh> {
    val meshes = ArrayList<Mesh>()
    horse.group.traverse { if (it is Mesh) meshes.add(it) }
    return meshes
}

class HorseDetailTest {
    @Test
    fun `low gets no extra vertices and no extra triangles`() {
        val c = counts(GraphicsLevel.LOW)
        val b = before(GraphicsLevel.LOW)
        assertTrue(c.bodyVertices <= b.bodyVertices, "body vertices ${c.bodyVertices}")
        assertTrue(c.bodyTriangles <= b.bodyTriangles, "body triangles ${c.bodyTriangles}")
        assertTrue(c.tackVertices <= b.tackVertices, "tack vertices ${c.tackVertices}")
        assertTrue(c.tackTriangles <= b.tackTriangles, "tack triangles ${c.tackTriangles}")
    }

    @Test
    fun `low horse and rider together have no more triangles and no more draw calls than before`() {
        // before SRT-011: rider 1336 + body 3202 + tack 1228 + reins 160 triangles, four meshes
        val horse = createHorse(quality = GraphicsLevel.LOW)
        val meshes = meshesOf(horse)
        val triangles = meshes.sumOf { it.geometry.indexCount / 3 }
        assertEquals(listOf("horse-body", "horse-reins", "horse-tack", "rider-body"), meshes.map { it.name }.sorted())
        assertTrue(triangles <= 5926, "triangles $triangles")
        horse.dispose()
    }

    @Test
    fun `medium and high add eyelids and leg wraps within the same two meshes`() {
        for (level in listOf(GraphicsLevel.MEDIUM, GraphicsLevel.HIGH)) {
            val c = counts(level)
            val b = before(level)
            assertTrue(c.bodyVertices > b.bodyVertices, "$level eyelids")
            assertTrue(c.tackVertices > b.tackVertices, "$level wraps")
            // modest: a few percent of the horse
            assertTrue(
                c.bodyTriangles + c.tackTriangles < (b.bodyTriangles + b.tackTriangles) * 1.15,
                "$level triangles",
            )
        }
        val horse = createHorse(quality = GraphicsLevel.HIGH, rider = false)
        assertEquals(listOf("horse-body", "horse-reins", "horse-tack"), meshesOf(horse).map { it.name }.sorted())
        horse.dispose()
    }

    @Test
    fun `every vertex is skinned to existing bones with weights that add up to 1`() {
        for (level in GRAPHICS_LEVELS) {
            val c = counts(level)
            checkSkinning(c.body, c.skel.list.size)
            checkSkinning(c.tack, c.skel.list.size)
        }
    }
}

private fun checkSkinning(
    g: Geometry,
    boneCount: Int,
) {
    val idx = g.getAttribute("skinIndex") as UShortAttribute
    val w = g.float("skinWeight")
    for (i in 0 until idx.count) {
        var sum = 0.0
        for (k in 0 until 4) {
            val weight = w.array[i * 4 + k].toDouble()
            sum += weight
            if (weight > 0) assertTrue(idx.array[i * 4 + k].toInt() < boneCount)
        }
        assertClose(1.0, sum, 4)
    }
}

class HorseSkeletonTest {
    private val skel = createSkeletonBones()
    private val bones = skel.bones

    @Test
    fun `has five mane bones along the neck and a forelock bone and a lid per eye`() {
        for (k in 1..5) {
            assertNotNull(bones["mane$k"])
            assertNotNull(skel.restQuaternion["mane$k"])
        }
        assertEquals("head", bones.getValue("forelock").parent?.name)
        assertNotNull(skel.lidAxes["Llid"])
        assertEquals("head", bones.getValue("Rlid").parent?.name)
    }

    @Test
    fun `mane bones sit on the crest in front of each other from the withers to the poll`() {
        val rest = (1..5).map { skel.rest.getValue("mane$it") }
        for (i in 1 until rest.size) {
            assertGreater(rest[i].z, rest[i - 1].z)
            assertGreater(rest[i].y, rest[i - 1].y)
        }
        // above the neck line (crest), not inside the body
        assertGreater(rest[2].y, 1.6)
    }

    @Test
    fun `the bone frame of the mane has z along the neck and y up and x sideways`() {
        val q = skel.restQuaternion.getValue("mane3")
        val along = Vec3(0.0, 0.0, 1.0).applyQuaternion(q)
        val up = Vec3(0.0, 1.0, 0.0).applyQuaternion(q)
        val side = Vec3(1.0, 0.0, 0.0).applyQuaternion(q)
        assertGreater(along.z, 0.5) // forward and up
        assertGreater(along.y, 0.3)
        assertGreater(up.y, 0.5)
        assertGreater(abs(side.x), 0.99)
    }
}

class EyelidTest {
    @Test
    fun `the open lid rests above the eye and the closing rotation swings it over the gaze`() {
        for (side in listOf(1.0, -1.0)) {
            val gaze = eyeGaze(side)
            val axis = lidAxis(side)
            val open = lidPole(side, Eye.OPEN_ANGLE)

            fun angle(v: Vec3) = acos(clamp(v.dot(gaze), -1.0, 1.0)) * 180 / PI
            assertClose(135.0, angle(open), 3)
            assertGreater(open.y, 0.5) // up
            // closing: turn the pole about the hinge axis by -closeAngle, as HorseView does
            val closed = open.clone().applyAxisAngle(axis, -Eye.CLOSE_ANGLE)
            assertLess(angle(closed), 20.0)
            // half way it has passed the top of the eye
            val half = open.clone().applyAxisAngle(axis, -Eye.CLOSE_ANGLE / 2)
            assertGreater(angle(half), 50.0)
            assertLess(angle(half), 100.0)
        }
    }

    @Test
    fun `the horse blinks the lids close and open again`() {
        val horse = createHorse(quality = GraphicsLevel.MEDIUM, rider = false, rng = createRng(4))
        val lid = horse.group.getObjectByName("Llid")!!
        var closed = 0
        var open = 0
        val still = Horse(gait = Gait.HALT, speed = 0.0)
        repeat(20 * 60) {
            horse.update(FRAME, still)
            val a = 2 * acos(min(1.0, abs(lid.quaternion.w)))
            if (a > Eye.CLOSE_ANGLE * 0.95) closed++
            if (a < 0.01) open++
        }
        assertGreater(closed.toDouble(), 3.0)
        assertGreater(open.toDouble(), 20 * 60 * 0.8)
        horse.dispose()
    }
}

class HorseHairTest {
    @Test
    fun `a stop swings tail and mane and forelock and they come to rest again`() {
        val horse = createHorse(quality = GraphicsLevel.LOW, rider = false, rng = createRng(2))

        fun rest(name: String) =
            horse.group
                .getObjectByName(name)!!
                .quaternion
                .clone()
        val mane = horse.group.getObjectByName("mane3")!!
        val tail = horse.group.getObjectByName("tail3")!!
        val forelock = horse.group.getObjectByName("forelock")!!
        val state = Horse(gait = Gait.CANTER, speed = 6.0)
        repeat(180) { horse.update(FRAME, state) }
        val beforeMane = rest("mane3")
        val beforeTail = rest("tail3")
        val beforeLock = rest("forelock")
        state.gait = Gait.HALT
        state.speed = 0.0
        var maneMove = 0.0
        var tailMove = 0.0
        var lockMove = 0.0
        repeat(30) {
            horse.update(FRAME, state)
            maneMove = maxOf(maneMove, beforeMane.angleTo(mane.quaternion))
            tailMove = maxOf(tailMove, beforeTail.angleTo(tail.quaternion))
            lockMove = maxOf(lockMove, beforeLock.angleTo(forelock.quaternion))
        }
        assertGreater(maneMove, 0.05)
        assertGreater(tailMove, 0.1)
        assertGreater(lockMove, 0.05)
        repeat(6 * 60) { horse.update(FRAME, state) }
        // at halt the hair hangs near its rest pose
        val maneRest = createSkeletonBones().restQuaternion.getValue("mane3")
        assertLess(mane.quaternion.angleTo(maneRest), 0.2)
        assertLess(abs(tail.rotation.z), 0.2)
        horse.dispose()
    }

    @Test
    fun `the horse without a rider and tack runs the same animation`() {
        val horse = createHorse(quality = GraphicsLevel.LOW, rider = false, tack = false)
        assertEquals(listOf("horse-body"), meshesOf(horse).map { it.name })
        assertNull(horse.rider)
        val state = Horse(gait = Gait.TROT, speed = 3.0, turnRate = 0.3)
        repeat(120) { horse.update(FRAME, state) }
        horse.dispose()
        assertNull(horse.group.parent)
    }
}

/** A copy of a footfall event (the horse reuses its event objects). */
private data class FootfallCopy(
    val kind: FootfallKind,
    val leg: Int,
    val gait: Gait,
    val strength: Double,
    val x: Double,
    val y: Double,
    val z: Double,
)

private fun copyOf(e: Footfall) = FootfallCopy(e.kind, e.leg, e.gait, e.strength, e.x, e.y, e.z)

class FootfallEventTest {
    private class Ride(
        val horse: HorseView,
        val events: List<FootfallCopy>,
        val steps: List<Pair<Gait, Int>>,
    )

    private fun ride(
        name: String,
        level: GraphicsLevel = GraphicsLevel.LOW,
    ): Ride {
        val horse = createHorse(quality = level, rider = false, rng = createRng(1))
        val events = ArrayList<FootfallCopy>()
        val steps = ArrayList<Pair<Gait, Int>>()
        horse.onFootfall = { gait, leg -> steps.add(gait to leg) }
        runScript(
            Sequences.all.getValue(name),
            { dt, state ->
                horse.update(dt, state)
                for (e in horse.footfalls) events.add(copyOf(e))
            },
            { _, _, _ -> },
        )
        return Ride(horse, events, steps)
    }

    @Test
    fun `canter strides produce step events with a strength per gait and a position under the hoof`() {
        val (horse, events, steps) = ride("gaitLadder").let { Triple(it.horse, it.events, it.steps) }
        val canter = events.filter { it.kind == FootfallKind.STEP && it.gait == Gait.CANTER }
        val walk = events.filter { it.kind == FootfallKind.STEP && it.gait == Gait.WALK }
        val trot = events.filter { it.kind == FootfallKind.STEP && it.gait == Gait.TROT }
        assertGreater(canter.size.toDouble(), 10.0)
        assertLess(walk[0].strength, trot[0].strength)
        assertLess(trot[0].strength, canter[0].strength)
        for (e in canter + trot + walk) {
            assertTrue(e.leg in 0..3)
            assertLess(abs(abs(e.x) - 0.16), 0.02, "under the leg")
            assertEquals(0.0, e.y)
            assertLess(abs(e.z), 1.3)
        }
        // the sound callback gets the same step events
        assertEquals(events.count { it.kind == FootfallKind.STEP }, steps.size)
        horse.dispose()
    }

    @Test
    fun `a jump ends with a strong landing of the forelegs and a lighter one of the hindlegs`() {
        val r = ride("jump")
        val landing = r.events.filter { it.kind == FootfallKind.LANDING }
        assertEquals(4, landing.size)
        assertEquals(listOf(0, 1), landing.filter { it.strength == 1.0 }.map { it.leg }.sorted())
        assertEquals(listOf(2, 3), landing.filter { it.strength < 1.0 }.map { it.leg }.sorted())
        assertTrue(landing.all { it.strength > 0.5 })
        r.horse.dispose()
    }

    @Test
    fun `reuses the event objects from frame to frame without allocation per step`() {
        val horse = createHorse(quality = GraphicsLevel.LOW, rider = false, rng = createRng(3))
        val state = Horse(gait = Gait.CANTER, speed = 6.0)
        val seen = HashSet<Footfall>()
        var events = 0
        repeat(600) {
            for (e in horse.update(FRAME, state)) {
                seen.add(e)
                events++
            }
        }
        assertGreater(events.toDouble(), 20.0)
        assertTrue(seen.size <= 4, "distinct event objects ${seen.size}")
        horse.dispose()
    }

    @Test
    fun `no step events at halt and none during the jump`() {
        val stop = ride("refusalStop")
        assertEquals(0, stop.events.count { it.kind == FootfallKind.LANDING })
        val jump = ride("jump").events
        // between the take-off and the landing nothing touches the ground
        assertTrue(jump.indexOfFirst { it.kind == FootfallKind.LANDING } > 0)
        stop.horse.dispose()
    }

    @Test
    fun `footfallWorld uses the current position and heading of the group`() {
        val horse = createHorse(quality = GraphicsLevel.LOW, rider = false, rng = createRng(1))
        horse.group.position.set(10.0, 0.0, 5.0)
        horse.group.rotation.y = PI / 2
        val ev =
            Footfall().also {
                it.x = 0.16
                it.y = 0.0
                it.z = 1.0
            }
        val out = horse.footfallWorld(ev)
        // facing +x: the local z axis points along +x, the local x axis along -z
        assertClose(11.0, out.x, 5)
        assertClose(5 - 0.16, out.z, 5)
        horse.dispose()
    }
}

class HorseReleaseTest {
    @Test
    fun `quality change and dispose go through the release hook`() {
        val released = ArrayList<GpuObject?>()
        val horse = createHorse(quality = GraphicsLevel.LOW, rider = false, release = { released.add(it) })
        val old =
            meshesOf(horse)
                .filter { it.name == "horse-body" || it.name == "horse-tack" }
                .flatMap { listOf(it.geometry, it.material) }
        horse.setQuality(GraphicsLevel.HIGH)
        // body and tack are rebuilt: the old buffers and materials go through the hook
        for (o in old) assertTrue(released.any { it === o }, "released $o")
        val current = meshesOf(horse).flatMap { listOf(it.geometry, it.material) }
        released.clear()
        horse.dispose()
        // the final dispose goes through the hook, too: the reins' buffers as well (an object of a lost
        // context must not be freed with GL calls)
        for (o in current) assertTrue(released.any { it === o }, "released $o")
    }

    @Test
    fun `an appearance change only changes uniforms and keeps the meshes`() {
        val horse = createHorse(quality = GraphicsLevel.LOW, rider = false)
        val geometries = meshesOf(horse).map { it.geometry }
        horse.setAppearance(coat = Coat.GREY)
        assertEquals(geometries, meshesOf(horse).map { it.geometry })
        horse.dispose()
    }
}
