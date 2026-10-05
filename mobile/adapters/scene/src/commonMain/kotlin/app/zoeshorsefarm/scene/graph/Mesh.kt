package app.zoeshorsefarm.scene.graph

import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.DisposeSupport
import app.zoeshorsefarm.scene.GpuObject
import app.zoeshorsefarm.scene.GpuResource
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.UShortAttribute
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.PointsMaterial
import app.zoeshorsefarm.scene.material.SpriteMaterial
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.Mat4
import app.zoeshorsefarm.scene.math.Sphere
import app.zoeshorsefarm.scene.math.Vec2
import app.zoeshorsefarm.scene.math.Vec3

/**
 * Triangles with one material (three.js `Mesh`). A mesh can also take a material list, then every
 * [Geometry.groups] entry is drawn with `materials[group.materialIndex]` (one draw call per group).
 */
open class Mesh(
    var geometry: Geometry,
    material: Material,
) : Node() {
    /** The material (the first one when a material list is used). */
    var material: Material = material
        set(value) {
            field = value
            materialArray = null
        }

    /** The material list when the mesh was built with one, else null. */
    var materialArray: List<Material>? = null
        private set

    /**
     * Bounds used for frustum culling instead of the geometry's (instanced and skinned meshes set them).
     * Null: the geometry's bounding sphere is used.
     */
    var boundingSphere: Sphere? = null

    constructor(geometry: Geometry, materials: List<Material>) : this(geometry, materials[0]) {
        materialArray = materials
    }

    /** Calls [block] for every material of the mesh. */
    fun forEachMaterial(block: (Material) -> Unit) {
        val list = materialArray
        if (list != null) list.forEach(block) else block(material)
    }
}

/**
 * One draw call for up to [capacity] copies of a geometry, each with its own matrix and optionally
 * colour (three.js `InstancedMesh`). Only the first [count] instances are drawn. After changing
 * matrices or colours set `instanceMatrix.needsUpdate` / `instanceColor.needsUpdate`.
 */
class InstancedMesh(
    geometry: Geometry,
    material: Material,
    val capacity: Int,
) : Mesh(geometry, material),
    GpuObject {
    private val support = DisposeSupport(this)

    /** 16 floats (column-major) per instance. */
    val instanceMatrix: FloatAttribute = FloatAttribute(FloatArray(capacity * 16), 16)

    /** 3 floats (linear RGB) per instance, created by the first [setColorAt]. */
    var instanceColor: FloatAttribute? = null
        private set

    /** Number of instances that are drawn (0..[capacity]). */
    var count: Int = capacity

    init {
        for (i in 0 until capacity) setMatrixAt(i, identity)
    }

    override val disposeCount: Int get() = support.count

    override fun addDisposeListener(listener: DisposeListener) = support.add(listener)

    override fun removeDisposeListener(listener: DisposeListener) = support.remove(listener)

    /** Frees the instance buffers on the GPU. */
    override fun dispose() = support.fire()

    fun setMatrixAt(
        index: Int,
        matrix: Mat4,
    ): InstancedMesh {
        matrix.toFloatArray(instanceMatrix.array, index * 16)
        return this
    }

    fun getMatrixAt(
        index: Int,
        matrix: Mat4,
    ): Mat4 = matrix.fromArray(instanceMatrix.array, index * 16)

    fun setColorAt(
        index: Int,
        color: Color,
    ): InstancedMesh {
        val attribute =
            instanceColor
                ?: FloatAttribute(FloatArray(capacity * 3).also { it.fill(1f) }, 3).also { instanceColor = it }
        attribute.array[index * 3] = color.r.toFloat()
        attribute.array[index * 3 + 1] = color.g.toFloat()
        attribute.array[index * 3 + 2] = color.b.toFloat()
        return this
    }

    fun getColorAt(
        index: Int,
        color: Color,
    ): Color {
        val attribute = instanceColor ?: return color.setRGB(1.0, 1.0, 1.0)
        return color.fromArray(attribute.array, index * 3)
    }

    /** Union of the geometry's bounding sphere under every instance matrix (first [count] instances). */
    fun computeBoundingSphere() {
        if (geometry.boundingSphere == null) geometry.computeBoundingSphere()
        val geometrySphere = geometry.boundingSphere ?: return
        val result = boundingSphere ?: Sphere().also { boundingSphere = it }
        result.makeEmpty()
        for (i in 0 until count) {
            getMatrixAt(i, scratchMatrix)
            scratchSphere.copy(geometrySphere).applyMatrix4(scratchMatrix)
            result.union(scratchSphere)
        }
    }

    private companion object {
        val identity = Mat4()
        val scratchMatrix = Mat4()
        val scratchSphere = Sphere()
    }
}

/** The GPU-side bone matrices of a [Skeleton] (three.js `skeleton.boneTexture`); dispose it to free them. */
class BoneTexture : GpuResource()

/**
 * Bones and their inverse bind matrices (three.js `Skeleton`). [update] recomputes [boneMatrices]
 * (`boneWorld * boneInverse`, 16 floats per bone) which the backend uploads before drawing.
 */
class Skeleton(
    bones: List<Bone>,
    boneInverses: List<Mat4> = emptyList(),
) {
    val bones: List<Bone> = bones.toList()
    val boneInverses: MutableList<Mat4> = boneInverses.toMutableList()
    val boneMatrices: FloatArray = FloatArray(this.bones.size * 16)

    /** GPU handle of the bone matrices: dispose it to free them, the backend builds them again on use. */
    val boneTexture: BoneTexture = BoneTexture()

    /** Incremented by every [update]: the backend uploads [boneMatrices] when it changes. */
    var version: Int = 0
        private set

    init {
        if (this.boneInverses.isEmpty()) {
            calculateInverses()
        } else if (this.bones.size != this.boneInverses.size) {
            this.boneInverses.clear()
            for (i in this.bones.indices) this.boneInverses.add(Mat4())
        }
    }

    /** Inverse bind matrices from the current world matrices of the bones. */
    fun calculateInverses() {
        boneInverses.clear()
        for (bone in bones) boneInverses.add(Mat4().copy(bone.matrixWorld).invert())
    }

    /** Resets the bones to their bind pose. */
    fun pose() {
        for (i in bones.indices) bones[i].matrixWorld.copy(boneInverses[i]).invert()
        for (bone in bones) {
            val parentBone = bone.parent as? Bone
            if (parentBone != null) {
                bone.matrix.copy(parentBone.matrixWorld).invert()
                bone.matrix.multiply(bone.matrixWorld)
            } else {
                bone.matrix.copy(bone.matrixWorld)
            }
            bone.matrix.decompose(bone.position, bone.quaternion, bone.scale)
        }
    }

    /** Computes [boneMatrices] from the world matrices of the bones. */
    fun update() {
        for (i in bones.indices) {
            offset.multiplyMatrices(bones[i].matrixWorld, boneInverses[i])
            offset.toFloatArray(boneMatrices, i * 16)
        }
        version++
    }

    fun getBoneByName(name: String): Bone? = bones.firstOrNull { it.name == name }

    private companion object {
        val offset = Mat4()
    }
}

/** A mesh deformed by a [Skeleton] through `skinIndex` (4 bone indices) and `skinWeight` attributes. */
class SkinnedMesh(
    geometry: Geometry,
    material: Material,
) : Mesh(geometry, material) {
    /** Always `"attached"`: the bind matrix inverse follows the mesh's world matrix. */
    val bindMatrix: Mat4 = Mat4()
    val bindMatrixInverse: Mat4 = Mat4()
    var skeleton: Skeleton? = null
        private set

    /**
     * Binds the skeleton. Without `bindMatrix` the current world matrices become the bind pose;
     * with it (the web code always passes one) the inverse bone matrices stay as they are.
     */
    fun bind(
        skeleton: Skeleton,
        bindMatrix: Mat4? = null,
    ) {
        this.skeleton = skeleton
        val bind =
            if (bindMatrix == null) {
                updateMatrixWorld(true)
                skeleton.calculateInverses()
                matrixWorld
            } else {
                bindMatrix
            }
        this.bindMatrix.copy(bind)
        bindMatrixInverse.copy(bind).invert()
    }

    override fun updateMatrixWorld(force: Boolean) {
        super.updateMatrixWorld(force)
        bindMatrixInverse.copy(matrixWorld).invert()
    }

    /** Position of vertex `index` after skinning, in the mesh's local space (three.js `getVertexPosition`). */
    fun getVertexPosition(
        index: Int,
        target: Vec3,
    ): Vec3 {
        val position = geometry.position
        target.set(position.getX(index), position.getY(index), position.getZ(index))
        return applyBoneTransform(index, target)
    }

    fun applyBoneTransform(
        index: Int,
        target: Vec3,
    ): Vec3 {
        val skeleton = skeleton ?: return target
        val skinIndex = geometry.getAttribute("skinIndex") as UShortAttribute
        val skinWeight = geometry.float("skinWeight")
        base.copy(target)
        target.set(0.0, 0.0, 0.0)
        base.applyMatrix4(bindMatrix)
        for (i in 0 until 4) {
            val weight = skinWeight.array[index * 4 + i].toDouble()
            if (weight != 0.0) {
                val boneIndex = skinIndex.array[index * 4 + i].toInt() and 0xFFFF
                matrix4.multiplyMatrices(skeleton.bones[boneIndex].matrixWorld, skeleton.boneInverses[boneIndex])
                target.addScaledVector(tmp.copy(base).applyMatrix4(matrix4), weight)
            }
        }
        return target.applyMatrix4(bindMatrixInverse)
    }

    /** Bounding sphere of the skinned vertices in the current pose (one pass over all vertices). */
    fun computeBoundingSphere() {
        val result = boundingSphere ?: Sphere().also { boundingSphere = it }
        result.makeEmpty()
        val count = geometry.vertexCount
        for (i in 0 until count) result.expandByPoint(getVertexPosition(i, vertex))
    }

    private companion object {
        val base = Vec3()
        val tmp = Vec3()
        val vertex = Vec3()
        val matrix4 = Mat4()
    }
}

/** Point sprites: one point per vertex (three.js `Points`). Counts as one draw call without triangles. */
class Points(
    var geometry: Geometry,
    var material: Material = PointsMaterial(),
) : Node()

/**
 * A camera-facing quad (three.js `Sprite`): [center] is the pivot (0..1) in the quad, [scale] its size
 * in world units. Counts as one draw call with two triangles.
 */
class Sprite(
    var material: SpriteMaterial = SpriteMaterial(),
) : Node() {
    val center: Vec2 = Vec2(0.5, 0.5)
}
