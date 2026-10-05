package app.zoeshorsefarm.render.filament.material

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GlslPreprocessorTest {
    private fun resolve(
        source: String,
        vararg defines: String,
    ) = GlslPreprocessor.resolve(source.trimIndent(), defines.toSet())

    @Test
    fun `text without directives is unchanged`() {
        assertEquals("a\nb", resolve("a\nb"))
    }

    @Test
    fun `ifdef keeps its branch when the name is defined`() {
        val source = """
            #ifdef LOW
            low
            #else
            high
            #endif
        """
        assertEquals("low", resolve(source, "LOW"))
        assertEquals("high", resolve(source))
    }

    @Test
    fun `ifndef is the opposite of ifdef`() {
        val source = """
            #ifndef LOW
            high
            #endif
            tail
        """
        assertEquals("high\ntail", resolve(source))
        assertEquals("tail", resolve(source, "LOW"))
    }

    @Test
    fun `directives can nest`() {
        val source = """
            #ifdef A
            a
            #ifdef B
            ab
            #else
            a-not-b
            #endif
            #endif
        """
        assertEquals("a\nab", resolve(source, "A", "B"))
        assertEquals("a\na-not-b", resolve(source, "A"))
        assertEquals("", resolve(source, "B"))
    }

    @Test
    fun `lines around directives are kept`() {
        val source = """
            before
            #ifdef X
            inside
            #endif
            after
        """
        assertEquals("before\ninside\nafter", resolve(source, "X"))
        assertEquals("before\nafter", resolve(source))
    }

    @Test
    fun `an unclosed or stray directive is an error`() {
        assertFailsWith<IllegalArgumentException> { resolve("#ifdef X\nfoo") }
        assertFailsWith<IllegalArgumentException> { resolve("foo\n#endif") }
        assertFailsWith<IllegalArgumentException> { resolve("#else\nfoo") }
    }

    @Test
    fun `other preprocessor lines such as define pass through`() {
        assertEquals("#define A 1\nA", resolve("#define A 1\nA"))
    }
}
