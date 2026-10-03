// Gangarten als reine Funktionen (ohne three.js): Fußfolge, Takt, Bodenkontakt-Anteil und
// Huf-Bahnen. Beinindex: 0 = LV (links vorn), 1 = RV, 2 = LH, 3 = RH.
// Takt nach Reitlehre: Schritt ≈ 55/min, Trab ≈ 80/min, Galopp ≈ 100/min; Tempo steigt
// hauptsächlich über die Schrittlänge. Fußgleiten wird vermieden: Hufweg in der Stützphase
// L = duty · speed / frequency (Huf ruht relativ zum Boden).
import { clamp, lerp, smoothstep } from './math.js';

export const GAIT_KEYS = ['halt', 'walk', 'trot', 'canter'];

const powSafe = (x, e) => Math.pow(Math.max(x, 1e-4), e);

export const GAITS = {
  walk: {
    // Viertakt: LH → LV → RH → RV
    offsets: [0.25, 0.75, 0, 0.5],
    duty: (v) => lerp(0.64, 0.58, clamp(v / 1.8, 0, 1)),
    freq: (v) => 0.92 * powSafe(Math.max(v, 0.25) / 1.6, 0.35),
    lift: [0.1, 0.1, 0.09, 0.09],
    flex: [1.05, 1.05, 0.45, 0.45],
    past: [0.9, 0.9, 0.7, 0.7],
    center: [0.03, 0.03, 0.0, 0.0],
    sink: 0.015,
  },
  trot: {
    // Zweitakt diagonal: LV + RH, dann RV + LH
    offsets: [0, 0.5, 0.5, 0],
    duty: (v) => lerp(0.44, 0.36, clamp((v - 2) / 2, 0, 1)),
    freq: (v) => 1.33 * powSafe(Math.max(v, 1.2) / 3.2, 0.25),
    lift: [0.2, 0.2, 0.15, 0.15],
    flex: [1.75, 1.75, 0.8, 0.8],
    past: [1.4, 1.4, 1.1, 1.1],
    center: [0.07, 0.07, 0.02, 0.02],
    sink: 0.045,
  },
  canter: {
    // Linksgalopp: RH → (LH + RV) → LV → Schwebephase. Rechtsgalopp gespiegelt.
    offsets: [0.47, 0.26, 0.22, 0],
    offsetsRight: [0.26, 0.47, 0, 0.22],
    duty: (v) => lerp(0.38, 0.3, clamp((v - 4.5) / 3.5, 0, 1)),
    freq: (v) => 1.67 * powSafe(Math.max(v, 3) / 6, 0.2),
    lift: [0.27, 0.27, 0.2, 0.2],
    flex: [1.95, 1.95, 1.0, 1.0],
    past: [1.5, 1.5, 1.2, 1.2],
    center: [0.1, 0.1, 0.05, 0.05],
    sink: 0.055,
  },
};

/** Maximaler Hufweg je Stützphase (darüber würde das Bein überstreckt). */
export const MAX_STANCE_TRAVEL = 1.15;

export const legPhase = (phi, offset) => (((phi - offset) % 1) + 1) % 1;

export function offsetsFor(gait, lead) {
  const g = GAITS[gait];
  return gait === 'canter' && lead < 0 ? g.offsetsRight : g.offsets;
}

/** Gemischte Taktfrequenz (Hz) für Gewichte {walk, trot, canter} bei Tempo v. */
export function blendedFrequency(weights, v) {
  let f = 0;
  for (const k of ['walk', 'trot', 'canter']) {
    if (weights[k] > 0) f += weights[k] * GAITS[k].freq(v);
  }
  return f;
}

/**
 * Huf-Bahn eines Beins in einer Gangart. f = (gemischte) Taktfrequenz, v = Tempo.
 * Rückgabe: dz (vor/zurück relativ zur Neutralstellung, Modellraum), y (Hubhöhe),
 * flex (Karpus- bzw. Sprunggelenk-Beugung), past (Fessel-Einklappen), sink (Fessel-Senken),
 * stance (true in der Stützphase).
 */
export function legSample(gait, leg, phi, v, f, lead = 1, out = {}) {
  const g = GAITS[gait];
  const d = g.duty(v);
  const p = legPhase(phi, offsetsFor(gait, lead)[leg]);
  const L = f > 1e-4 ? Math.min(MAX_STANCE_TRAVEL, (d * v) / f) : 0;
  const c = g.center[leg];
  // Heben skaliert mit dem Tempo innerhalb der Gangart etwas mit (langsamer Schritt: flacher)
  const liftScale = gait === 'walk' ? lerp(0.45, 1, clamp(v / 1.4, 0, 1)) : 1;
  if (p < d) {
    const u = p / d;
    out.dz = c + L / 2 - L * u;
    out.y = 0;
    out.flex = 0;
    out.past = 0;
    out.sink = g.sink * Math.sin(Math.PI * u);
    out.stance = true;
  } else {
    const u = (p - d) / (1 - d);
    const e = u - Math.sin(2 * Math.PI * u) / (2 * Math.PI);
    out.dz = c - L / 2 + L * e;
    out.y = g.lift[leg] * liftScale * Math.sin(Math.PI * Math.pow(u, 0.75));
    out.flex = g.flex[leg] * liftScale * Math.pow(Math.sin(Math.PI * Math.pow(u, 0.65)), 1.3);
    out.past = g.past[leg] * liftScale * Math.sin(Math.PI * Math.pow(u, 0.55));
    out.sink = 0;
    out.stance = false;
  }
  return out;
}

/** Rumpfbewegung je Gangart: Heben/Senken, Nicken, Kopfnicken, Rollen. */
export function bodySample(gait, phi, v, lead = 1, out = {}) {
  const TAU = Math.PI * 2;
  out.bob = 0;
  out.pitch = 0;
  out.neck = 0;
  out.roll = 0;
  if (gait === 'walk') {
    const a = clamp(v / 1.6, 0, 1);
    out.bob = -0.012 * a * (0.5 + 0.5 * Math.cos(2 * TAU * (phi - 0.12)));
    // Kopfnicken zweimal je Zyklus, tief wenn ein Vorderbein fußt
    out.neck = 0.06 * a * Math.cos(2 * TAU * (phi - 0.3));
    out.roll = 0.018 * a * Math.sin(TAU * (phi - 0.1));
  } else if (gait === 'trot') {
    const d = GAITS.trot.duty(v);
    out.bob = -0.045 * (0.5 + 0.5 * Math.cos(2 * TAU * (phi - d / 2)));
    out.neck = 0.015 * Math.cos(2 * TAU * (phi - d / 2));
    out.roll = 0.006 * Math.sin(TAU * phi);
  } else if (gait === 'canter') {
    out.bob = -0.065 * (0.5 + 0.5 * Math.cos(TAU * (phi - 0.36)));
    // Wiegen: Nase hoch, wenn die Hinterhand fußt; Nase tief beim führenden Vorderbein
    out.pitch = 0.07 * Math.sin(TAU * (phi - 0.33));
    out.neck = 0.11 * Math.sin(TAU * (phi - 0.28));
    out.roll = 0.012 * lead * Math.sin(TAU * (phi - 0.2));
  }
  return out;
}

/** Glatter Übergang eines Gewichts in Richtung Ziel (exponentiell, frame-unabhängig). */
export function approach(current, target, rate, dt) {
  return target + (current - target) * Math.exp(-rate * dt);
}

/** Hilfsfunktion für Gewichte mit weichem Ein-/Ausblenden über progress 0..1. */
export function bump(p, inEnd, outStart) {
  return smoothstep(0, inEnd, p) * (1 - smoothstep(outStart, 1, p));
}
