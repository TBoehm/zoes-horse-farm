package app.zoeshorsefarm.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class AppVersionTest {
    @Test
    fun `falls back to dev when the build did not inject a version`() {
        assertEquals("dev", appVersionOf(null))
        assertEquals("dev", appVersionOf(""))
        assertEquals("dev", appVersionOf("   "))
        assertEquals(DEV_VERSION, appVersionOf(null))
    }

    @Test
    fun `uses the injected version text`() {
        assertEquals("2026-10-05 · 3fdf19e", appVersionOf("2026-10-05 · 3fdf19e"))
    }
}
