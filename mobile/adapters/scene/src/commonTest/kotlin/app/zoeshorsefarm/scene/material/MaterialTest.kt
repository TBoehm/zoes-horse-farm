package app.zoeshorsefarm.scene.material

import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.RgbaImage
import app.zoeshorsefarm.scene.texture.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MaterialTest {
    private fun texture() = Texture(RgbaImage(2, 2))

    @Test
    fun `standard material has the three js defaults`() {
        val m = StandardMaterial()
        assertEquals("MeshStandardMaterial", m.type)
        assertNear(1.0, m.roughness)
        assertNear(0.0, m.metalness)
        assertNear(1.0, m.color.r)
        assertEquals(Side.FRONT, m.side)
        assertTrue(m.depthWrite && m.fog && m.toneMapped)
        assertFalse(m.transparent || m.vertexColors || m.flatShading)
        assertNear(1.0, m.opacity)
        assertNear(1.0, m.normalScale.x)
    }

    @Test
    fun `colour is read as sRGB hex and stored linear`() {
        val m = StandardMaterial(color = 0x808080)
        assertNear(0.21586050010324415, m.color.r)
    }

    @Test
    fun `named parameters set the fields`() {
        val map = texture()
        val m =
            StandardMaterial(
                color = 0xff0000,
                roughness = 0.6,
                metalness = 0.1,
                map = map,
                side = Side.DOUBLE,
                transparent = true,
                opacity = 0.5,
                polygonOffset = true,
                polygonOffsetFactor = -2.0,
                polygonOffsetUnits = -2.0,
            )
        assertSame(map, m.map)
        assertEquals(Side.DOUBLE, m.side)
        assertTrue(m.transparent && m.polygonOffset)
        assertNear(-2.0, m.polygonOffsetFactor)
        assertNear(0.5, m.opacity)
        assertEquals(listOf(map), m.textures())
    }

    @Test
    fun `lambert basic sprite and points types`() {
        assertEquals("MeshLambertMaterial", LambertMaterial().type)
        assertEquals("MeshBasicMaterial", BasicMaterial().type)
        val sprite = SpriteMaterial()
        assertEquals("SpriteMaterial", sprite.type)
        assertTrue(sprite.transparent)
        assertTrue(sprite.sizeAttenuation)
        val points = PointsMaterial(size = 3.0)
        assertEquals("PointsMaterial", points.type)
        assertNear(3.0, points.size)
    }

    @Test
    fun `a parameter set builds the standard and the lambert variant`() {
        val params = MaterialParams(color = 0x336699, roughness = 0.4, vertexColors = true, side = Side.DOUBLE)
        val standard = StandardMaterial(params)
        val lambert = LambertMaterial(params)
        assertNear(0.4, standard.roughness)
        assertTrue(standard.vertexColors && lambert.vertexColors)
        assertEquals(Side.DOUBLE, lambert.side)
        assertNear(standard.color.g, lambert.color.g)
        assertNear(0.8, StandardMaterial(MaterialParams()).roughness)
    }

    @Test
    fun `normal map is listed with the textures of a standard material`() {
        val normal = texture()
        val m = StandardMaterial(normalMap = normal)
        assertEquals(listOf(normal), m.textures())
    }

    @Test
    fun `needsUpdate bumps the version`() {
        val m = StandardMaterial()
        assertEquals(0, m.version)
        m.needsUpdate = true
        m.needsUpdate = true
        assertEquals(2, m.version)
    }

    @Test
    fun `effect keys match the web patches`() {
        val wind = Wind()
        assertEquals("wind-tree-v1", WindEffect.tree(wind).programKey)
        assertEquals("wind-bush-v1", WindEffect.bush(wind).programKey)
        assertEquals("wind-tuft-v2", WindEffect.tuft(wind).programKey)
        assertEquals("wind-blossom-v2-0.000", WindEffect.blossoms(wind).programKey)
        assertEquals("wind-blossom-v2-0.350", WindEffect.blossoms(wind, base = 0.35).programKey)
        assertEquals("wind-bunting-v1", WindEffect.bunting(wind).programKey)
        assertEquals("wind-wings-9.000-0.150-0.500", WindEffect.wings(wind, 9.0, 0.15, 0.5).programKey)
        assertEquals("zhf-horse-coat-low", CoatEffect(CoatUniforms(), low = true).programKey)
        assertEquals("zhf-horse-coat-std", CoatEffect(CoatUniforms(), low = false).programKey)
    }

    @Test
    fun `an effect can be switched off and on without losing it`() {
        val wind = Wind()
        val effect = WindEffect.tree(wind)
        val m = StandardMaterial(effect = effect)
        assertEquals("wind-tree-v1", m.customProgramCacheKey())
        m.effectEnabled = false
        assertEquals("", m.customProgramCacheKey())
        assertNull(m.activeEffect)
        m.effectEnabled = true
        assertSame(effect, m.activeEffect)
    }

    @Test
    fun `shader materials are keyed by their program name`() {
        val sky = SkyMaterial(0x3f7fcf, 0xcfe2ee, 0xb8c7bf, 0xfff1d6, Vec3(0.0, 1.0, 0.0))
        assertEquals("ShaderMaterial", sky.type)
        assertEquals("sky", sky.customProgramCacheKey())
        assertEquals(Side.BACK, sky.side)
        assertFalse(sky.depthWrite)
        assertFalse(sky.fog)
        assertNear(1.0, sky.sunDir.y)
        val dust =
            ShaderMaterial(
                "hoof-dust",
                mapOf("uColor" to Uniform(0.5), "uPixelScale" to Uniform(600.0)),
                transparent = true,
                depthWrite = false,
            )
        assertEquals("hoof-dust", dust.customProgramCacheKey())
        dust.uniform("uPixelScale").value = 480.0
        assertEquals(480.0, dust.uniform("uPixelScale").value)
        assertTrue(dust.transparent)
    }

    @Test
    fun `dispose notifies listeners and counts`() {
        val m = BasicMaterial()
        var told = 0
        val listener = app.zoeshorsefarm.scene.DisposeListener { told++ }
        m.addDisposeListener(listener)
        m.dispose()
        m.removeDisposeListener(listener)
        m.dispose()
        assertEquals(1, told)
        assertEquals(2, m.disposeCount)
    }

    @Test
    fun `texture defaults and versions`() {
        val t = texture()
        assertEquals(0, t.version)
        t.needsUpdate = true
        assertEquals(1, t.version)
        t.setWrap(app.zoeshorsefarm.scene.texture.Wrap.REPEAT)
        assertEquals(app.zoeshorsefarm.scene.texture.Wrap.REPEAT, t.wrapT)
        assertEquals(2, t.width)
    }
}
