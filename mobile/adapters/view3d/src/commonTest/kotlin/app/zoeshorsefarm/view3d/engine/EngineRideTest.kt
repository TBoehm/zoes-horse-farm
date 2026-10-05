package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.application.CameraMode
import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.application.modes.CourseLines
import app.zoeshorsefarm.application.modes.LineLabelKeys
import app.zoeshorsefarm.domain.course.Line
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.DEFAULT_APPEARANCE
import app.zoeshorsefarm.domain.sim.SimInput
import app.zoeshorsefarm.domain.sim.Vec2
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The 3D side of a ride: what the ride screen did with the world, the horse and the camera in the
// web app's frame function.

class EngineRideTest {
    @Test
    fun aRestartPutsTheHorseOnTheStartPoseAndSnapsTheCamera() {
        val rig = EngineRig()
        val session = newSession()
        rig.engine.beginRide(session.obstacles, session.flags)
        val state = session.view.horse
        rig.engine.restartRide(DEFAULT_APPEARANCE.copy(coat = Coat.GREY), state)
        val position = rig.engine.horse.group.position
        assertEquals(state.x, position.x)
        assertEquals(state.z, position.z)
        assertClose(state.heading, rig.engine.horse.group.rotation.y, 9)
    }

    @Test
    fun updateRideFollowsTheSimulationWithHorseAndCamera() {
        val rig = EngineRig(GraphicsLevel.MEDIUM)
        val session = newSession()
        rig.engine.beginRide(session.obstacles, session.flags)
        rig.engine.restartRide(DEFAULT_APPEARANCE, session.view.horse)
        val input = SimInput()
        input.set(steer = 0.0, throttle = 1.0, gallop = false, jump = false)
        repeat(240) {
            session.step(DT, input)
            rig.engine.updateRide(DT, session.view)
        }
        val state = session.view.horse
        assertGreater(hypot(state.x, state.z), 1.0)
        val position = rig.engine.horse.group.position
        assertEquals(state.x, position.x)
        assertEquals(state.z, position.z)
        // the follow camera sits behind and above the horse
        val camera = rig.engine.camera.position
        assertGreater(camera.y, 1.0)
        assertTrue(hypot(camera.x - state.x, camera.z - state.z) in 3.0..12.0)
    }

    @Test
    fun theCameraButtonSwitchesTheViewAndReportsTheNewOne() {
        val rig = EngineRig()
        assertEquals(CameraMode.RIDER, rig.engine.toggleCamera())
        assertEquals(CameraMode.RIDER, rig.engine.cameraRig.mode)
        assertEquals(CameraMode.FOLLOW, rig.engine.toggleCamera())
        rig.engine.setCameraMode(CameraMode.RIDER)
        assertEquals(CameraMode.RIDER, rig.engine.cameraRig.mode)
    }

    @Test
    fun showsAndHidesTheLines() {
        val rig = EngineRig()
        val lines =
            CourseLines(
                start = Line(Vec2(-3.0, -30.0), Vec2(3.0, -30.0), Vec2(0.0, 1.0)),
                finish = Line(Vec2(-3.0, 30.0), Vec2(3.0, 30.0), Vec2(0.0, 1.0)),
                labelKeys = LineLabelKeys("a", "b"),
            )

        fun visibleLineMeshes(): Int {
            var count = 0
            rig.engine.world.scene.traverseVisible { node ->
                var parent = node.parent
                while (parent != null && parent.name != "course-lines") parent = parent.parent
                if (parent != null && node is app.zoeshorsefarm.scene.graph.Mesh) count++
            }
            return count
        }
        assertEquals(0, visibleLineMeshes())
        rig.engine.showLines(lines, "Start", "Finish")
        assertTrue(visibleLineMeshes() >= 2) // the chalk lines with posts and the signs
        rig.engine.showLines(null, "", "")
        assertEquals(0, visibleLineMeshes())
        rig.engine.showLines(lines, "Ziel", "Start")
        assertTrue(visibleLineMeshes() >= 2)
    }

    @Test
    fun aRideDrawsAgainWhenItsContentChangesWhilePaused() {
        val rig = EngineRig()
        rig.run()
        rig.frames(2)
        rig.engine.setPaused(true)
        rig.frames(2)
        val drawn = rig.fake.renderCount
        rig.engine.showLines(null, "", "")
        rig.frames(2)
        assertEquals(drawn + 1, rig.fake.renderCount)
    }

    @Test
    fun disposeStopsTheLoopAndTheListeners() {
        val rig = EngineRig(GraphicsLevel.MEDIUM, auto = true)
        rig.run()
        rig.engine.dispose()
        assertEquals(false, rig.engine.running)
        rig.settings.setGraphicsLevel(GraphicsLevel.HIGH) // nobody listens any more
        assertEquals(GraphicsLevel.MEDIUM, rig.engine.level)
    }
}
