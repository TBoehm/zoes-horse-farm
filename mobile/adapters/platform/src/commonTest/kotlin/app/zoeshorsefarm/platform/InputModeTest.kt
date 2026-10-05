package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputModeTest {
    @Test
    fun `classifies devices`() {
        assertEquals(DeviceClass.KEYBOARD, classifyDevice(touchCapable = false, finePointer = false))
        assertEquals(DeviceClass.TOUCH, classifyDevice(touchCapable = true, finePointer = false))
        assertEquals(DeviceClass.HYBRID, classifyDevice(touchCapable = true, finePointer = true))
        assertEquals(DeviceClass.KEYBOARD, classifyDevice(touchCapable = false, finePointer = true))
    }

    @Test
    fun `touch only device is always active and keys change nothing`() {
        val mode = InputMode(DeviceClass.TOUCH)
        assertTrue(mode.touch)
        mode.onKey(GameKey.KEY_W)
        assertTrue(mode.touch)
    }

    @Test
    fun `keyboard only device is never active`() {
        val mode = InputMode(DeviceClass.KEYBOARD)
        mode.onTouch()
        assertFalse(mode.touch)
    }

    @Test
    fun `hybrid starts off and a touch turns it on and a game key turns it off`() {
        val mode = InputMode(DeviceClass.HYBRID)
        val changes = mutableListOf<Boolean>()
        mode.onChange { changes += it }
        assertFalse(mode.touch)
        mode.onTouch()
        assertTrue(mode.touch)
        mode.onKey(GameKey.fromCode("KeyX"))
        assertTrue(mode.touch)
        mode.onKey(GameKey.SPACE)
        assertFalse(mode.touch)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `a game key typed into an editable field keeps hybrid mode on`() {
        val mode = InputMode(DeviceClass.HYBRID)
        mode.onTouch()
        mode.onKey(GameKey.KEY_W, inEditableField = true)
        assertTrue(mode.touch)
        mode.onKey(GameKey.KEY_W, inEditableField = false)
        assertFalse(mode.touch)
    }

    @Test
    fun `arrow keys steer so they end the touch mode like WASD`() {
        for (code in listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight")) {
            val mode = InputMode(DeviceClass.HYBRID)
            mode.onTouch()
            assertTrue(mode.touch)
            mode.onKey(GameKey.fromCode(code))
            assertFalse(mode.touch, code)
        }
    }

    @Test
    fun `an unchanged mode notifies nobody`() {
        val mode = InputMode(DeviceClass.HYBRID)
        var calls = 0
        mode.onChange { calls += 1 }
        mode.onKey(GameKey.KEY_W)
        mode.onTouch()
        mode.onTouch()
        assertEquals(1, calls)
    }

    @Test
    fun `dispose removes the listeners`() {
        val mode = InputMode(DeviceClass.HYBRID)
        var calls = 0
        val off = mode.onChange { calls += 1 }
        off()
        mode.onTouch()
        assertEquals(0, calls)
    }

    @Test
    fun `phones and tablets are touch devices`() {
        assertTrue(InputMode.mobile().touch)
        assertEquals(DeviceClass.TOUCH, InputMode.mobile().device)
    }

    @Test
    fun `portrait means taller than wide`() {
        assertTrue(isPortrait(width = 390, height = 844))
        assertFalse(isPortrait(width = 844, height = 390))
        assertFalse(isPortrait(width = 600, height = 600))
    }
}
