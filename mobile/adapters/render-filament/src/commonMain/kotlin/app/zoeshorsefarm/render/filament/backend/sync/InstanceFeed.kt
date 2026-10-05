package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.GpuDevice
import app.zoeshorsefarm.render.filament.backend.device.InstanceDataHandle
import app.zoeshorsefarm.render.filament.backend.mapping.InstanceMatrices
import app.zoeshorsefarm.render.filament.mesh.Aabb
import app.zoeshorsefarm.scene.graph.InstancedMesh
import kotlin.math.max
import kotlin.math.min

/**
 * The instance data texture of one `InstancedMesh`: matrices (and colours) are uploaded when
 * `instanceMatrix` / `instanceColor` are marked `needsUpdate`, when more instances become visible
 * (`count` grew) or when the mesh node moved. The texture holds world matrices, so a mesh that is not
 * at the origin has its matrices multiplied by its world matrix before the upload.
 */
internal class InstanceFeed(
    val mesh: InstancedMesh,
) {
    var data: InstanceDataHandle? = null
        private set

    /** The box around all visible instances, valid after [upload] with `wantBounds`. */
    val bounds = Aabb()
    var boundsVersion = 0
        private set

    private val world = FloatArray(MATRIX_SIZE)
    private val lastWorld = FloatArray(MATRIX_SIZE)
    private var baked: FloatArray? = null
    private var matrixVersion = -1
    private var colorVersion = -1
    private var hadColors = false
    private var uploadedCount = 0
    private var boundsCurrent = false

    /** Instances that are drawn. */
    val count: Int get() = max(0, min(mesh.count, mesh.capacity))

    val uploadOverflows: Int get() = data?.uploadOverflows ?: 0

    /** The data texture, made on first use; a fresh texture is filled by the next [upload]. */
    fun ensure(device: GpuDevice): InstanceDataHandle {
        data?.let { return it }
        val created = device.createInstanceData(mesh.name.ifEmpty { "instances" }, mesh.capacity)
        data = created
        matrixVersion = -1
        colorVersion = -1
        uploadedCount = 0
        boundsCurrent = false
        return created
    }

    /**
     * Uploads what changed. With `wantBounds` the box around the instances is kept current (the
     * renderable culls all instances with one box). Returns true if the bounds changed.
     */
    fun upload(
        device: GpuDevice,
        geometryBounds: Aabb,
        wantBounds: Boolean,
    ): Boolean {
        val handle = ensure(device)
        val instanceCount = count
        mesh.matrixWorld.toFloatArray(world)
        val colors = mesh.instanceColor
        val moved = !world.contentEquals(lastWorld)
        val dirty =
            mesh.instanceMatrix.version != matrixVersion ||
                (colors?.version ?: NO_VERSION) != colorVersion ||
                (colors != null) != hadColors ||
                instanceCount > uploadedCount ||
                moved
        var changed = false
        if (dirty && instanceCount > 0) {
            val source = worldMatrices(instanceCount)
            handle.update(source, colors?.array, 0, instanceCount)
            matrixVersion = mesh.instanceMatrix.version
            colorVersion = colors?.version ?: NO_VERSION
            hadColors = colors != null
            uploadedCount = instanceCount
            world.copyInto(lastWorld)
            boundsCurrent = false
        }
        if (wantBounds && !boundsCurrent && instanceCount > 0) {
            bounds.copyFrom(Aabb.ofInstances(geometryBounds, worldMatrices(instanceCount), instanceCount))
            boundsVersion++
            boundsCurrent = true
            changed = true
        }
        return changed
    }

    /** Frees the data texture (the mesh was disposed); the next [ensure] makes a new one. */
    fun release() {
        data?.destroy()
        data = null
    }

    private fun worldMatrices(instanceCount: Int): FloatArray {
        if (InstanceMatrices.isIdentity(world)) return mesh.instanceMatrix.array
        val out =
            baked?.takeIf { it.size >= mesh.capacity * MATRIX_SIZE }
                ?: FloatArray(mesh.capacity * MATRIX_SIZE).also { baked = it }
        InstanceMatrices.bake(world, mesh.instanceMatrix.array, instanceCount, out)
        return out
    }

    private companion object {
        const val MATRIX_SIZE = 16
        const val NO_VERSION = -2
    }
}
