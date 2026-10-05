package app.zoeshorsefarm.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// The save is handled as a parsed-JSON tree (Map<String, Any?>, List<Any?>, String, Long, Double,
// Boolean, null), the form the application's sections read and write, so that unknown sections and
// fields survive a save untouched.

// Doubles that are whole numbers below this are written without a fraction, like JSON.stringify.
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

/** The tree of [text], or null when it is not valid JSON or not an object (JS: `isPlainObject`). */
fun decodeJsonTree(text: String): Map<String, Any?>? {
    val element =
        try {
            Json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            return null // SerializationException is one: broken or empty text
        }
    return (element as? JsonObject)?.let(::objectTree)
}

private fun objectTree(obj: JsonObject): Map<String, Any?> =
    obj.entries.associateTo(LinkedHashMap()) { (key, value) -> key to valueTree(value) }

private fun valueTree(element: JsonElement): Any? =
    when (element) {
        is JsonNull -> null
        is JsonObject -> objectTree(element)
        is JsonArray -> element.map(::valueTree)
        is JsonPrimitive -> primitiveTree(element)
    }

private fun primitiveTree(primitive: JsonPrimitive): Any? =
    when {
        primitive.isString -> {
            primitive.content
        }

        else -> {
            primitive.booleanOrNull
                ?: primitive.content.toLongOrNull()
                ?: primitive.content.toDouble()
        }
    }

/** JSON text of [tree]; a value that is not JSON like is a programming error (IllegalArgumentException). */
fun encodeJsonTree(tree: Map<String, Any?>): String =
    Json.encodeToString(JsonElement.serializer(), toElement(tree, "$"))

private fun toElement(
    value: Any?,
    path: String,
): JsonElement =
    when (value) {
        null -> {
            JsonNull
        }

        is String -> {
            JsonPrimitive(value)
        }

        is Boolean -> {
            JsonPrimitive(value)
        }

        is Int -> {
            JsonPrimitive(value)
        }

        is Long -> {
            JsonPrimitive(value)
        }

        is Number -> {
            numberElement(value.toDouble())
        }

        is Map<*, *> -> {
            JsonObject(
                value.entries.associate { (key, item) ->
                    require(key is String) { "Key of $path is not a string: $key" }
                    key to toElement(item, "$path.$key")
                },
            )
        }

        is List<*> -> {
            JsonArray(value.mapIndexed { index, item -> toElement(item, "$path[$index]") })
        }

        else -> {
            throw IllegalArgumentException("Value at $path is not JSON like: ${value::class.simpleName}")
        }
    }

private fun numberElement(value: Double): JsonElement =
    when {
        !value.isFinite() -> JsonNull
        value == value.toLong().toDouble() && kotlin.math.abs(value) < MAX_SAFE_INTEGER -> JsonPrimitive(value.toLong())
        else -> JsonPrimitive(value)
    }
