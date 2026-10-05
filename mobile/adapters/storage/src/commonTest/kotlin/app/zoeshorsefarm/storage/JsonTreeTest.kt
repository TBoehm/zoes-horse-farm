package app.zoeshorsefarm.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTreeTest {
    @Test
    fun `reads objects lists and scalars into plain Kotlin values`() {
        val tree = decodeJsonTree("""{"a":1,"b":1.5,"c":"x","d":true,"e":null,"f":[1,"y",{"g":2}],"h":{}}""")
        assertEquals(
            mapOf(
                "a" to 1L,
                "b" to 1.5,
                "c" to "x",
                "d" to true,
                "e" to null,
                "f" to listOf(1L, "y", mapOf("g" to 2L)),
                "h" to emptyMap<String, Any?>(),
            ),
            tree,
        )
    }

    @Test
    fun `numbers that look like text stay text`() {
        assertEquals(mapOf("a" to "12"), decodeJsonTree("""{"a":"12"}"""))
    }

    @Test
    fun `anything but an object is not a save`() {
        for (text in listOf("42", "\"x\"", "null", "[1,2]", "", "{broken", "{\"a\":")) {
            assertNull(decodeJsonTree(text), text)
        }
    }

    @Test
    fun `writes what it reads`() {
        val text = """{"a":1,"b":1.5,"c":"x\"y","d":false,"e":null,"f":[1,{"g":[]}]}"""
        val tree = decodeJsonTree(text)!!
        assertEquals(tree, decodeJsonTree(encodeJsonTree(tree)))
    }

    @Test
    fun `writes whole numbers without a fraction and keeps big timestamps exact`() {
        val text = encodeJsonTree(mapOf("a" to 3, "b" to 1_700_000_000_123L, "c" to 0.25))
        assertEquals("""{"a":3,"b":1700000000123,"c":0.25}""", text)
    }

    @Test
    fun `non finite numbers become null like JSON stringify`() {
        val text = encodeJsonTree(mapOf("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY))
        assertEquals("""{"a":null,"b":null}""", text)
    }

    @Test
    fun `escapes special characters and keeps unicode`() {
        val tree = mapOf("name" to "Zoë \"Blitz\"\n❤")
        assertEquals(tree, decodeJsonTree(encodeJsonTree(tree)))
    }

    @Test
    fun `values that are not JSON like are a programming error`() {
        val error = assertFailsWith<IllegalArgumentException> { encodeJsonTree(mapOf("x" to Any())) }
        assertTrue(error.message!!.contains("x"))
    }

    @Test
    fun `keys of nested maps must be strings`() {
        assertFailsWith<IllegalArgumentException> { encodeJsonTree(mapOf("x" to mapOf(1 to 2))) }
    }
}
