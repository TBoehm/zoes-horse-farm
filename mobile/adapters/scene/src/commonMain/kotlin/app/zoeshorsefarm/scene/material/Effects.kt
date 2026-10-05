package app.zoeshorsefarm.scene.material

import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.toFixed

/**
 * A named shader patch for a [StandardMaterial] / [LambertMaterial] (the web app's `onBeforeCompile`
 * hooks). The render backend implements each subclass; view code only picks one and sets its
 * parameters. [programKey] separates the program variants (three.js `customProgramCacheKey`):
 * effects with different keys never share a program.
 */
sealed class MaterialEffect(
    val programKey: String,
)

/** The wind of a world: `time` (s, advanced once per frame) and `strength` (0 = calm, 1 = full). */
class Wind(
    var time: Double = 0.0,
    var strength: Double = 1.0,
)

/** Which vertex animation a [WindEffect] adds. */
enum class WindKind {
    /** Tree crowns lean and swing (instanced meshes; the phase comes from the instance position). */
    TREE,

    /** The whole blob sways a little, more at the top (instanced). */
    BUSH,

    /** Grass tuft tips sway (instanced). */
    TUFT,

    /** Stems bend; the geometry's float attribute `petal` (1 on petals) picks the vertices that take the instance colour. */
    BLOSSOMS,

    /** Pennants flutter; needs the vec3 attribute `aFlutter` (direction times weight). */
    BUNTING,

    /** Wings flap along X (instanced, every instance with its own phase). */
    WINGS,
}

/**
 * Wind or small vertex animations of the scenery (rule 3: nothing is done on the CPU). All effects
 * read the shared [wind]. `base` (BLOSSOMS): height in metres of a rigid part below the plants;
 * `rate`, `amplitude`, `glide` (WINGS): beat in rad/s, lift of the tips per metre from the body, share of pauses.
 */
class WindEffect(
    val kind: WindKind,
    val wind: Wind,
    val base: Double = 0.0,
    val rate: Double = 0.0,
    val amplitude: Double = 0.0,
    val glide: Double = 0.0,
) : MaterialEffect(keyOf(kind, base, rate, amplitude, glide)) {
    companion object {
        private fun keyOf(
            kind: WindKind,
            base: Double,
            rate: Double,
            amplitude: Double,
            glide: Double,
        ): String =
            when (kind) {
                WindKind.TREE -> "wind-tree-v1"
                WindKind.BUSH -> "wind-bush-v1"
                WindKind.TUFT -> "wind-tuft-v2"
                WindKind.BLOSSOMS -> "wind-blossom-v2-${base.toFixed(3)}"
                WindKind.BUNTING -> "wind-bunting-v1"
                WindKind.WINGS -> "wind-wings-${rate.toFixed(3)}-${amplitude.toFixed(3)}-${glide.toFixed(3)}"
            }

        fun tree(wind: Wind) = WindEffect(WindKind.TREE, wind)

        fun bush(wind: Wind) = WindEffect(WindKind.BUSH, wind)

        fun tuft(wind: Wind) = WindEffect(WindKind.TUFT, wind)

        fun blossoms(
            wind: Wind,
            base: Double = 0.0,
        ) = WindEffect(WindKind.BLOSSOMS, wind, base = base)

        fun bunting(wind: Wind) = WindEffect(WindKind.BUNTING, wind)

        fun wings(
            wind: Wind,
            rate: Double,
            amplitude: Double,
            glide: Double = 0.0,
        ) = WindEffect(WindKind.WINGS, wind, rate = rate, amplitude = amplitude, glide = glide)
    }
}

/**
 * Parameters of the horse coat (colours in the linear working space, like `Color` everywhere).
 * Shared by all coat materials of a horse: changing the appearance only sets values here.
 */
class CoatUniforms {
    val base = Color()
    val dark = Color()
    val belly = Color()
    val hair = Color()
    val pointColor = Color()
    val muzzle = Color()
    val hoof = Color()
    val white = Color()
    var points: Double = 0.0
    var dapple: Double = 0.0
    var pinto: Double = 0.0
    var marking: Double = 1.0

    /** Nostril flare 0..1, animated every frame. */
    var flare: Double = 0.0

    /** Blink of the painted eye on low, 0..1, animated every frame. */
    var blink: Double = 0.0
}

/**
 * The horse coat: colour per pixel from the rest-pose position (`aRest`, vec3: dapples, pinto patches,
 * dark lower legs), material weights (`aMat`, vec4: long hair, hoof, eye, inner ear) and head
 * coordinates (`aFace`, vec3), which are float attributes of the geometry. `low` is the cheaper
 * variant (no fine noise, painted leg wraps and blink).
 */
class CoatEffect(
    val uniforms: CoatUniforms,
    val low: Boolean,
) : MaterialEffect("zhf-horse-coat-${if (low) "low" else "std"}")
