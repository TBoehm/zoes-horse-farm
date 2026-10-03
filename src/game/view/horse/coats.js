// Fellfarben und Kopfabzeichen als reine Daten (sRGB 0..1). Der Shader (material.js) setzt sie um.

export const COATS = ['chestnut', 'bay', 'black', 'grey', 'pinto']; // Fuchs, Brauner, Rappe, Schimmel, Schecke
export const MARKINGS = ['none', 'star', 'blaze', 'snip']; // keins, Stern, Blesse, Schnippe
export const DEFAULT_APPEARANCE = Object.freeze({ coat: 'bay', marking: 'star' });

const WHITE = [0.93, 0.92, 0.9];

// base: Grundfell, dark: Oberlinie/Apfelung, belly: Unterseite, hair: Mähne/Schweif,
// points: Anteil dunkler Beine unten (pointColor), muzzle: Maul/Nüstern-Haut, hoof: Horn
const PALETTE = {
  chestnut: {
    base: [0.55, 0.25, 0.1],
    dark: [0.42, 0.18, 0.07],
    belly: [0.62, 0.33, 0.15],
    hair: [0.72, 0.45, 0.22],
    points: 0,
    pointColor: [0.45, 0.2, 0.08],
    muzzle: [0.36, 0.2, 0.14],
    hoof: [0.2, 0.16, 0.13],
    white: WHITE,
    dapple: 0,
    pinto: 0,
  },
  bay: {
    base: [0.44, 0.22, 0.1],
    dark: [0.3, 0.14, 0.06],
    belly: [0.52, 0.29, 0.14],
    hair: [0.035, 0.03, 0.028],
    points: 1,
    pointColor: [0.04, 0.035, 0.032],
    muzzle: [0.14, 0.09, 0.07],
    hoof: [0.12, 0.1, 0.09],
    white: WHITE,
    dapple: 0,
    pinto: 0,
  },
  black: {
    base: [0.075, 0.068, 0.068],
    dark: [0.05, 0.046, 0.046],
    belly: [0.1, 0.085, 0.08],
    hair: [0.03, 0.028, 0.028],
    points: 0.6,
    pointColor: [0.045, 0.042, 0.042],
    muzzle: [0.09, 0.075, 0.07],
    hoof: [0.1, 0.09, 0.085],
    white: WHITE,
    dapple: 0,
    pinto: 0,
  },
  grey: {
    base: [0.86, 0.86, 0.84],
    dark: [0.6, 0.6, 0.6],
    belly: [0.9, 0.9, 0.88],
    hair: [0.9, 0.89, 0.86],
    points: 0.55,
    pointColor: [0.55, 0.55, 0.55],
    muzzle: [0.22, 0.21, 0.22],
    hoof: [0.25, 0.23, 0.21],
    white: [0.95, 0.95, 0.94],
    dapple: 1,
    pinto: 0,
  },
  pinto: {
    base: [0.33, 0.17, 0.08],
    dark: [0.24, 0.12, 0.06],
    belly: [0.38, 0.2, 0.1],
    hair: [0.08, 0.06, 0.05],
    points: 0,
    pointColor: [0.3, 0.15, 0.07],
    muzzle: [0.16, 0.1, 0.08],
    hoof: [0.18, 0.15, 0.12],
    white: WHITE,
    dapple: 0,
    pinto: 1,
  },
};

export function normalizeAppearance(a = {}) {
  return {
    coat: COATS.includes(a?.coat) ? a.coat : DEFAULT_APPEARANCE.coat,
    marking: MARKINGS.includes(a?.marking) ? a.marking : DEFAULT_APPEARANCE.marking,
  };
}

export function coatParams(coat) {
  return PALETTE[COATS.includes(coat) ? coat : DEFAULT_APPEARANCE.coat];
}

export function markingIndex(marking) {
  const i = MARKINGS.indexOf(marking);
  return i < 0 ? MARKINGS.indexOf(DEFAULT_APPEARANCE.marking) : i;
}

/** Abzeichen-Regionen in Kopf-Koordinaten (s entlang Kopf ab Genick, u lateral; Meter). */
export const MARKING_REGIONS = {
  star: { s: 0.155, rs: 0.048, ru: 0.038 },
  blaze: { s0: 0.08, s1: 0.615, w0: 0.026, w1: 0.042 },
  snip: { s: 0.575, rs: 0.026, ru: 0.022 },
};
