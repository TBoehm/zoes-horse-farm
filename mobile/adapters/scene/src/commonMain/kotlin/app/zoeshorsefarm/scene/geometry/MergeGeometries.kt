package app.zoeshorsefarm.scene.geometry

/**
 * Merges geometries with the same attribute set into one (three.js `mergeGeometries`). Returns null
 * when the geometries are not compatible (different attributes, some indexed and some not, or
 * mixed attribute types). With `useGroups` every input becomes a group with its own material index.
 */
fun mergeGeometries(
    geometries: List<Geometry>,
    useGroups: Boolean = false,
): Geometry? {
    if (!areCompatible(geometries, useGroups)) return null
    val merged = Geometry()
    if (useGroups) {
        var offset = 0
        for ((i, geometry) in geometries.withIndex()) {
            val count = if (geometry.index != null) geometry.indexCount else geometry.vertexCount
            merged.addGroup(offset, count, i)
            offset += count
        }
    }
    if (geometries[0].index != null) merged.setIndex(mergeIndices(geometries))
    for (name in geometries[0].attributes.keys) {
        val attribute = mergeAttributes(geometries.map { requireNotNull(it.getAttribute(name)) }) ?: return null
        merged.setAttribute(name, attribute)
    }
    return merged
}

/** All geometries must be indexed or none, and have exactly the attributes of the first one. */
private fun areCompatible(
    geometries: List<Geometry>,
    useGroups: Boolean,
): Boolean {
    if (geometries.isEmpty()) return false
    val indexed = geometries[0].index != null
    val names = geometries[0].attributes.keys
    return geometries.all { g ->
        val sameIndex = (g.index != null) == indexed
        val sameAttributes = g.attributes.keys == names
        // groups of a non-indexed geometry count its vertices, so it needs positions
        val canCount = !useGroups || indexed || g.attributes["position"] is FloatAttribute
        sameIndex && sameAttributes && canCount
    }
}

private fun mergeIndices(geometries: List<Geometry>): IntArray {
    val merged = IntArray(geometries.sumOf { it.indexCount })
    var indexOffset = 0
    var o = 0
    for (geometry in geometries) {
        for (value in geometry.index ?: IntArray(0)) merged[o++] = value + indexOffset
        indexOffset += geometry.position.count
    }
    return merged
}

/** Concatenates attributes of the same type and item size; null if they differ. */
fun mergeAttributes(attributes: List<BufferAttribute>): BufferAttribute? {
    val first = attributes.firstOrNull() ?: return null
    val itemSize = first.itemSize
    val sameKind = attributes.all { it.itemSize == itemSize && it::class == first::class }
    return when {
        !sameKind -> {
            null
        }

        first is FloatAttribute -> {
            val parts = attributes.map { (it as FloatAttribute).array }
            val out = FloatArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) {
                p.copyInto(out, o)
                o += p.size
            }
            FloatAttribute(out, itemSize)
        }

        else -> {
            val parts = attributes.map { (it as UShortAttribute).array }
            val out = ShortArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) {
                p.copyInto(out, o)
                o += p.size
            }
            UShortAttribute(out, itemSize)
        }
    }
}
