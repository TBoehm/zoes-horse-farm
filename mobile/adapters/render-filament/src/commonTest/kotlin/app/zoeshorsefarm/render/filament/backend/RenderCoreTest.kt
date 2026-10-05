package app.zoeshorsefarm.render.filament.backend

import app.zoeshorsefarm.render.filament.backend.device.FakeGpuDevice
import app.zoeshorsefarm.render.filament.backend.device.FakeStage
import app.zoeshorsefarm.render.filament.backend.sync.RecordingLog
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.graph.DirectionalLight
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.render.ContextListener
import app.zoeshorsefarm.scene.render.ToneMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderCoreTest {
    private val device = FakeGpuDevice(maxTextureSize = 8192, maxAnisotropy = 8)
    private val stage = FakeStage()
    private val log = RecordingLog()
    private val core = RenderCore(device, stage, log, gpuName = "Test GPU")
    private val scene = Scene()
    private val camera = PerspectiveCamera()

    private class Listener : ContextListener {
        var lost = 0
        var restored = 0

        override fun onContextLost() {
            lost++
        }

        override fun onContextRestored() {
            restored++
        }
    }

    @Test
    fun `the capabilities come from the device`() {
        assertEquals(8192, core.capabilities.maxTextureSize)
        assertEquals(8, core.capabilities.maxAnisotropy)
        assertEquals("Test GPU", core.gpuDescription)
    }

    @Test
    fun `a frame updates matrices draws and fills the info`() {
        val mesh = Mesh(BoxGeometry(), StandardMaterial())
        mesh.position.set(1.0, 2.0, 3.0)
        scene.add(mesh, InstancedMesh(BoxGeometry(), StandardMaterial(), 5))
        core.render(scene, camera)
        assertEquals(1, stage.frames)
        assertEquals(1L, core.framesRendered)
        assertEquals(2, core.info.drawCalls)
        assertEquals(72, core.info.triangles)
        assertEquals(2, core.info.programs)
        assertEquals(2, core.info.geometries)
        assertEquals(1f, device.liveRenderables[0].transform[12] * 1f)
        assertEquals(2, core.stats().geometries)
    }

    @Test
    fun `a camera outside the scene gets its matrices updated`() {
        camera.position.set(0.0, 0.0, 5.0)
        core.render(scene, camera)
        assertEquals(5f, stage.poses.last()[14])
    }

    @Test
    fun `the shadow pass counts only while the sun casts shadows`() {
        val sun = DirectionalLight()
        scene.add(sun, sun.target, Mesh(BoxGeometry(), StandardMaterial()).also { it.castShadow = true })
        core.render(scene, camera)
        assertEquals(1, core.info.drawCalls)
        core.shadowsEnabled = true
        core.render(scene, camera)
        assertEquals(1, core.info.drawCalls)
        sun.castShadow = true
        core.render(scene, camera)
        assertEquals(2, core.info.drawCalls)
        assertEquals(24, core.info.triangles)
    }

    @Test
    fun `the settings of the backend reach the stage`() {
        core.toneMapping = ToneMapping.ACES_FILMIC
        core.toneMappingExposure = 0.9
        core.setPixelRatio(1.5)
        core.msaaSamples = 2
        core.render(scene, camera)
        val settings = stage.settings.last()
        assertEquals(0.9f, settings.exposure)
        assertEquals(1.5f, settings.maxPixelRatio)
        assertEquals(2, settings.msaaSamples)
        assertEquals(ToneMapping.ACES_FILMIC, core.toneMapping)
        assertEquals(0.9, core.toneMappingExposure)
        assertFalse(core.shadowsEnabled)
    }

    @Test
    fun `size and drawing buffer follow the surface and the pixel ratio`() {
        core.surfaceChanged(1080, 2160, 3f)
        assertEquals(360.0 to 720.0, core.getSize(Vec2()).let { it.x to it.y })
        core.setPixelRatio(2.0)
        assertEquals(2.0, core.pixelRatio)
        val buffer = core.getDrawingBufferSize(Vec2())
        assertEquals(720.0, buffer.x)
        assertEquals(1440.0, buffer.y)
        assertEquals(Triple(1080, 2160, 3f), stage.resizes.last())
    }

    @Test
    fun `the pixel ratio is capped by the device ratio`() {
        core.surfaceChanged(400, 800, 2f)
        core.setPixelRatio(3.0)
        assertEquals(2.0, core.pixelRatio)
        assertEquals(400.0, core.getDrawingBufferSize(Vec2()).x)
    }

    @Test
    fun `setSize resizes the surface in physical pixels`() {
        core.surfaceChanged(400, 800, 2f)
        core.setSize(300, 500)
        assertEquals(Triple(600, 1000, 2f), stage.resizes.last())
        assertEquals(300.0, core.getSize(Vec2()).x)
    }

    @Test
    fun `a lost context stops drawing and tells the listeners and a restored one sends everything again`() {
        val listener = Listener()
        core.addContextListener(listener)
        scene.add(Mesh(BoxGeometry(), StandardMaterial()))
        core.render(scene, camera)
        val settingsBefore = stage.settings.size
        core.loseContext()
        assertEquals(1, listener.lost)
        assertTrue(core.contextLost)
        assertEquals("", core.gpuDescription)
        core.render(scene, camera)
        assertEquals(1, stage.frames)
        core.restoreContext()
        assertEquals(1, listener.restored)
        core.render(scene, camera)
        assertEquals(2, stage.frames)
        assertEquals(settingsBefore + 1, stage.settings.size)
        // the device objects survived: nothing was uploaded twice
        assertEquals(1, device.meshes.size)
        assertEquals(1, device.renderables.size)
    }

    @Test
    fun `losing or restoring twice is told once`() {
        val listener = Listener()
        core.addContextListener(listener)
        core.loseContext()
        core.loseContext()
        core.restoreContext()
        core.restoreContext()
        assertEquals(1, listener.lost)
        assertEquals(1, listener.restored)
        core.removeContextListener(listener)
        core.loseContext()
        assertEquals(1, listener.lost)
    }

    @Test
    fun `compile builds the objects ahead and calls back at once`() {
        scene.add(Mesh(BoxGeometry(), StandardMaterial()))
        var done = 0
        core.compile(scene, camera, scene) { done++ }
        assertEquals(1, done)
        assertEquals(1, device.liveMeshes.size)
        assertEquals(1, device.liveMaterialInstances.size)
        assertEquals(1, core.info.programs)
        assertEquals(0, stage.frames)
        core.render(scene, camera)
        assertEquals(1, device.materialBuilds)
    }

    @Test
    fun `compile while the context is lost still calls back`() {
        core.loseContext()
        var done = 0
        core.compile(scene, camera, scene) { done++ }
        assertEquals(1, done)
        assertTrue(device.liveMeshes.isEmpty())
    }

    @Test
    fun `resetResources frees the device objects and the next frame builds them again`() {
        scene.add(Mesh(BoxGeometry(), StandardMaterial()))
        core.render(scene, camera)
        core.resetResources()
        assertTrue(device.liveMeshes.isEmpty())
        assertEquals(0, core.info.geometries)
        core.render(scene, camera)
        assertEquals(1, device.liveRenderables.size)
    }

    @Test
    fun `dispose frees everything and stops drawing`() {
        scene.add(Mesh(BoxGeometry(), StandardMaterial()), Group())
        core.render(scene, camera)
        core.dispose()
        assertTrue(device.disposed)
        assertTrue(device.liveRenderables.isEmpty())
        assertTrue(device.liveMeshes.isEmpty())
        assertEquals("", core.gpuDescription)
        core.render(scene, camera)
        assertEquals(1, stage.frames)
        core.dispose()
    }

    @Test
    fun `a frame the stage did not draw is not counted`() {
        stage.drawResult = false
        core.render(scene, camera)
        assertEquals(0L, core.framesRendered)
    }

    @Test
    fun `a node moved between frames reaches the renderable`() {
        val mesh = Mesh(BoxGeometry(), StandardMaterial())
        scene.add(mesh)
        core.render(scene, camera)
        mesh.position.x = 7.0
        core.render(scene, camera)
        assertEquals(7f, device.liveRenderables.single().transform[12])
    }
}
