package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.GpuResource
import app.zoeshorsefarm.scene.geometry.BoxGeometry
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.material.BasicMaterial
import app.zoeshorsefarm.scene.material.PointsMaterial
import app.zoeshorsefarm.scene.material.SpriteMaterial
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.math.Frustum
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Sphere
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.texture.RgbaImage
import app.zoeshorsefarm.scene.texture.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrustumCullingTest {
    private fun frustum(): Frustum {
        val camera = PerspectiveCamera(50.0, 16.0 / 9.0, 0.1, 500.0)
        camera.position.set(3.0, 4.0, 10.0)
        camera.lookAt(0.0, 1.0, 0.0)
        camera.updateMatrixWorld(true)
        return Frustum().setFromProjectionMatrix(
            Mat4().multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse),
        )
    }

    private fun meshAt(
        x: Double,
        y: Double,
        z: Double,
    ): Mesh {
        val mesh = Mesh(BoxGeometry(1.0, 1.0, 1.0), BasicMaterial())
        mesh.position.set(x, y, z)
        mesh.updateMatrixWorld(true)
        return mesh
    }

    @Test
    fun `meshes are culled by the bounding sphere of their geometry`() {
        val f = frustum()
        assertTrue(f.intersectsObject(meshAt(0.0, 1.0, 0.0)))
        assertFalse(f.intersectsObject(meshAt(100.0, 1.0, 0.0)))
        assertFalse(f.intersectsObject(meshAt(0.0, 1.0, 100.0)))
    }

    @Test
    fun `an own bounding sphere wins over the geometry`() {
        val f = frustum()
        val mesh = meshAt(0.0, 1.0, 0.0)
        mesh.boundingSphere = Sphere(Vec3(500.0, 0.0, 0.0), 1.0)
        assertFalse(f.intersectsObject(mesh))
    }

    @Test
    fun `sprites points and groups`() {
        val f = frustum()
        val sprite = Sprite(SpriteMaterial())
        sprite.position.set(0.0, 1.0, 0.0)
        sprite.updateMatrixWorld(true)
        assertTrue(f.intersectsObject(sprite))
        sprite.position.set(300.0, 1.0, 0.0)
        sprite.updateMatrixWorld(true)
        assertFalse(f.intersectsObject(sprite))
        val geometry = Geometry()
        geometry.setAttribute("position", FloatAttribute(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), 3))
        val points = Points(geometry, PointsMaterial())
        points.position.set(0.0, 1.0, 0.0)
        points.updateMatrixWorld(true)
        assertTrue(f.intersectsObject(points))
        assertTrue(f.intersectsObject(Group()))
    }

    @Test
    fun `collectGpuObjects lists geometries materials textures instanced meshes bone data and shadow maps once`() {
        val scene = Scene()
        val texture = Texture(RgbaImage(1, 1))
        val geometry = BoxGeometry()
        val material = StandardMaterial(map = texture)
        scene.add(Mesh(geometry, material), Mesh(geometry, material))
        val instanced = InstancedMesh(geometry, material, 2)
        scene.add(instanced)
        val sun = DirectionalLight()
        val shadowMap = object : GpuResource() {}
        sun.shadow.map = shadowMap
        scene.add(sun)
        val bone = Bone()
        val skinned = SkinnedMesh(Geometry(), BasicMaterial())
        val skeleton = Skeleton(listOf(bone))
        scene.add(bone, skinned)
        skinned.bind(skeleton, Mat4())
        val objects = collectGpuObjects(scene)
        assertTrue(geometry in objects && material in objects && texture in objects && instanced in objects)
        assertTrue(skeleton.boneTexture in objects && shadowMap in objects)
        assertEquals(objects.size, objects.toSet().size)
        assertEquals(1, objects.count { it === geometry })
    }
}
