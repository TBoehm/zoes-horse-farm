package app.zoeshorsefarm.presentation.theme

import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// The Kotlin tokens must stay the colours of the web app: this reads the first `:root` block of
// `src/adapters/ui/styles/main.css` (the web app is the reference) and compares it with DesignTokens.
class DesignTokensCssTest {
    private val cssPath = "src/adapters/ui/styles/main.css"

    // the repo root is the nearest parent of the working directory that has the style sheet
    private fun findCss(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, cssPath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Cannot find $cssPath above ${System.getProperty("user.dir")}")
    }

    /** Custom properties of the first :root block as { '--name': 'value' }. */
    private fun rootTokens(): Map<String, String> {
        val css = findCss().readText()
        val block = Regex(""":root\s*\{([^}]*)\}""").find(css)?.groupValues?.get(1)
        assertNotNull(block, "no :root block")
        val withoutComments = block.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        return Regex("""(--[\w-]+)\s*:\s*([^;]+);""")
            .findAll(withoutComments)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    // --c-bg-top -> bgTop
    private fun tokenName(cssName: String): String =
        cssName
            .removePrefix("--c-")
            .split('-')
            .mapIndexed { i, part ->
                if (i == 0) part else part.replaceFirstChar { it.uppercase() }
            }.joinToString("")

    private fun parse(value: String): Argb {
        Regex("""^#([0-9a-fA-F]{6})$""").matchEntire(value)?.let { return Argb.rgb(it.groupValues[1].toInt(16)) }
        val rgba = Regex("""^rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)$""").matchEntire(value)
        val (r, g, b) = rgba?.groupValues?.subList(1, 4)?.map { it.toInt() } ?: error("Cannot parse $value")
        return Argb(alpha = (rgba.groupValues[4].toDouble() * 255).roundToInt(), red = r, green = g, blue = b)
    }

    @Test
    fun everyColourTokenOfTheStyleSheetIsInDesignTokensWithTheSameValue() {
        val colours = rootTokens().filterKeys { it.startsWith("--c-") }
        assertEquals(
            colours.size,
            DesignTokens.all.size,
            "token count: css ${colours.keys} vs kotlin ${DesignTokens.all.keys}",
        )
        for ((cssName, value) in colours) {
            val name = tokenName(cssName)
            assertEquals(parse(value), DesignTokens.all[name], "$cssName ($name)")
        }
    }

    @Test
    fun designTokensHasNoColourThatTheStyleSheetLacks() {
        val names =
            rootTokens()
                .keys
                .filter { it.startsWith("--c-") }
                .map(::tokenName)
                .toSet()
        assertEquals(names, DesignTokens.all.keys)
    }

    @Test
    fun theSizesAndTheShadowFollowTheStyleSheet() {
        val tokens = rootTokens()
        assertEquals("${DesignTokens.RADIUS_DP}px", tokens["--radius"])
        assertEquals("${DesignTokens.TOUCH_MIN_DP}px", tokens["--touch-min"])
        val shadow = Regex("""^0 (\d+)px (\d+)px (rgba\(.*\))$""").matchEntire(tokens.getValue("--shadow"))
        assertNotNull(shadow)
        assertEquals(DesignTokens.SHADOW_OFFSET_Y_DP, shadow.groupValues[1].toInt())
        assertEquals(DesignTokens.SHADOW_BLUR_DP, shadow.groupValues[2].toInt())
        assertEquals(DesignTokens.shadowColor, parse(shadow.groupValues[3]))
    }
}
