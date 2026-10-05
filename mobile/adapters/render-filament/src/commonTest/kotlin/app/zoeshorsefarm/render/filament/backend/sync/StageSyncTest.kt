package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.FakeStage
import app.zoeshorsefarm.render.filament.context.ToneMapping
import app.zoeshorsefarm.render.filament.light.AmbientSh
import app.zoeshorsefarm.scene.graph.DirectionalLight
import app.zoeshorsefarm.scene.graph.EnvironmentLight
import app.zoeshorsefarm.scene.graph.Fog
import app.zoeshorsefarm.scene.graph.HemisphereLight
import app.zoeshorsefarm.scene.graph.OrthographicCamera
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.zoeshorsefarm.scene.render.ToneMapping as SceneToneMapping

class StageSyncTest {
    private val stage = FakeStage()
    private val settings = StageSettings()
    private val log = RecordingLog()
    private val stageSync = StageSync(stage, settings, log)
    private val lights = SceneLights()
    private val scene = Scene()
    private val camera = PerspectiveCamera(fov = 58.0, aspect = 1.5, near = 0.1, far = 900.0)

    private fun frame(wind: Wind? = null) {
        scene.updateMatrixWorld()
        camera.updateMatrixWorld()
        stageSync.sync(scene, camera, lights, wind)
    }

    private fun sun(): DirectionalLight {
        val light = DirectionalLight(0xfff1d6, 2.7)
        light.position.set(-52.0, 74.0, -42.0)
        scene.add(light, light.target)
        scene.updateMatrixWorld()
        lights.clear()
        lights.add(light)
        return light
    }

    @Test
    fun `the first frame sends the settings the lens the pose the sun and the ambient light`() {
        frame()
        assertEquals(1, stage.settings.size)
        assertEquals(1, stage.lenses.size)
        assertEquals(1, stage.poses.size)
        assertEquals(1, stage.suns.size)
        assertEquals(1, stage.ambients.size)
    }

    @Test
    fun `a steady frame sends nothing`() {
        sun()
        frame()
        val before = stage.changes()
        repeat(3) { frame() }
        assertEquals(before, stage.changes())
    }

    @Test
    fun `invalidate sends everything again`() {
        frame()
        val before = stage.changes()
        stageSync.invalidate()
        frame()
        assertTrue(stage.changes() > before)
    }

    // ---- settings ------------------------------------------------------------------------------

    @Test
    fun `tone mapping exposure pixel ratio and antialiasing become render settings`() {
        settings.toneMapping = SceneToneMapping.ACES_FILMIC
        settings.exposure = 0.8
        settings.maxPixelRatio = 1.5
        settings.msaaSamples = 4
        frame()
        val result = stage.settings.last()
        assertEquals(ToneMapping.ACES_LEGACY, result.toneMapping)
        assertEquals(0.8f, result.exposure)
        assertEquals(1.5f, result.maxPixelRatio)
        assertEquals(4, result.msaaSamples)
        settings.toneMapping = SceneToneMapping.NONE
        frame()
        assertEquals(ToneMapping.LINEAR, stage.settings.last().toneMapping)
        assertEquals(2, stage.settings.size)
    }

    @Test
    fun `fog and background become fog and clear colour`() {
        scene.fog = Fog(0xcfe2ee, near = 40.0, far = 240.0)
        scene.background = Color(0x3f7fcf)
        frame()
        val result = stage.settings.last()
        val fog = assertNotNull(result.fog)
        assertEquals(40f, fog.distance)
        assertEquals(1f / 200f, fog.density, 1e-6f)
        assertEquals(Color(0xcfe2ee).r.toFloat(), fog.color.r)
        assertEquals(Color(0x3f7fcf).b.toFloat(), result.clearColor.b)
        scene.fog = null
        frame()
        assertNull(stage.settings.last().fog)
    }

    @Test
    fun `changing the fog distance sends new settings`() {
        scene.fog = Fog(0xffffff, 10.0, 100.0)
        frame()
        scene.fog!!.far = 200.0
        frame()
        assertEquals(2, stage.settings.size)
        assertEquals(
            1f / 190f,
            stage.settings
                .last()
                .fog!!
                .density,
            1e-6f,
        )
    }

    @Test
    fun `shadows need the switch a casting sun and a power of two map`() {
        val light = sun()
        light.castShadow = true
        light.shadow.mapSize.set(1000.0, 1000.0)
        light.shadow.camera.left = -24.0
        light.shadow.camera.right = 24.0
        frame()
        assertNull(stage.settings.last().shadows)
        settings.shadowsEnabled = true
        frame()
        val shadows = assertNotNull(stage.settings.last().shadows)
        assertEquals(1024, shadows.mapSize)
        assertEquals(24f, shadows.halfExtent)
        assertTrue(stageSync.shadowsActive)
        light.castShadow = false
        frame()
        assertNull(stage.settings.last().shadows)
        assertTrue(!stageSync.shadowsActive)
    }

    @Test
    fun `the shadow box is read from the shadow camera every frame`() {
        val light = sun()
        light.castShadow = true
        settings.shadowsEnabled = true
        light.shadow.camera.left = -24.0
        light.shadow.camera.right = 24.0
        frame()
        assertEquals(
            24f,
            stage.settings
                .last()
                .shadows!!
                .halfExtent,
        )
        light.shadow.camera.left = -10.0
        light.shadow.camera.right = 10.0
        frame()
        assertEquals(
            10f,
            stage.settings
                .last()
                .shadows!!
                .halfExtent,
        )
        light.shadow.camera.zoom = 2.0
        frame()
        assertEquals(
            5f,
            stage.settings
                .last()
                .shadows!!
                .halfExtent,
        )
    }

    @Test
    fun `the shadow map size is limited`() {
        val light = sun()
        light.castShadow = true
        light.shadow.mapSize.set(16384.0, 16384.0)
        settings.shadowsEnabled = true
        frame()
        assertEquals(
            4096,
            stage.settings
                .last()
                .shadows!!
                .mapSize,
        )
    }

    @Test
    fun `the sun gets a shadow map object that can be released`() {
        val light = sun()
        light.castShadow = true
        settings.shadowsEnabled = true
        frame()
        val map = assertNotNull(light.shadow.map)
        map.dispose()
        assertNull(light.shadow.map)
        frame()
        val again = assertNotNull(light.shadow.map)
        assertTrue(again !== map)
        settings.shadowsEnabled = false
        frame()
        assertNull(light.shadow.map)
    }

    // ---- camera --------------------------------------------------------------------------------

    @Test
    fun `the lens is the effective field of view near and far`() {
        camera.zoom = 2.0
        camera.updateProjectionMatrix()
        frame()
        val (fov, near, far) = stage.lenses.single()
        assertEquals(camera.getEffectiveFOV().toFloat(), fov)
        assertEquals(0.1f, near)
        assertEquals(900f, far)
        camera.far = 500.0
        frame()
        assertEquals(500f, stage.lenses.last().third)
    }

    @Test
    fun `the pose is the camera's world matrix and is sent when it moves`() {
        camera.position.set(1.0, 2.0, 3.0)
        frame()
        assertEquals(listOf(1f, 2f, 3f), stage.poses.last().slice(12..14))
        frame()
        assertEquals(1, stage.poses.size)
        camera.position.x = 9.0
        frame()
        assertEquals(2, stage.poses.size)
    }

    @Test
    fun `an orthographic main camera is reported once and keeps the lens`() {
        val ortho = OrthographicCamera()
        scene.updateMatrixWorld()
        ortho.updateMatrixWorld()
        stageSync.sync(scene, ortho, lights, null)
        stageSync.sync(scene, ortho, lights, null)
        assertEquals(1, log.messages.size)
        assertTrue(stage.lenses.isEmpty())
    }

    // ---- sun -----------------------------------------------------------------------------------

    @Test
    fun `the sun points from its position to its target`() {
        sun()
        frame()
        val (direction, color, intensity) = stage.suns.last()
        val length = kotlin.math.sqrt(52.0 * 52 + 74 * 74 + 42 * 42)
        assertEquals((-52.0 / length).toFloat(), direction[0], 1e-6f)
        assertEquals((74.0 / length).toFloat(), direction[1], 1e-6f)
        assertEquals((-42.0 / length).toFloat(), direction[2], 1e-6f)
        assertEquals(2.7f, intensity)
        assertEquals(Color(0xfff1d6).g.toFloat(), color.g)
    }

    @Test
    fun `moving the target changes the direction`() {
        val light = sun()
        frame()
        light.target.position.set(52.0, 0.0, 0.0)
        frame()
        assertEquals(2, stage.suns.size)
        assertEquals(-104f / kotlin.math.sqrt(104f * 104 + 74 * 74 + 42 * 42), stage.suns.last().first[0], 1e-5f)
    }

    @Test
    fun `without a sun the light is switched off`() {
        frame()
        assertEquals(0f, stage.suns.single().third)
    }

    @Test
    fun `the shadow focus follows the target every frame`() {
        val light = sun()
        light.castShadow = true
        settings.shadowsEnabled = true
        light.target.position.set(3.0, 0.0, 4.0)
        camera.position.set(0.0, 5.0, 10.0)
        frame()
        assertEquals(listOf(3f, 0f, 4f, 0f, 5f, 10f), stage.focuses.last())
        light.target.position.set(6.0, 0.0, 8.0)
        frame()
        assertEquals(2, stage.focuses.size)
        assertEquals(6f, stage.focuses.last()[0])
    }

    @Test
    fun `no focus is sent while shadows are off`() {
        sun()
        frame()
        assertTrue(stage.focuses.isEmpty())
    }

    // ---- ambient light -------------------------------------------------------------------------

    @Test
    fun `a hemisphere light becomes ambient spherical harmonics`() {
        val hemisphere = HemisphereLight(0xdde9f7, 0x7a6c50, 1.0)
        lights.add(hemisphere)
        frame()
        val expected = AmbientSh.hemisphere(linear(hemisphere.color), linear(hemisphere.groundColor), 1f)
        assertEquals(expected.toList(), stage.ambients.last().toList())
    }

    private fun linear(color: Color) =
        app.zoeshorsefarm.render.filament.math
            .LinearRgb(color.r.toFloat(), color.g.toFloat(), color.b.toFloat())

    @Test
    fun `an unchanged ambient light is not sent again but a changed intensity is`() {
        val hemisphere = HemisphereLight(0xffffff, 0x000000, 1.0)
        lights.add(hemisphere)
        frame()
        frame()
        assertEquals(1, stage.ambients.size)
        hemisphere.intensity = 0.5
        frame()
        assertEquals(2, stage.ambients.size)
    }

    @Test
    fun `the environment adds to the hemisphere light and follows its intensity`() {
        lights.add(HemisphereLight(0xffffff, 0x000000, 0.5))
        frame()
        val hemisphereOnly = stage.ambients.last()
        scene.environment =
            EnvironmentLight(Color(0x3f7fcf), Color(0xcfe2ee), Color(0xb8c7bf), Color(0xfff1d6), Vec3(0.0, 1.0, 0.0))
        scene.environmentIntensity = 0.8
        frame()
        assertTrue(stage.ambients.last()[0] > hemisphereOnly[0])
        val withEnvironment = stage.ambients.last()
        scene.environmentIntensity = 0.4
        frame()
        assertTrue(stage.ambients.last()[0] < withEnvironment[0])
    }

    @Test
    fun `no lights give a black ambient light`() {
        frame()
        assertTrue(stage.ambients.single().all { it == 0f })
        assertEquals(AmbientSh.FLOAT_COUNT, stage.ambients.single().size)
    }

    @Test
    fun `disposing the environment light makes the ambient light be computed again`() {
        val environment =
            EnvironmentLight(Color(0x3f7fcf), Color(0xcfe2ee), Color(0xb8c7bf), Color(0xfff1d6), Vec3(0.0, 1.0, 0.0))
        scene.environment = environment
        frame()
        environment.dispose()
        frame()
        assertEquals(2, stage.ambients.size)
        assertTrue(abs(stage.ambients[0][0] - stage.ambients[1][0]) < 1e-6f)
    }

    @Test
    fun `at most four hemisphere lights count`() {
        repeat(6) { lights.add(HemisphereLight(0xffffff, 0x000000, 1.0)) }
        frame()
        val four =
            AmbientSh.sum(
                *Array(4) { AmbientSh.hemisphere(linear(Color(0xffffff)), linear(Color(0x000000)), 1f) },
            )
        assertEquals(four.toList(), stage.ambients.last().toList())
    }

    // ---- wind ----------------------------------------------------------------------------------

    @Test
    fun `the wind is sent when it changes`() {
        val wind = Wind(time = 1.0, strength = 1.0)
        frame(wind)
        assertEquals(listOf(1f to 1f), stage.winds)
        frame(wind)
        assertEquals(1, stage.winds.size)
        wind.time = 1.5
        frame(wind)
        assertEquals(1.5f to 1f, stage.winds.last())
        frame(null)
        assertEquals(2, stage.winds.size)
        assertSame(wind, wind)
    }
}
