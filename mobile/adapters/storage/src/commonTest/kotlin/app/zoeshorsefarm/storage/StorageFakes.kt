package app.zoeshorsefarm.storage

import app.zoeshorsefarm.application.Field
import app.zoeshorsefarm.application.FieldSpec
import app.zoeshorsefarm.application.ObjectSection
import app.zoeshorsefarm.application.SaveEnv
import app.zoeshorsefarm.application.Section

/** The save text a backend holds under [SAVE_KEY] as a tree (null: nothing stored). */
fun savedTree(backend: KeyValueBackend): Map<String, Any?>? = backend.getString(SAVE_KEY)?.let { decodeJsonTree(it) }

/** Walks a parsed tree: `tree.at("settings", "lang")`. */
fun Map<String, Any?>.at(vararg keys: String): Any? {
    var node: Any? = this
    for (key in keys) node = (node as? Map<*, *>)?.get(key)
    return node
}

/** A backend whose writes always fail (storage full or blocked). */
class FailingBackend : KeyValueBackend {
    override fun getString(key: String): String? = null

    override fun setString(
        key: String,
        value: String,
    ) = throw IllegalStateException("QuotaExceeded")

    override fun remove(key: String) = Unit
}

/** A backend whose writes to [SAVE_KEY] can be switched off, like a full disk that is freed later. */
class SwitchableBackend : KeyValueBackend {
    private val inner = MemoryKeyValueBackend()
    var failing = true

    override fun getString(key: String): String? = inner.getString(key)

    override fun setString(
        key: String,
        value: String,
    ) {
        if (failing && key == SAVE_KEY) throw IllegalStateException("QuotaExceeded")
        inner.setString(key, value)
    }

    override fun remove(key: String) = inner.remove(key)
}

/** A section holding a plain map with number or bool fields (stands in for sections added later). */
class MapSection(
    override val name: String,
    fields: Map<String, FieldSpec>,
) : Section<Map<String, Any?>> {
    private val schema = ObjectSection(fields)

    override fun defaults(env: SaveEnv): Map<String, Any?> = schema.defaults(env)

    override fun sanitize(
        raw: Any?,
        env: SaveEnv,
    ): Map<String, Any?> = schema.sanitize(raw, env)

    override fun toTree(value: Map<String, Any?>): Map<String, Any?> = value
}

fun numberSection(
    name: String,
    key: String,
    max: Double,
    fallback: Double,
) = MapSection(name, mapOf(key to Field.number(0.0, max, fallback)))

/** [NoticeMarker] that survives "reloads" (a second store with the same marker). */
class FlagMarker : NoticeMarker {
    var marked = false

    override fun isSet() = marked

    override fun set() {
        marked = true
    }
}
