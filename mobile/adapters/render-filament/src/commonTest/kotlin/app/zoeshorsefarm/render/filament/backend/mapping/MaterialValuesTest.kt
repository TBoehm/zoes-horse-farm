package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.material.Blend
import app.zoeshorsefarm.render.filament.material.CoatKind
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.material.Shading
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.CoatEffect
import app.zoeshorsefarm.scene.material.CoatUniforms
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.PointsMaterial
import app.zoeshorsefarm.scene.material.SandEffect
import app.zoeshorsefarm.scene.material.ShaderMaterial
import app.zoeshorsefarm.scene.material.SkyMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.material.Uniform
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialValuesTest {
    private class Recorder : ParamSink {
        val values = LinkedHashMap<String, List<Float>>()

        override fun setFloat(
            name: String,
            x: Float,
        ) {
            values[name] = listOf(x)
        }

        override fun setFloat3(
            name: String,
            x: Float,
            y: Float,
            z: Float,
        ) {
            values[name] = listOf(x, y, z)
        }

        override fun setFloat4(
            name: String,
            x: Float,
            y: Float,
            z: Float,
            w: Float,
        ) {
            values[name] = listOf(x, y, z, w)
        }
    }

    private fun write(
        material: app.zoeshorsefarm.scene.material.Material,
        spec: MaterialSpec,
    ) = Recorder().also { MaterialValues.write(material, spec, it) }.values

    @Test
    fun `a lit material writes colour opacity roughness and metalness`() {
        val material = StandardMaterial(color = 0xffffff, roughness = 0.3, metalness = 0.1, opacity = 0.5)
        val values = write(material, MaterialSpec(Shading.LIT))
        assertEquals(listOf(1f, 1f, 1f, 0.5f), values["baseColor"])
        assertEquals(listOf(0.3f), values["roughness"])
        assertEquals(listOf(0.1f), values["metallic"])
    }

    @Test
    fun `the colour is the linear working colour of the scene model`() {
        val material = StandardMaterial(color = 0x808080)
        val color = write(material, MaterialSpec(Shading.LIT))["baseColor"]!!
        assertEquals(material.color.r.toFloat(), color[0])
        assertTrue(color[0] < 0.25f, "sRGB 0.5 is about 0.216 linear but was ${color[0]}")
    }

    @Test
    fun `a lambert material has no roughness`() {
        val values = write(LambertMaterial(color = 0xff0000), MaterialSpec(Shading.LAMBERT))
        assertEquals(setOf("baseColor"), values.keys)
    }

    @Test
    fun `an unlit material writes only its colour`() {
        val values = write(BasicMaterial(), MaterialSpec(Shading.UNLIT))
        assertEquals(setOf("baseColor"), values.keys)
    }

    @Test
    fun `the normal scale is written for normal mapped materials`() {
        val material = StandardMaterial(normalScale = Vec2(0.7, 0.7))
        val values = write(material, MaterialSpec(Shading.LIT, normalMap = true))
        assertEquals(listOf(0.7f), values["normalScale"])
    }

    @Test
    fun `the coat writes its colours and scalars and the roughness`() {
        val uniforms = CoatUniforms()
        uniforms.base.setRGB(0.1, 0.2, 0.3)
        uniforms.white.setRGB(0.9, 0.8, 0.7)
        uniforms.dapple = 0.4
        uniforms.flare = 0.25
        uniforms.blink = 1.0
        val material = StandardMaterial(roughness = 0.6, effect = CoatEffect(uniforms, low = false))
        val values = write(material, MaterialSpec(Shading.LIT, coat = CoatKind.STANDARD))
        assertEquals(listOf(0.1f, 0.2f, 0.3f), values["uBase"])
        assertEquals(listOf(0.9f, 0.8f, 0.7f), values["uWhite"])
        assertEquals(listOf(0.4f), values["uDapple"])
        assertEquals(listOf(0.25f), values["uFlare"])
        assertEquals(listOf(1f), values["uBlink"])
        assertEquals(listOf(0.6f), values["roughness"])
        assertEquals(null, values["baseColor"])
        assertEquals(14, values.keys.count { it.startsWith("u") })
    }

    @Test
    fun `the sand writes the half size of the arena next to the colour`() {
        val material = StandardMaterial(effect = SandEffect(20.0, 35.0))
        val values = write(material, MaterialSpec(Shading.LIT, sand = true))
        assertEquals(listOf(20f, 35f, 0f), values["arenaHalf"])
        assertTrue("baseColor" in values.keys && "roughness" in values.keys)
    }

    @Test
    fun `the sky writes its five uniforms`() {
        val sky = SkyMaterial(0x3f7fcf, 0xcfe2ee, 0xb8c7bf, 0xfff1d6, Vec3(0.0, 1.0, 0.0))
        val values = write(sky, MaterialSpec(Shading.SKY))
        assertEquals(setOf("zenith", "horizon", "groundColor", "sunColor", "sunDir"), values.keys)
        assertEquals(listOf(0f, 1f, 0f), values["sunDir"])
        assertEquals(sky.zenith.b.toFloat(), values["zenith"]!![2])
    }

    @Test
    fun `the hoof dust takes its colour from the uColor uniform`() {
        val dust = ShaderMaterial("hoof-dust", mapOf("uColor" to Uniform(Color(0xe8dcc2))))
        val spec = MaterialSpec(Shading.SPRITE, blend = Blend.TRANSPARENT)
        val color = write(dust, spec)["baseColor"]!!
        assertEquals(1f, color[3])
        assertEquals(Color(0xe8dcc2).g.toFloat(), color[1])
    }

    @Test
    fun `points take colour and opacity of the material`() {
        val material = PointsMaterial(color = 0xffffff, opacity = 0.4)
        val color = write(material, MaterialSpec(Shading.SPRITE, blend = Blend.TRANSPARENT))["baseColor"]!!
        assertEquals(0.4f, color[3])
    }

    @Test
    fun `uniform values follow the material every time`() {
        val material = StandardMaterial(roughness = 0.2)
        val spec = MaterialSpec(Shading.LIT)
        assertEquals(listOf(0.2f), write(material, spec)["roughness"])
        material.roughness = 0.9
        assertEquals(listOf(0.9f), write(material, spec)["roughness"])
    }
}
