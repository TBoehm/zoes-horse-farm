package app.zoeshorsefarm.view3d.engine

import app.zoeshorsefarm.scene.graph.PerspectiveCamera
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.render.FakeRenderBackend
import app.zoeshorsefarm.scene.render.ShadowType
import app.zoeshorsefarm.scene.render.ToneMapping
import app.zoeshorsefarm.view3d.assertClose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Renderer setup (web: renderer.js has no test of its own; these are the cases of its three functions).

class RendererTest {
    private val backend = FakeRenderBackend()
    private val sizing = RenderSizing(backend, ViewMetrics(800.0, 400.0, 3.0))
    private val camera = PerspectiveCamera(58.0, 16.0 / 9, 0.1, 900.0)

    @Test
    fun configuresToneMappingAndShadowsLikeTheWebRenderer() {
        configureRenderer(backend)
        assertEquals(ToneMapping.ACES_FILMIC, backend.toneMapping)
        assertEquals(1.0, backend.toneMappingExposure)
        assertFalse(backend.shadowsEnabled)
        assertEquals(ShadowType.PCF, backend.shadowType)
        assertTrue(backend.shadowAutoUpdate)
    }

    @Test
    fun startsWithTheDefaultPixelRatioCapOfTwo() {
        assertEquals(2.0, sizing.maxPixelRatio)
    }

    @Test
    fun appliesTheCapAtOnceAndNeverExceedsTheDeviceRatio() {
        assertEquals(1.5, sizing.setMaxPixelRatio(1.5))
        assertEquals(1.5, backend.pixelRatio)
        sizing.view.devicePixelRatio = 1.0
        assertEquals(1.0, sizing.setMaxPixelRatio(2.0))
        assertEquals(1.0, backend.pixelRatio)
        assertEquals(2.0, sizing.maxPixelRatio)
    }

    @Test
    fun resizeFitsSurfaceAndCameraAndReportsTheChangeOnce() {
        assertTrue(sizing.resize(camera))
        val size = backend.getSize(Vec2())
        assertEquals(800.0, size.x)
        assertEquals(400.0, size.y)
        assertEquals(2.0, backend.pixelRatio) // min(device 3, cap 2)
        assertClose(2.0, camera.aspect)
        assertFalse(sizing.resize(camera))
    }

    @Test
    fun resizeFollowsAChangeOfTheSizeTheRatioAndTheCap() {
        sizing.resize(camera)
        sizing.view.set(500.0, 500.0, 3.0)
        assertTrue(sizing.resize(camera))
        assertClose(1.0, camera.aspect)
        sizing.setMaxPixelRatio(1.0)
        // the cap alone changed the ratio the surface should have, but setMaxPixelRatio applied it
        assertFalse(sizing.resize(camera))
        sizing.view.devicePixelRatio = 1.0
        sizing.setMaxPixelRatio(2.0)
        assertFalse(sizing.resize(camera))
    }

    @Test
    fun resizeUsesWholePixelsAndAtLeastOne() {
        sizing.view.set(0.4, 99.9, 1.0)
        assertTrue(sizing.resize(camera))
        val size = backend.getSize(Vec2())
        assertEquals(1.0, size.x)
        assertEquals(99.0, size.y)
    }

    @Test
    fun resizeWorksWithoutACamera() {
        assertTrue(sizing.resize(null))
    }
}
