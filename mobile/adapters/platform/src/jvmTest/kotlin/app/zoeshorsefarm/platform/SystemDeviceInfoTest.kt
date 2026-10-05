package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemDeviceInfoTest {
    @Test
    fun `reads at least one core and a positive memory size when known`() {
        val info = SystemDeviceInfo.read()
        assertTrue(info.cores >= 1)
        assertTrue((info.totalMemoryBytes ?: 1L) > 0L)
    }
}
