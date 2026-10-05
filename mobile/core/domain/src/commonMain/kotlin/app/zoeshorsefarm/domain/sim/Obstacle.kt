package app.zoeshorsefarm.domain.sim

/** Kind of an obstacle element. [id] is the stable name (save files, i18n, 3D view). */
enum class ElementKind(
    val id: String,
) {
    CROSS("cross"),
    VERTICAL("vertical"),
    OXER("oxer"),
}

/** Something with a position and a jump direction in the arena: the coordinate system of a fence. */
interface Placed {
    val x: Double
    val z: Double

    /** Rotation such that +n = (sin rot, cos rot) is the jump direction. */
    val rot: Double
}

/** A bare placement without an obstacle (a pose in the element coordinate system). */
data class Placement(
    override val x: Double,
    override val z: Double,
    override val rot: Double,
) : Placed

/**
 * One fence element: a cross, vertical or oxer. [height] is the top pole (m), [spread] the oxer
 * depth (m, 0 for crosses and verticals). Coordinates per the spec "Hindernisse": meters, arena
 * x in [-20, 20], z in [-35, 35].
 */
data class Element(
    val id: String,
    val kind: ElementKind,
    val height: Double,
    val spread: Double,
    override val x: Double,
    override val z: Double,
    override val rot: Double,
) : Placed

/**
 * An obstacle of a course: one element or a double combination (a, b). [number] is the number in the
 * course (null in free mode); [directed] means it may only be jumped in direction +n.
 */
data class Obstacle(
    val number: Int?,
    val elements: List<Element>,
    val directed: Boolean,
)
