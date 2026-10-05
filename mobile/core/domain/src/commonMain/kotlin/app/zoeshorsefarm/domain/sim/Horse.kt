package app.zoeshorsefarm.domain.sim

/** Gait of the horse. [id] is the stable name. BACK is the rein-back (negative speed). */
enum class Gait(
    val id: String,
) {
    HALT("halt"),
    WALK("walk"),
    TROT("trot"),
    CANTER("canter"),
    BACK("back"),
}

enum class JumpPhase(
    val id: String,
) {
    TAKEOFF("takeoff"),
    FLIGHT("flight"),
    LANDING("landing"),
}

enum class RefusalType(
    val id: String,
) {
    /** The horse brakes in front of the fence and stands. */
    STOP("stop"),

    /** The horse runs out past the fence. */
    RUNOUT("runout"),
}

/** Why the horse refuses (concept rules 16, 17, 20). */
enum class RefusalReason(
    val id: String,
) {
    GAIT("gait"),
    SPEED("speed"),
    ANGLE("angle"),
}

/** Jump in progress: phase, progress 0..1 within the phase, and the element. Live: reused. */
class JumpView(
    var phase: JumpPhase = JumpPhase.TAKEOFF,
    var progress: Double = 0.0,
    var elementId: String = "",
)

/** Hop in progress: progress 0..1. Live: reused. */
class HopView(
    var progress: Double = 0.0,
)

/** Refusal in progress: type, progress 0..1, and the element. Live: reused. */
class RefusalView(
    var type: RefusalType = RefusalType.STOP,
    var progress: Double = 0.0,
    var elementId: String = "",
)

/**
 * The horse as the simulation exposes it to the view (position, heading, speed, gait and the
 * animation phases). The ride simulation mutates it in place; consumers read it every frame and
 * must not keep the nested views ([jump], [hop], [refusal]), they are reused between steps.
 * `turnRate` is rad/s, positive = right turn.
 */
class Horse(
    override var x: Double = 0.0,
    override var z: Double = 0.0,
    override var heading: Double = 0.0,
    var speed: Double = 0.0,
    var gait: Gait = Gait.HALT,
    var gallop: Boolean = false,
    var y: Double = 0.0,
    var jump: JumpView? = null,
    var hop: HopView? = null,
    var refusal: RefusalView? = null,
    var turnRate: Double = 0.0,
) : HorsePose
