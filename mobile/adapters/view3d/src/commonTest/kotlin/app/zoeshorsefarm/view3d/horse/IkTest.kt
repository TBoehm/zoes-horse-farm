package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertFinite
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import app.zoeshorsefarm.view3d.assertLessOrEqual
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

private fun p(
    a: DoubleArray,
    parent: DoubleArray,
) = RigPoint(a[1] - parent[1], a[2] - parent[2])

private class Hoof(
    val z: Double,
    val y: Double,
)

// forward kinematics: segment angles from the rotations (rotation.x = -deltaAngD)
private fun fkFront(
    rig: FrontRig,
    rot: DoubleArray,
): Hoof {
    var acc = -rot[0]
    val tsc = rig.tsc + acc
    var z = rig.a.z + rig.lsc * sin(tsc)
    var y = rig.a.y - rig.lsc * cos(tsc)
    val segs = listOf(rig.l1 to rig.t1, rig.l2 to rig.t2, rig.l3 to rig.t3, rig.l4 to rig.t4)
    segs.forEachIndexed { i, (l, t) ->
        acc += -rot[i + 1]
        z += l * sin(t + acc)
        y -= l * cos(t + acc)
    }
    return Hoof(z, y)
}

private fun fkHind(
    rig: HindRig,
    rot: DoubleArray,
): Hoof {
    var acc = 0.0
    var z = rig.p.z
    var y = rig.p.y
    val segs = listOf(rig.l1 to rig.t1, rig.l2 to rig.t2, rig.l3 to rig.t3, rig.l4 to rig.t4)
    segs.forEachIndexed { i, (l, t) ->
        acc += -rot[i]
        z += l * sin(t + acc)
        y -= l * cos(t + acc)
    }
    return Hoof(z, y)
}

class IkTest {
    private val f = REST.front
    private val sf = REST.spineFront
    private val front =
        makeFrontRig(
            p(f.scapula, sf),
            p(f.shoulder, sf),
            p(f.elbow, sf),
            p(f.knee, sf),
            p(f.fetlock, sf),
            p(f.hoof, sf),
        )
    private val h = REST.hind
    private val sr = REST.spineRear
    private val hind = makeHindRig(p(h.hip, sr), p(h.stifle, sr), p(h.hock, sr), p(h.fetlock, sr), p(h.hoof, sr))

    @Test
    fun `rest position yields zero rotation`() {
        val r = solveFront(front, front.h.z, front.h.y, front.t4, 0.0, 0.0)
        for (x in r) assertClose(0.0, x, 6)
        val hr = solveHind(hind, hind.h.z, hind.h.y, hind.t4, hind.t3)
        for (x in hr) assertClose(0.0, x, 6)
    }

    @Test
    fun `foreleg reaches reachable hoof points exactly also with flexed carpus`() {
        for ((dz, dy, knee) in listOf(
            Triple(0.3, 0.0, 0.0),
            Triple(-0.25, 0.0, 0.0),
            Triple(0.1, 0.3, 1.5),
            Triple(0.0, 0.5, 2.4),
        )) {
            val tz = front.h.z + dz
            val ty = front.h.y + dy
            val rot = solveFront(front, tz, ty, front.t4 - knee * 0.5, knee, 0.0)
            val pos = fkFront(front, rot)
            assertClose(tz, pos.z, 4)
            assertClose(ty, pos.y, 4)
        }
    }

    @Test
    fun `hind leg reaches hoof points exactly`() {
        for ((dz, dy, tilt) in listOf(Triple(0.3, 0.0, 0.2), Triple(-0.3, 0.0, -0.2), Triple(0.1, 0.12, -0.15))) {
            val tz = hind.h.z + dz
            val ty = hind.h.y + dy
            val rot = solveHind(hind, tz, ty, hind.t4, hind.t3 + tilt)
            val pos = fkHind(hind, rot)
            assertClose(tz, pos.z, 4)
            assertClose(ty, pos.y, 4)
        }
    }

    @Test
    fun `femur never tips up beyond its limit when the hoof target is close to the hip`() {
        val rot = solveHind(hind, hind.h.z + 0.4, hind.h.y + 0.7, hind.t4, hind.t3 - 1.2)
        val femurAngle = hind.t1 - rot[0]
        assertLessOrEqual(femurAngle, 1.35 + 1e-9)
    }

    @Test
    fun `unreachable targets leave the leg straight and give no NaN`() {
        val r = solveFront(front, front.h.z + 2, front.h.y - 1, front.t4, 0.0, 0.0)
        for (x in r) assertFinite(x)
        val hr = solveHind(hind, hind.h.z, hind.h.y - 2, hind.t4, hind.t3)
        for (x in hr) assertFinite(x)
    }

    /** Largest change of any rotation while the hoof target comes in from out of reach. */
    private fun worstStep(soft: Double): Double {
        var prev: DoubleArray? = null
        var worst = 0.0
        // the target rises 1 cm per step from 0.4 m below to 0.1 m above the rest hoof point
        var dy = -0.4
        while (dy <= 0.1) {
            val r = solveHind(hind, hind.h.z, hind.h.y + dy, hind.t4, hind.t3, DoubleArray(4), soft)
            val before = prev
            if (before != null) for (k in 0 until 4) worst = maxOf(worst, abs(r[k] - before[k]))
            prev = r
            dy += 0.01
        }
        return worst
    }

    @Test
    fun `without soft reach the leg bends in one step when the target comes back into reach`() {
        assertGreater(worstStep(0.0), 0.08)
    }

    @Test
    fun `with soft reach the same sweep bends the joints more gently`() {
        assertLess(worstStep(0.08), worstStep(0.0) * 0.7)
    }

    @Test
    fun `soft reach reaches targets that are well within reach exactly`() {
        val dz = 0.2
        val dy = 0.15
        val hard = solveFront(front, front.h.z + dz, front.h.y + dy, front.t4, 0.0, 0.0)
        val soft = solveFront(front, front.h.z + dz, front.h.y + dy, front.t4, 0.0, 0.0, soft = 0.08)
        for (k in 0 until 5) assertClose(hard[k], soft[k], 6)
    }

    @Test
    fun `soft reach stays finite for targets far out of reach`() {
        val r = solveFront(front, front.h.z, front.h.y - 3, front.t4, 0.0, 0.0, soft = 0.08)
        for (x in r) assertFinite(x)
        val hr = solveHind(hind, hind.h.z, hind.h.y - 3, hind.t4, hind.t3, soft = 0.08)
        for (x in hr) assertFinite(x)
    }

    @Test
    fun `angle helpers`() {
        assertClose(0.0, angD(0.0, -1.0))
        assertClose(PI / 2, angD(1.0, 0.0))
        assertClose(PI, wrap(3 * PI))
        assertClose(0.0, hindSweep(hind, hind.h.z, hind.h.y, hind.t4), 6)
    }
}
