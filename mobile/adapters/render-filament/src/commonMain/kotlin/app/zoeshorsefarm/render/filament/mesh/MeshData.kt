package app.zoeshorsefarm.render.filament.mesh

/** Extra per vertex data for materials (`CUSTOM0..7`), 1 to 4 floats per vertex. */
class CustomAttribute(
    val slot: Int,
    val components: Int,
    val data: FloatArray,
) {
    init {
        require(slot in 0 until MAX_CUSTOM_SLOTS) { "custom slot $slot is outside 0..${MAX_CUSTOM_SLOTS - 1}" }
        require(components in 1..4) { "custom attribute needs 1 to 4 components, not $components" }
    }

    companion object {
        const val MAX_CUSTOM_SLOTS = 8
    }
}

/**
 * Plain arrays of one mesh, as a renderer neutral geometry holds them. Validated on creation, so
 * the packer and the GPU upload can trust the sizes.
 *
 * - `positions`: xyz per vertex.
 * - `normals`: xyz per vertex (unit length); becomes the tangent frame.
 * - `colors`: `colorComponents` (3 or 4) linear floats per vertex.
 * - `uvs`: uv per vertex.
 * - `skinIndices` / `skinWeights`: four bone indices and four weights per vertex, both or none.
 * - `indices`: triangle list; null draws consecutive vertices.
 */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray? = null,
    val colors: FloatArray? = null,
    val colorComponents: Int = 3,
    val uvs: FloatArray? = null,
    val custom: List<CustomAttribute> = emptyList(),
    val skinIndices: ShortArray? = null,
    val skinWeights: FloatArray? = null,
    val indices: IntArray? = null,
) {
    val vertexCount: Int

    init {
        require(positions.size % 3 == 0) { "positions must hold xyz triples, size ${positions.size}" }
        vertexCount = positions.size / 3
        require(normals == null || normals.size == vertexCount * 3) { "normals must hold one xyz per vertex" }
        require(colorComponents == 3 || colorComponents == 4) { "colorComponents must be 3 or 4" }
        require(colors == null || colors.size == vertexCount * colorComponents) {
            "colors must hold $colorComponents floats per vertex"
        }
        require(uvs == null || uvs.size == vertexCount * 2) { "uvs must hold one uv per vertex" }
        require((skinIndices == null) == (skinWeights == null)) { "skin indices and weights must come together" }
        require(skinIndices == null || skinIndices.size == vertexCount * 4) { "skin indices need 4 per vertex" }
        require(skinWeights == null || skinWeights.size == vertexCount * 4) { "skin weights need 4 per vertex" }
        require(custom.map { it.slot }.toSet().size == custom.size) { "custom attribute slots must be unique" }
        for (attribute in custom) {
            require(attribute.data.size == vertexCount * attribute.components) {
                "custom${attribute.slot} must hold ${attribute.components} floats per vertex"
            }
        }
        if (indices != null) {
            for (index in indices) {
                require(index in 0 until vertexCount) { "index $index is outside 0..${vertexCount - 1}" }
            }
        }
    }

    val isSkinned: Boolean get() = skinIndices != null
}
