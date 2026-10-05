package app.zoeshorsefarm.render.filament.backend.mapping

import app.zoeshorsefarm.render.filament.mesh.PrimitiveRange
import app.zoeshorsefarm.scene.geometry.Geometry
import kotlin.math.max
import kotlin.math.min

/**
 * Which parts of a geometry are drawn with which material, by the rules of three.js `renderObject`:
 *
 * - one material: the whole index (or vertex list) inside the draw range, groups are ignored
 * - a material list: one range per group (the group clipped to the draw range, empty ones skipped);
 *   without groups the whole geometry is drawn with the first material
 *
 * Ranges are cut down to whole triangles, because Filament draws triangle lists only.
 */
object PrimitiveRanges {
    fun of(
        geometry: Geometry,
        materialList: Boolean,
    ): List<PrimitiveRange> {
        val total = geometry.index?.size ?: geometry.vertexCount
        val range = geometry.drawRange
        val first = max(range.start, 0)
        val last = min(total, saturatedSum(range.start, range.count))
        if (!materialList || geometry.groups.isEmpty()) return listOfNotNull(clipped(first, last, 0))
        val result = ArrayList<PrimitiveRange>(geometry.groups.size)
        for (group in geometry.groups) {
            val start = max(group.start, first)
            val end = min(group.start + group.count, last)
            clipped(start, end, group.materialIndex)?.let { result += it }
        }
        return result
    }

    /** The triangles the ranges draw once. */
    fun triangles(ranges: List<PrimitiveRange>): Int {
        var count = 0
        for (i in ranges.indices) count += ranges[i].count / TRIANGLE
        return count
    }

    private fun saturatedSum(
        start: Int,
        count: Int,
    ): Int = if (start > 0 && count > Int.MAX_VALUE - start) Int.MAX_VALUE else start + count

    private fun clipped(
        start: Int,
        end: Int,
        materialIndex: Int,
    ): PrimitiveRange? {
        val count = (end - start) / TRIANGLE * TRIANGLE
        return if (count > 0) PrimitiveRange(start, count, materialIndex) else null
    }

    private const val TRIANGLE = 3
}
