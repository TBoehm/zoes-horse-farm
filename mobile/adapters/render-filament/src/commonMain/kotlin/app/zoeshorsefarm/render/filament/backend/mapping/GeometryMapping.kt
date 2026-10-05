package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.mesh.CustomAttribute
import app.zoeshorsefarm.render.filament.mesh.MeshData
import app.zoeshorsefarm.scene.geometry.FloatAttribute
import app.zoeshorsefarm.scene.geometry.Geometry
import app.zoeshorsefarm.scene.geometry.UShortAttribute

/**
 * Turns a scene [Geometry] into the plain arrays of a [MeshData], which the mesh packer turns into
 * GPU buffers (16 or 32 bit indices by vertex count, tangent frames from the normals).
 *
 * The attributes of the shader effects are custom vertex attributes; their slots are fixed by the
 * Filament materials (see `MaterialSpec.requiredAttributes`):
 *
 * | scene attribute | slot | read by |
 * | --- | --- | --- |
 * | `aRest` (3), `aMat` (4), `aFace` (3) | 0, 1, 2 | horse coat |
 * | `petal` (1) | 0 | blossom wind |
 * | `aFlutter` (3) | 0 | bunting wind |
 */
object GeometryMapping {
    private val CUSTOM_SLOTS: List<Pair<String, Int>> =
        listOf("aRest" to 0, "aMat" to 1, "aFace" to 2, "petal" to 0, "aFlutter" to 0)

    /** The attributes the coat material needs. */
    val COAT_ATTRIBUTES: List<String> = listOf("aRest", "aMat", "aFace")

    /** The custom vertex slot of a known effect attribute, null for any other name. */
    fun customSlot(name: String): Int? {
        for (i in CUSTOM_SLOTS.indices) if (CUSTOM_SLOTS[i].first == name) return CUSTOM_SLOTS[i].second
        return null
    }

    /** Does the geometry have what can be drawn: positions (xyz) and at least one vertex? */
    fun isDrawable(geometry: Geometry): Boolean {
        val position = geometry.getAttribute("position") as? FloatAttribute ?: return false
        return position.itemSize == POSITION_COMPONENTS && position.count > 0
    }

    /**
     * The mesh arrays of `geometry`. Normals, colours (3 or 4 floats), uvs, skin data and the known
     * custom attributes are taken when present and well formed; anything else is left out.
     * Throws `IllegalArgumentException` if the geometry has no valid positions (check [isDrawable]).
     */
    fun toMeshData(geometry: Geometry): MeshData {
        val position = geometry.getAttribute("position") as? FloatAttribute
        require(position != null && position.itemSize == POSITION_COMPONENTS) { "geometry has no xyz positions" }
        val vertexCount = position.count
        val normals = floats(geometry, "normal", POSITION_COMPONENTS, vertexCount)
        val color3 = floats(geometry, "color", COLOR3, vertexCount)
        val color4 = if (color3 == null) floats(geometry, "color", COLOR4, vertexCount) else null
        val skinIndex =
            (geometry.getAttribute("skinIndex") as? UShortAttribute)
                ?.takeIf {
                    it.itemSize == SKIN_COMPONENTS && it.count == vertexCount
                }?.array
        val skinWeight = floats(geometry, "skinWeight", SKIN_COMPONENTS, vertexCount)
        val skinned = skinIndex != null && skinWeight != null
        return MeshData(
            positions = position.array,
            normals = normals,
            colors = color3 ?: color4,
            colorComponents = if (color4 != null) COLOR4 else COLOR3,
            uvs = floats(geometry, "uv", UV_COMPONENTS, vertexCount),
            custom = customAttributes(geometry, vertexCount),
            skinIndices = if (skinned) skinIndex else null,
            skinWeights = if (skinned) skinWeight else null,
            indices = geometry.index,
        )
    }

    private fun floats(
        geometry: Geometry,
        name: String,
        itemSize: Int,
        vertexCount: Int,
    ): FloatArray? {
        val attribute = geometry.getAttribute(name) as? FloatAttribute ?: return null
        return if (attribute.itemSize == itemSize && attribute.count == vertexCount) attribute.array else null
    }

    private fun customAttributes(
        geometry: Geometry,
        vertexCount: Int,
    ): List<CustomAttribute> {
        val result = ArrayList<CustomAttribute>(0)
        for ((name, slot) in CUSTOM_SLOTS) {
            val attribute = customAttribute(geometry, name, slot, vertexCount)
            if (attribute != null && result.none { it.slot == slot }) result += attribute
        }
        return result
    }

    private fun customAttribute(
        geometry: Geometry,
        name: String,
        slot: Int,
        vertexCount: Int,
    ): CustomAttribute? {
        val attribute = geometry.getAttribute(name) as? FloatAttribute ?: return null
        val fits = attribute.itemSize in 1..MAX_COMPONENTS && attribute.count == vertexCount
        return if (fits) CustomAttribute(slot, attribute.itemSize, attribute.array) else null
    }

    private const val POSITION_COMPONENTS = 3
    private const val UV_COMPONENTS = 2
    private const val COLOR3 = 3
    private const val COLOR4 = 4
    private const val SKIN_COMPONENTS = 4
    private const val MAX_COMPONENTS = 4
}
