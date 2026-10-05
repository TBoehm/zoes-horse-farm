package app.zoeshorsefarm.scene.render

import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Scene
import app.zoeshorsefarm.scene.graph.Sprite
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.PointsMaterial
import app.zoeshorsefarm.scene.material.SpriteMaterial
import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of tests/support/scene-stats.test.js. */
class SceneStatsTest {
    private val material = BasicMaterial()

    private fun box() = BoxGeometry(1.0, 1.0, 1.0) // 12 triangles, indexed

    @Test
    fun `counts one draw call and the triangles per mesh`() {
        val scene = Scene()
        scene.add(Mesh(box(), material), Mesh(box(), material))
        val stats = SceneStats.of(scene)
        assertEquals(2, stats.calls)
        assertEquals(24, stats.triangles)
        assertEquals(0, stats.instances)
    }

    @Test
    fun `counts an instanced mesh once times its instances`() {
        val scene = Scene()
        val mesh = InstancedMesh(box(), material, 50)
        mesh.count = 30
        scene.add(mesh)
        val stats = SceneStats.of(scene)
        assertEquals(1, stats.calls)
        assertEquals(360, stats.triangles)
        assertEquals(30, stats.instances)
    }

    @Test
    fun `counts a point cloud as one draw call without triangles`() {
        val scene = Scene()
        val geometry = Geometry()
        geometry.setAttribute("position", FloatAttribute(FloatArray(9), 3))
        val points = Points(geometry, PointsMaterial())
        scene.add(points)
        val stats = SceneStats.of(scene)
        assertEquals(1, stats.calls)
        assertEquals(0, stats.triangles)
        points.visible = false
        assertEquals(0, SceneStats.of(scene).calls)
    }

    @Test
    fun `skips hidden meshes and hidden parents and empty instanced meshes`() {
        val scene = Scene()
        val hidden = Mesh(box(), material).also { it.visible = false }
        val group = Group().also { it.visible = false }
        group.add(Mesh(box(), material))
        val empty = InstancedMesh(box(), material, 5).also { it.count = 0 }
        scene.add(hidden, group, empty, Mesh(box(), material))
        val stats = SceneStats.of(scene)
        assertEquals(1, stats.calls)
        assertEquals(12, stats.triangles)
    }

    @Test
    fun `follows the draw range of non-indexed geometry`() {
        val scene = Scene()
        val geometry = Geometry()
        geometry.setAttribute("position", FloatAttribute(FloatArray(90), 3))
        geometry.setDrawRange(0, 30)
        scene.add(Mesh(geometry, material))
        assertEquals(10, SceneStats.of(scene).triangles)
        geometry.setDrawRange(0, 0)
        val stats = SceneStats.of(scene)
        assertEquals(0, stats.calls)
        assertEquals(0, stats.triangles)
    }

    @Test
    fun `counts a call per material group`() {
        val scene = Scene()
        val geometry = box()
        geometry.clearGroups()
        geometry.addGroup(0, 18, 0)
        geometry.addGroup(18, 18, 1)
        scene.add(Mesh(geometry, listOf(material, material)))
        assertEquals(2, SceneStats.of(scene).calls)
    }

    @Test
    fun `adds the shadow pass of the meshes that cast shadows`() {
        val scene = Scene()
        val caster = Mesh(box(), material).also { it.castShadow = true }
        scene.add(caster, Mesh(box(), material))
        assertEquals(PassStats(1, 12), SceneStats.of(scene).shadowPass)
    }

    @Test
    fun `counts a sprite as a quad`() {
        val scene = Scene()
        scene.add(Sprite(SpriteMaterial()))
        val stats = SceneStats.of(scene)
        assertEquals(1, stats.calls)
        assertEquals(2, stats.triangles)
    }
}
