package app.zoeshorsefarm.domain.sim

import kotlin.math.PI
import kotlin.math.sin

// Tuning values (concept glossary "tuning value"). All numbers that set the game feel live
// here. Units: meters, seconds, radians. The classes are immutable data classes with the game
// values as defaults, so a test can build a variant with `TUNING.copy(...)`.

private const val DEG = PI / 180

/** Arena size in meters: x in [-width/2, width/2], z in [-length/2, length/2]. */
data class ArenaSize(
    val width: Double,
    val length: Double,
)

val ARENA = ArenaSize(width = 40.0, length = 70.0)

const val POLE_LENGTH = 3.5
const val STAND_WIDTH = 0.15

data class SpeedTuning(
    // below this speed the horse counts as standing (gait halt)
    val haltBelow: Double = 0.15,
    val walkMax: Double = 1.8,
    val trotMin: Double = 2.0,
    val trotMedium: Double = 3.2,
    val trotMax: Double = 4.0,
    val canterMin: Double = 4.5,
    // jumping canter ~ 325-350 m/min
    val canterMedium: Double = 5.8,
    val canterMax: Double = 8.0,
)

// The horse's reference point is the ground under chest/forelegs; all takeoff distances
// are measured from this point.
data class HorseTuning(
    // distance to the fence
    val radius: Double = 0.8,
    // half body width (lateral distance to stands)
    val halfWidth: Double = 0.45,
    // minimum distance before a pole as long as no jump happens
    val frontMargin: Double = 0.15,
    // the hindquarters (rear point) are this far behind the reference point and also stay
    // inside the arena, rearMargin away from the fence
    val rearLength: Double = 1.5,
    val rearMargin: Double = 0.3,
)

data class ControlTuning(
    // speed change (m/s^2) at full deflection of W or S
    val speedUp: Double = 2.0,
    val slowDown: Double = 3.0,
    // acceleration when striking off into canter up to canterMin
    val canterDepart: Double = 3.0,
    // gentle deceleration after the gallop ends down to trotMedium
    val settleDecel: Double = 2.5,
    // turn rate on the spot (rad/s); it drops with speed as w = turnInPlace / (1 + v / turnSpeedRef),
    // so the turn radius v / w grows with speed. Reference values (real horses): 10 m volte
    // (r = 5 m) at walk/trot, 20 m circle (r = 10 m) at canter, jump-off turns at jumping canter
    // r ~ 6-8 m, turn on the haunches ~ on the spot (real lateral acceleration v*w: 2.5 m/s^2 on
    // a 20 m canter circle, ~ 6-8 m/s^2 in tight turns).
    // The game is deliberately MUCH more agile than reality (SRT-009): the child found turning
    // "far too hard" and asked for it to be about 50 % better, so every radius is 1/1.5 of the
    // earlier value (turnInPlace 1.8 -> 2.7 rad/s, same speed falloff). Full lock now gives
    // r ~ 0.7 m at walk (1.5 m/s), 1.8 m at medium trot (3.2 m/s), 4.2 m at jumping canter
    // (5.8 m/s) and 6.9 m at full gallop (8 m/s); v*w peaks at ~ 9.3 m/s^2 (full gallop), kept
    // below 1 g on purpose. Agility beats realism in a children's game, but the horse stays
    // controllable: first-order turn-in (no overshoot) and the axial stick dead zone below.
    val turnInPlace: Double = 2.7,
    val turnSpeedRef: Double = 6.0,
    // steering responsiveness (1/s): the turn rate reaches 90 % of its target in ~0.13 s
    // (12 -> ~0.19 s before SRT-009); a first-order filter, so it never overshoots
    val turnResponse: Double = 18.0,
    // gamepad/touch stick: deflection (share of the stick radius) below this value counts as
    // centered; scaled radial dead zone, see joystick-mapping
    val stickDeadZone: Double = 0.12,
    // stick: sideways deflection (share of the stick radius) that already gives full steering
    // lock; must stay <= 2/3 so the tightest turn is reached before the stop (rule 10). Lowered
    // from 0.6 to 0.5 (SRT-009): full lock comes earlier, a 45 degree forward hold already turns fully.
    val stickSteerFull: Double = 0.5,
    // stick: axial dead zones on the unit direction components (hybrid dead zone), so the two
    // controls do not bleed into each other. A hold within +-asin(0.2) ~ +-11.5 degrees of horizontal
    // changes no speed (turning on the spot stays a turn, no rein-back or walk-off); a hold
    // within +-asin(0.12) ~ +-7 degrees of vertical does not steer (straight approach, thumb wobble).
    val stickAxialThrottle: Double = 0.2,
    val stickAxialSteer: Double = 0.12,
    // ending the gallop below trotMin (strike-off): acceleration (m/s^2) up to the working trot
    // instead of dropping to a walk
    val gallopEndTrotUp: Double = 2.5,
)

// Rein-back (concept rule 9). A horse reins back in a slow two-beat diagonal gait (same footfall
// as the trot, reversed; Mad Barn "Guide to Horse Gaits", USDF "We Got Rhythm"). No published
// speed exists, so the values are estimated from the dressage test requirement of a few clear,
// calm steps: ~ 0.5 m per diagonal step at about one stride per second, well below the walk.
data class ReinBackTuning(
    // standing with S (or the stick down) held: pause before the horse starts to step back (< 0.5 s);
    // long enough that a child who is still braking has time to release before it reverses
    val delayS: Double = 0.45,
    // backing speed (m/s) at full deflection; the stick deflection scales it
    val maxSpeed: Double = 0.5,
    // speed up from the first step to the target speed (m/s^2)
    val accel: Double = 1.5,
    // slow down to a stop when S is released or the deflection is reduced (m/s^2)
    val decel: Double = 3.0,
    // when fence or obstacle hold the hindquarters back, the backward travel of a step falls
    // below this share of the intended distance: the horse stops (until S is released)
    val blockedShare: Double = 0.98,
    // clearance (m) the rear point keeps from an obstacle's pole line while backing: the tail and
    // buttocks reach ~ 0.27 m behind the rear point (the fence uses horse.rearMargin = 0.3)
    val rearClearance: Double = 0.35,
)

data class FenceTuning(
    // angle to the wall normal below which an impact counts as frontal
    val frontalAngle: Double = 35 * DEG,
    // turn rate (rad/s) at which the heading eases parallel to the wall on a glancing hit
    val slideTurnRate: Double = 6.0,
    // after a frontal stop the stop only counts as "left" again once the horse is this far
    // (m) from the wall or turned away
    val releaseGap: Double = 0.05,
)

// difficulty 0..1 from height and spread
data class DifficultyTuning(
    val heightRef: Double = 0.4,
    val spreadWeight: Double = 0.5,
    val range: Double = 0.8,
)

// center of the takeoff zone (m before the leading edge); real ~ 1.3-1.8 m at 40-85 cm
data class ZoneTuning(
    val base: Double = 1.0,
    val perHeight: Double = 0.8,
    // an oxer is approached slightly closer than a vertical
    val perSpread: Double = -0.2,
    val perSpeed: Double = 0.08,
    val speedRef: Double = 4.0,
    val minCenter: Double = 0.8,
    val minNear: Double = 0.5,
    // minimum speed used to compute the zone (halt/walk)
    val minSpeed: Double = 2.0,
)

// half time window of the zone (s); depth = 2 * window * speed
data class WindowTuning(
    val cross: Double = 0.22,
    val base: Double = 0.22,
    val perHeight: Double = 0.14,
    val perSpread: Double = 0.08,
    val min: Double = 0.08,
    // height (m) above which the window narrows
    val heightRef: Double = 0.4,
)

// last takeoff point: this much time behind the zone, but never closer than min and never
// farther than this share of the near edge
data class LastPointTuning(
    val lead: Double = 0.12,
    val min: Double = 0.3,
    val maxShareOfNear: Double = 0.9,
)

// angle tolerance of the safe core
data class SafeAngleTuning(
    val base: Double = 12 * DEG,
    val perDifficulty: Double = 2 * DEG,
)

// target speed range (m/s)
data class SpeedBandTuning(
    val crossMin: Double = 2.6,
    val crossMax: Double = 7.2,
    val crossSelfMin: Double = 2.2,
    val base: Double = 3.1,
    val perHeight: Double = 2.6,
    val perSpread: Double = 0.4,
    val width: Double = 2.2,
    // self-jump minimum speed = target range minimum minus selfMargin
    val selfMargin: Double = 0.8,
)

// knockdown risk per deviation, scaled with severity = base + gain * difficulty
data class RiskTuning(
    // per m/s outside the target range
    val perSpeed: Double = 0.22,
    // per m outside the takeoff zone
    val perDistance: Double = 0.4,
    // per degree above the angle tolerance
    val perDegree: Double = 0.02,
    val severityBase: Double = 0.5,
    val severityGain: Double = 1.5,
    val selfBase: Double = 0.3,
    val selfPerDifficulty: Double = 0.2,
    val factorCap: Double = 0.95,
    val max: Double = 0.9,
)

data class FlightTuning(
    val landBase: Double = 0.4,
    val landPerTakeoff: Double = 0.6,
    val landPerHeight: Double = 0.5,
    val landMin: Double = 1.2,
    val landMax: Double = 3.0,
    val minSpeed: Double = 2.0,
    val takeoffShare: Double = 0.2,
    val landingShare: Double = 0.25,
    val clearance: Double = 0.25,
)

data class HopTuning(
    val duration: Double = 0.4,
    val height: Double = 0.2,
)

// oxer, risk > 0 in the middle of the zone: chance that the pole crossed first is chosen
data class RailChoiceTuning(
    val firstProbability: Double = 0.5,
)

data class JumpTuning(
    val maxAngle: Double = 30 * DEG,
    val difficulty: DifficultyTuning = DifficultyTuning(),
    val zone: ZoneTuning = ZoneTuning(),
    val window: WindowTuning = WindowTuning(),
    // reach starts this much time (at least reachMin m) before the zone
    val reachLead: Double = 0.35,
    val reachMin: Double = 0.6,
    val lastPoint: LastPointTuning = LastPointTuning(),
    val safeAngle: SafeAngleTuning = SafeAngleTuning(),
    val speedBand: SpeedBandTuning = SpeedBandTuning(),
    val risk: RiskTuning = RiskTuning(),
    val flight: FlightTuning = FlightTuning(),
    val hop: HopTuning = HopTuning(),
    val railChoice: RailChoiceTuning = RailChoiceTuning(),
    // Space pressed this long before landing counts for the next obstacle (s)
    val spaceBuffer: Double = 0.15,
)

data class RefusalTuning(
    val stopDuration: Double = 1.2,
    // the stop ends this far before the leading edge, so the horse clearly stands "in front"
    val stopMargin: Double = 0.4,
    // the stop decelerates at least this much (m/s^2), however much room there is
    val stopDecelMin: Double = 4.0,
    // smallest braking distance (m) assumed for the stop deceleration (avoids division by ~0)
    val minStopRoom: Double = 0.05,
    val runoutDuration: Double = 1.2,
    // turn rate when evading/running past (rad/s)
    val maneuverTurnRate: Double = 5.0,
    val clearMargin: Double = 0.1,
    val maneuverTimeout: Double = 5.0,
    // from this lateral component (sin 10 degrees) on, the course direction decides the evasion side
    val driftSide: Double = sin(10 * DEG),
)

data class SimTuning(
    val maxDt: Double = 0.1,
    val substep: Double = 1.0 / 120,
)

/** Oxer depth for heights up to [maxHeight] (the first matching entry wins). */
data class OxerSpreadEntry(
    val maxHeight: Double,
    val spread: Double,
)

// oxer depth by the height of the top pole: first entry with height <= maxHeight, else tall
data class OxerSpreadTuning(
    val byMaxHeight: List<OxerSpreadEntry> =
        listOf(OxerSpreadEntry(0.7, 0.7), OxerSpreadEntry(0.8, 0.8)),
    val tall: Double = 0.9,
)

// course building (concept rule 25): related distances and oxer depths
data class CourseTuning(
    // canter stride (m)
    val stride: Double = 3.7,
    // landing or takeoff distance next to an obstacle edge (m)
    val takeoffLanding: Double = 1.8,
    // straight stretch after landing before a turn (m)
    val landingFree: Double = 8.0,
    val oxerSpread: OxerSpreadTuning = OxerSpreadTuning(),
)

data class Tuning(
    val speeds: SpeedTuning = SpeedTuning(),
    // Approach distance (glossary "approach"): closer than this distance before an obstacle
    val approachDistance: Double = 12.0,
    val horse: HorseTuning = HorseTuning(),
    val control: ControlTuning = ControlTuning(),
    val reinBack: ReinBackTuning = ReinBackTuning(),
    val fence: FenceTuning = FenceTuning(),
    val jump: JumpTuning = JumpTuning(),
    val refusal: RefusalTuning = RefusalTuning(),
    // fallen rails are rebuilt this long after the fall (rules 26, 29, 41); course run and free mode
    val rebuildDelayS: Double = 3.0,
    // how long the "missing obstacle" hint stays visible (s)
    val missingHintS: Double = 5.0,
    val sim: SimTuning = SimTuning(),
    val course: CourseTuning = CourseTuning(),
)

/** The game values. */
val TUNING = Tuning()

/** Combination distance a->b (center to center): landing + one stride + takeoff (~ 7.3 m). */
val COMBI_DISTANCE: Double = 2 * TUNING.course.takeoffLanding + TUNING.course.stride
