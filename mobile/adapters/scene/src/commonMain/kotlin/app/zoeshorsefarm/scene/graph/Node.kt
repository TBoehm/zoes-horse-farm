package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.math.Euler
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Quat
import app.zoeshorsefarm.scene.math.Vec3
import app.zoeshorsefarm.scene.render.RenderBackend

/** three.js name for [Node]: `THREE.Object3D` ports to `Object3D`. */
typealias Object3D = Node

/**
 * A node of the scene graph (three.js `Object3D`): position, rotation (as [quaternion] and [rotation],
 * which stay in sync), scale, children and the world matrix. Renderable subclasses are [Mesh],
 * [InstancedMesh], [SkinnedMesh], [Points] and [Sprite]; [Group] and [Bone] are plain containers.
 *
 * Thread confinement: the scene model is confined to one thread (hot paths share scratch objects, like
 * three.js). Do not touch it from two threads at the same time.
 */
@Suppress("TooManyFunctions") // mirrors the three.js Object3D API
open class Node : Traversable {
    var name: String = ""
    var parent: Node? = null
        private set

    private val childList = ArrayList<Node>()
    val children: List<Node> get() = childList

    val up: Vec3 = DEFAULT_UP.clone()
    val position: Vec3 = Vec3()
    val rotation: Euler = Euler()
    val quaternion: Quat = Quat()
    val scale: Vec3 = Vec3(1.0, 1.0, 1.0)

    val matrix: Mat4 = Mat4()
    val matrixWorld: Mat4 = Mat4()
    var matrixAutoUpdate: Boolean = true
    var matrixWorldAutoUpdate: Boolean = true
    var matrixWorldNeedsUpdate: Boolean = false

    var visible: Boolean = true
    var castShadow: Boolean = false
    var receiveShadow: Boolean = false
    var frustumCulled: Boolean = true
    var renderOrder: Int = 0

    /** Free-form data of the view code (three.js `userData`). */
    val userData: MutableMap<String, Any?> = HashMap()

    /** Called by the render backend right before the object is drawn (hoof dust: pixel scale of the camera). */
    var onBeforeRender: ((RenderBackend, Scene, Camera) -> Unit)? = null

    init {
        rotation.onChange = { quaternion.setFromEuler(rotation, false) }
        quaternion.onChange = { rotation.setFromQuaternion(quaternion, rotation.order, false) }
    }

    fun applyMatrix4(m: Mat4) {
        if (matrixAutoUpdate) updateMatrix()
        matrix.premultiply(m)
        matrix.decompose(position, quaternion, scale)
    }

    fun applyQuaternion(q: Quat): Node {
        quaternion.premultiply(q)
        return this
    }

    fun setRotationFromAxisAngle(
        axis: Vec3,
        angle: Double,
    ) {
        quaternion.setFromAxisAngle(axis, angle)
    }

    fun setRotationFromEuler(euler: Euler) {
        quaternion.setFromEuler(euler, true)
    }

    fun setRotationFromMatrix(m: Mat4) {
        quaternion.setFromRotationMatrix(m)
    }

    fun setRotationFromQuaternion(q: Quat) {
        quaternion.copy(q)
    }

    fun rotateOnAxis(
        axis: Vec3,
        angle: Double,
    ): Node {
        q1.setFromAxisAngle(axis, angle)
        quaternion.multiply(q1)
        return this
    }

    fun rotateOnWorldAxis(
        axis: Vec3,
        angle: Double,
    ): Node {
        q1.setFromAxisAngle(axis, angle)
        quaternion.premultiply(q1)
        return this
    }

    fun rotateX(angle: Double): Node = rotateOnAxis(xAxis, angle)

    fun rotateY(angle: Double): Node = rotateOnAxis(yAxis, angle)

    fun rotateZ(angle: Double): Node = rotateOnAxis(zAxis, angle)

    fun translateOnAxis(
        axis: Vec3,
        distance: Double,
    ): Node {
        v1.copy(axis).applyQuaternion(quaternion)
        position.add(v1.multiplyScalar(distance))
        return this
    }

    fun translateX(distance: Double): Node = translateOnAxis(xAxis, distance)

    fun translateY(distance: Double): Node = translateOnAxis(yAxis, distance)

    fun translateZ(distance: Double): Node = translateOnAxis(zAxis, distance)

    fun localToWorld(vector: Vec3): Vec3 {
        updateWorldMatrix(updateParents = true, updateChildren = false)
        return vector.applyMatrix4(matrixWorld)
    }

    fun worldToLocal(vector: Vec3): Vec3 {
        updateWorldMatrix(updateParents = true, updateChildren = false)
        return vector.applyMatrix4(m1.copy(matrixWorld).invert())
    }

    /** Turns the node so that its +Z (cameras and lights: -Z) points at the world point. */
    fun lookAt(
        x: Double,
        y: Double,
        z: Double,
    ) {
        target.set(x, y, z)
        lookAtTarget()
    }

    fun lookAt(v: Vec3) {
        target.copy(v)
        lookAtTarget()
    }

    private fun lookAtTarget() {
        val parentNode = parent
        updateWorldMatrix(updateParents = true, updateChildren = false)
        tmpPosition.setFromMatrixPosition(matrixWorld)
        if (this is Camera || this is Light) {
            m1.lookAt(tmpPosition, target, up)
        } else {
            m1.lookAt(target, tmpPosition, up)
        }
        quaternion.setFromRotationMatrix(m1)
        if (parentNode != null) {
            m1.extractRotation(parentNode.matrixWorld)
            q1.setFromRotationMatrix(m1)
            quaternion.premultiply(q1.invert())
        }
    }

    fun add(child: Node): Node {
        require(child !== this) { "a node cannot be a child of itself" }
        child.removeFromParent()
        child.parent = this
        childList.add(child)
        return this
    }

    fun add(vararg nodes: Node): Node {
        for (node in nodes) add(node)
        return this
    }

    fun remove(child: Node): Node {
        val index = childList.indexOf(child)
        if (index != -1) {
            child.parent = null
            childList.removeAt(index)
        }
        return this
    }

    fun removeFromParent(): Node {
        parent?.remove(this)
        return this
    }

    /** Removes all children. */
    fun clear(): Node {
        for (child in childList) child.parent = null
        childList.clear()
        return this
    }

    /** Adds `child` and keeps its world transform. */
    fun attach(child: Node): Node {
        updateWorldMatrix(updateParents = true, updateChildren = false)
        m1.copy(matrixWorld).invert()
        val oldParent = child.parent
        if (oldParent != null) {
            oldParent.updateWorldMatrix(updateParents = true, updateChildren = false)
            m1.multiply(oldParent.matrixWorld)
        }
        child.applyMatrix4(m1)
        child.removeFromParent()
        child.parent = this
        childList.add(child)
        child.updateWorldMatrix(updateParents = false, updateChildren = true)
        return this
    }

    fun getObjectByName(name: String): Node? {
        if (this.name == name) return this
        for (child in childList) {
            val found = child.getObjectByName(name)
            if (found != null) return found
        }
        return null
    }

    fun getWorldPosition(target: Vec3): Vec3 {
        updateWorldMatrix(updateParents = true, updateChildren = false)
        return target.setFromMatrixPosition(matrixWorld)
    }

    fun getWorldQuaternion(target: Quat): Quat {
        updateWorldMatrix(updateParents = true, updateChildren = false)
        matrixWorld.decompose(tmpPosition, target, tmpScale)
        return target
    }

    fun getWorldScale(target: Vec3): Vec3 {
        updateWorldMatrix(updateParents = true, updateChildren = false)
        matrixWorld.decompose(tmpPosition, tmpQuaternion, target)
        return target
    }

    /** World direction of the local +Z axis. */
    open fun getWorldDirection(target: Vec3): Vec3 {
        updateWorldMatrix(updateParents = true, updateChildren = false)
        val e = matrixWorld.e
        return target.set(e[8], e[9], e[10]).normalize()
    }

    override fun traverse(callback: (Node) -> Unit) {
        callback(this)
        for (i in childList.indices) childList[i].traverse(callback)
    }

    /** Like [traverse] but skips invisible nodes and everything below them. */
    override fun traverseVisible(callback: (Node) -> Unit) {
        if (!visible) return
        callback(this)
        for (i in childList.indices) childList[i].traverseVisible(callback)
    }

    fun traverseAncestors(callback: (Node) -> Unit) {
        val p = parent ?: return
        callback(p)
        p.traverseAncestors(callback)
    }

    open fun updateMatrix() {
        matrix.compose(position, quaternion, scale)
        matrixWorldNeedsUpdate = true
    }

    open fun updateMatrixWorld(force: Boolean = false) {
        if (matrixAutoUpdate) updateMatrix()
        var forceChildren = force
        if (matrixWorldNeedsUpdate || force) {
            if (matrixWorldAutoUpdate) {
                val p = parent
                if (p == null) matrixWorld.copy(matrix) else matrixWorld.multiplyMatrices(p.matrixWorld, matrix)
            }
            matrixWorldNeedsUpdate = false
            forceChildren = true
        }
        for (i in childList.indices) childList[i].updateMatrixWorld(forceChildren)
    }

    open fun updateWorldMatrix(
        updateParents: Boolean,
        updateChildren: Boolean,
        force: Boolean = false,
    ) {
        val p = parent
        if (updateParents && p != null) p.updateWorldMatrix(updateParents = true, updateChildren = false)
        if (matrixAutoUpdate) updateMatrix()
        var forceChildren = force
        if (matrixWorldNeedsUpdate || force) {
            if (matrixWorldAutoUpdate) {
                if (p == null) matrixWorld.copy(matrix) else matrixWorld.multiplyMatrices(p.matrixWorld, matrix)
            }
            matrixWorldNeedsUpdate = false
            forceChildren = true
        }
        if (updateChildren) {
            for (i in childList.indices) childList[i].updateWorldMatrix(false, true, forceChildren)
        }
    }

    companion object {
        /** Default up axis (0, 1, 0) of new nodes; do not modify. */
        val DEFAULT_UP: Vec3 = Vec3(0.0, 1.0, 0.0)

        // Scratch objects (not thread-safe, like three.js).
        private val v1 = Vec3()
        private val q1 = Quat()
        private val m1 = Mat4()
        private val target = Vec3()
        private val tmpPosition = Vec3()
        private val tmpScale = Vec3()
        private val tmpQuaternion = Quat()
        private val xAxis = Vec3(1.0, 0.0, 0.0)
        private val yAxis = Vec3(0.0, 1.0, 0.0)
        private val zAxis = Vec3(0.0, 0.0, 1.0)
    }
}

/** A plain container (three.js `Group`). */
open class Group : Node()

/** A joint of a [Skeleton] (three.js `Bone`). */
class Bone : Node()
