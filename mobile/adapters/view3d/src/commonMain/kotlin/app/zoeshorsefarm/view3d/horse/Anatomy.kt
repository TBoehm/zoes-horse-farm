package app.zoeshorsefarm.view3d.horse

// Horse rest (bind) pose as pure data: meters, Y up, facing +Z, origin on the ground below the
// body centre (the adapter shifts the model, see HorseView.kt).
// Warmblood: withers ~ 1.65 m, body point of shoulder-point of buttock ~ 1.9 m,
// nose-tail ~ 2.6 m.

/** Left (+X) and right (-X) as seen by horse/rider. */
val SIDES: List<Double> = listOf(1.0, -1.0)

/** Leg index 0..3 (footfall events). */
val LEG_NAMES: List<String> = listOf("LF", "RF", "LH", "RH")

private fun p(
    x: Double,
    y: Double,
    z: Double,
) = doubleArrayOf(x, y, z)

/** Foreleg joints (left side, x > 0). */
class FrontRest(
    val scapula: DoubleArray,
    val shoulder: DoubleArray,
    val elbow: DoubleArray,
    val knee: DoubleArray,
    val fetlock: DoubleArray,
    val hoof: DoubleArray,
)

/** Hind leg joints (left side, x > 0). */
class HindRest(
    val hip: DoubleArray,
    val stifle: DoubleArray,
    val hock: DoubleArray,
    val fetlock: DoubleArray,
    val hoof: DoubleArray,
)

/** The rest pose of the skeleton joints. */
class RestPose(
    val root: DoubleArray,
    val spineFront: DoubleArray,
    val spineRear: DoubleArray,
    val belly: DoubleArray,
    val front: FrontRest,
    val hind: HindRest,
    val neck: List<DoubleArray>,
    val head: DoubleArray,
    val tail: List<DoubleArray>,
)

val REST =
    RestPose(
        root = p(0.0, 1.3, 0.0),
        spineFront = p(0.0, 1.36, 0.42),
        spineRear = p(0.0, 1.36, -0.42),
        belly = p(0.0, 1.08, -0.05),
        front =
            FrontRest(
                scapula = p(0.16, 1.5, 0.44),
                shoulder = p(0.15, 1.16, 0.78),
                elbow = p(0.175, 0.94, 0.56),
                knee = p(0.165, 0.5, 0.6),
                fetlock = p(0.16, 0.17, 0.605),
                hoof = p(0.16, 0.0, 0.7),
            ),
        hind =
            HindRest(
                hip = p(0.16, 1.3, -0.56),
                stifle = p(0.2, 0.98, -0.36),
                hock = p(0.165, 0.55, -0.74),
                fetlock = p(0.155, 0.17, -0.7),
                hoof = p(0.155, 0.0, -0.615),
            ),
        neck = listOf(p(0.0, 1.36, 0.6), p(0.0, 1.64, 0.97), p(0.0, 1.9, 1.24)),
        head = p(0.0, 2.05, 1.41),
        tail =
            listOf(
                p(0.0, 1.56, -0.84),
                p(0.0, 1.46, -0.98),
                p(0.0, 1.25, -1.05),
                p(0.0, 0.99, -1.07),
                p(0.0, 0.74, -1.06),
            ),
    )
