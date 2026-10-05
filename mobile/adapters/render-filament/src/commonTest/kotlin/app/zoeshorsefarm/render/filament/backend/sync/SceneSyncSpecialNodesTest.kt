package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.material.InstancingMode
import app.zoeshorsefarm.render.filament.material.Shading
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.UShortAttribute
import app.zoeshorsefarm.scene.graph.Bone
import app.zoeshorsefarm.scene.graph.InstancedMesh
import app.zoeshorsefarm.scene.graph.Points
import app.zoeshorsefarm.scene.graph.Skeleton
import app.zoeshorsefarm.scene.graph.SkinnedMesh
import app.zoeshorsefarm.scene.graph.Sprite
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.ShaderMaterial
import app.zoeshorsefarm.scene.material.SpriteMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.material.Uniform
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.material.WindEffect
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.texture.RgbaImage
import app.zoeshorsefarm.scene.texture.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import app.zoeshorsefarm.render.filament.material.WindEffect as FilamentWind

class SceneSyncSpecialNodesTest : SyncHarness() {
    private fun translation(
        x: Double,
        y: Double = 0.0,
        z: Double = 0.0,
    ) = Mat4().makeTranslation(x, y, z)

    // ---- instanced meshes ----------------------------------------------------------------------

    private fun instanced(
        count: Int,
        material: StandardMaterial = StandardMaterial(),
    ): InstancedMesh {
        val mesh = InstancedMesh(box(), material, 10)
        for (i in 0 until 10) mesh.setMatrixAt(i, translation(i * 2.0))
        mesh.count = count
        scene.add(mesh)
        return mesh
    }

    @Test
    fun `an instanced mesh makes instance data and a renderable that draws the instances`() {
        instanced(count = 6)
        frame()
        val data = device.liveInstanceData.single()
        assertEquals(10, data.capacity)
        assertEquals(listOf(0 until 6), data.updates)
        val renderable = live().single()
        assertEquals(6, renderable.options.instanceCount)
        assertEquals(
            InstancingMode.TRANSFORMS,
            renderable.materials
                .single()
                .spec.instancing,
        )
        assertSame(data, renderable.materials.single().instanceData)
        assertEquals(0, renderable.transformCalls)
    }

    @Test
    fun `the bounds of an instanced renderable cover all instances`() {
        instanced(count = 3)
        frame()
        val box = live().single().options.bounds!!
        assertEquals(-0.5f, box.min[0])
        assertEquals(4.5f, box.max[0])
    }

    @Test
    fun `an unchanged instanced mesh uploads nothing`() {
        instanced(count = 4)
        frame()
        val before = deviceActivity()
        frame()
        frame()
        assertEquals(before, deviceActivity())
    }

    @Test
    fun `changed matrices are uploaded and the bounds follow`() {
        val mesh = instanced(count = 4)
        frame()
        val renderable = live().single()
        mesh.setMatrixAt(3, translation(100.0))
        mesh.instanceMatrix.needsUpdate = true
        frame()
        assertEquals(
            2,
            device.liveInstanceData
                .single()
                .updates.size,
        )
        assertEquals(100.5f, renderable.bounds.max[0])
        assertEquals(1, renderable.boundsCalls)
    }

    @Test
    fun `instance colours are uploaded with the matrices`() {
        val mesh = instanced(count = 2)
        mesh.setColorAt(0, Color(0xff0000))
        mesh.setColorAt(1, Color(0x00ff00))
        frame()
        assertEquals(
            InstancingMode.TRANSFORMS_AND_COLOR,
            live()
                .single()
                .materials
                .single()
                .spec.instancing,
        )
        assertEquals(
            mesh.instanceColor!!.array.toList(),
            device.liveInstanceData
                .single()
                .lastColors!!
                .toList(),
        )
    }

    @Test
    fun `a colour added later switches the variant`() {
        val mesh = instanced(count = 2)
        frame()
        mesh.setColorAt(0, Color(0xff0000))
        frame()
        assertEquals(
            InstancingMode.TRANSFORMS_AND_COLOR,
            live()
                .single()
                .materials
                .single()
                .spec.instancing,
        )
        assertEquals(1, live().size)
    }

    @Test
    fun `more instances rebuild the renderable and upload the new ones`() {
        val mesh = instanced(count = 4)
        frame()
        val first = live().single()
        mesh.count = 8
        frame()
        assertTrue(first.destroyed)
        assertEquals(8, live().single().options.instanceCount)
        assertEquals(1, device.liveInstanceData.size)
        assertEquals(
            0 until 8,
            device.liveInstanceData
                .single()
                .updates
                .last(),
        )
    }

    @Test
    fun `fewer instances rebuild the renderable without a new upload`() {
        val mesh = instanced(count = 8)
        frame()
        mesh.count = 3
        frame()
        assertEquals(3, live().single().options.instanceCount)
        assertEquals(
            1,
            device.liveInstanceData
                .single()
                .updates.size,
        )
    }

    @Test
    fun `an instanced mesh with no instances draws nothing`() {
        val mesh = instanced(count = 4)
        frame()
        mesh.count = 0
        frame()
        assertTrue(live().isEmpty() || !live().single().visible)
        assertEquals(0, sync.counts().drawCalls)
    }

    @Test
    fun `a mesh that is not at the origin has its matrices baked into world matrices`() {
        val mesh = instanced(count = 2)
        mesh.position.set(0.0, 10.0, 0.0)
        frame()
        val sent = device.liveInstanceData.single().lastMatrices
        assertEquals(10f, sent[13])
        assertEquals(2f, sent[16 + 12])
        assertEquals(10f, sent[16 + 13])
        assertEquals(0, live().single().transformCalls)
        mesh.position.set(0.0, 20.0, 0.0)
        frame()
        assertEquals(20f, device.liveInstanceData.single().lastMatrices[13])
        assertEquals(
            2,
            device.liveInstanceData
                .single()
                .updates.size,
        )
    }

    @Test
    fun `disposing an instanced mesh frees the instance data and builds it again`() {
        val mesh = instanced(count = 4)
        frame()
        mesh.dispose()
        assertTrue(device.liveInstanceData.isEmpty())
        assertTrue(live().isEmpty())
        assertTrue(device.liveMaterialInstances.isEmpty())
        frame()
        assertEquals(1, device.liveInstanceData.size)
        assertEquals(1, live().size)
        assertSame(
            device.liveInstanceData.single(),
            live()
                .single()
                .materials
                .single()
                .instanceData,
        )
    }

    @Test
    fun `a variant change of an instanced material binds the instance data to the new instance`() {
        val material = StandardMaterial()
        val mesh = instanced(count = 3, material = material)
        frame()
        material.transparent = true
        material.needsUpdate = true
        mesh.count = 5
        frame()
        val instance = live().single().materials.single()
        assertEquals(app.zoeshorsefarm.render.filament.material.Blend.TRANSPARENT, instance.spec.blend)
        assertSame(device.liveInstanceData.single(), instance.instanceData)
    }

    @Test
    fun `two instanced meshes with one material have their own data`() {
        val material = StandardMaterial()
        instanced(count = 2, material = material)
        instanced(count = 3, material = material)
        frame()
        assertEquals(2, device.liveInstanceData.size)
        val datas = live().map { it.materials.single().instanceData }
        assertNotSame(datas[0], datas[1])
    }

    @Test
    fun `an instanced mesh that leaves the scene frees its private instances`() {
        val mesh = instanced(count = 2)
        frame()
        scene.remove(mesh)
        frame()
        assertTrue(device.liveInstanceData.isEmpty())
        assertTrue(device.liveMaterialInstances.isEmpty())
        assertTrue(device.builtMaterials.isEmpty())
    }

    @Test
    fun `tree wind on an instanced mesh uses the wind variant and reports the wind`() {
        val wind = Wind(time = 2.0)
        instanced(count = 2, material = StandardMaterial(effect = WindEffect.tree(wind)))
        frame()
        assertEquals(
            FilamentWind.Tree,
            live()
                .single()
                .materials
                .single()
                .spec.wind,
        )
        assertSame(wind, sync.wind)
    }

    @Test
    fun `an instanced mesh that is not culled keeps the geometry bounds`() {
        val mesh = instanced(count = 3)
        mesh.frustumCulled = false
        frame()
        assertFalse(live().single().culling)
        assertEquals(
            0.5f,
            live()
                .single()
                .options.bounds!!
                .max[0],
        )
    }

    // ---- skinned meshes ------------------------------------------------------------------------

    private fun skinned(): SkinnedMesh {
        val geometry = box()
        val n = geometry.vertexCount
        geometry.setAttribute("skinIndex", UShortAttribute(ShortArray(n * 4), 4))
        geometry.setAttribute("skinWeight", FloatAttribute(FloatArray(n * 4) { if (it % 4 == 0) 1f else 0f }, 4))
        val mesh = SkinnedMesh(geometry, StandardMaterial())
        val root = Bone()
        val tip = Bone()
        tip.position.set(0.0, 1.0, 0.0)
        root.add(tip)
        mesh.add(root)
        scene.add(mesh)
        scene.updateMatrixWorld()
        mesh.bind(Skeleton(listOf(root, tip)), mesh.matrixWorld.clone())
        return mesh
    }

    @Test
    fun `a skinned mesh sets its bones every frame`() {
        val mesh = skinned()
        frame()
        val renderable = live().single()
        assertEquals(2, renderable.options.boneCount)
        assertTrue(
            renderable.materials
                .single()
                .spec.skinning,
        )
        assertEquals(1, renderable.boneCalls)
        assertEquals(32, renderable.bones.size)
        frame()
        assertEquals(2, renderable.boneCalls)
        assertSame(mesh, mesh)
    }

    @Test
    fun `the bones in the bind pose are identity matrices`() {
        skinned()
        frame()
        val bones = live().single().bones
        for (b in 0 until 2) {
            for (i in 0 until 16) {
                val expected = if (i % 5 == 0) 1f else 0f
                assertEquals(expected, bones[b * 16 + i], 1e-5f, "bone $b element $i")
            }
        }
    }

    @Test
    fun `a moved bone moves its palette entry`() {
        val mesh = skinned()
        frame()
        mesh.skeleton!!
            .bones[1]
            .position
            .set(0.0, 3.0, 0.0)
        frame()
        val bones = live().single().bones
        assertEquals(2f, bones[16 + 13], 1e-5f)
    }

    @Test
    fun `the transform of a skinned mesh is its world matrix`() {
        val mesh = skinned()
        mesh.position.set(5.0, 0.0, 0.0)
        frame()
        assertEquals(5f, live().single().transform[12])
    }

    // ---- points --------------------------------------------------------------------------------

    private fun dustGeometry(count: Int): Geometry {
        val geometry = Geometry()
        geometry.setAttribute("position", FloatAttribute(FloatArray(count * 3) { it.toFloat() }, 3))
        geometry.setAttribute("aSize", FloatAttribute(FloatArray(count) { 0.5f }, 1))
        geometry.setAttribute("aAlpha", FloatAttribute(FloatArray(count) { 0.25f }, 1))
        return geometry
    }

    private fun dust(count: Int = 5): Points {
        val material =
            ShaderMaterial(
                "hoof-dust",
                mapOf("uColor" to Uniform(Color(0xe8dcc2)), "uPixelScale" to Uniform(600.0)),
                transparent = true,
                depthWrite = false,
            )
        val points = Points(dustGeometry(count), material)
        points.frustumCulled = false
        points.renderOrder = 2
        scene.add(points)
        return points
    }

    @Test
    fun `points make a sprite batch and a sprite material`() {
        dust(5)
        frame()
        val batch = device.liveSpriteBatches.single()
        assertEquals(5, batch.capacity)
        assertEquals(1, batch.updates)
        assertEquals(15, batch.lastCenters.size)
        assertEquals(0.5f, batch.lastSizes[0])
        assertEquals(0.25f, batch.lastOpacities[4])
        val renderable = live().single()
        assertEquals(
            Shading.SPRITE,
            renderable.materials
                .single()
                .spec.shading,
        )
        assertFalse(renderable.options.culling)
        assertEquals(6, renderable.options.priority)
        assertEquals(1, sync.counts().drawCalls)
        assertEquals(0, sync.counts().triangles)
    }

    @Test
    fun `points are rewritten only when an attribute is marked as changed`() {
        val points = dust(4)
        frame()
        frame()
        assertEquals(1, device.liveSpriteBatches.single().updates)
        points.geometry.float("aAlpha").needsUpdate = true
        frame()
        assertEquals(2, device.liveSpriteBatches.single().updates)
    }

    @Test
    fun `the dust colour reaches the material`() {
        dust()
        frame()
        val color = device.liveMaterialInstances.single().floats["baseColor"]!!
        assertEquals(Color(0xe8dcc2).r.toFloat(), color[0])
        assertEquals(1f, color[3])
    }

    @Test
    fun `invisible points are hidden`() {
        val points = dust()
        frame()
        points.visible = false
        frame()
        assertFalse(live().single().visible)
        assertEquals(0, sync.counts().drawCalls)
    }

    @Test
    fun `points with a different vertex count make a new batch`() {
        val points = dust(4)
        frame()
        points.geometry = dustGeometry(6)
        frame()
        assertEquals(1, device.liveSpriteBatches.size)
        assertEquals(6, device.liveSpriteBatches.single().capacity)
        assertEquals(1, live().size)
    }

    @Test
    fun `disposing the geometry of points frees the batch and the next frame makes it again`() {
        val points = dust(4)
        frame()
        points.geometry.dispose()
        assertTrue(device.liveSpriteBatches.isEmpty())
        assertTrue(live().isEmpty())
        frame()
        assertEquals(1, device.liveSpriteBatches.size)
        assertEquals(1, live().size)
    }

    @Test
    fun `points without sizes use the size of the material`() {
        val geometry = Geometry()
        geometry.setAttribute("position", FloatAttribute(FloatArray(6), 3))
        val material =
            app.zoeshorsefarm.scene.material.PointsMaterial(
                size = 0.3,
                transparent = true,
                depthWrite = false,
            )
        scene.add(Points(geometry, material))
        frame()
        val batch = device.liveSpriteBatches.single()
        assertEquals(listOf(0.3f, 0.3f), batch.lastSizes.toList())
        assertEquals(listOf(1f, 1f), batch.lastOpacities.toList())
    }

    @Test
    fun `a points object with an unusable position attribute is skipped`() {
        scene.add(Points(Geometry(), ShaderMaterial("hoof-dust", mapOf("uColor" to Uniform(Color(0xffffff))))))
        frame()
        assertTrue(live().isEmpty())
        assertNull(sync.wind)
    }

    // ---- sprites -------------------------------------------------------------------------------

    private fun pin(): Sprite {
        val texture = Texture(RgbaImage(2, 2))
        val material = SpriteMaterial(map = texture, depthWrite = false, fog = false, toneMapped = false)
        val sprite = Sprite(material)
        sprite.center.set(0.5, 0.0)
        sprite.renderOrder = 5
        sprite.position.set(0.0, 2.0, -5.0)
        scene.add(sprite)
        return sprite
    }

    @Test
    fun `a sprite is the shared quad with an unlit transparent material`() {
        pin()
        frame()
        val renderable = live().single()
        val spec = renderable.materials.single().spec
        assertEquals(Shading.UNLIT, spec.shading)
        assertTrue(spec.baseColorMap)
        assertEquals(4, renderable.mesh.vertexCount)
        assertFalse(renderable.options.fog)
        assertEquals(7, renderable.options.priority)
        assertEquals(1, sync.counts().drawCalls)
        assertEquals(2, sync.counts().triangles)
    }

    @Test
    fun `a sprite follows the camera`() {
        pin()
        frame()
        val renderable = live().single()
        val first = renderable.transform.copyOf()
        // look from the side: the quad's x axis follows the camera's right axis
        camera.position.set(0.0, 0.0, 0.0)
        camera.rotation.y = 1.0
        frame()
        assertEquals(2, renderable.transformCalls)
        assertTrue(first.toList() != renderable.transform.toList())
        frame()
        assertEquals(2, renderable.transformCalls)
    }

    @Test
    fun `two sprites share one quad`() {
        pin()
        pin()
        frame()
        assertEquals(1, device.liveMeshes.size)
        assertEquals(2, live().size)
    }

    @Test
    fun `sprites are not counted in the program books`() {
        pin()
        frame()
        assertEquals(0, sync.stats().programs)
    }

    @Test
    fun `a basic material on a sprite node is drawn like a sprite material`() {
        val sprite = Sprite(SpriteMaterial())
        scene.add(sprite)
        scene.add(
            app.zoeshorsefarm.scene.graph
                .Mesh(box(), BasicMaterial()),
        )
        frame()
        assertEquals(2, live().size)
    }
}
