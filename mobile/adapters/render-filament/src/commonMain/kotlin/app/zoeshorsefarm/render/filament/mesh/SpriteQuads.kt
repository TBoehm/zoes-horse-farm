package app.zoeshorsefarm.render.filament.mesh

/**
 * Camera facing particles for the `SPRITE` material (the hoof dust; `THREE.Points` in the web app).
 * Filament has no point size, so a particle is a quad of four vertices that all sit at the particle
 * centre; the vertex shader moves them apart along the camera axes by `size / 2`:
 *
 *  - `POSITION`: the centre, four times
 *  - `CUSTOM0` (float2): the corner, (-1,-1) (1,-1) (1,1) (-1,1)
 *  - `CUSTOM1` (float2): size in metres and opacity, four times
 *
 * The draw call covers all `capacity` particles; unused ones have size 0 (collapsed quads).
 */
object SpriteQuads {
    private const val VERTICES_PER_QUAD = 4
    private const val INDICES_PER_QUAD = 6
    private val CORNERS = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)

    /** More quads would need vertex indices above 65535. */
    const val MAX_CAPACITY = 65536 / VERTICES_PER_QUAD - 1

    fun meshData(capacity: Int): MeshData {
        require(capacity in 1..MAX_CAPACITY) { "capacity must be 1 to $MAX_CAPACITY" }
        val corners = FloatArray(capacity * VERTICES_PER_QUAD * 2)
        val indices = IntArray(capacity * INDICES_PER_QUAD)
        for (q in 0 until capacity) {
            CORNERS.copyInto(corners, q * CORNERS.size)
            val v = q * VERTICES_PER_QUAD
            val i = q * INDICES_PER_QUAD
            indices[i] = v
            indices[i + 1] = v + 1
            indices[i + 2] = v + 2
            indices[i + 3] = v
            indices[i + 4] = v + 2
            indices[i + 5] = v + 3
        }
        return MeshData(
            positions = FloatArray(capacity * VERTICES_PER_QUAD * 3),
            custom =
                listOf(
                    CustomAttribute(slot = 0, components = 2, data = corners),
                    CustomAttribute(slot = 1, components = 2, data = FloatArray(capacity * VERTICES_PER_QUAD * 2)),
                ),
            indices = indices,
        )
    }

    /** Writes the `count` centres (xyz each) four times into `out` (the positions of the quads). */
    fun expandCenters(
        centers: FloatArray,
        count: Int,
        out: FloatArray,
    ) {
        for (p in 0 until count) {
            for (c in 0 until VERTICES_PER_QUAD) {
                val o = (p * VERTICES_PER_QUAD + c) * 3
                out[o] = centers[p * 3]
                out[o + 1] = centers[p * 3 + 1]
                out[o + 2] = centers[p * 3 + 2]
            }
        }
    }

    /** Writes size and opacity of `count` particles four times into `out` (`CUSTOM1`). */
    fun expandPuffs(
        sizes: FloatArray,
        opacities: FloatArray,
        count: Int,
        out: FloatArray,
    ) {
        for (p in 0 until count) {
            for (c in 0 until VERTICES_PER_QUAD) {
                val o = (p * VERTICES_PER_QUAD + c) * 2
                out[o] = sizes[p]
                out[o + 1] = opacities[p]
            }
        }
    }
}
