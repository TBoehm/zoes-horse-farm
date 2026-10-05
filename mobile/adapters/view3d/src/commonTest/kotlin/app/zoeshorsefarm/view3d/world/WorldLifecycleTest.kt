package app.zoeshorsefarm.view3d.world

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.graph.BoneTexture
import app.zoeshorsefarm.scene.graph.DirectionalLight
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.collectGpuObjects
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.texture.Texture
import app.zoeshorsefarm.view3d.quality.TextureInfo
import app.zoeshorsefarm.view3d.quality.presetFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// What the world gives back and when: the environment light, the shadow map, the textures that follow
// the anisotropy of a level, after a lost device. These tests use the real presets (with the
// environment light), unlike the budget tests.

private class Lifecycle {
    val released = ArrayList<GpuObject?>()
    val backend = FakeRenderBackend()
    val world =
        World(backend, presetFor(GraphicsLevel.LOW), release = {
            released.add(it)
            it?.dispose()
        })
    val sun = world.scene.children.first { it is DirectionalLight } as DirectionalLight
}

class WorldLifecycleTest {
    private val low = presetFor(GraphicsLevel.LOW)
    private val medium = presetFor(GraphicsLevel.MEDIUM)
    private val high = presetFor(GraphicsLevel.HIGH)

    @Test
    fun `builds the environment light for a level with it and gives it back before the next is built`() {
        val t = Lifecycle()
        assertNull(t.world.scene.environment)
        t.world.setQuality(medium)
        val first = assertNotNull(t.world.scene.environment)
        assertEquals(0.8, t.world.scene.environmentIntensity)
        t.world.setQuality(low)
        assertNull(t.world.scene.environment)
        assertTrue(first in t.released)
        assertEquals(1, first.disposeCount)
        t.world.setQuality(medium)
        assertNotSame(first, t.world.scene.environment)
        // a level that keeps the light keeps the same one
        val second = t.world.scene.environment
        t.world.setQuality(high)
        assertSame(second, t.world.scene.environment)
        t.world.dispose()
    }

    @Test
    fun `leaves the environment light to the restore while the device is lost`() {
        val t = Lifecycle()
        t.world.setQuality(medium, gpu = false)
        assertNull(t.world.scene.environment)
        assertEquals(0, t.world.gpuObjects().count { it === t.world.scene.environment })
        t.world.restoreAfterContextLoss()
        assertNotNull(t.world.scene.environment)
        t.world.dispose()
    }

    @Test
    fun `forgets and rebuilds the environment light and the shadow map after a lost device`() {
        val t = Lifecycle()
        t.world.setQuality(medium)
        val light = assertNotNull(t.world.scene.environment)
        val map = BoneTexture()
        t.sun.shadow.map = map
        t.world.restoreAfterContextLoss()
        assertTrue(light in t.released)
        assertTrue(map in t.released)
        assertNull(t.sun.shadow.map)
        val rebuilt = assertNotNull(t.world.scene.environment)
        assertNotSame(light, rebuilt)
        // on a level without the light nothing is built
        t.world.setQuality(low)
        t.world.restoreAfterContextLoss()
        assertNull(t.world.scene.environment)
        t.world.dispose()
    }

    @Test
    fun `lists the environment light that is built but not set among the GPU objects`() {
        val t = Lifecycle()
        t.world.setQuality(medium)
        val light = assertNotNull(t.world.scene.environment)
        assertTrue(light in t.world.gpuObjects())
        t.world.dispose()
    }

    @Test
    fun `gives the shadow map back when its size changes or the shadows go off`() {
        val t = Lifecycle()
        t.world.setQuality(high)
        assertEquals(2048.0, t.sun.shadow.mapSize.x)
        val big = BoneTexture()
        t.sun.shadow.map = big
        t.world.setQuality(medium)
        assertEquals(1024.0, t.sun.shadow.mapSize.x)
        assertTrue(big in t.released)
        assertNull(t.sun.shadow.map)
        // the same size keeps the map
        val map = BoneTexture()
        t.sun.shadow.map = map
        t.world.setQuality(medium)
        assertSame(map, t.sun.shadow.map)
        t.world.setQuality(low)
        assertTrue(map in t.released)
        assertNull(t.sun.shadow.map)
        assertFalse(t.backend.shadowsEnabled)
        assertFalse(t.sun.castShadow)
        t.world.dispose()
    }

    @Test
    fun `sets the anisotropy of the textures of a level only where it differs`() {
        val t = Lifecycle()
        // at medium the standard material of the sand has its normal map: three textures follow the level
        t.world.setQuality(medium)

        fun textures() = collectGpuObjects(t.world.scene).filterIsInstance<Texture>()

        fun versions() = textures().associateWith { it.version }
        val followers = textures().filter { it.anisotropy == medium.anisotropy }
        assertEquals(3, followers.size) // grass, sand colour and sand normal map
        val start = versions()
        // the same value (and a level above the limit of the device, 4) changes nothing
        t.world.syncAnisotropy(medium)
        t.world.syncAnisotropy(high)
        assertEquals(start, versions())
        t.world.syncAnisotropy(low)
        for (texture in textures()) {
            val follows = texture in followers
            assertEquals(if (follows) 1 else texture.anisotropy, texture.anisotropy)
            assertEquals(start.getValue(texture) + if (follows) 1 else 0, texture.version)
        }
        t.world.dispose()
    }

    @Test
    fun `tells the sizes of the textures the world uploads`() {
        val t = Lifecycle()
        val sizes = t.world.textureSizes()
        assertEquals(TextureInfo(512, 512, normal = false), sizes[0]) // sand colour
        assertEquals(TextureInfo(512, 512, normal = true), sizes[1]) // sand normal map
        assertEquals(TextureInfo(512, 512, normal = false), sizes[2]) // grass
        assertEquals(3, sizes.count { it.width == 512 })
        t.world.dispose()
    }

    @Test
    fun `lets the finish line glow only while it is marked and exists`() {
        val t = Lifecycle()
        val group = checkNotNull(t.world.scene.getObjectByName("course-lines"))
        val glow = group.children[0] as Mesh
        assertFalse(glow.visible)
        t.world.setFinishMarked(true)
        assertFalse(glow.visible) // no lines yet
        t.world.setLines(LINES)
        assertTrue(glow.visible) // the mark is kept
        val material = glow.material as BasicMaterial
        t.world.update(0.1, newCamera())
        val first = material.opacity
        t.world.update(0.1, newCamera())
        assertTrue(first != material.opacity)
        t.world.setFinishMarked(false)
        assertFalse(glow.visible)
        t.world.setFinishMarked(true)
        assertTrue(glow.visible)
        t.world.setLines(null)
        assertFalse(glow.visible)
        t.world.dispose()
    }
}
