package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.application.GRAPHICS_LEVELS
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.sim.Gait
import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.horse.GaitWeights
import app.zoeshorsefarm.view3d.horse.RiderContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The rider before the face and the ponytail had 1336 / 2518 / 4036 triangles (low / medium / high);
// on medium and high the face, chin strap, ponytail bow and jacket details may cost this much more,
// no more (GPU memory estimate). Low gets them for free (rule 3): it must not exceed what it had.
private fun triangleBudget(level: GraphicsLevel) =
    when (level) {
        GraphicsLevel.LOW -> 1336
        GraphicsLevel.MEDIUM -> 4300
        GraphicsLevel.HIGH -> 7400
    }

private fun trianglesOf(rider: Rider) = rider.mesh.geometry.indexCount / 3

private fun isFrontFace(
    z: Double,
    y: Double,
) = z > 0.085 && y > 0.7 && y < 0.84

private fun verticesOf(rider: Rider) = rider.mesh.geometry.position.count

class RiderGeometryTest {
    @Test
    fun `every level has one skinned mesh within the triangle budget`() {
        for (level in GRAPHICS_LEVELS) {
            val rider = createRider(level)
            val meshes = ArrayList<Mesh>()
            rider.group.traverse { if (it is Mesh) meshes.add(it) }
            assertEquals(1, meshes.size, "$level draw calls")
            assertTrue(trianglesOf(rider) <= triangleBudget(level), "$level triangles ${trianglesOf(rider)}")
            rider.dispose()
        }
    }

    @Test
    fun `every level has valid skinning and finite positions and unit normals`() {
        for (level in GRAPHICS_LEVELS) {
            val rider = createRider(level)
            val geo = rider.mesh.geometry
            val boneCount =
                rider.mesh.skeleton!!
                    .bones.size
            val skinIndex = geo.getAttribute("skinIndex") as app.zoeshorsefarm.scene.geometry.UShortAttribute
            val skinWeight = geo.float("skinWeight")
            val position = geo.position
            val normal = geo.normal
            // pole duplicates of the primitive spheres are not part of any triangle (no normal)
            val used = geo.index!!.toHashSet()
            for (i in 0 until position.count) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += skinWeight.array[i * 4 + k]
                    assertTrue(skinIndex.array[i * 4 + k].toInt() < boneCount, "$level bone index")
                }
                assertClose(1.0, sum, 4)
                assertTrue((position.getX(i) + position.getY(i) + position.getZ(i)).isFinite())
                val len = hypot(hypot(normal.getX(i), normal.getY(i)), normal.getZ(i))
                if (i in used) assertClose(1.0, len, 3)
            }
            rider.dispose()
        }
    }

    @Test
    fun `more detail on higher levels and low is the cheapest`() {
        val counts =
            GRAPHICS_LEVELS.map { level ->
                val rider = createRider(level)
                val n = trianglesOf(rider)
                rider.dispose()
                n
            }
        assertTrue(counts[0] < counts[1])
        assertTrue(counts[1] < counts[2])
    }

    @Test
    fun `the face sits on the front of the head and the ponytail behind it`() {
        val rider = createRider(GraphicsLevel.HIGH)
        val geo = rider.mesh.geometry
        val position = geo.position
        val skinIndex = geo.getAttribute("skinIndex") as app.zoeshorsefarm.scene.geometry.UShortAttribute
        val skinWeight = geo.float("skinWeight")
        val bones =
            rider.mesh.skeleton!!
                .bones
                .map { it.name }
        val ponyIdx = listOf("pony1", "pony2", "pony3").map { bones.indexOf(it) }.toSet()
        var frontFace = 0
        var pony = 0
        for (i in 0 until position.count) {
            val main = skinIndex.getX(i)
            if (skinWeight.array[i * 4] > 0.5 && main in ponyIdx) {
                pony++
                assertLess(position.getZ(i), -0.05, "behind the head")
            }
            if (bones[main] == "head" && isFrontFace(position.getZ(i), position.getY(i))) frontFace++
        }
        assertGreater(pony.toDouble(), 30.0)
        assertGreater(frontFace.toDouble(), 150.0)
        rider.dispose()
    }
}

class RiderLifecycleTest {
    @Test
    fun `hands the replaced geometry and material to release on a quality change`() {
        val released = ArrayList<GpuObject?>()
        val rider = createRider(GraphicsLevel.LOW) { released.add(it) }
        val oldGeometry = rider.mesh.geometry
        val oldMaterial = rider.mesh.material
        val before = verticesOf(rider)
        rider.setQuality(GraphicsLevel.HIGH)
        assertEquals(2, released.size)
        assertTrue(released.any { it === oldGeometry })
        assertTrue(released.any { it === oldMaterial })
        assertTrue(verticesOf(rider) > before)
        rider.setQuality(GraphicsLevel.HIGH)
        assertEquals(2, released.size, "same level: nothing is rebuilt")
        rider.dispose()
    }

    @Test
    fun `dispose frees the geometry and the material through release`() {
        val released = ArrayList<GpuObject?>()
        val rider = createRider(GraphicsLevel.MEDIUM) { released.add(it) }
        val geometry = rider.mesh.geometry
        val material = rider.mesh.material
        rider.dispose()
        assertTrue(released.any { it === geometry })
        assertTrue(released.any { it === material })
        assertNull(rider.group.parent)
    }

    @Test
    fun `dispose frees the geometry and the material right away by default`() {
        val rider = createRider(GraphicsLevel.MEDIUM)
        val geometry = rider.mesh.geometry
        val material = rider.mesh.material
        rider.dispose()
        assertEquals(1, geometry.disposeCount)
        assertEquals(1, material.disposeCount)
    }
}

class RiderHeadLookTest {
    private fun ctx(gait: Gait): RiderContext =
        RiderContext().apply {
            weights =
                GaitWeights(
                    halt = if (gait == Gait.HALT) 1.0 else 0.0,
                    canter = if (gait == Gait.CANTER) 1.0 else 0.0,
                )
        }

    private fun headYaw(rider: Rider) = Euler().setFromQuaternion(rider.bones.getValue("head").quaternion).y

    @Test
    fun `turns the head into the curve and back`() {
        val rider = createRider(GraphicsLevel.LOW)
        val context = ctx(Gait.CANTER)
        val state = Horse(gait = Gait.CANTER)
        state.turnRate = 0.9
        repeat(120) { rider.update(1.0 / 60, state, context) }
        val right = headYaw(rider)
        assertLess(right, -0.1)
        assertGreater(right, -0.4, "clamped, the body keeps facing forward")
        state.turnRate = -0.9
        repeat(120) { rider.update(1.0 / 60, state, context) }
        assertGreater(headYaw(rider), 0.1)
        state.turnRate = 0.0
        repeat(240) { rider.update(1.0 / 60, state, context) }
        assertLess(abs(headYaw(rider)), 0.02)
        rider.dispose()
    }
}

class RiderPonytailMotionTest {
    private fun swing(rider: Rider) =
        listOf("pony1", "pony2", "pony3").sumOf {
            val bone = rider.bones.getValue(it)
            abs(bone.rotation.x) + abs(bone.rotation.y)
        }

    // moves the whole rider so that the head accelerates, like the horse speeding up
    private fun drive(
        rider: Rider,
        accel: Double,
        seconds: Double,
    ) {
        for (i in 0 until kotlin.math.round(seconds * 60).toInt()) {
            val t = i / 60.0
            rider.group.position.z = 0.5 * accel * t * t
            rider.group.updateMatrixWorld(true)
            rider.lateUpdate(1.0 / 60)
        }
    }

    @Test
    fun `hangs still without motion`() {
        val rider = createRider(GraphicsLevel.LOW)
        drive(rider, 0.0, 1.0)
        assertLess(swing(rider), 1e-3)
        rider.dispose()
    }

    @Test
    fun `streams back when the rider speeds up and settles when it stops`() {
        val rider = createRider(GraphicsLevel.LOW)
        drive(rider, 3.0, 1.5)
        assertGreater(swing(rider), 0.05)
        // stands still again (position stays), the ponytail comes to rest
        repeat(300) { rider.lateUpdate(1.0 / 60) }
        assertLess(swing(rider), 0.01)
        rider.dispose()
    }

    @Test
    fun `ignores a teleport instead of whipping`() {
        val rider = createRider(GraphicsLevel.LOW)
        drive(rider, 0.0, 0.5)
        rider.group.position.copy(Vec3(40.0, 0.0, -30.0))
        rider.group.updateMatrixWorld(true)
        rider.lateUpdate(1.0 / 60)
        assertLess(swing(rider), 1e-3)
        rider.dispose()
    }

    @Test
    fun `survives a zero or negative time step`() {
        val rider = createRider(GraphicsLevel.LOW)
        rider.lateUpdate(0.0)
        rider.lateUpdate(-1.0)
        assertEquals(0.0, swing(rider))
        rider.dispose()
    }
}
