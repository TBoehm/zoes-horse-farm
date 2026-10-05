package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.material.Shading
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.material.WindEffect
import app.zoeshorsefarm.scene.render.GpuTracker
import app.zoeshorsefarm.scene.render.SceneStats
import app.zoeshorsefarm.scene.texture.RgbaImage
import app.zoeshorsefarm.scene.texture.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SceneSyncResourcesTest : SyncHarness() {
    private fun texture(size: Int = 2) = Texture(RgbaImage(size, size))

    @Test
    fun `a map is uploaded once and bound`() {
        val map = texture()
        meshAt(material = StandardMaterial(map = map))
        meshAt(2.0, material = StandardMaterial(map = map))
        frame()
        frame()
        assertEquals(1, device.liveTextures.size)
        assertEquals(1, sync.stats().textures)
        val bound = device.liveMaterialInstances.map { it.textures["baseColorMap"] }
        assertTrue(bound.all { it === device.liveTextures.single() })
    }

    @Test
    fun `the texture is a copy of the pixels with the sampling of the texture`() {
        val map = texture()
        map.colorSpace = app.zoeshorsefarm.scene.texture.TextureColorSpace.SRGB
        meshAt(material = StandardMaterial(map = map))
        frame()
        val data = device.liveTextures.single().data!!
        assertTrue(data.srgb)
        assertNotSame(map.image.pixels, data.pixels)
    }

    @Test
    fun `a texture marked as changed is uploaded again and rebound`() {
        val map = texture()
        meshAt(material = StandardMaterial(map = map))
        frame()
        val first = device.liveTextures.single()
        map.needsUpdate = true
        frame()
        val second = device.liveTextures.single()
        assertNotSame(first, second)
        assertTrue(first.destroyed)
        assertTrue(second === device.liveMaterialInstances.single().textures["baseColorMap"])
        frame()
        assertEquals(2, device.textures.size)
    }

    @Test
    fun `a disposed texture is freed and uploaded again by the next frame`() {
        val map = texture()
        meshAt(material = StandardMaterial(map = map))
        frame()
        map.dispose()
        assertTrue(device.liveTextures.isEmpty())
        assertEquals(0, sync.stats().textures)
        assertTrue(device.placeholderTexture === device.liveMaterialInstances.single().textures["baseColorMap"])
        frame()
        assertEquals(1, device.liveTextures.size)
        assertTrue(device.liveTextures.single() === device.liveMaterialInstances.single().textures["baseColorMap"])
    }

    @Test
    fun `replacing the map of a material binds the new texture`() {
        val material = StandardMaterial(map = texture())
        meshAt(material = material)
        frame()
        val other = texture(4)
        material.map = other
        frame()
        assertEquals(2, device.liveTextures.size)
        assertEquals(
            4,
            device.liveMaterialInstances
                .single()
                .textures["baseColorMap"]!!
                .data!!
                .width,
        )
    }

    @Test
    fun `a disposed geometry is freed and the renderable is built again by the next frame`() {
        val geometry = box()
        meshAt(geometry = geometry)
        frame()
        val first = live().single()
        geometry.dispose()
        assertTrue(device.liveMeshes.isEmpty())
        assertTrue(live().isEmpty())
        assertTrue(first.destroyed)
        assertEquals(0, sync.stats().geometries)
        frame()
        assertEquals(1, device.liveMeshes.size)
        assertEquals(1, live().size)
        assertEquals(1, sync.stats().geometries)
    }

    @Test
    fun `a disposed material frees its instance and its shader`() {
        val material = StandardMaterial()
        meshAt(material = material)
        frame()
        assertEquals(1, device.builtMaterials.size)
        material.dispose()
        assertTrue(device.liveMaterialInstances.isEmpty())
        assertTrue(device.builtMaterials.isEmpty())
        assertTrue(live().isEmpty())
        assertEquals(0, sync.stats().programs)
        frame()
        assertEquals(1, device.liveMaterialInstances.size)
        assertEquals(1, live().size)
        assertEquals(1, sync.stats().programs)
    }

    @Test
    fun `the shader of a spec stays while another material instance uses it`() {
        val first = StandardMaterial()
        val second = StandardMaterial()
        meshAt(material = first)
        meshAt(1.0, material = second)
        frame()
        assertEquals(1, device.builtMaterials.size)
        first.dispose()
        assertEquals(1, device.builtMaterials.size)
        second.dispose()
        assertTrue(device.builtMaterials.isEmpty())
    }

    @Test
    fun `disposing a texture of one material leaves the other material alone`() {
        val a = texture()
        val b = texture()
        meshAt(material = StandardMaterial(map = a))
        meshAt(1.0, material = StandardMaterial(map = b))
        frame()
        a.dispose()
        frame()
        assertEquals(2, device.liveTextures.size)
        assertEquals(2, live().size)
    }

    @Test
    fun `the program count follows the scene model's GpuTracker`() {
        val wind = Wind()
        val standard = StandardMaterial(vertexColors = true)
        val lambert = LambertMaterial(effect = WindEffect.tree(wind))
        val trees = InstancedMesh(box(), lambert, 4)
        trees.setColorAt(
            0,
            app.zoeshorsefarm.scene.math
                .Color(0xffffff),
        )
        val shadowed = Mesh(box(), standard).also { it.receiveShadow = true }
        scene.add(Mesh(box(), BasicMaterial()), Mesh(box(), standard), trees, shadowed)
        scene.fog =
            app.zoeshorsefarm.scene.graph
                .Fog(0xffffff)
        shadowsEnabled = true
        frame()
        val tracker = GpuTracker(scene) { true }
        tracker.compile(scene)
        assertEquals(tracker.snapshot().programs, sync.stats().programs)
        assertEquals(tracker.programKeys(), sync.programKeys())
        assertEquals(tracker.snapshot().geometries, sync.stats().geometries)
    }

    @Test
    fun `a material that switches variant keeps both programs until it is disposed`() {
        val material = StandardMaterial()
        meshAt(material = material)
        frame()
        assertEquals(1, sync.stats().programs)
        material.transparent = true
        material.needsUpdate = true
        frame()
        assertEquals(2, sync.stats().programs)
        material.dispose()
        assertEquals(0, sync.stats().programs)
    }

    @Test
    fun `fog and shadows change the program keys`() {
        val material = StandardMaterial()
        meshAt(material = material).receiveShadow = true
        frame()
        assertEquals(1, sync.stats().programs)
        scene.fog =
            app.zoeshorsefarm.scene.graph
                .Fog(0xffffff)
        frame()
        assertEquals(2, sync.stats().programs)
        shadowsEnabled = true
        frame()
        assertEquals(3, sync.stats().programs)
    }

    @Test
    fun `the draw counts equal the scene model's SceneStats`() {
        val geometry = box()
        geometry.clearGroups()
        geometry.addGroup(0, 12, 0)
        geometry.addGroup(12, 24, 1)
        val casting = Mesh(box(), StandardMaterial()).also { it.castShadow = true }
        val instanced = InstancedMesh(box(), StandardMaterial(), 7).also { it.castShadow = true }
        scene.add(casting, instanced, Mesh(geometry, listOf(StandardMaterial(), BasicMaterial())))
        val hiddenGroup = Group().also { it.visible = false }
        hiddenGroup.add(Mesh(box(), StandardMaterial()))
        scene.add(hiddenGroup)
        frame()
        val expected = SceneStats.of(scene)
        val counts = sync.counts()
        assertEquals(expected.calls, counts.drawCalls)
        assertEquals(expected.triangles, counts.triangles)
        assertEquals(expected.shadowPass.calls, counts.shadowCalls)
        assertEquals(expected.shadowPass.triangles, counts.shadowTriangles)
    }

    @Test
    fun `the wind of the first wind material is reported`() {
        val wind = Wind(time = 3.0, strength = 0.5)
        val trees = InstancedMesh(box(), StandardMaterial(effect = WindEffect.tree(wind)), 2)
        scene.add(Mesh(box(), StandardMaterial()), trees)
        frame()
        assertSame(wind, sync.wind)
        scene.remove(trees)
        frame()
        assertEquals(null, sync.wind)
    }

    @Test
    fun `lights are collected from the visible nodes`() {
        val sun =
            app.zoeshorsefarm.scene.graph
                .DirectionalLight()
        val hidden =
            app.zoeshorsefarm.scene.graph
                .HemisphereLight()
                .also { it.visible = false }
        val sky =
            app.zoeshorsefarm.scene.graph
                .HemisphereLight()
        scene.add(
            sun,
            hidden,
            sky,
            app.zoeshorsefarm.scene.graph
                .DirectionalLight(),
        )
        frame()
        assertSame(sun, sync.lights.sun)
        assertEquals(listOf(sky), sync.lights.hemispheres)
    }

    @Test
    fun `onBeforeRender runs for visible nodes before their values are read`() {
        val material = BasicMaterial()
        val mesh = Mesh(box(), material)
        var calls = 0
        mesh.onBeforeRender = { backend, s, c ->
            calls++
            assertSame(owner, backend)
            assertSame(scene, s)
            assertSame(camera, c)
            material.opacity = 0.25
        }
        scene.add(mesh)
        frame()
        assertEquals(1, calls)
        assertEquals(0.25f, device.liveMaterialInstances.single().floats["baseColor"]!![3])
        mesh.visible = false
        frame()
        assertEquals(1, calls)
    }

    @Test
    fun `compile builds geometries materials and textures without drawing`() {
        val map = texture()
        meshAt(material = StandardMaterial(map = map))
        sync.compile(scene, scene, false)
        assertEquals(1, device.liveMeshes.size)
        assertEquals(1, device.liveTextures.size)
        assertEquals(1, device.liveMaterialInstances.size)
        assertTrue(live().isEmpty())
        assertEquals(1, sync.stats().programs)
        assertEquals(1, device.preparedMaterials.size)
        assertEquals(1, device.flushes)
        frame()
        assertEquals(1, device.materialInstances.size)
        assertEquals(1, device.meshes.size)
        assertEquals(1, device.materialBuilds)
    }

    @Test
    fun `compile also reaches invisible nodes`() {
        val group = Group().also { it.visible = false }
        group.add(Mesh(box(), StandardMaterial()))
        scene.add(group)
        sync.compile(scene, scene, false)
        assertEquals(1, device.liveMeshes.size)
    }

    @Test
    fun `clear destroys every device object`() {
        val map = texture()
        meshAt(material = StandardMaterial(map = map))
        scene.add(InstancedMesh(box(), StandardMaterial(), 3))
        frame()
        sync.clear()
        assertTrue(live().isEmpty())
        assertTrue(device.liveMeshes.isEmpty())
        assertTrue(device.liveTextures.isEmpty())
        assertTrue(device.liveMaterialInstances.isEmpty())
        assertTrue(device.liveInstanceData.isEmpty())
        assertTrue(device.builtMaterials.isEmpty())
        assertEquals(0, sync.stats().programs)
        frame()
        assertEquals(2, live().size)
    }

    @Test
    fun `a geometry stays valid for the other mesh when one user leaves`() {
        val geometry = box()
        val a = meshAt(geometry = geometry)
        meshAt(2.0, geometry = geometry)
        frame()
        scene.remove(a)
        frame()
        assertEquals(1, live().size)
        assertFalse(live().single().destroyed)
        assertEquals(1, device.liveMeshes.size)
        assertEquals(
            Shading.LIT,
            live()
                .single()
                .materials
                .single()
                .spec.shading,
        )
    }
}
