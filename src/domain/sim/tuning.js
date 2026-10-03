// Tuning values (concept glossary "tuning value"). All numbers that set the game feel live
// here. Units: meters, seconds, radians.

const DEG = Math.PI / 180;

export const ARENA = Object.freeze({ width: 40, length: 70 });

export const POLE_LENGTH = 3.5;
export const STAND_WIDTH = 0.15;
export const COMBI_DISTANCE = 7.3;

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
    // turn rate on the spot (rad/s); it drops with speed,
    // so the turn radius v / ω grows with speed
    turnInPlace: 1.6,
    turnSpeedRef: 5.0,
    // steering responsiveness (1/s)
    turnResponse: 10,
    // gamepad/touch stick deflection below this value counts as centered
    stickDeadZone: 0.12,
    // ending the gallop below trotMin (strike-off): acceleration (m/s²) up to the working trot
    // instead of dropping to a walk
    gallopEndTrotUp: 2.5,
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
};
