package app.zoeshorsefarm.render.filament.backend.sync

import app.zoeshorsefarm.render.filament.backend.device.GpuDevice
import app.zoeshorsefarm.render.filament.backend.device.MeshHandle
import app.zoeshorsefarm.render.filament.backend.mapping.GeometryMapping
import app.zoeshorsefarm.render.filament.mesh.Aabb
import app.zoeshorsefarm.render.filament.mesh.PackOptions
import app.zoeshorsefarm.render.filament.mesh.VertexSemantic
import app.zoeshorsefarm.scene.DisposeListener
import app.zoeshorsefarm.scene.geometry.BufferAttribute
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry

/** A renderable built on the mesh of a geometry: told when that mesh is replaced or freed. */
internal interface GeometryUser {
    /** The old mesh is about to be destroyed: drop the renderable. */
    fun onGeometryReset()
}

/**
 * What a changed attribute means for the device copy: `semantic` is the vertex buffer to rewrite in
 * place, null if the attribute is not uploaded; attributes stored packed (colours, skin data) cannot
 * be rewritten and ask for a full upload.
 */
internal class AttributeRole(
    val semantic: VertexSemantic?,
    val fullUpload: Boolean,
) {
    companion object {
        private val IGNORED = AttributeRole(null, false)
        private val FULL = AttributeRole(null, true)

        fun of(
            name: String,
            attribute: BufferAttribute,
        ): AttributeRole =
            when (name) {
                "position" -> AttributeRole(VertexSemantic.POSITION, false)
                "normal" -> AttributeRole(VertexSemantic.TANGENTS, false)
                "uv" -> AttributeRole(VertexSemantic.UV0, false)
                "color", "skinIndex", "skinWeight" -> FULL
                else -> custom(name, attribute)
            }

        private fun custom(
            name: String,
            attribute: BufferAttribute,
        ): AttributeRole {
            val slot = GeometryMapping.customSlot(name)
            return if (slot != null && attribute is FloatAttribute) {
                AttributeRole(VertexSemantic.custom(slot), false)
            } else {
                IGNORED
            }
        }
    }
}

/** The device copy of one geometry. */
internal class GeometryEntry(
    val geometry: Geometry,
    var mesh: MeshHandle,
    var uvTangents: Boolean,
) {
    val users = ArrayList<GeometryUser>(2)

    /** The box of the current positions; changes when a dynamic position buffer is rewritten. */
    val bounds = Aabb().also { it.copyFrom(mesh.bounds) }
    var boundsVersion = 0

    var structureVersion = geometry.structureVersion
    var indexVersion = geometry.indexVersion
    var attributes: Array<BufferAttribute> = emptyArray()
    var roles: Array<AttributeRole> = emptyArray()
    var versions = IntArray(0)

    /** Attribute versions of the last upload or rewrite. */
    fun snapshot() {
        val list = geometry.attributes
        attributes = list.values.toTypedArray()
        versions = IntArray(attributes.size) { attributes[it].version }
        val names = list.keys.toTypedArray()
        roles = Array(names.size) { AttributeRole.of(names[it], attributes[it]) }
    }
}

/**
 * Uploads geometries when they are first drawn and keeps them in step with the scene object: an
 * attribute that is marked `needsUpdate` is rewritten in place (positions, normals, uvs and custom
 * attributes, which the reins and the grass change), anything that changes the buffer layout (an
 * attribute added or removed, a new index, colours, skin data, a different vertex count) uploads the
 * geometry again, and `free` is what `Geometry.dispose()` does: the device copy goes, the next use
 * uploads again.
 */
internal class GeometryRegistry(
    private val device: GpuDevice,
    private val disposeListener: DisposeListener,
    private val log: SyncLog,
) {
    private val entries = HashMap<Geometry, GeometryEntry>()
    private val failed = HashMap<Geometry, Long>()

    /** Geometries on the GPU. */
    val count: Int get() = entries.size

    /** GPU bytes of all meshes. */
    val byteSize: Long
        get() {
            var total = 0L
            for (entry in entries.values) total += entry.mesh.byteSize
            return total
        }

    val uploadOverflows: Int
        get() {
            var total = 0
            for (entry in entries.values) total += entry.mesh.uploadOverflows
            return total
        }

    /**
     * The device copy of `geometry`, or null if it cannot be drawn (no positions, or the upload was
     * refused). `uvTangents` asks for tangents that follow the uvs (normal maps); `needsNormals` makes
     * normals for a lit material when the geometry has none.
     */
    fun acquire(
        geometry: Geometry,
        uvTangents: Boolean,
        needsNormals: Boolean,
    ): GeometryEntry? {
        if (!GeometryMapping.isDrawable(geometry)) return null
        if (needsNormals && !geometry.hasAttribute("normal")) geometry.computeVertexNormals()
        return if (isRefused(geometry)) null else current(geometry, uvTangents)
    }

    private fun current(
        geometry: Geometry,
        uvTangents: Boolean,
    ): GeometryEntry? {
        val entry = entries[geometry] ?: return upload(geometry, uvTangents)
        // tangents that follow the uvs stay once a normal mapped material asked for them: users
        // with different needs would otherwise upload the geometry again every frame
        val tangents = uvTangents || entry.uvTangents
        return if (needsFullUpload(entry, tangents) || rewriteChanged(entry)) reupload(entry, tangents) else entry
    }

    /** Frees the device copy of a disposed geometry. */
    fun free(geometry: Geometry) {
        val entry = entries.remove(geometry) ?: return
        geometry.removeDisposeListener(disposeListener)
        destroy(entry)
    }

    fun clear() {
        for (entry in entries.values) {
            entry.geometry.removeDisposeListener(disposeListener)
            destroy(entry)
        }
        entries.clear()
        failed.clear()
    }

    private fun destroy(entry: GeometryEntry) {
        resetUsers(entry)
        entry.mesh.destroy()
    }

    private fun resetUsers(entry: GeometryEntry) {
        // a copy: the users unregister themselves while they drop their renderables
        val users = entry.users.toTypedArray()
        entry.users.clear()
        for (user in users) user.onGeometryReset()
    }

    private fun needsFullUpload(
        entry: GeometryEntry,
        uvTangents: Boolean,
    ): Boolean {
        val geometry = entry.geometry
        return geometry.structureVersion != entry.structureVersion ||
            geometry.indexVersion != entry.indexVersion ||
            (uvTangents && !entry.uvTangents) ||
            geometry.vertexCount != entry.mesh.vertexCount
    }

    private fun upload(
        geometry: Geometry,
        uvTangents: Boolean,
    ): GeometryEntry? {
        val mesh = create(geometry, uvTangents) ?: return null
        val entry = GeometryEntry(geometry, mesh, uvTangents)
        entry.snapshot()
        entries[geometry] = entry
        geometry.addDisposeListener(disposeListener)
        return entry
    }

    private fun reupload(
        entry: GeometryEntry,
        uvTangents: Boolean,
    ): GeometryEntry? {
        val geometry = entry.geometry
        val mesh = create(geometry, uvTangents)
        resetUsers(entry)
        entry.mesh.destroy()
        if (mesh == null) {
            entries.remove(geometry)
            geometry.removeDisposeListener(disposeListener)
            return null
        }
        entry.mesh = mesh
        entry.uvTangents = uvTangents
        entry.structureVersion = geometry.structureVersion
        entry.indexVersion = geometry.indexVersion
        entry.bounds.copyFrom(mesh.bounds)
        entry.boundsVersion++
        entry.snapshot()
        return entry
    }

    private fun create(
        geometry: Geometry,
        uvTangents: Boolean,
    ): MeshHandle? =
        try {
            val label = geometry.name.ifEmpty { "geometry" }
            device.createMesh(label, GeometryMapping.toMeshData(geometry), PackOptions(tangentsFromUvs = uvTangents))
        } catch (e: IllegalArgumentException) {
            failed[geometry] = versionKey(geometry)
            log.warn("geometry '${geometry.name}' cannot be uploaded: ${e.message}")
            null
        }

    private fun isRefused(geometry: Geometry): Boolean {
        val key = failed[geometry] ?: return false
        if (key == versionKey(geometry)) return true
        failed.remove(geometry)
        return false
    }

    private fun versionKey(geometry: Geometry): Long =
        geometry.structureVersion.toLong() * VERSION_STRIDE + geometry.indexVersion

    /** Rewrites the attributes marked as changed; true if one of them needs a full upload instead. */
    private fun rewriteChanged(entry: GeometryEntry): Boolean {
        var full = false
        for (i in entry.attributes.indices) {
            val attribute = entry.attributes[i]
            if (attribute.version == entry.versions[i]) continue
            entry.versions[i] = attribute.version
            val role = entry.roles[i]
            val semantic = role.semantic
            if (role.fullUpload) {
                full = true
            } else if (semantic != null) {
                rewrite(entry, attribute, semantic)
            }
        }
        return full
    }

    private fun rewrite(
        entry: GeometryEntry,
        attribute: BufferAttribute,
        semantic: VertexSemantic,
    ) {
        val floats = attribute as? FloatAttribute ?: return
        if (semantic == VertexSemantic.TANGENTS) {
            if (VertexSemantic.TANGENTS in entry.mesh.semantics) entry.mesh.updateNormals(floats.array)
            return
        }
        if (semantic !in entry.mesh.semantics) return
        entry.mesh.updateFloats(semantic, floats.array, floats.count * floats.itemSize)
        if (semantic == VertexSemantic.POSITION) {
            entry.bounds.setFromPositions(floats.array, floats.count)
            entry.boundsVersion++
        }
    }

    private companion object {
        const val VERSION_STRIDE = 1L shl 32
    }
}
