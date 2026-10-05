package app.zoeshorsefarm.scene.render

import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Node
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Traversable
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of tests/support/gpu-tracker.test.js. */
class GpuTrackerTest {
    private class Setup {
        val scene = Scene()
        var shadows = false
        val tracker = GpuTracker(scene) { shadows }
    }

    @Test
    fun `counts programs by key, equal materials share one, different ones do not`() {
        val s = Setup()
        val a = Mesh(BoxGeometry(), StandardMaterial())
        val b = Mesh(BoxGeometry(), StandardMaterial())
        val c = Mesh(BoxGeometry(), LambertMaterial())
        s.scene.add(a, b, c)
        s.tracker.compile(s.scene)
        assertEquals(GpuCounts(programs = 2, materials = 3, geometries = 3, instanced = 0), s.tracker.snapshot())
    }

    @Test
    fun `keeps the old program of a material that switches variant until it is disposed`() {
        val s = Setup()
        val material = StandardMaterial()
        val mesh = Mesh(BoxGeometry(), material)
        s.scene.add(mesh)
        s.tracker.compile(s.scene)
        s.shadows = true
        mesh.receiveShadow = true
        s.tracker.compile(s.scene)
        assertEquals(2, s.tracker.snapshot().programs) // the leak of a switch without dispose
        material.dispose()
        assertEquals(0, s.tracker.snapshot().programs)
        s.tracker.compile(s.scene)
        assertEquals(1, s.tracker.snapshot().programs) // built again on use
    }

    @Test
    fun `frees geometries and instanced meshes by dispose and tracks the peak`() {
        val s = Setup()
        val geometry = BoxGeometry()
        val mesh = InstancedMesh(geometry, StandardMaterial(), 4)
        s.scene.add(mesh)
        s.tracker.compile(s.scene)
        assertTrue(s.tracker.holds(geometry))
        assertTrue(s.tracker.holds(mesh))
        geometry.dispose()
        mesh.dispose()
        assertFalse(s.tracker.holds(geometry))
        assertEquals(0, s.tracker.snapshot().instanced)
        assertEquals(1, s.tracker.peak.geometries)
        s.tracker.resetPeak()
        assertEquals(0, s.tracker.peak.geometries)
    }

    @Test
    fun `only draws what the root yields, hidden objects are not uploaded`() {
        val s = Setup()
        val hidden = Mesh(BoxGeometry(), StandardMaterial()).also { it.visible = false }
        s.scene.add(hidden)
        val visibleOnly =
            object : Traversable {
                override fun traverse(callback: (Node) -> Unit) = s.scene.traverseVisible(callback)

                override fun traverseVisible(callback: (Node) -> Unit) = s.scene.traverseVisible(callback)
            }
        s.tracker.compile(visibleOnly)
        assertEquals(0, s.tracker.snapshot().programs)
    }

    @Test
    fun `the program key separates instancing, skinning, colours, fog and effects`() {
        val standard = StandardMaterial(vertexColors = true)
        val plain = Mesh(BoxGeometry(), standard)
        val inst = InstancedMesh(BoxGeometry(), standard, 2)
        val state = ProgramState(fog = true, environment = true, shadows = true)
        assertEquals("MeshStandardMaterial||||||vc||||||fog|env|", programKey(plain, standard, state))
        assertEquals("MeshStandardMaterial||inst||||vc||||||fog|env|", programKey(inst, standard, state))
        plain.receiveShadow = true
        assertEquals("MeshStandardMaterial||||||vc||||||fog|env|shadow", programKey(plain, standard, state))
    }
}
