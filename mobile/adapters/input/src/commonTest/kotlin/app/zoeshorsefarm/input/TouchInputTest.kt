package app.zoeshorsefarm.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchInputTest {
    @Test
    fun theStickMapsThroughTheJoystickMappingAndReleaseCentersIt() {
        val touch = TouchInput()
        touch.moveStick(1.0, 0.0)
        assertEquals(1.0, touch.poll().steer)
        touch.releaseStick()
        assertEquals(InputState(), touch.poll())
    }

    @Test
    fun moveStickXYUsesTheCartesianPositionOfTheThumb() {
        val touch = TouchInput()
        touch.moveStickXY(0.0, -1.0)
        assertTrue(touch.poll().throttle < -0.99)
    }

    @Test
    fun theGallopButtonToggles() {
        val touch = TouchInput()
        touch.toggleGallop()
        assertTrue(touch.gallop)
        assertTrue(touch.poll().gallop)
        touch.toggleGallop()
        assertFalse(touch.gallop)
    }

    @Test
    fun buttonsAreEdgesThatPollConsumes() {
        val touch = TouchInput()
        touch.pressJump()
        touch.pressPause()
        touch.pressCamera()
        touch.poll().let {
            assertTrue(it.jump)
            assertTrue(it.pause)
            assertTrue(it.camera)
        }
        assertEquals(InputState(), touch.poll())
    }

    @Test
    fun clearEdgesKeepsStickAndGallop() {
        val touch = TouchInput()
        touch.setGallop(true)
        touch.moveStick(1.0, 0.0)
        touch.pressJump()
        touch.clearEdges()
        touch.poll().let {
            assertFalse(it.jump)
            assertTrue(it.gallop)
            assertEquals(1.0, it.steer)
        }
    }

    @Test
    fun hidingTheControlsReleasesTheStickButNotTheGallop() {
        val touch = TouchInput()
        touch.setVisible(true)
        touch.moveStick(1.0, 0.0)
        touch.setGallop(true)
        touch.setVisible(false)
        assertFalse(touch.visible)
        touch.poll().let {
            assertEquals(0.0, it.steer)
            assertTrue(it.gallop)
        }
    }
}
