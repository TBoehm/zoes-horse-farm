package app.zoeshorsefarm.input

import app.zoeshorsefarm.platform.DeviceClass
import app.zoeshorsefarm.platform.GameKey
import app.zoeshorsefarm.platform.InputMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputTest {
    // mergeInputs

    @Test
    fun mergeAddsSteerAndThrottleClampedToOneAndOrsTheButtons() {
        val merged =
            mergeInputs(
                InputState(steer = 1.0, throttle = -1.0, gallop = false, jump = true, pause = false, camera = false),
                InputState(steer = 0.5, throttle = -0.5, gallop = true, jump = false, pause = true, camera = true),
            )
        assertEquals(
            InputState(steer = 1.0, throttle = -1.0, gallop = true, jump = true, pause = true, camera = true),
            merged,
        )
    }

    // shared game keys (web input-mode.test.js "shared game keys")

    @Test
    fun theKeyboardHandlesExactlyTheKeysThatEndTheTouchMode() {
        for (key in GameKey.entries) {
            val keyboard = KeyboardInput()
            assertTrue(keyboard.onKeyDown(key), "the keyboard ignores ${key.code}")
            val mode = InputMode(DeviceClass.HYBRID)
            mode.onTouch()
            assertTrue(mode.touch)
            mode.onKey(key)
            assertFalse(mode.touch, "${key.code} does not end the touch mode")
        }
        // a key that is not a game key is neither handled nor ends the touch mode
        assertEquals(null, GameKey.fromCode("KeyX"))
    }

    @Test
    fun aTouchModeSwitchCausedByAnArrowKeyAlsoCounts() {
        // arrow key -> (steer, throttle) it steers with
        val expected =
            mapOf(
                GameKey.ARROW_UP to (0.0 to 1.0),
                GameKey.ARROW_DOWN to (0.0 to -1.0),
                GameKey.ARROW_LEFT to (-1.0 to 0.0),
                GameKey.ARROW_RIGHT to (1.0 to 0.0),
            )
        for ((arrow, steering) in expected) {
            val input = Input(touchMode = true)
            val mode = InputMode(DeviceClass.HYBRID)
            mode.onTouch()
            mode.onChange { on -> input.onTouchModeChange(on) }
            mode.onKey(arrow) // the detector sees the key first and switches the mode
            assertFalse(input.touch.visible, arrow.code)
            input.keyboard.onKeyDown(arrow)
            val state = input.poll()
            assertEquals(steering, state.steer to state.throttle, arrow.code)
        }
    }

    // input rules

    @Test
    fun showsTheTouchControlsAccordingToTheMode() {
        val hidden = Input(touchMode = false)
        assertFalse(hidden.touch.visible)
        hidden.onTouchModeChange(true)
        assertTrue(hidden.touch.visible)
        assertTrue(Input(touchMode = true).touch.visible)
    }

    @Test
    fun theGameEndingTheGallopSwitchesTouchOffAndNeedsANewShiftPress() {
        val input = Input(touchMode = false)
        input.touch.setGallop(true)
        input.keyboard.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(input.poll().gallop)
        input.endGallop()
        assertFalse(input.touch.gallop)
        assertFalse(input.poll().gallop)
        input.keyboard.onKeyUp(GameKey.SHIFT_LEFT)
        input.keyboard.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(input.poll().gallop)
    }

    @Test
    fun aTouchModeSwitchEndsTheTouchGallop() {
        val input = Input(touchMode = true)
        input.touch.setGallop(true)
        assertTrue(input.poll().gallop)
        // a game key switches back to the keyboard (the platform reports that first)
        input.onTouchModeChange(false)
        input.keyboard.onKeyDown(GameKey.KEY_W)
        assertFalse(input.touch.gallop)
        assertFalse(input.poll().gallop)
    }

    @Test
    fun aTouchModeSwitchCausedByPressingShiftDoesNotStartAGallopUntilShiftIsPressedAnew() {
        val input = Input(touchMode = true)
        input.touch.setGallop(true)
        // the Shift key switches the mode and is the Shift press itself: mode change comes first
        input.onTouchModeChange(false)
        input.keyboard.onKeyDown(GameKey.SHIFT_LEFT)
        assertFalse(input.touch.gallop)
        assertFalse(input.poll().gallop)
        input.keyboard.onKeyUp(GameKey.SHIFT_LEFT)
        input.keyboard.onKeyDown(GameKey.SHIFT_LEFT)
        assertTrue(input.poll().gallop)
    }

    @Test
    fun clearEdgesAfterContinueDropsPendingJumpPauseAndCameraOfBothSources() {
        val input = Input(touchMode = true)
        input.keyboard.onKeyDown(GameKey.SPACE)
        input.touch.pressJump()
        input.touch.pressPause()
        input.touch.pressCamera()
        input.clearEdges()
        input.poll().let {
            assertFalse(it.jump)
            assertFalse(it.pause)
            assertFalse(it.camera)
        }
    }

    @Test
    fun keepsTheTouchGallopAcrossAPauseSinceClearEdgesDoesNotTouchIt() {
        val input = Input(touchMode = true)
        input.touch.setGallop(true)
        input.clearEdges()
        assertTrue(input.poll().gallop)
    }

    @Test
    fun resetTouchGallopOnlyAffectsTouch() {
        val input = Input(touchMode = true)
        input.touch.setGallop(true)
        input.keyboard.onKeyDown(GameKey.SHIFT_LEFT)
        input.resetTouchGallop()
        assertFalse(input.touch.gallop)
        assertTrue(input.poll().gallop) // keyboard Shift still held
    }

    @Test
    fun pollMergesKeyboardAndTouchAndConsumesTheEdges() {
        val input = Input(touchMode = true)
        input.keyboard.onKeyDown(GameKey.KEY_D)
        input.touch.moveStick(1.0, 0.0)
        input.touch.pressJump()
        input.poll().let {
            assertEquals(1.0, it.steer)
            assertTrue(it.jump)
        }
        assertFalse(input.poll().jump)
    }

    @Test
    fun theKeyboardRespectsTheIsActiveCallback() {
        var active = false
        val input = Input(touchMode = false, isActive = { active })
        input.keyboard.onKeyDown(GameKey.KEY_W)
        assertEquals(0.0, input.poll().throttle)
        active = true
        input.keyboard.onKeyDown(GameKey.KEY_W)
        assertEquals(1.0, input.poll().throttle)
    }
}
