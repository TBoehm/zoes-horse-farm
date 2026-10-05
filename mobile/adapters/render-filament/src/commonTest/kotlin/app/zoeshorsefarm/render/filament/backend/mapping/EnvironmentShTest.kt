package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.light.AmbientSh
import app.zoeshorsefarm.scene.graph.EnvironmentLight
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentShTest {
    private fun light(
        zenith: Int,
        horizon: Int,
        ground: Int,
        sun: Int = 0x000000,
        sunDirection: Vec3 = Vec3(0.0, 1.0, 0.0),
        floor: Int? = null,
    ) = EnvironmentLight(
        Color(zenith),
        Color(horizon),
        Color(ground),
        Color(sun),
        sunDirection,
        floor?.let { Color(it) },
    )

    private fun near(
        expected: Float,
        actual: Float,
        eps: Float = 0.01f,
    ) = assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    @Test
    fun `a uniform environment is a constant term only`() {
        val sh = EnvironmentSh.compute(light(0x808080, 0x808080, 0x808080), 1.0)
        val grey = Color(0x808080).r.toFloat()
        for (c in 0..2) near(grey, sh[c], 0.002f)
        for (i in 3 until AmbientSh.FLOAT_COUNT) near(0f, sh[i], 0.002f)
    }

    @Test
    fun `the intensity scales every coefficient`() {
        val env = light(0x3f7fcf, 0xcfe2ee, 0xb8c7bf)
        val full = EnvironmentSh.compute(env, 1.0)
        val half = EnvironmentSh.compute(env, 0.5)
        for (i in full.indices) near(full[i] * 0.5f, half[i], 0.0005f)
    }

    @Test
    fun `a bright zenith over a dark ground leans the light upwards`() {
        val sh = EnvironmentSh.compute(light(0xffffff, 0x808080, 0x000000), 1.0)
        // y band: positive; x and z bands: none (the dome is symmetric around the vertical axis)
        assertTrue(sh[3] > 0.05f && sh[4] > 0.05f && sh[5] > 0.05f)
        for (i in 6 until AmbientSh.FLOAT_COUNT) near(0f, sh[i], 0.01f)
        val up = AmbientSh.diffuseAt(sh, 0f, 1f, 0f).r
        val down = AmbientSh.diffuseAt(sh, 0f, -1f, 0f).r
        assertTrue(up > down, "up $up down $down")
    }

    @Test
    fun `a floor replaces the ground below the horizon`() {
        val grassy = EnvironmentSh.compute(light(0xffffff, 0xffffff, 0xff0000, floor = 0x00ff00), 1.0)
        val down = AmbientSh.diffuseAt(grassy, 0f, -1f, 0f)
        assertTrue(down.g > down.r, "the floor colour (green) dominates downwards: $down")
    }

    @Test
    fun `the sun adds light from its direction`() {
        val dark = EnvironmentSh.compute(light(0x101010, 0x101010, 0x101010), 1.0)
        val sunny =
            EnvironmentSh.compute(
                light(0x101010, 0x101010, 0x101010, sun = 0xffffff, sunDirection = Vec3(1.0, 0.0, 0.0)),
                1.0,
            )
        val towards = AmbientSh.diffuseAt(sunny, 1f, 0f, 0f).r
        val away = AmbientSh.diffuseAt(sunny, -1f, 0f, 0f).r
        assertTrue(towards > away + 0.05f, "towards $towards away $away")
        assertTrue(sunny[0] > dark[0])
    }

    @Test
    fun `the sun disc is added exactly and not lost between samples`() {
        val withDisc =
            EnvironmentSh.compute(
                light(0x000000, 0x000000, 0x000000, sun = 0xffffff, sunDirection = Vec3(0.0, 1.0, 0.0)),
                1.0,
            )
        // glow terms integrate to about 0.18 * 1/7 + 0.35 * 1/65 per unit radiance; the disc adds 6 * 0.00314 / (4 pi)
        val disc = 6.0 * 0.00314 / (4.0 * PI)
        val glow = 0.18 / 7.0 / 2.0 + 0.35 / 65.0 / 2.0
        near((disc + glow).toFloat(), withDisc[0], 0.003f)
        assertEquals(AmbientSh.FLOAT_COUNT, withDisc.size)
    }
}
