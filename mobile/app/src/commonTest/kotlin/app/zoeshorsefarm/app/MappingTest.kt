package app.zoeshorsefarm.app

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.platform.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappingTest {
    @Test
    fun theDeviceFactsOfThePlatformBecomeTheFactsOfTheGraphicsBudget() {
        val info = DeviceInfo(6L * 1024 * 1024 * 1024, 6, true, 2532, 1170, "Apple GPU").toQualityDeviceInfo()

        assertEquals(6.0, info.totalMemoryGiB)
        assertEquals(6, info.cores)
        assertTrue(info.isTouch)
        assertEquals("Apple GPU", info.rendererName)
        assertEquals(2532, info.screenWidthPx)
        assertEquals(1170, info.screenHeightPx)
    }

    @Test
    fun anUnknownMemoryStaysUnknown() {
        val info = DeviceInfo(null, 2, false, null, null, null).toQualityDeviceInfo()

        assertNull(info.totalMemoryGiB)
        assertNull(info.rendererName)
        assertNull(info.screenWidthPx)
    }

    @Test
    fun aCrashWhileDrawingFollowsTheRuleOfALostDeviceInTheForeground() {
        // automatic: low is saved, no hint
        val auto = decideAfterCrash(auto = true, level = GraphicsLevel.HIGH)
        assertEquals(GraphicsLevel.LOW, auto.level)
        assertTrue(auto.persist)
        assertFalse(auto.hint)

        // manual above low: the level stays, the player gets the hint
        val manual = decideAfterCrash(auto = false, level = GraphicsLevel.MEDIUM)
        assertEquals(GraphicsLevel.MEDIUM, manual.level)
        assertFalse(manual.persist)
        assertTrue(manual.hint)

        // manual at low: nothing lower to pick
        assertFalse(decideAfterCrash(auto = false, level = GraphicsLevel.LOW).hint)
    }
}
