package app.zoeshorsefarm.render.filament.material

/** A material that the compiler rejected. */
class MaterialBuildException(
    message: String,
) : RuntimeException(message)

/**
 * An estimate of the shader programs Filament makes for a material, to report in the debug box (the
 * web `renderer.info.programs`). Filament builds the variants of a material on demand: for a lit
 * material the combinations of directional light, shadow receiver and fog (8) and a depth variant
 * for the shadow pass; for an unlit one fog and the depth variant. Skinned materials double it.
 * Variants the source filters out (dynamic lights, VSM, ...) are not counted.
 */
object ProgramEstimate {
    private const val LIT_VARIANTS = 9
    private const val UNLIT_VARIANTS = 3

    fun of(source: MaterialSource): Int {
        val base = if (source.shading == SourceShading.LIT) LIT_VARIANTS else UNLIT_VARIANTS
        return if (FilteredVariant.SKINNING in source.filteredVariants) base else base * 2
    }
}

/**
 * Builds materials lazily and keeps one per [MaterialSpec]: the first `get` of a spec generates its
 * source and compiles it, every later one returns the same material. Compiling takes a noticeable
 * time (it is the shader compile stall of a level change in the web app), so a material is never
 * built twice, and `materialCount` / `estimatedPrograms` tell how much has been built.
 *
 * `build` compiles a source into a backend material (null if the compiler fails), `destroy` frees
 * one. The library does not know Filament, which keeps it testable; see `FilamentMaterials`.
 */
class MaterialLibrary<M : Any>(
    private val markings: MarkingRegions = MarkingRegions.WEB,
    private val build: (MaterialSource) -> M?,
    private val destroy: (M) -> Unit,
) {
    private class Entry<M>(
        val spec: MaterialSpec,
        val material: M,
        val programs: Int,
    )

    private val entries = LinkedHashMap<String, Entry<M>>()
    private val failed = LinkedHashSet<String>()

    /** Materials alive now. */
    val materialCount: Int get() = entries.size

    /** Specs whose compile failed (they are not retried). */
    val failedCount: Int get() = failed.size

    /** Every compile since the library was made, also of materials that were released since. */
    var totalBuilds: Int = 0
        private set

    val estimatedPrograms: Int get() = entries.values.sumOf { it.programs }

    fun contains(spec: MaterialSpec): Boolean = spec.key in entries

    fun keys(): List<String> = entries.keys.toList()

    fun get(spec: MaterialSpec): M {
        val key = spec.key
        entries[key]?.let { return it.material }
        if (key in failed) throw MaterialBuildException("material zhf-$key failed to compile earlier")
        val source = MaterialSources.generate(spec, markings)
        totalBuilds++
        val material = build(source)
        if (material == null) {
            failed += key
            throw MaterialBuildException("material ${source.name} failed to compile")
        }
        entries[key] = Entry(spec, material, ProgramEstimate.of(source))
        return material
    }

    /** Destroys one material; the next `get` builds it again. False if it was not built. */
    fun release(spec: MaterialSpec): Boolean {
        val entry = entries.remove(spec.key) ?: return false
        destroy(entry.material)
        return true
    }

    /**
     * Destroys all materials, newest first (and forgets failed ones, so that a new engine gets a new
     * chance). Returns the errors the destroy function threw; it never stops half way.
     */
    fun clear(): List<Throwable> {
        val errors = ArrayList<Throwable>()
        for (entry in entries.values.reversed()) {
            try {
                destroy(entry.material)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                errors += e
            }
        }
        entries.clear()
        failed.clear()
        return errors
    }
}
