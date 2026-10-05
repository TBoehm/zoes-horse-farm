package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.sim.JumpPhase
import app.zoeshorsefarm.domain.sim.JumpView
import app.zoeshorsefarm.view3d.assertClose
import app.zoeshorsefarm.view3d.assertGreater
import app.zoeshorsefarm.view3d.assertLess
import kotlin.test.Test
import kotlin.test.assertEquals

class PosesTest {
    @Test
    fun `phase and progress map to J in 0 to 3`() {
        assertEquals(0.0, jumpParam(null))
        assertEquals(0.5, jumpParam(JumpView(JumpPhase.TAKEOFF, 0.5)))
        assertEquals(1.25, jumpParam(JumpView(JumpPhase.FLIGHT, 0.25)))
        assertEquals(3.0, jumpParam(JumpView(JumpPhase.LANDING, 1.0)))
        assertEquals(3.0, jumpParam(JumpView(JumpPhase.LANDING, 7.0)))
    }

    @Test
    fun `hits the key poses at the knots`() {
        JUMP_KEYS.forEachIndexed { i, k ->
            val p = samplePoses(JUMP_KEYS, i * 0.5)
            for (j in 0 until POSE_SIZE) assertClose(k[j], p[j], 6)
        }
    }

    @Test
    fun `take-off nose up flight bascule landing nose down`() {
        assertLess(samplePoses(JUMP_KEYS, 0.9)[POSE_PITCH], -0.2)
        assertGreater(samplePoses(JUMP_KEYS, 1.5)[POSE_BEND], 0.1)
        assertGreater(samplePoses(JUMP_KEYS, 2.0)[POSE_PITCH], 0.2)
    }
}
