package app.zoeshorsefarm.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolchainTest {
    @Test
    fun compilesAndRunsCommonTests() {
        assertEquals(1, TOOLCHAIN_PROBE)
    }
}
