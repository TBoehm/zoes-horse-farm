package app.zoeshorsefarm.application

// Save-game sections with field-by-field sanitizing (rule 47). Invalid or missing fields -> default
// value, readable fields stay, unknown fields are preserved.
//
// The tree convention: a section is stored as a parsed-JSON tree (Map<String, Any?>, List<Any?>,
// String, Number, Boolean, null). Every [Section] reads such a tree (loosely typed, may come from a
// newer version or be damaged) into an immutable typed value and writes the value back as a tree, so
// a storage adapter can persist any section without knowing it.

const val SAVE_VERSION = 1

/** Values of the environment that initial values depend on (the system language). */
data class SaveEnv(
    val defaultLang: Language? = null,
)

/** One field of an object section: its fallback (may depend on the environment) and its validity check. */
class FieldSpec(
    val fallback: (SaveEnv) -> Any?,
    val check: (Any?) -> Boolean,
) {
    companion object {
        /** A field whose fallback is the same everywhere. */
        fun constant(
            fallback: Any?,
            check: (Any?) -> Boolean,
        ) = FieldSpec({ fallback }, check)
    }
}

/** Builders for the common field specs. */
object Field {
    fun oneOf(
        values: List<Any?>,
        fallback: Any?,
    ) = FieldSpec.constant(fallback) { it in values }

    fun bool(fallback: Boolean) = FieldSpec.constant(fallback) { it is Boolean }

    fun number(
        min: Double,
        max: Double,
        fallback: Double,
    ) = FieldSpec.constant(fallback) { v -> v is Number && isInRange(v.toDouble(), min, max) }

    private fun isInRange(
        value: Double,
        min: Double,
        max: Double,
    ) = value.isFinite() && value >= min && value <= max
}

/** The entries of a parsed JSON object with string keys; empty for anything else. */
internal fun treeEntries(raw: Any?): LinkedHashMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    if (raw is Map<*, *>) {
        for ((key, value) in raw) if (key is String) out[key] = value
    }
    return out
}

/** Sanitizer for an object with fixed fields; everything else in the object is kept as it is. */
class ObjectSection(
    private val fields: Map<String, FieldSpec>,
) {
    fun defaults(env: SaveEnv = SaveEnv()): Map<String, Any?> =
        fields.mapValuesTo(LinkedHashMap()) { (_, spec) -> spec.fallback(env) }

    /** A copy of [raw] where every invalid or missing field has its fallback; [raw] is not changed. */
    fun sanitize(
        raw: Any?,
        env: SaveEnv = SaveEnv(),
    ): Map<String, Any?> {
        val out = treeEntries(raw)
        for ((key, spec) in fields) {
            if (key !in out || !spec.check(out[key])) out[key] = spec.fallback(env)
        }
        return out
    }
}

/** A section of the save game: the typed value [T], its sanitizer and its tree form. */
interface Section<T : Any> {
    /** Key of the section in the save file. */
    val name: String

    /** The value of a new player. */
    fun defaults(env: SaveEnv = SaveEnv()): T

    /** The value read from a parsed JSON tree: invalid -> default, valid fields stay, unknown fields stay. */
    fun sanitize(
        raw: Any?,
        env: SaveEnv = SaveEnv(),
    ): T

    /** The JSON-like form of [value] (what [sanitize] reads back). */
    fun toTree(value: T): Map<String, Any?>

    /** [value] after the sanitizer (what a store keeps after a change). */
    fun clean(
        value: T,
        env: SaveEnv = SaveEnv(),
    ): T = sanitize(toTree(value), env)

    /** The sanitized tree of [raw]; usable without knowing the section's type. */
    fun sanitizedTree(
        raw: Any?,
        env: SaveEnv = SaveEnv(),
    ): Map<String, Any?> = toTree(sanitize(raw, env))
}
