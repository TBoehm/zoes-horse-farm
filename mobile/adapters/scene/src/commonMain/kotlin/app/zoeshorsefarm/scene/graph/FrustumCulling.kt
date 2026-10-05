package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.math.Frustum
import app.zoeshorsefarm.scene.math.Sphere
import app.zoeshorsefarm.scene.math.Vec2

private val cullSphere = Sphere()
private val spriteDefaultCenter = Vec2(0.5, 0.5)

/**
 * Does the world-space bounding sphere of a mesh, points object or sprite touch the frustum
 * (three.js `Frustum.intersectsObject` / `intersectsSprite`)? A mesh uses its own `boundingSphere`
 * (instanced and skinned meshes) or else the one of its geometry, computed on first use. Other
 * nodes (groups, lights) always count as visible. `matrixWorld` must be up to date.
 */
fun Frustum.intersectsObject(node: Node): Boolean {
    when (node) {
        is Sprite -> {
            cullSphere.center.set(0.0, 0.0, 0.0)
            cullSphere.radius = 0.7071067811865476 + spriteDefaultCenter.distanceTo(node.center)
        }

        is Mesh -> {
            val own = node.boundingSphere
            val sphere =
                own ?: node.geometry.boundingSphere ?: run {
                    node.geometry.computeBoundingSphere()
                    node.geometry.boundingSphere
                } ?: return true
            cullSphere.copy(sphere)
        }

        is Points -> {
            val sphere =
                node.geometry.boundingSphere ?: run {
                    node.geometry.computeBoundingSphere()
                    node.geometry.boundingSphere
                } ?: return true
            cullSphere.copy(sphere)
        }

        else -> {
            return true
        }
    }
    cullSphere.applyMatrix4(node.matrixWorld)
    return intersectsSphere(cullSphere)
}

/**
 * Every GPU object a scene holds: geometries, materials with their textures, instanced meshes,
 * skeleton bone data, shadow maps and the environment light; plus `extras` (objects held outside of
 * the scene, such as an environment that is not set yet). Objects are listed once.
 */
fun collectGpuObjects(
    scene: Scene,
    extras: List<GpuObject> = emptyList(),
): List<GpuObject> {
    val found = LinkedHashSet<GpuObject>()
    scene.traverse { node ->
        if (node is Mesh) {
            found.add(node.geometry)
            node.forEachMaterial { material ->
                found.add(material)
                found.addAll(material.textures())
            }
            if (node is InstancedMesh) found.add(node)
            if (node is SkinnedMesh) node.skeleton?.let { found.add(it.boneTexture) }
        } else if (node is Points) {
            found.add(node.geometry)
            found.add(node.material)
            found.addAll(node.material.textures())
        } else if (node is Sprite) {
            found.add(node.material)
            found.addAll(node.material.textures())
        } else if (node is DirectionalLight) {
            node.shadow.map?.let { found.add(it) }
        }
    }
    scene.environment?.let { found.add(it) }
    found.addAll(extras)
    return found.toList()
}
