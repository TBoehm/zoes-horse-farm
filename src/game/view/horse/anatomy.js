// Ruhepose (Bind-Pose) des Pferdes als reine Daten: Meter, Y oben, Blick nach +Z, Ursprung am
// Boden unter der Körpermitte (der Adapter verschiebt das Modell, siehe index.js).
// Warmblut: Widerrist ≈ 1,65 m, Rumpf Bug–Sitzbein ≈ 1,9 m, Nase–Schweif ≈ 2,6 m.

export const SIDES = [1, -1]; // links (+X), rechts (−X) aus Sicht des Pferdes/Reiters
export const LEG_NAMES = ['LF', 'RF', 'LH', 'RH']; // Beinindex 0..3 (onFootfall)

export const REST = {
  root: [0, 1.3, 0],
  spineFront: [0, 1.36, 0.42],
  spineRear: [0, 1.36, -0.42],
  belly: [0, 1.08, -0.05],
  front: {
    scapula: [0.16, 1.5, 0.44],
    shoulder: [0.15, 1.16, 0.78],
    elbow: [0.175, 0.94, 0.56],
    knee: [0.165, 0.5, 0.6],
    fetlock: [0.16, 0.17, 0.605],
    hoof: [0.16, 0.0, 0.7],
  },
  hind: {
    hip: [0.16, 1.3, -0.56],
    stifle: [0.2, 0.98, -0.36],
    hock: [0.165, 0.55, -0.74],
    fetlock: [0.155, 0.17, -0.7],
    hoof: [0.155, 0.0, -0.615],
  },
  neck: [
    [0, 1.36, 0.6],
    [0, 1.64, 0.97],
    [0, 1.9, 1.24],
  ],
  head: [0, 2.05, 1.41],
  tail: [
    [0, 1.56, -0.84],
    [0, 1.46, -0.98],
    [0, 1.25, -1.05],
    [0, 0.99, -1.07],
    [0, 0.74, -1.06],
  ],
};

