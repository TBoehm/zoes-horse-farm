package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.material.ShaderMaterial
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.scene.texture.createRng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DT = 1.0 / 60

class DustPoolTest {
    @Test
    fun `emits more puffs for a stronger footfall and never beyond the capacity`() {
        val pool = DustPool(20, createRng(1))
        val weak = pool.emit(0.0, 0.0, 0.0, 0.1)
        val strong = pool.emit(0.0, 0.0, 0.0, 1.0)
        assertTrue(strong > weak)
        repeat(100) { pool.emit(0.0, 0.0, 0.0, 1.0) }
        pool.update(0.0)
        assertTrue(pool.update(0.0) <= 20)
        assertEquals(60, pool.pos.size)
    }

    @Test
    fun `ignores a footfall without strength and an empty pool`() {
        val pool = DustPool(8, createRng(1))
        assertEquals(0, pool.emit(0.0, 0.0, 0.0, 0.0))
        assertEquals(0, pool.update(DT))
        val none = DustPool(0, createRng(1))
        assertEquals(0, none.emit(0.0, 0.0, 0.0, 1.0))
        assertEquals(0, none.update(DT))
    }

    @Test
    fun `puffs rise from the sand drift apart grow fade out and are free again`() {
        val pool = DustPool(16, createRng(2))
        pool.emit(5.0, 0.0, 7.0, 1.0)
        var alive = pool.update(DT)
        assertTrue(alive > 0)
        val start = pool.size.copyOf()
        var maxAlpha = 0.0
        var maxY = 0.0
        var t = 0.0
        while (t < 3) {
            alive = pool.update(DT)
            for (i in 0 until 16) {
                maxAlpha = max(maxAlpha, pool.alpha[i].toDouble())
                maxY = max(maxY, pool.pos[i * 3 + 1].toDouble())
                // never under the sand
                if (pool.life[i] > 0) assertTrue(pool.pos[i * 3 + 1] >= 0.02 - 1e-6)
            }
            t += DT
        }
        assertTrue(maxAlpha > 0.15)
        assertTrue(maxAlpha < 0.65)
        assertTrue(maxY > 0.1)
        assertTrue(maxY < 2)
        assertEquals(0, alive)
        assertEquals(0f, pool.alpha.max())
        // they had a size while alive
        assertTrue(start.max() > 0)
    }

    @Test
    fun `puffs start where the footfall is within the scatter`() {
        val pool = DustPool(8, createRng(3))
        pool.emit(10.0, 0.0, -4.0, 0.6)
        for (i in 0 until 8) {
            if (pool.life[i] > 0) {
                assertTrue(abs(pool.pos[i * 3] - 10) < 0.11)
                assertTrue(abs(pool.pos[i * 3 + 2] + 4) < 0.11)
            }
        }
    }

    @Test
    fun `a very long frame does not throw the puffs away`() {
        val pool = DustPool(16, createRng(4))
        pool.emit(0.0, 0.0, 0.0, 1.0)
        pool.update(5.0)
        for (i in 0 until 16) {
            assertTrue(pool.pos[i * 3].isFinite())
            assertTrue(abs(pool.pos[i * 3]) < 5)
        }
    }

    @Test
    fun `fewer puffs at medium than at high for the same footfall`() {
        val med = DustPool(40, createRng(5), DustQuality.MEDIUM)
        val high = DustPool(40, createRng(5), DustQuality.HIGH)
        assertTrue(med.emit(0.0, 0.0, 0.0, 1.0) < high.emit(0.0, 0.0, 0.0, 1.0))
    }
}

class DustObjectTest {
    @Test
    fun `low has no dust no geometry and nothing to draw`() {
        val dust = Dust(DustQuality.LOW)
        assertEquals(0, dust.capacity)
        assertEquals(0, dust.obj.children.size)
        dust.emit(0.0, 0.0, 0.0, 1.0)
        dust.update(DT)
        assertEquals(0, dust.alive)
        assertEquals(0, dust.obj.children.size)
        dust.dispose()
    }

    @Test
    fun `medium and high have one Points object invisible while idle and visible while puffs live`() {
        for (quality in listOf(DustQuality.MEDIUM, DustQuality.HIGH)) {
            val dust = Dust(quality)
            assertEquals(1, dust.obj.children.size)
            val points = dust.obj.children[0]
            assertTrue(points is Points)
            assertFalse(points.visible)
            assertTrue(dust.capacity > 0)
            dust.emit(1.0, 0.0, 2.0, 0.8)
            assertTrue(points.visible)
            dust.update(DT)
            assertTrue(dust.alive > 0)
            var t = 0.0
            while (t < 3) {
                dust.update(DT)
                t += DT
            }
            assertEquals(0, dust.alive)
            assertFalse(points.visible)
            dust.dispose()
        }
        assertTrue(Dust(DustQuality.HIGH).capacity > Dust(DustQuality.MEDIUM).capacity)
    }

    @Test
    fun `uses no textures and a fixed size buffer`() {
        val dust = Dust(DustQuality.HIGH)
        val points = dust.obj.children[0] as Points
        assertTrue(points.material is ShaderMaterial)
        val n = dust.capacity
        repeat(500) { dust.emit(0.0, 0.0, 0.0, 1.0) }
        dust.update(DT)
        assertEquals(n, points.geometry.float("position").count)
        assertEquals(n, points.geometry.float("aSize").count)
        assertEquals(n, points.geometry.float("aAlpha").count)
        val material = points.material as ShaderMaterial
        assertTrue(material.textures().isEmpty())
        assertFalse(material.uniforms.values.any { it.value is Texture })
    }

    @Test
    fun `releases its GPU objects on a quality change and on dispose through the release hook`() {
        val released = ArrayList<GpuObject?>()
        val dust = Dust(DustQuality.MEDIUM, release = { released.add(it) })
        val points = dust.obj.children[0] as Points
        dust.setQuality(DustQuality.HIGH)
        assertTrue(released.contains(points.geometry))
        assertTrue(released.contains(points.material))
        assertNotSame(points, dust.obj.children[0])
        dust.setQuality(DustQuality.LOW)
        assertEquals(0, dust.obj.children.size)
        dust.setQuality(DustQuality.MEDIUM)
        assertEquals(1, dust.obj.children.size)
        val last = dust.obj.children[0] as Points
        released.clear()
        dust.dispose()
        // the final dispose goes through the hook, too (objects of a lost context are not freed)
        assertTrue(released.contains(last.geometry))
        assertTrue(released.contains(last.material))
        assertNull(dust.obj.parent)
        assertNull(last.parent)
    }

    @Test
    fun `keeps size attenuation per camera in the before render hook`() {
        val dust = Dust(DustQuality.MEDIUM)
        val points = dust.obj.children[0] as Points
        val backend = FakeRenderBackend()
        backend.setSize(800, 600)
        val camera = PerspectiveCamera(60.0, 4.0 / 3, 0.1, 100.0)
        points.onBeforeRender!!.invoke(backend, Scene(), camera) // the hook is set by the dust
        val scale = (points.material as ShaderMaterial).uniform("uPixelScale").value as Double
        assertTrue(abs(scale - 600 / (2 * tan(PI / 6))) < 1e-3)
    }
}
