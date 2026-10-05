package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.material.Blend
import app.zoeshorsefarm.render.filament.material.MaterialSpec
import app.zoeshorsefarm.render.filament.material.Shading
import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Usage
import app.zoeshorsefarm.scene.graph.Group
import app.zoeshorsefarm.scene.graph.Mesh
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.ShaderMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SceneSyncMeshTest : SyncHarness() {
    @Test
    fun `a mesh makes one mesh one material instance and one renderable`() {
        meshAt()
        frame()
        assertEquals(1, device.liveMeshes.size)
        assertEquals(1, device.liveMaterialInstances.size)
        assertEquals(1, live().size)
        assertEquals(0, device.flushes)
        assertEquals(MaterialSpec(Shading.LIT), device.liveMaterialInstances[0].spec)
    }

    @Test
    fun `a frame where nothing changed calls nothing`() {
        meshAt()
        meshAt(2.0, material = StandardMaterial(map = null, transparent = true))
        frame()
        val before = deviceActivity()
        repeat(3) { frame() }
        assertEquals(before, deviceActivity())
    }

    @Test
    fun `meshes with the same geometry and material share them`() {
        val geometry = box()
        val material = StandardMaterial()
        meshAt(0.0, geometry, material)
        meshAt(1.0, geometry, material)
        frame()
        assertEquals(1, device.liveMeshes.size)
        assertEquals(1, device.liveMaterialInstances.size)
        assertEquals(2, live().size)
        assertEquals(1, sync.stats().geometries)
    }

    @Test
    fun `the world matrix goes to the renderable and only when it changes`() {
        val parent = Group()
        parent.position.set(1.0, 2.0, 3.0)
        scene.add(parent)
        val mesh = Mesh(box(), StandardMaterial())
        mesh.position.set(0.5, 0.0, 0.0)
        parent.add(mesh)
        frame()
        val renderable = live().single()
        assertEquals(1, renderable.transformCalls)
        assertContentEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1.5f, 2f, 3f, 1f),
            renderable.transform,
        )
        frame()
        assertEquals(1, renderable.transformCalls)
        parent.position.x = 4.0
        frame()
        assertEquals(2, renderable.transformCalls)
        assertEquals(4.5f, renderable.transform[12])
    }

    @Test
    fun `an invisible node or an invisible parent hides the renderable`() {
        val group = Group()
        scene.add(group)
        val mesh = Mesh(box(), StandardMaterial())
        group.add(mesh)
        frame()
        val renderable = live().single()
        assertTrue(renderable.visible)
        group.visible = false
        frame()
        assertFalse(renderable.visible)
        group.visible = true
        mesh.visible = false
        frame()
        assertFalse(renderable.visible)
        mesh.visible = true
        frame()
        assertTrue(renderable.visible)
        assertSame(renderable, live().single())
    }

    @Test
    fun `a node that leaves the scene frees its renderable but not its geometry`() {
        val mesh = meshAt()
        frame()
        scene.remove(mesh)
        frame()
        assertTrue(live().isEmpty())
        assertEquals(1, device.liveMeshes.size)
        assertEquals(1, sync.stats().geometries)
    }

    @Test
    fun `a node that comes back gets a new renderable on the old mesh`() {
        val mesh = meshAt()
        frame()
        scene.remove(mesh)
        frame()
        scene.add(mesh)
        frame()
        assertEquals(1, live().size)
        assertEquals(1, device.liveMeshes.size)
        assertEquals(2, device.renderables.size)
    }

    @Test
    fun `shadow flags culling and render order change in place`() {
        val mesh = meshAt()
        frame()
        val renderable = live().single()
        assertFalse(renderable.castShadows)
        mesh.castShadow = true
        mesh.receiveShadow = true
        mesh.frustumCulled = false
        mesh.renderOrder = 2
        frame()
        assertSame(renderable, live().single())
        assertTrue(renderable.castShadows)
        assertTrue(renderable.receiveShadows)
        assertFalse(renderable.culling)
        assertEquals(6, renderable.priority)
    }

    @Test
    fun `the renderable is built with the options of the node`() {
        val mesh = meshAt(material = StandardMaterial(transparent = true, fog = false))
        mesh.castShadow = true
        mesh.frustumCulled = false
        mesh.renderOrder = 3
        frame()
        val options = live().single().options
        assertTrue(options.castShadows)
        assertFalse(options.culling)
        assertFalse(options.fog)
        assertEquals(7, options.priority)
        assertTrue(options.blendOrder > 0)
    }

    @Test
    fun `opaque meshes keep the Filament blend order`() {
        meshAt()
        frame()
        assertEquals(0, live().single().options.blendOrder)
    }

    @Test
    fun `a mesh with a material list draws one range per group`() {
        val geometry = box()
        geometry.clearGroups()
        geometry.addGroup(0, 12, 0)
        geometry.addGroup(12, 24, 1)
        val first = StandardMaterial()
        val second = BasicMaterial()
        scene.add(Mesh(geometry, listOf(first, second)))
        frame()
        val renderable = live().single()
        assertEquals(listOf(PrimitiveRange(0, 12, 0), PrimitiveRange(12, 24, 1)), renderable.drawRanges)
        assertEquals(2, renderable.materials.size)
        assertEquals(setOf(Shading.LIT, Shading.UNLIT), renderable.materials.map { it.spec.shading }.toSet())
        assertEquals(2, sync.counts().drawCalls)
        assertEquals(12, sync.counts().triangles)
    }

    @Test
    fun `a changed draw range changes the ranges in place`() {
        val geometry = box()
        meshAt(geometry = geometry)
        frame()
        val renderable = live().single()
        geometry.setDrawRange(0, 12)
        frame()
        assertSame(renderable, live().single())
        assertEquals(listOf(PrimitiveRange(0, 12, 0)), renderable.drawRanges)
        assertEquals(1, renderable.rangeCalls)
        assertEquals(4, sync.counts().triangles)
    }

    @Test
    fun `a mesh whose draw range is empty draws nothing and hides`() {
        val geometry = box()
        meshAt(geometry = geometry)
        frame()
        geometry.setDrawRange(0, 0)
        frame()
        assertFalse(live().single().visible)
        assertEquals(0, sync.counts().drawCalls)
    }

    @Test
    fun `swapping the material of a mesh changes the material instance in place`() {
        val mesh = meshAt()
        frame()
        val renderable = live().single()
        val lambert = LambertMaterial()
        mesh.material = lambert
        frame()
        assertSame(renderable, live().single())
        assertEquals(
            Shading.LAMBERT,
            renderable.materials
                .single()
                .spec.shading,
        )
    }

    @Test
    fun `a material variant change makes a new material instance and keeps the renderable`() {
        val material = StandardMaterial()
        meshAt(material = material)
        frame()
        val renderable = live().single()
        material.transparent = true
        material.needsUpdate = true
        frame()
        assertSame(renderable, live().single())
        assertEquals(
            Blend.TRANSPARENT,
            renderable.materials
                .single()
                .spec.blend,
        )
        assertEquals(2, device.liveMaterialInstances.size)
    }

    @Test
    fun `plain value changes are written to the existing instance`() {
        val material = StandardMaterial(roughness = 0.5)
        meshAt(material = material)
        frame()
        val instance = device.liveMaterialInstances.single()
        val calls = instance.setCalls
        material.opacity = 0.5
        frame()
        assertEquals(calls + 1, instance.setCalls)
        assertEquals(0.5f, instance.floats["baseColor"]!![3])
        material.roughness = 0.9
        frame()
        assertEquals(0.9f, instance.floats["roughness"]!![0])
        assertEquals(1, device.liveMaterialInstances.size)
    }

    @Test
    fun `polygon offset and depth test follow the material`() {
        val material =
            BasicMaterial(
                polygonOffset = true,
                polygonOffsetFactor = -2.0,
                polygonOffsetUnits = -3.0,
                depthTest = false,
            )
        scene.add(Mesh(box(), material))
        frame()
        val instance = device.liveMaterialInstances.single()
        assertEquals(-2f to -3f, instance.polygonOffset)
        assertFalse(instance.depthTest)
        material.polygonOffset = false
        frame()
        assertEquals(0f to 0f, instance.polygonOffset)
    }

    @Test
    fun `a position buffer marked as changed is rewritten and the bounds follow`() {
        val geometry = box()
        meshAt(geometry = geometry)
        frame()
        val renderable = live().single()
        val mesh = device.liveMeshes.single()
        val positions = geometry.position
        for (i in positions.array.indices) positions.array[i] = positions.array[i] * 4f
        positions.needsUpdate = true
        frame()
        assertSame(mesh, device.liveMeshes.single())
        assertEquals(24 * 3, mesh.floatUpdates.single().second)
        assertEquals(1, renderable.boundsCalls)
        assertEquals(2f, renderable.bounds.max[0])
    }

    @Test
    fun `changed normals rewrite the tangent frames`() {
        val geometry = box()
        meshAt(geometry = geometry)
        frame()
        geometry.normal.needsUpdate = true
        frame()
        assertEquals(1, device.liveMeshes.single().normalUpdates)
    }

    @Test
    fun `an attribute added to a geometry uploads it again`() {
        val geometry = box()
        meshAt(geometry = geometry)
        frame()
        val first = device.liveMeshes.single()
        geometry.setAttribute("color", FloatAttribute(FloatArray(72), 3))
        frame()
        assertEquals(1, device.liveMeshes.size)
        assertNotSame(first, device.liveMeshes.single())
        assertEquals(1, live().size)
        assertTrue(first.destroyed)
    }

    @Test
    fun `changed colours upload the geometry again`() {
        val geometry = box()
        geometry.setAttribute("color", FloatAttribute(FloatArray(72), 3))
        meshAt(geometry = geometry, material = StandardMaterial(vertexColors = true))
        frame()
        val first = device.liveMeshes.single()
        geometry.color.needsUpdate = true
        frame()
        assertNotSame(first, device.liveMeshes.single())
        assertEquals(1, live().size)
    }

    @Test
    fun `a new index uploads the geometry again`() {
        val geometry = box()
        meshAt(geometry = geometry)
        frame()
        geometry.setIndex(intArrayOf(0, 1, 2))
        frame()
        assertEquals(3, device.liveMeshes.single().indexCount)
        assertEquals(1, live().size)
        assertEquals(listOf(PrimitiveRange(0, 3, 0)), live().single().drawRanges)
    }

    @Test
    fun `a lit mesh without normals gets them`() {
        val geometry = box()
        geometry.deleteAttribute("normal")
        meshAt(geometry = geometry)
        frame()
        assertTrue(geometry.hasAttribute("normal"))
        assertTrue(
            device.liveMeshes
                .single()
                .data
                ?.normals != null,
        )
    }

    @Test
    fun `a normal mapped material asks for tangents that follow the uvs`() {
        val texture =
            app.zoeshorsefarm.scene.texture
                .Texture(
                    app.zoeshorsefarm.scene.texture
                        .RgbaImage(1, 1),
                )
        meshAt(material = StandardMaterial(normalMap = texture))
        frame()
        assertTrue(
            device.liveMeshes
                .single()
                .options.tangentsFromUvs,
        )
    }

    @Test
    fun `a material that cannot be built is reported once and the others are drawn`() {
        val broken = StandardMaterial(roughness = 0.1, transparent = true)
        device.failingSpecs += MaterialSpec(Shading.LIT, blend = Blend.TRANSPARENT, depthWrite = true)
        meshAt(material = broken)
        meshAt(1.0)
        frame()
        frame()
        assertEquals(1, live().size)
        assertEquals(1, log.messages.size)
    }

    @Test
    fun `a material without a Filament shader is reported and not drawn`() {
        scene.add(Mesh(box(), ShaderMaterial("water", emptyMap())))
        frame()
        assertTrue(live().isEmpty())
        assertEquals(1, log.messages.size)
        frame()
        assertEquals(1, log.messages.size)
    }

    @Test
    fun `a geometry that cannot be uploaded is reported once`() {
        val geometry = box()
        geometry.setIndex(intArrayOf(0, 1, 99))
        meshAt(geometry = geometry)
        frame()
        frame()
        assertTrue(live().isEmpty())
        assertEquals(1, log.messages.size)
        geometry.setIndex(intArrayOf(0, 1, 2))
        frame()
        assertEquals(1, live().size)
    }

    @Test
    fun `a mesh without positions is skipped`() {
        scene.add(
            Mesh(
                app.zoeshorsefarm.scene.geometry
                    .Geometry(),
                StandardMaterial(),
            ),
        )
        frame()
        assertTrue(live().isEmpty())
    }

    @Test
    fun `dynamic attributes are not uploaded again when nothing changed`() {
        val geometry = box()
        geometry.position.setUsage(Usage.DYNAMIC_DRAW)
        meshAt(geometry = geometry)
        frame()
        frame()
        assertTrue(
            device.liveMeshes
                .single()
                .floatUpdates
                .isEmpty(),
        )
    }
}
