package app.zoeshorsefarm.render.filament.mesh

/**
 * One draw call of a renderable: `count` indices from `start` of the index buffer (vertices for a mesh
 * without indices) drawn with the material at `materialIndex`. Both are multiples of 3.
 */
class PrimitiveRange(
    val start: Int,
    val count: Int,
    val materialIndex: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is PrimitiveRange && other.start == start && other.count == count && other.materialIndex == materialIndex

    override fun hashCode(): Int = (start * HASH_PRIME + count) * HASH_PRIME + materialIndex

    override fun toString(): String = "PrimitiveRange($start, $count, material $materialIndex)"

    private companion object {
        const val HASH_PRIME = 31
    }
}
