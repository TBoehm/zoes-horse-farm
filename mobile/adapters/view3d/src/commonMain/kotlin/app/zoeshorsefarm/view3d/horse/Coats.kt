package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.domain.horse.Coat
import app.zoeshorsefarm.domain.horse.DEFAULT_APPEARANCE
import app.zoeshorsefarm.domain.horse.MARKINGS
import app.zoeshorsefarm.domain.horse.Marking

// Coat colors and head markings as pure data (sRGB 0..1). The coat shader of the render backend
// draws them (see CoatEffect in :adapters:scene).

private val WHITE = doubleArrayOf(0.93, 0.92, 0.9)

/**
 * Colours of a coat: [base] main coat, [dark] topline/dapple net, [belly] underside, [hair]
 * mane/tail, [points] amount of dark lower legs ([pointColor]), [muzzle] muzzle/nostril skin,
 * [hoof] horn, [white] markings and patches; [dapple] and [pinto] switch the patterns on.
 */
class CoatParams(
    val base: DoubleArray,
    val dark: DoubleArray,
    val belly: DoubleArray,
    val hair: DoubleArray,
    val points: Double,
    val pointColor: DoubleArray,
    val muzzle: DoubleArray,
    val hoof: DoubleArray,
    val white: DoubleArray,
    val dapple: Double,
    val pinto: Double,
)

private fun rgb(
    r: Double,
    g: Double,
    b: Double,
) = doubleArrayOf(r, g, b)

private val PALETTE: Map<Coat, CoatParams> =
    mapOf(
        Coat.CHESTNUT to
            CoatParams(
                base = rgb(0.52, 0.24, 0.1),
                dark = rgb(0.4, 0.17, 0.07),
                belly = rgb(0.62, 0.33, 0.15),
                hair = rgb(0.72, 0.45, 0.22),
                points = 0.0,
                pointColor = rgb(0.45, 0.2, 0.08),
                muzzle = rgb(0.36, 0.2, 0.14),
                hoof = rgb(0.2, 0.16, 0.13),
                white = WHITE,
                dapple = 0.0,
                pinto = 0.0,
            ),
        Coat.BAY to
            CoatParams(
                base = rgb(0.36, 0.19, 0.09),
                dark = rgb(0.22, 0.11, 0.05),
                belly = rgb(0.45, 0.26, 0.13),
                hair = rgb(0.035, 0.03, 0.028),
                points = 1.0,
                pointColor = rgb(0.04, 0.035, 0.032),
                muzzle = rgb(0.14, 0.09, 0.07),
                hoof = rgb(0.12, 0.1, 0.09),
                white = WHITE,
                dapple = 0.0,
                pinto = 0.0,
            ),
        Coat.BLACK to
            CoatParams(
                base = rgb(0.075, 0.068, 0.068),
                dark = rgb(0.05, 0.046, 0.046),
                belly = rgb(0.1, 0.085, 0.08),
                hair = rgb(0.03, 0.028, 0.028),
                points = 0.6,
                pointColor = rgb(0.045, 0.042, 0.042),
                muzzle = rgb(0.09, 0.075, 0.07),
                hoof = rgb(0.1, 0.09, 0.085),
                white = WHITE,
                dapple = 0.0,
                pinto = 0.0,
            ),
        Coat.GREY to
            CoatParams(
                base = rgb(0.86, 0.86, 0.84),
                dark = rgb(0.6, 0.6, 0.6),
                belly = rgb(0.9, 0.9, 0.88),
                hair = rgb(0.9, 0.89, 0.86),
                points = 0.55,
                pointColor = rgb(0.55, 0.55, 0.55),
                muzzle = rgb(0.22, 0.21, 0.22),
                hoof = rgb(0.25, 0.23, 0.21),
                white = rgb(0.95, 0.95, 0.94),
                dapple = 1.0,
                pinto = 0.0,
            ),
        Coat.PINTO to
            CoatParams(
                base = rgb(0.33, 0.17, 0.08),
                dark = rgb(0.24, 0.12, 0.06),
                belly = rgb(0.38, 0.2, 0.1),
                hair = rgb(0.08, 0.06, 0.05),
                points = 0.0,
                pointColor = rgb(0.3, 0.15, 0.07),
                muzzle = rgb(0.16, 0.1, 0.08),
                hoof = rgb(0.18, 0.15, 0.12),
                white = WHITE,
                dapple = 0.0,
                pinto = 1.0,
            ),
    )

/** Coat and marking with the defaults for missing values. */
fun normalizeAppearance(
    coat: Coat? = null,
    marking: Marking? = null,
): Appearance = Appearance(coat ?: DEFAULT_APPEARANCE.coat, marking ?: DEFAULT_APPEARANCE.marking)

/** The colours of [coat] (the default coat for null). */
fun coatParams(coat: Coat?): CoatParams = PALETTE.getValue(coat ?: DEFAULT_APPEARANCE.coat)

/** Index of the marking for the shader: none 0, star 1, blaze 2, snip 3 (default marking for null). */
fun markingIndex(marking: Marking?): Int = MARKINGS.indexOf(marking ?: DEFAULT_APPEARANCE.marking)

/** Star marking on the head (head coordinates: s along the head from the poll, u lateral; metres). */
class StarRegion(
    val s: Double,
    val rs: Double,
    val ru: Double,
)

/** Blaze marking on the head: from [s0] to [s1] along the head, half width from [w0] to [w1]. */
class BlazeRegion(
    val s0: Double,
    val s1: Double,
    val w0: Double,
    val w1: Double,
)

/** Snip marking on the muzzle. */
class SnipRegion(
    val s: Double,
    val rs: Double,
    val ru: Double,
)

/** Marking regions in head coordinates (s along the head from the poll, u lateral; metres). */
class MarkingRegions(
    val star: StarRegion,
    val blaze: BlazeRegion,
    val snip: SnipRegion,
)

val MARKING_REGIONS =
    MarkingRegions(
        star = StarRegion(s = 0.155, rs = 0.048, ru = 0.038),
        blaze = BlazeRegion(s0 = 0.08, s1 = 0.615, w0 = 0.026, w1 = 0.042),
        snip = SnipRegion(s = 0.575, rs = 0.026, ru = 0.022),
    )
