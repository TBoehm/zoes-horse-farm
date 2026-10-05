package app.zoeshorsefarm.render.filament.mesh

import kotlin.math.max

/**
 * Per instance data (transform and colour) in a float data texture.
 *
 * Filament's `InstanceBuffer` holds at most `Engine.maxAutomaticInstances` (64 on most platforms)
 * transforms, far fewer than the web app draws in one call (11 000 grass tufts). Plain
 * `instances(count)` has no limit (32 767) but every instance would sit at the same place, so the
 * instance material reads its own data from this texture with `getInstanceIndex()`.
 *
 * Layout, one RGBA32F texel = 4 floats: an instance takes 4 texels in one row, 256 instances share a
 * row of [TEXTURE_WIDTH] texels.
 *
 *  - texel 0, 1, 2: rows 0, 1, 2 of the affine 4x4 matrix (x, y, z axis components and translation)
 *  - texel 3: colour (rgb linear, a)
 */
object InstanceData {
    const val TEXELS_PER_INSTANCE = 4
    const val INSTANCES_PER_ROW = 256
    const val TEXTURE_WIDTH = INSTANCES_PER_ROW * TEXELS_PER_INSTANCE

    /** Every GLES 3.0 / Metal device guarantees at least 2048 texels per side. */
    const val MAX_ROWS = 2048
    const val MAX_INSTANCES = MAX_ROWS * INSTANCES_PER_ROW
    private const val FLOATS_PER_TEXEL = 4
    private const val BYTES_PER_TEXEL = 16

    fun rowsFor(capacity: Int): Int {
        require(capacity in 0..MAX_INSTANCES) { "capacity $capacity is outside 0..$MAX_INSTANCES" }
        return max(1, (capacity + INSTANCES_PER_ROW - 1) / INSTANCES_PER_ROW)
    }

    /** Floats of the whole texture for this capacity. */
    fun floatCount(capacity: Int): Int = TEXTURE_WIDTH * rowsFor(capacity) * FLOATS_PER_TEXEL

    /** GPU bytes of the texture, for the memory estimate (no mipmaps, RGBA32F). */
    fun byteSize(capacity: Int): Long = TEXTURE_WIDTH.toLong() * rowsFor(capacity) * BYTES_PER_TEXEL

    /**
     * Writes the instances `from until to` into `out` (the whole texture as floats). Matrices are
     * column-major (16 floats per instance, indexed by the absolute instance number), colours have
     * `colorComponents` (3 or 4) floats per instance or are null for white.
     */
    fun pack(
        out: FloatArray,
        matrices: FloatArray,
        colors: FloatArray?,
        colorComponents: Int,
        from: Int,
        to: Int,
    ) {
        require(colorComponents == 3 || colorComponents == 4) { "colorComponents must be 3 or 4" }
        for (i in from until to) {
            val base =
                ((i / INSTANCES_PER_ROW) * TEXTURE_WIDTH + (i % INSTANCES_PER_ROW) * TEXELS_PER_INSTANCE) *
                    FLOATS_PER_TEXEL
            val m = i * 16
            for (row in 0..2) {
                val o = base + row * FLOATS_PER_TEXEL
                out[o] = matrices[m + row]
                out[o + 1] = matrices[m + 4 + row]
                out[o + 2] = matrices[m + 8 + row]
                out[o + 3] = matrices[m + 12 + row]
            }
            val c = base + 3 * FLOATS_PER_TEXEL
            if (colors == null) {
                out[c] = 1f
                out[c + 1] = 1f
                out[c + 2] = 1f
                out[c + 3] = 1f
            } else {
                val s = i * colorComponents
                out[c] = colors[s]
                out[c + 1] = colors[s + 1]
                out[c + 2] = colors[s + 2]
                out[c + 3] = if (colorComponents == 4) colors[s + 3] else 1f
            }
        }
    }

    /** The texture rows that hold the instances `from until to`, to upload only those. */
    fun rowRange(
        from: Int,
        to: Int,
    ): IntRange = (from / INSTANCES_PER_ROW)..((to - 1) / INSTANCES_PER_ROW)

    /** Little endian bytes of `count` floats, into `scratch` when it is big enough. */
    fun toBytes(
        floats: FloatArray,
        count: Int,
        scratch: ByteArray,
    ): ByteArray = MeshPacker.packFloatsInto(floats, count, scratch)
}
