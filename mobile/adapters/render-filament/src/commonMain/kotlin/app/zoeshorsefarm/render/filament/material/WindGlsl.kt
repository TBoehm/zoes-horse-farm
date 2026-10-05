package app.zoeshorsefarm.render.filament.material

/**
 * The vertex effects of `plant-shaders.js`, ported to Filament's `materialVertex`.
 *
 * Each snippet works on `transformed` (the object space position that the effect may move, three.js
 * names it the same), and may read:
 *
 *  - `position`: the unmoved object space position
 *  - `inst`: the instance matrix (a `mat4`, from the instance data texture), instanced effects only
 *  - `iid`: the instance index
 *  - `windTime` and `windStrength`: Filament material global 0 (`x` and `y`)
 *
 * The maths is the three.js code unchanged, except that the instance matrix translation
 * `instanceMatrix[3]` is `inst[3]` here (the data texture holds world matrices, so it is the same
 * world position and does not depend on the camera).
 */
internal object WindGlsl {
    /** Declarations that the snippet needs before it runs, or an empty string. */
    fun declarations(effect: WindEffect): String =
        when (effect) {
            is WindEffect.Blossom -> "float petal = getCustom0().x;"
            WindEffect.Bunting -> "vec3 aFlutter = getCustom0().xyz;"
            else -> ""
        }

    /** The code that moves `transformed`. */
    fun snippet(effect: WindEffect): String =
        when (effect) {
            WindEffect.None -> ""
            WindEffect.Tree -> TREE
            WindEffect.Bush -> BUSH
            WindEffect.Tuft -> TUFT
            is WindEffect.Blossom -> blossom(effect.base)
            WindEffect.Bunting -> BUNTING
            is WindEffect.Wings -> wings(effect.rate, effect.amplitude, effect.glide)
        }

    private const val TREE = """
vec2 wp = vec2(inst[3].x, inst[3].z);
float crown = clamp((position.y - 1.6) / 5.6, 0.0, 1.0);
crown *= crown;
float gust = 0.65 + 0.35 * sin(windTime * 0.31 + wp.x * 0.021 + wp.y * 0.017);
float swayA = sin(windTime * 1.05 + wp.x * 0.085 + wp.y * 0.06);
float swayB = sin(windTime * 2.1 + wp.y * 0.13 + position.x * 0.9);
float leaf = sin(windTime * 5.3 + position.x * 3.1 + position.z * 2.7 + position.y * 2.3);
float treeK = crown * gust * windStrength;
transformed.x += (swayA * 0.26 + swayB * 0.07) * treeK + leaf * 0.025 * crown * windStrength;
transformed.z += (swayA * 0.12 + swayB * 0.05) * treeK - leaf * 0.02 * crown * windStrength;"""

    private const val BUSH = """
vec2 wp = vec2(inst[3].x, inst[3].z);
float bushTop = clamp(position.y / 1.2, 0.0, 1.0);
bushTop *= bushTop;
float bushSway = sin(windTime * 1.6 + wp.x * 0.3 + wp.y * 0.23);
float bushLeaf = sin(windTime * 3.7 + position.x * 2.0 + position.z * 1.7);
transformed.x += (bushSway * 0.055 + bushLeaf * 0.018) * bushTop * windStrength;
transformed.z += (bushSway * 0.025 - bushLeaf * 0.012) * bushTop * windStrength;"""

    private const val TUFT = """
vec2 ip = vec2(inst[3].x, inst[3].z);
float tuftSway = sin(windTime * 1.7 + ip.x * 0.35 + ip.y * 0.21) * 0.6
           + sin(windTime * 3.1 + ip.y * 0.5) * 0.25;
transformed.x += tuftSway * position.y * 0.18 * windStrength;
transformed.z += tuftSway * position.y * 0.08 * windStrength;"""

    private fun blossom(base: Float): String {
        val b = GlslNumber.format(base)
        return """
vec2 fp = vec2(inst[3].x, inst[3].z);
float stemBend = sin(windTime * 1.9 + fp.x * 0.5 + fp.y * 0.37) * 0.6
           + sin(windTime * 3.3 + fp.y * 0.9) * 0.25;
float stemLength = max(position.y - $b, 0.0);
transformed.x += stemBend * stemLength * 0.35 * windStrength;
transformed.z += stemBend * stemLength * 0.15 * windStrength;"""
    }

    private const val BUNTING = """
float pennantFlap = sin(windTime * 3.1 + position.x * 1.9 + position.z * 1.5) * 0.6
           + sin(windTime * 5.7 + position.x * 3.3 - position.z * 2.1) * 0.25 + 0.3;
transformed += aFlutter * pennantFlap * windStrength * 0.12;"""

    private fun wings(
        rate: Float,
        amplitude: Float,
        glide: Float,
    ): String {
        val r = GlslNumber.format(rate)
        val a = GlslNumber.format(amplitude)
        val g = GlslNumber.format(glide)
        return """
float wingPhase = float(iid) * 1.37;
float wingBeat = sin(windTime * $r + wingPhase);
float wingPause = $g > 0.0
  ? mix(1.0, smoothstep(-0.3, 0.5, sin(windTime * 0.6 + wingPhase * 2.0)), $g)
  : 1.0;
transformed.y += wingBeat * abs(position.x) * $a * wingPause;"""
    }
}
