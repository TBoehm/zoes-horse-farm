package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.domain.sim.Horse
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// camera.js has no tests in the web app. The expected values were computed by running its
// `createCameraRig` with three.js r186 (node) through the same sequence of frames as below.

private const val DT = 1.0 / 60

private fun assertPose(
    camera: PerspectiveCamera,
    position: DoubleArray,
    quaternion: DoubleArray,
    message: String,
) {
    val p = camera.position
    assertEquals(position[0], p.x, 1e-9, "$message x")
    assertEquals(position[1], p.y, 1e-9, "$message y")
    assertEquals(position[2], p.z, 1e-9, "$message z")
    val q = camera.quaternion
    assertEquals(quaternion[0], q.x, 1e-9, "$message qx")
    assertEquals(quaternion[1], q.y, 1e-9, "$message qy")
    assertEquals(quaternion[2], q.z, 1e-9, "$message qz")
    assertEquals(quaternion[3], q.w, 1e-9, "$message qw")
}

private class ExpectedPose(
    val position: DoubleArray,
    val quaternion: DoubleArray,
) {
    fun assertMatches(
        camera: PerspectiveCamera,
        message: String,
    ) = assertPose(camera, position, quaternion, message)
}

/** A pose from "x y z qx qy qz qw". */
private fun pose(numbers: String): ExpectedPose {
    val v = numbers.split(' ').map { it.toDouble() }
    return ExpectedPose(doubleArrayOf(v[0], v[1], v[2]), doubleArrayOf(v[3], v[4], v[5], v[6]))
}

private val FOLLOW_POSES =
    mapOf(
        0 to
            pose(
                "-0.19399747183274352 3.6 -10.697371137239301 " +
                    "0.020175197709528175 0.9712638023693226 0.08917375681864798 -0.2197444622722388",
            ),
        10 to
            pose(
                "-0.2979964934920959 3.6 -10.32819618319546 " +
                    "0.022257297128583535 0.9654967740315579 0.0880472376120096 -0.24406613039928157",
            ),
        59 to
            pose(
                "0.06293008904095612 3.6 -0.8084509396373465 " +
                    "-0.08517697450995387 -0.4742924981523331 -0.046169003934034905 0.8750199610783109",
            ),
        // in the jump the camera rises only halfway
        75 to
            pose(
                "3.741898264261303 3.9277682207061537 1.106820045929381 " +
                    "-0.09300010527921329 -0.14030917736229415 -0.013238859224008269 0.9856414397601282",
            ),
        149 to
            pose(
                "4.397605220826817 3.6022194654713675 -10.584865418695845 " +
                    "-0.004748211063083064 0.9940054568865706 0.0980480952003091 0.04813706678861488",
            ),
    )

private val RIDER_POSES =
    mapOf(
        0 to
            pose(
                "1.3921983168093304 1.84 3.3436064719425405 " +
                    "-0.0582639147699779 0.12704519495186348 0.007475741444690609 0.9901560220314634",
            ),
        20 to
            pose(
                "1.6433264878880491 1.84 3.1878283148221827 " +
                    "-0.05490913682227125 0.3556433199042064 0.020935509890319726 0.9327724910858917",
            ),
        99 to
            pose(
                "1.2242022172496878 1.84 2.361550289938841 " +
                    "-0.0013348935348671122 0.9980142161984386 0.05875057449279391 0.022676250171343792",
            ),
    )

private val RIDER_WITHOUT_ANCHOR =
    pose(
        "3.8170965096074134 3.6 -10.841575548708272 " +
            "-0.0019234731173178655 0.9955913295512171 0.09140731786316054 0.020950107748411554",
    )

class CameraModeTest {
    @Test
    fun `knows both modes by their stored id`() {
        assertEquals(CameraMode.FOLLOW, CameraMode.fromId("follow"))
        assertEquals(CameraMode.RIDER, CameraMode.fromId("rider"))
        assertNull(CameraMode.fromId("bogus"))
        assertEquals(listOf("follow", "rider"), CAMERA_MODES.map { it.id })
    }

    @Test
    fun `starts in the follow mode toggles and ignores unknown ids`() {
        val rig = CameraRig(PerspectiveCamera())
        assertEquals(CameraMode.FOLLOW, rig.mode)
        assertEquals(CameraMode.RIDER, rig.toggle())
        assertEquals(CameraMode.RIDER, rig.mode)
        rig.setMode("bogus")
        assertEquals(CameraMode.RIDER, rig.mode)
        rig.setMode("follow")
        assertEquals(CameraMode.FOLLOW, rig.mode)
        assertEquals(CameraMode.RIDER, rig.toggle())
    }
}

class CameraRigTest {
    private val camera = PerspectiveCamera(60.0, 1.5, 0.1, 500.0)
    private val rig = CameraRig(camera)
    private val horse = Horse(x = 3.0, z = -4.0, heading = 0.4)

    /** One frame of the horse turning and, between frames 60 and 90, jumping. */
    private fun followFrame(
        i: Int,
        earAnchor: Node? = null,
    ) {
        horse.heading += 0.045
        horse.x += sin(horse.heading) * 0.08
        horse.z += cos(horse.heading) * 0.08
        horse.y = if (i in 61..89) sin((i - 60) / 30.0 * PI) * 1.2 else 0.0
        rig.update(DT, horse, earAnchor)
    }

    @Test
    fun `the follow camera trails the horse and reproduces the web app`() {
        for (i in 0 until 150) {
            followFrame(i)
            FOLLOW_POSES[i]?.assertMatches(camera, "frame $i")
        }
    }

    @Test
    fun `a big jump of the horse heading is swung to slowly`() {
        for (i in 0 until 150) followFrame(i)
        horse.heading += 2.5
        rig.update(DT, horse, null)
        assertPose(
            camera,
            doubleArrayOf(4.124264284645879, 3.6020420068103944, -10.570982658176089),
            doubleArrayOf(-0.0025101633518771526, 0.994840709299622, 0.09817676980342908, 0.02543588156790387),
            "after the jump",
        )
    }

    @Test
    fun `the rider view sits behind and above the ears and reproduces the web app`() {
        for (i in 0 until 150) followFrame(i)
        horse.heading += 2.5
        rig.update(DT, horse, null)
        rig.toggle()
        val horseNode = Group()
        horseNode.position.set(1.0, 0.0, 2.0)
        horseNode.rotation.y = 0.3
        val ear = Group()
        ear.position.set(0.0, 1.6, 0.9)
        horseNode.add(ear)
        for (i in 0 until 100) {
            horse.heading += 0.03
            rig.update(DT, horse, ear)
            RIDER_POSES[i]?.assertMatches(camera, "rider $i")
        }
        // rider mode without an anchor falls back to the follow code
        rig.update(DT, horse, null)
        RIDER_WITHOUT_ANCHOR.assertMatches(camera, "rider without anchor")
    }

    @Test
    fun `snap jumps straight to the target position`() {
        for (i in 0 until 150) followFrame(i)
        horse.heading += 2.5
        rig.update(DT, horse, null)
        rig.snap()
        rig.update(DT, horse, null)
        // 7.5 m behind the horse along the camera heading, 3.6 m up
        assertEquals(3.6, camera.position.y, 1e-12)
        assertEquals(7.5, hypot(horse.x - camera.position.x, horse.z - camera.position.z), 1e-9)
    }

    @Test
    fun `setMode restarts the follow camera from the target`() {
        for (i in 0 until 50) followFrame(i)
        rig.setMode("follow")
        rig.update(DT, horse, null)
        assertEquals(7.5, hypot(horse.x - camera.position.x, horse.z - camera.position.z), 1e-9)
    }
}
