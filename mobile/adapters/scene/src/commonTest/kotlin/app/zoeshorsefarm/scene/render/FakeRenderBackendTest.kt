package app.zoeshorsefarm.scene.render

import app.zoeshorsefarm.scene.assertNear
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.texture.RgbaImage
import app.zoeshorsefarm.scene.texture.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeRenderBackendTest {
    private val camera = PerspectiveCamera()

    @Test
    fun `render counts draw calls and triangles into info`() {
        val backend = FakeRenderBackend()
        val scene = Scene()
        scene.add(Mesh(BoxGeometry(), BasicMaterial()), InstancedMesh(BoxGeometry(), BasicMaterial(), 5))
        backend.render(scene, camera)
        assertEquals(2, backend.info.drawCalls)
        assertEquals(72, backend.info.triangles)
        assertEquals(1, backend.renderCount)
        assertEquals(2, backend.info.programs) // plain and instanced variant
        assertEquals(2, backend.info.geometries)
    }

    @Test
    fun `the shadow pass adds to the counters only while shadows are enabled`() {
        val backend = FakeRenderBackend()
        val scene = Scene()
        scene.add(Mesh(BoxGeometry(), BasicMaterial()).also { it.castShadow = true })
        backend.render(scene, camera)
        assertEquals(1, backend.info.drawCalls)
        backend.shadowsEnabled = true
        backend.render(scene, camera)
        assertEquals(2, backend.info.drawCalls)
        assertEquals(24, backend.info.triangles)
    }

    @Test
    fun `render updates the matrices and calls onBeforeRender of visible nodes`() {
        val backend = FakeRenderBackend()
        val scene = Scene()
        val mesh = Mesh(BoxGeometry(), BasicMaterial())
        mesh.position.set(1.0, 2.0, 3.0)
        val hidden = Mesh(BoxGeometry(), BasicMaterial()).also { it.visible = false }
        var calls = 0
        var hiddenCalls = 0
        mesh.onBeforeRender = { b, _, _ -> if (b === backend) calls++ }
        hidden.onBeforeRender = { _, _, _ -> hiddenCalls++ }
        scene.add(mesh, hidden)
        backend.render(scene, camera)
        assertNear(2.0, mesh.matrixWorld.e[13])
        assertEquals(1, calls)
        assertEquals(0, hiddenCalls)
    }

    @Test
    fun `size and pixel ratio give the drawing buffer`() {
        val backend = FakeRenderBackend()
        backend.setPixelRatio(2.0)
        backend.setSize(300, 200)
        assertNear(600.0, backend.getDrawingBufferSize(Vec2()).x)
        assertNear(200.0, backend.getSize(Vec2()).y)
        assertNear(2.0, backend.pixelRatio)
    }

    @Test
    fun `compile uploads without drawing and calls back`() {
        val backend = FakeRenderBackend()
        val scene = Scene()
        val texture = Texture(RgbaImage(2, 2))
        scene.add(Mesh(BoxGeometry(), StandardMaterial(map = texture)))
        var done = false
        backend.compile(scene, camera, scene) { done = true }
        assertTrue(done)
        assertEquals(0, backend.renderCount)
        assertEquals(1, backend.compileCount)
        assertEquals(1, backend.info.programs)
        assertEquals(1, backend.info.textures)
        texture.dispose()
        assertEquals(0, backend.info.textures)
    }

    @Test
    fun `context loss stops drawing and restore starts from an empty GPU`() {
        val backend = FakeRenderBackend()
        val scene = Scene()
        scene.add(Mesh(BoxGeometry(), BasicMaterial()))
        val events = ArrayList<String>()
        backend.addContextListener(
            object : ContextListener {
                override fun onContextLost() {
                    events.add("lost")
                }

                override fun onContextRestored() {
                    events.add("restored")
                }
            },
        )
        backend.render(scene, camera)
        backend.simulateContextLoss()
        assertTrue(backend.contextLost)
        assertEquals("", backend.gpuDescription)
        backend.render(scene, camera)
        assertEquals(1, backend.renderCount)
        backend.simulateContextRestore()
        assertFalse(backend.contextLost)
        assertEquals(0, backend.info.programs)
        backend.render(scene, camera)
        assertEquals(2, backend.renderCount)
        assertEquals(1, backend.info.programs)
        assertEquals(listOf("lost", "restored"), events)
    }
}
