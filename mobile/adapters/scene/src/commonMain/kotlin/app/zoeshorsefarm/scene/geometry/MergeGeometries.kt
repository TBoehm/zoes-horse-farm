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
    if (geometries.isEmpty()) return null
    val isIndexed = geometries[0].index != null
    val attributesUsed = geometries[0].attributes.keys.toSet()
    val merged = Geometry()
    var offset = 0
    val perName = LinkedHashMap<String, MutableList<BufferAttribute>>()
    for ((i, geometry) in geometries.withIndex()) {
        if (isIndexed != (geometry.index != null)) return null
        var attributesCount = 0
        for ((name, attribute) in geometry.attributes) {
            if (name !in attributesUsed) return null
            perName.getOrPut(name) { ArrayList() }.add(attribute)
            attributesCount++
        }
        if (attributesCount != attributesUsed.size) return null
        if (useGroups) {
            val count =
                if (isIndexed) {
                    geometry.indexCount
                } else {
                    (geometry.attributes["position"] as? FloatAttribute)?.count ?: return null
                }
            merged.addGroup(offset, count, i)
            offset += count
        }
    }
    if (isIndexed) {
        var indexOffset = 0
        val total = geometries.sumOf { it.indexCount }
        val mergedIndex = IntArray(total)
        var o = 0
        for (geometry in geometries) {
            val index = geometry.index ?: return null
            for (value in index) mergedIndex[o++] = value + indexOffset
            indexOffset += geometry.position.count
        }
        merged.setIndex(mergedIndex)
    }
    for ((name, list) in perName) {
        merged.setAttribute(name, mergeAttributes(list) ?: return null)
    }
    return merged
}

/** Concatenates attributes of the same type and item size. */
fun mergeAttributes(attributes: List<BufferAttribute>): BufferAttribute? {
    if (attributes.isEmpty()) return null
    val itemSize = attributes[0].itemSize
    if (attributes.any { it.itemSize != itemSize }) return null
    val first = attributes[0]
    return when (first) {
        is FloatAttribute -> {
            if (attributes.any { it !is FloatAttribute }) return null
            val total = attributes.sumOf { (it as FloatAttribute).array.size }
            val out = FloatArray(total)
            var o = 0
            for (a in attributes) {
                val arr = (a as FloatAttribute).array
                arr.copyInto(out, o)
                o += arr.size
            }
            FloatAttribute(out, itemSize)
        }

        is UShortAttribute -> {
            if (attributes.any { it !is UShortAttribute }) return null
            val total = attributes.sumOf { (it as UShortAttribute).array.size }
            val out = ShortArray(total)
            var o = 0
            for (a in attributes) {
                val arr = (a as UShortAttribute).array
                arr.copyInto(out, o)
                o += arr.size
            }
            UShortAttribute(out, itemSize)
        }
    }
}
