// Spielwerte (Konzept §Begriffe „Spielwert"). Alle Zahlen, die das Spielgefühl einstellen,
// stehen hier. Einheiten: Meter, Sekunden, Radiant.

const DEG = Math.PI / 180;

export const ARENA = Object.freeze({ width: 40, length: 70 });

export const POLE_LENGTH = 3.5;
export const STAND_WIDTH = 0.15;
export const COMBI_DISTANCE = 7.3;

export const TUNING = {
  speeds: {
    // unter diesem Tempo gilt das Pferd als stehend (Gangart Halt)
    haltBelow: 0.15,
    walkMax: 1.8,
    trotMin: 2.0,
    trotMedium: 3.2,
    trotMax: 4.0,
    canterMin: 4.5,
    // Springgalopp ≈ 325–350 m/min
    canterMedium: 5.8,
    canterMax: 8.0,
  },
  // Anreitabstand (Begriffe „Anreiten"): näher als dieser Abstand vor einem Hindernis
  approachDistance: 12,

  // Bezugspunkt des Pferdes ist der Boden unter Brust/Vorderbeinen; alle Absprung-Abstände
  // werden von diesem Punkt aus gemessen.
  horse: {
    // Abstand zur Umzäunung
    radius: 0.8,
    // halbe Körperbreite (seitlicher Abstand zu Ständern)
    halfWidth: 0.45,
    // Mindestabstand vor einer Stange, solange nicht gesprungen wird
    frontMargin: 0.15,
  },

  control: {
    // Tempoänderung (m/s²) bei voller Auslenkung von W bzw. S
    speedUp: 2.0,
    slowDown: 3.0,
    // Beschleunigung beim Angaloppieren bis canterMin
    canterDepart: 3.0,
    // sanftes Abbremsen nach Galopp-Ende bis trotMedium
    settleDecel: 2.5,
    // Wendegeschwindigkeit auf der Stelle (rad/s); sie sinkt mit dem Tempo,
    // der Kurvenradius v / ω wächst dadurch mit dem Tempo
    turnInPlace: 1.6,
    turnSpeedRef: 5.0,
    // Ansprechen der Lenkung (1/s)
    turnResponse: 10,
  },

  fence: {
    // Winkel zur Wand-Normale, unter dem ein Aufprall als frontal gilt
    frontalAngle: 35 * DEG,
  },

  jump: {
    maxAngle: 30 * DEG,
    // Schwierigkeit 0..1 aus Höhe und Spread
    difficulty: { heightRef: 0.4, spreadWeight: 0.5, range: 0.8 },
    // Zentrum der Absprungzone (m vor der Vorderkante); real ≈ 1,3–1,8 m bei 40–85 cm
    zone: {
      base: 1.0,
      perHeight: 0.8,
      // Oxer wird etwas dichter angeritten als ein Steilsprung
      perSpread: -0.2,
      perSpeed: 0.08,
      speedRef: 4.0,
      minCenter: 0.8,
      minNear: 0.5,
      // Tempo, mit dem die Zone mindestens berechnet wird (Halt/Schritt)
      minSpeed: 2.0,
    },
    // halbes Zeitfenster der Zone (s); Tiefe = 2 · Fenster · Tempo
    window: { cross: 0.22, base: 0.22, perHeight: 0.14, perSpread: 0.08, min: 0.08 },
    // Reichweite beginnt so viel Zeit (mindestens reachMin m) vor der Zone
    reachLead: 0.35,
    reachMin: 0.6,
    // letzter Absprungpunkt: so viel Zeit hinter der Zone, aber nie dichter als min
    lastPoint: { lead: 0.12, min: 0.3 },
    // Winkel-Toleranz des sicheren Kerns
    safeAngle: { base: 12 * DEG, perDifficulty: 2 * DEG },
    // Tempo-Sollbereich (m/s)
    speedBand: {
      crossMin: 2.6,
      crossMax: 7.2,
      crossSelfMin: 2.2,
      base: 3.1,
      perHeight: 2.6,
      perSpread: 0.4,
      width: 2.2,
      // Selbstsprung-Mindesttempo = Sollbereich-Minimum minus selfMargin
      selfMargin: 0.8,
    },
    // Abwurfrisiko je Abweichung, skaliert mit severity = base + gain · Schwierigkeit
    risk: {
      perSpeed: 0.22, // je m/s außerhalb des Sollbereichs
      perDistance: 0.4, // je m außerhalb der Absprungzone
      perDegree: 0.02, // je Grad über der Winkel-Toleranz
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
  },

  refusal: {
    stopDuration: 1.2,
    stopMargin: 0.15,
    runoutDuration: 1.2,
    // Wendegeschwindigkeit beim Ausweichen/Vorbeilaufen (rad/s)
    maneuverTurnRate: 5.0,
    clearMargin: 0.1,
    maneuverTimeout: 5.0,
  },

  sim: { maxDt: 0.1, substep: 1 / 120 },
};
