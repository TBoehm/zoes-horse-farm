// Tuning values (concept glossary "tuning value"). All numbers that set the game feel live
// here. Units: meters, seconds, radians.

const DEG = Math.PI / 180;

export const ARENA = Object.freeze({ width: 40, length: 70 });

export const POLE_LENGTH = 3.5;
export const STAND_WIDTH = 0.15;
export const TUNING = {
  speeds: {
    // below this speed the horse counts as standing (gait halt)
    haltBelow: 0.15,
    walkMax: 1.8,
    trotMin: 2.0,
    trotMedium: 3.2,
    trotMax: 4.0,
    canterMin: 4.5,
    // jumping canter ≈ 325–350 m/min
    canterMedium: 5.8,
    canterMax: 8.0,
  },
  // Approach distance (glossary "approach"): closer than this distance before an obstacle
  approachDistance: 12,

  // The horse's reference point is the ground under chest/forelegs; all takeoff distances
  // are measured from this point.
  horse: {
    // distance to the fence
    radius: 0.8,
    // half body width (lateral distance to stands)
    halfWidth: 0.45,
    // minimum distance before a pole as long as no jump happens
    frontMargin: 0.15,
    // the hindquarters (rear point) are this far behind the reference point and also stay
    // inside the arena, rearMargin away from the fence
    rearLength: 1.5,
    rearMargin: 0.3,
  },

  control: {
    // speed change (m/s²) at full deflection of W or S
    speedUp: 2.0,
    slowDown: 3.0,
    // acceleration when striking off into canter up to canterMin
    canterDepart: 3.0,
    // gentle deceleration after the gallop ends down to trotMedium
    settleDecel: 2.5,
    // turn rate on the spot (rad/s); it drops with speed as ω = turnInPlace / (1 + v / turnSpeedRef),
    // so the turn radius v / ω grows with speed. Reference values (real horses): 10 m volte
    // (r = 5 m) at walk/trot, 20 m circle (r = 10 m) at canter, jump-off turns at jumping canter
    // r ≈ 6–8 m, turn on the haunches ≈ on the spot. The game is a bit more agile (child
    // audience): full lock gives r ≈ 1.0 m at walk (1.5 m/s), 2.7 m at medium trot (3.2 m/s),
    // 6.3 m at jumping canter (5.8 m/s) and 10.4 m at full gallop (8 m/s); lateral acceleration
    // v·ω stays ≤ 6.3 m/s² (real: 2.5 m/s² on a 20 m canter circle, ≈ 6–8 m/s² in tight turns).
    // Before SRT-007: 1.6 / 5.0 / 10 (r = 1.2 / 3.3 / 7.8 / 13 m).
    turnInPlace: 1.8,
    turnSpeedRef: 6.0,
    // steering responsiveness (1/s): the turn rate reaches 90 % of its target in ~0.19 s
    turnResponse: 12,
    // gamepad/touch stick: deflection (share of the stick radius) below this value counts as
    // centered; scaled radial dead zone, see joystick-mapping.js
    stickDeadZone: 0.12,
    // stick: sideways deflection (share of the stick radius) that already gives full steering
    // lock; must stay ≤ 2/3 so the tightest turn is reached before the stop (rule 10)
    stickSteerFull: 0.6,
    // ending the gallop below trotMin (strike-off): acceleration (m/s²) up to the working trot
    // instead of dropping to a walk
    gallopEndTrotUp: 2.5,
  },

  // Rein-back (concept rule 9). A horse reins back in a slow two-beat diagonal gait (same footfall
  // as the trot, reversed; Mad Barn "Guide to Horse Gaits", USDF "We Got Rhythm"). No published
  // speed exists, so the values are estimated from the dressage test requirement of a few clear,
  // calm steps: ≈ 0.5 m per diagonal step at about one stride per second, well below the walk.
  reinBack: {
    // standing with S (or the stick down) held: pause before the horse starts to step back (< 0.5 s)
    delayS: 0.35,
    // backing speed (m/s) at full deflection; the stick deflection scales it
    maxSpeed: 0.5,
    // speed up from the first step to the target speed (m/s²)
    accel: 1.5,
    // slow down to a stop when S is released or the deflection is reduced (m/s²)
    decel: 3.0,
    // when fence or obstacle hold the hindquarters back, the backward travel of a step falls
    // below this share of the intended distance: the horse stops (until S is released)
    blockedShare: 0.98,
  },

  fence: {
    // angle to the wall normal below which an impact counts as frontal
    frontalAngle: 35 * DEG,
    // turn rate (rad/s) at which the heading eases parallel to the wall on a glancing hit
    slideTurnRate: 6.0,
    // after a frontal stop the stop only counts as "left" again once the horse is this far
    // (m) from the wall or turned away
    releaseGap: 0.05,
  },

  jump: {
    maxAngle: 30 * DEG,
    // difficulty 0..1 from height and spread
    difficulty: { heightRef: 0.4, spreadWeight: 0.5, range: 0.8 },
    // center of the takeoff zone (m before the leading edge); real ≈ 1.3–1.8 m at 40–85 cm
    zone: {
      base: 1.0,
      perHeight: 0.8,
      // an oxer is approached slightly closer than a vertical
      perSpread: -0.2,
      perSpeed: 0.08,
      speedRef: 4.0,
      minCenter: 0.8,
      minNear: 0.5,
      // minimum speed used to compute the zone (halt/walk)
      minSpeed: 2.0,
    },
    // half time window of the zone (s); depth = 2 · window · speed
    window: {
      cross: 0.22,
      base: 0.22,
      perHeight: 0.14,
      perSpread: 0.08,
      min: 0.08,
      // height (m) above which the window narrows
      heightRef: 0.4,
    },
    // reach starts this much time (at least reachMin m) before the zone
    reachLead: 0.35,
    reachMin: 0.6,
    // last takeoff point: this much time behind the zone, but never closer than min and never
    // farther than this share of the near edge
    lastPoint: { lead: 0.12, min: 0.3, maxShareOfNear: 0.9 },
    // angle tolerance of the safe core
    safeAngle: { base: 12 * DEG, perDifficulty: 2 * DEG },
    // target speed range (m/s)
    speedBand: {
      crossMin: 2.6,
      crossMax: 7.2,
      crossSelfMin: 2.2,
      base: 3.1,
      perHeight: 2.6,
      perSpread: 0.4,
      width: 2.2,
      // self-jump minimum speed = target range minimum minus selfMargin
      selfMargin: 0.8,
    },
    // knockdown risk per deviation, scaled with severity = base + gain · difficulty
    risk: {
      perSpeed: 0.22, // per m/s outside the target range
      perDistance: 0.4, // per m outside the takeoff zone
      perDegree: 0.02, // per degree above the angle tolerance
      severityBase: 0.5,
      severityGain: 1.5,
      selfBase: 0.3,
      selfPerDifficulty: 0.2,
      factorCap: 0.95,
      max: 0.9,
    },
    flight: {
      landBase: 0.4,
      landPerTakeoff: 0.6,
      landPerHeight: 0.5,
      landMin: 1.2,
      landMax: 3.0,
      minSpeed: 2.0,
      takeoffShare: 0.2,
      landingShare: 0.25,
      clearance: 0.25,
    },
    hop: { duration: 0.4, height: 0.2 },
    // oxer, risk > 0 in the middle of the zone: chance that the pole crossed first is chosen
    railChoice: { firstProbability: 0.5 },
    // Space pressed this long before landing counts for the next obstacle (s)
    spaceBuffer: 0.15,
  },

  refusal: {
    stopDuration: 1.2,
    // the stop ends this far before the leading edge, so the horse clearly stands "in front"
    stopMargin: 0.4,
    // the stop decelerates at least this much (m/s²), however much room there is
    stopDecelMin: 4,
    // smallest braking distance (m) assumed for the stop deceleration (avoids division by ~0)
    minStopRoom: 0.05,
    runoutDuration: 1.2,
    // turn rate when evading/running past (rad/s)
    maneuverTurnRate: 5.0,
    clearMargin: 0.1,
    maneuverTimeout: 5.0,
    // from this lateral component (sin 10°) on, the course direction decides the evasion side
    driftSide: Math.sin(10 * DEG),
  },

  // fallen rails are rebuilt this long after the fall (rules 26, 29, 41); course run and free mode
  rebuildDelayS: 3,
  // how long the "missing obstacle" hint stays visible (s)
  missingHintS: 5,

  sim: { maxDt: 0.1, substep: 1 / 120 },

  // course building (concept rule 25): related distances and oxer depths
  course: {
    // canter stride (m)
    stride: 3.7,
    // landing or takeoff distance next to an obstacle edge (m)
    takeoffLanding: 1.8,
    // straight stretch after landing before a turn (m)
    landingFree: 8,
    // oxer depth by the height of the top pole: first entry with height <= maxHeight, else tall
    oxerSpread: {
      byMaxHeight: [
        { maxHeight: 0.7, spread: 0.7 },
        { maxHeight: 0.8, spread: 0.8 },
      ],
      tall: 0.9,
    },
  },
};

// Combination distance a→b (center to center): landing + one stride + takeoff (≈ 7.3 m).
export const COMBI_DISTANCE = 2 * TUNING.course.takeoffLanding + TUNING.course.stride;
