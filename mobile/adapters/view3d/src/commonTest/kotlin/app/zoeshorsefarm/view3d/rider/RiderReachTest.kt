package app.zoeshorsefarm.view3d.rider

import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertGreaterOrEqual
import app.zoeshorsefarm.view3d.assertLess
import kotlin.test.Test

class RiderReachTest {
    private val l1 = 0.25
    private val l2 = 0.31

    @Test
    fun `leaves comfortable distances untouched`() {
        for (d in listOf(0.1, 0.3, 0.4, 0.47)) assertClose(d, limbReach(d, l1, l2), 9)
    }

    @Test
    fun `never reaches full extension however far the target is`() {
        for (d in listOf(0.56, 0.7, 2.0, 100.0)) {
            assertLess(limbReach(d, l1, l2), l1 + l2)
            assertGreater(limbReach(d, l1, l2), 0.5)
        }
    }

    @Test
    fun `is monotonic and continuous with no kink where the compression starts`() {
        var prev = limbReach(0.0, l1, l2)
        var d = 0.01
        while (d < 1) {
            val r = limbReach(d, l1, l2)
            assertGreaterOrEqual(r, prev - 1e-12)
            assertLess(r - prev, 0.0101)
            prev = r
            d += 0.01
        }
    }

    @Test
    fun `keeps the limb from folding completely`() {
        assertGreaterOrEqual(limbReach(0.0, l1, l2), kotlin.math.abs(l1 - l2))
    }
}
