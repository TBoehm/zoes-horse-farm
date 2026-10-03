// Prozedurale Pferde-Geometrie: Rumpf, Hals, Kopf, Beine, Hufe, Ohren, Augen, Mähne, Schweif
// (eine skinned Geometrie, Material „Fell") und Sattelzeug (zweite Geometrie, Vertex-Farben).
import * as THREE from 'three';
import {
  Loft,
  MeshBuilder,
  buildShell,
  chainWeights,
  clamp,
  curveFrames,
  ellipsoidData,
  lerp,
  lineFrames,
  oval,
  smoothstep,
  table,
  torusData,
} from './loft.js';
import { EAR, HEAD, REST, SIDES, headPoint } from './skeleton.js';

const V = (a) => new THREE.Vector3(a[0], a[1], a[2]);
const TAU = Math.PI * 2;
const hash = (i) => {
  const s = Math.sin(i * 127.1 + 311.7) * 43758.5453;
  return s - Math.floor(s);
};

/** Segmentzahlen je Qualitätsstufe: [Ringe entlang, Segmente rundum]. */
export const DETAIL = {
  low: {
    torso: [20, 14],
    neck: [9, 10],
    head: [12, 10],
    leg: [16, 7],
    tail: [8, 6],
    mane: [12, 2],
    forelock: [3, 2],
    ear: [4, 5],
    eye: [6, 4],
    hoof: [2, 7],
    pad: [6, 6],
    saddle: [5, 6],
    flap: [3, 3],
    band: [1, 8],
    cheek: [5, 1],
    ring: [3, 6],
  },
  medium: {
    torso: [36, 22],
    neck: [16, 16],
    head: [20, 16],
    leg: [30, 11],
    tail: [16, 9],
    mane: [30, 3],
    forelock: [6, 4],
    ear: [6, 7],
    eye: [10, 6],
    hoof: [3, 11],
    pad: [10, 10],
    saddle: [9, 10],
    flap: [5, 4],
    band: [1, 14],
    cheek: [10, 1],
    ring: [4, 10],
  },
  high: {
    torso: [56, 32],
    neck: [26, 24],
    head: [30, 24],
    leg: [46, 15],
    tail: [26, 14],
    mane: [52, 4],
    forelock: [9, 6],
    ear: [8, 10],
    eye: [14, 8],
    hoof: [4, 15],
    pad: [16, 16],
    saddle: [14, 14],
    flap: [8, 6],
    band: [2, 20],
    cheek: [16, 2],
    ring: [6, 14],
  },
};

const samples = (n, a = 0, b = 1) => Array.from({ length: n + 1 }, (_, i) => lerp(a, b, i / n));

// ------------------------------------------------------------------------------------------
// Rumpf
// z, Oberlinie, Unterlinie, halbe Breite, Lage der breitesten Stelle (0 unten .. 1 oben),
// Verjüngung oben, Verjüngung unten, Kerbe unten zwischen den Beinen
const TORSO = table([
  [-0.97, 1.43, 1.25, 0.08, 0.5, 0.75, 0.8, 0.0],
  [-0.93, 1.52, 1.08, 0.165, 0.5, 0.78, 0.75, 0.08],
  [-0.85, 1.585, 0.99, 0.22, 0.5, 0.78, 0.72, 0.16],
  [-0.73, 1.635, 0.95, 0.255, 0.48, 0.76, 0.72, 0.17],
  [-0.59, 1.665, 0.96, 0.27, 0.46, 0.72, 0.76, 0.12],
  [-0.44, 1.66, 0.99, 0.275, 0.42, 0.68, 0.8, 0.04],
  [-0.3, 1.63, 0.94, 0.285, 0.4, 0.66, 0.84, 0.0],
  [-0.14, 1.595, 0.875, 0.298, 0.42, 0.62, 0.86, 0.0],
  [0.02, 1.575, 0.855, 0.3, 0.42, 0.6, 0.86, 0.0],
  [0.17, 1.578, 0.865, 0.29, 0.42, 0.55, 0.86, 0.0],
  [0.31, 1.61, 0.885, 0.265, 0.42, 0.46, 0.86, 0.0],
  [0.44, 1.655, 0.91, 0.24, 0.43, 0.36, 0.86, 0.0],
  [0.57, 1.635, 0.94, 0.225, 0.44, 0.42, 0.85, 0.02],
  [0.69, 1.56, 0.98, 0.21, 0.46, 0.5, 0.82, 0.04],
  [0.79, 1.46, 1.02, 0.195, 0.48, 0.6, 0.8, 0.05],
  [0.87, 1.37, 1.06, 0.17, 0.5, 0.7, 0.8, 0.04],
  [0.93, 1.29, 1.1, 0.13, 0.5, 0.76, 0.8, 0.02],
  [0.965, 1.23, 1.13, 0.075, 0.5, 0.8, 0.8, 0.0],
]);
const TZ0 = -0.97;
const TZ1 = 0.965;

function torsoSection(z) {
  const [top, bottom, w, m, upW, downW, notch] = TORSO(z);
  const H = top - bottom;
  return { cy: bottom + m * H, w, up: (1 - m) * H, down: m * H, upW, downW, notch };
}

export function torsoWeights(p) {
  const { x, y, z } = p;
  const wf = smoothstep(0.1, 0.42, z);
  const wr = 1 - smoothstep(-0.42, -0.1, z);
  const out = [];
  const sx = Math.abs(x);
  const side = x >= 0 ? 'L' : 'R';
  const sideMask = smoothstep(0.03, 0.12, sx);
  // Atmen (Bauch)
  const bel = (1 - smoothstep(0.98, 1.25, y)) * (1 - smoothstep(0.2, 0.42, Math.abs(z + 0.05))) * 0.7;
  // Schulter: Schulterblatt oben, Oberarm unten
  const es = ((y - 1.15) / 0.32) ** 2 + ((z - 0.66) / 0.27) ** 2;
  const fs = (1 - smoothstep(0.45, 1, es)) * sideMask;
  const scap = 0.5 * fs * smoothstep(1.0, 1.28, y);
  const hum = 0.55 * fs * (1 - smoothstep(1.0, 1.22, y));
  // Hinterhand: Oberschenkel
  const eh = ((y - 1.12) / 0.3) ** 2 + ((z + 0.68) / 0.32) ** 2;
  const fh = (1 - smoothstep(0.4, 1, eh)) * sideMask;
  const fem = fh * lerp(0.75, 0.35, smoothstep(1.0, 1.42, y));
  const infl = bel + scap + hum + fem;
  const k = Math.max(0, 1 - infl);
  out.push(['root', (1 - wf - wr) * k], ['spineFront', wf * k], ['spineRear', wr * k]);
  if (bel > 0) out.push(['belly', bel]);
  if (scap > 0) out.push([`${side}scapula`, scap]);
  if (hum > 0) out.push([`${side}humerus`, hum]);
  if (fem > 0) out.push([`${side}femur`, fem]);
  return out;
}

function makeTorso() {
  return new Loft({
    frame: (u) => {
      const z = lerp(TZ0, TZ1, u);
      const s = torsoSection(z);
      return {
        o: new THREE.Vector3(0, s.cy, z),
        t: new THREE.Vector3(0, 0, 1),
        n: new THREE.Vector3(0, 1, 0),
        b: new THREE.Vector3(1, 0, 0),
      };
    },
    section: (u, a) => {
      const s = torsoSection(lerp(TZ0, TZ1, u));
      const r = oval(a, { ...s, nUp: 2.25, nDown: 2.1 });
      if (r[1] < 0 && s.notch > 0) r[1] += s.notch * Math.exp(-((r[0] / 0.08) ** 2)) * -Math.sin(a);
      return r;
    },
    weights: (u, a, p) => torsoWeights(p),
  });
}

// ------------------------------------------------------------------------------------------
// Hals und Kopf
const NECK_PTS = [
  [0, 1.25, 0.46],
  [0, 1.42, 0.74],
  [0, 1.66, 1.0],
  [0, 1.9, 1.245],
  [0, 2.05, 1.41],
];
const NECK = table([
  // u, halbe Breite, Kamm, Kehle, Verjüngung oben
  [0.0, 0.2, 0.3, 0.3, 0.72],
  [0.15, 0.19, 0.27, 0.28, 0.66],
  [0.4, 0.145, 0.205, 0.185, 0.6],
  [0.65, 0.12, 0.165, 0.15, 0.55],
  [0.85, 0.104, 0.135, 0.14, 0.55],
  [1.0, 0.098, 0.11, 0.13, 0.62],
]);

function nearestU(curve, point, n = 400) {
  let best = 0;
  let bd = Infinity;
  const p = new THREE.Vector3();
  for (let i = 0; i <= n; i++) {
    curve.getPointAt(i / n, p);
    const d = p.distanceToSquared(point);
    if (d < bd) {
      bd = d;
      best = i / n;
    }
  }
  return best;
}

function makeNeck() {
  const curve = new THREE.CatmullRomCurve3(NECK_PTS.map(V), false, 'centripetal');
  const joints = [...REST.neck, REST.head].map((p) => nearestU(curve, V(p)));
  const len = curve.getLength();
  return new Loft({
    frame: curveFrames(curve),
    section: (u, a) => {
      const [w, up, down, upW] = NECK(u);
      return oval(a, { w, up, down, upW, downW: 0.82, nUp: 2.2, nDown: 2.0 });
    },
    weights: (u) =>
      chainWeights(
        u,
        joints,
        ['spineFront', 'neck1', 'neck2', 'neck3', 'head'],
        [0.1 / len, 0.08 / len, 0.08 / len, 0.05 / len],
      ),
  });
}

const HEAD_S0 = -0.04;
const HEAD_S1 = 0.625;
const HEADT = table([
  // s, halbe Breite, Stirnseite, Kieferseite
  [-0.04, 0.085, 0.07, 0.11],
  [0.0, 0.095, 0.085, 0.135],
  [0.06, 0.113, 0.1, 0.17],
  [0.14, 0.124, 0.108, 0.195],
  [0.22, 0.118, 0.103, 0.198],
  [0.3, 0.1, 0.09, 0.155],
  [0.4, 0.084, 0.073, 0.106],
  [0.49, 0.079, 0.066, 0.086],
  [0.56, 0.086, 0.066, 0.084],
  [0.6, 0.08, 0.056, 0.074],
  [0.625, 0.068, 0.046, 0.064],
]);

export const headS = (u) => lerp(HEAD_S0, HEAD_S1, u);
export const headU = (s) => (s - HEAD_S0) / (HEAD_S1 - HEAD_S0);

function headSection(s, a) {
  const [w, f, j] = HEADT(s);
  return oval(a, { w, up: f, down: j, upW: 0.86, downW: 0.62, nUp: 2.6, nDown: 1.8 });
}

function makeHead() {
  const p0 = HEAD.origin.clone().addScaledVector(HEAD.dir, HEAD_S0);
  const p1 = HEAD.origin.clone().addScaledVector(HEAD.dir, HEAD_S1);
  return new Loft({
    frame: lineFrames(p0, p1),
    section: (u, a) => headSection(headS(u), a),
    weights: (u) => chainWeights(headS(u), [0.0], ['neck3', 'head'], 0.045),
    attrs: (u, a, p, x) => ({
      aFace: [headS(u), x, a < 0 ? 0 : Math.sin(a)],
    }),
  });
}

// ------------------------------------------------------------------------------------------
// Beine
const FRONT_SECT = table([
  // k (Segment + Anteil), halbe Breite, vorn, hinten
  [0.0, 0.12, 0.12, 0.12],
  [1.0, 0.13, 0.14, 0.13],
  [1.5, 0.12, 0.13, 0.14],
  [2.0, 0.095, 0.1, 0.12],
  [2.13, 0.083, 0.088, 0.08],
  [2.5, 0.064, 0.064, 0.054],
  [2.88, 0.05, 0.046, 0.044],
  [3.0, 0.053, 0.05, 0.05],
  [3.1, 0.046, 0.042, 0.052],
  [3.25, 0.037, 0.031, 0.046],
  [3.6, 0.035, 0.03, 0.045],
  [3.9, 0.039, 0.035, 0.047],
  [4.0, 0.047, 0.044, 0.057],
  [4.15, 0.045, 0.04, 0.05],
  [4.5, 0.039, 0.035, 0.036],
  [4.85, 0.046, 0.043, 0.041],
  [5.0, 0.05, 0.047, 0.045],
]);
const HIND_SECT = table([
  [0.0, 0.15, 0.1, 0.2],
  [1.0, 0.17, 0.12, 0.27],
  [1.5, 0.17, 0.12, 0.33],
  [2.0, 0.14, 0.09, 0.34],
  [2.3, 0.11, 0.08, 0.22],
  [2.6, 0.08, 0.06, 0.12],
  [2.88, 0.055, 0.045, 0.062],
  [3.0, 0.058, 0.05, 0.075],
  [3.1, 0.05, 0.045, 0.06],
  [3.3, 0.039, 0.033, 0.048],
  [3.6, 0.036, 0.031, 0.046],
  [3.9, 0.039, 0.035, 0.047],
  [4.0, 0.047, 0.044, 0.057],
  [4.15, 0.045, 0.04, 0.05],
  [4.5, 0.039, 0.035, 0.036],
  [4.85, 0.046, 0.043, 0.041],
  [5.0, 0.05, 0.047, 0.045],
]);

function legChain(front, side) {
  const R = front ? REST.front : REST.hind;
  const pts = front
    ? [R.shoulder, R.elbow, R.knee, R.fetlock]
    : [R.hip, R.stifle, R.hock, R.fetlock];
  const P = pts.map((p) => V([p[0] * side, p[1], p[2]]));
  const hoof = V([R.hoof[0] * side, R.hoof[1], R.hoof[2]]);
  const top = P[0].clone().addScaledVector(P[0].clone().sub(P[1]), front ? 0.35 : 0.3);
  const coronet = P[3].clone().lerp(hoof, 0.5);
  return { chain: [top, ...P, coronet], hoof };
}

function makeLeg(front, side) {
  const { chain } = legChain(front, side);
  const curve = new THREE.CatmullRomCurve3(chain, false, 'centripetal');
  const ju = chain.map((p) => nearestU(curve, p));
  ju[0] = 0;
  ju[ju.length - 1] = 1;
  const len = curve.getLength();
  const toK = (u) => {
    let i = 0;
    while (i < ju.length - 2 && u > ju[i + 1]) i++;
    return i + clamp((u - ju[i]) / (ju[i + 1] - ju[i]), 0, 1);
  };
  const p = side > 0 ? 'L' : 'R';
  const bones = front
    ? [`${p}scapula`, `${p}humerus`, `${p}forearm`, `${p}fcannon`, `${p}fpastern`]
    : ['spineRear', `${p}femur`, `${p}tibia`, `${p}hcannon`, `${p}hpastern`];
  const blends = (front ? [0.07, 0.05, 0.035, 0.03] : [0.08, 0.06, 0.04, 0.03]).map((b) => b / len);
  const sect = front ? FRONT_SECT : HIND_SECT;
  const loft = new Loft({
    frame: curveFrames(curve),
    section: (u, a) => {
      const [w, f, b] = sect(toK(u));
      return oval(a, { w, up: f, down: b, nUp: 2.1, nDown: 2.1 });
    },
    weights: (u) => {
      const w = chainWeights(u, ju.slice(1, 5), bones, blends);
      if (front) {
        // ganz oben teilt sich der Oberarm das Gewicht mit dem Schulterblatt
        const t = smoothstep(0, ju[1] + 0.05, u);
        w[0][1] = w[0][1] * 1 + 0;
        w[1][1] = w[1][1] * lerp(0.6, 1, t);
        w[0][1] += (1 - w.reduce((s, x) => s + x[1], 0)) * 1;
      }
      return w;
    },
  });
  return { loft, ju };
}

function addHoof(builder, front, side, nh, radial) {
  const { hoof } = legChain(front, side);
  const p = side > 0 ? 'L' : 'R';
  const bone = front ? `${p}fpastern` : `${p}hpastern`;
  const w = [[bone, 1]];
  const attrs = { aMat: [0, 1, 0, 0] };
  const H = 0.088;
  const ring = (h, scale) => {
    const t = h / H;
    const hw = lerp(0.063, 0.051, t) * (front ? 1 : 0.94) * scale;
    const zf = hoof.z + 0.066 - h / Math.tan((front ? 52 : 56) * (Math.PI / 180));
    const zb = hoof.z - 0.056 - h * 0.12;
    const cz = (zf + zb) / 2;
    const hl = ((zf - zb) / 2) * scale;
    const ids = [];
    for (let j = 0; j < radial; j++) {
      const a = (TAU * j) / radial;
      const s = Math.sin(a);
      // Trachten hinten etwas eingezogen
      const hlz = s >= 0 ? hl : hl * 0.92;
      const pt = new THREE.Vector3(hoof.x + hw * Math.cos(a), h, cz + hlz * s);
      ids.push(builder.vertex(pt, w, attrs));
    }
    return ids;
  };
  const rings = [ring(0, 0.88), ring(0.004, 1)];
  for (let k = 1; k <= nh; k++) rings.push(ring((H * k) / nh, 1));
  rings.push(ring(H + 0.004, 0.8));
  const bottom = builder.vertex(new THREE.Vector3(hoof.x, 0, hoof.z), w, attrs);
  const top = builder.vertex(new THREE.Vector3(hoof.x, H + 0.01, hoof.z - 0.03), w, attrs);
  const start = builder.beginPart();
  for (let i = 0; i < rings.length - 1; i++) {
    for (let j = 0; j < radial; j++) {
      const j1 = (j + 1) % radial;
      const r0 = rings[i];
      const r1 = rings[i + 1];
      builder.tri(r0[j], r1[j], r0[j1]);
      builder.tri(r1[j], r1[j1], r0[j1]);
    }
  }
  for (let j = 0; j < radial; j++) {
    builder.tri(bottom, rings[0][j], rings[0][(j + 1) % radial]);
    const rl = rings[rings.length - 1];
    builder.tri(top, rl[(j + 1) % radial], rl[j]);
  }
  builder.endPart(start);
}

// ------------------------------------------------------------------------------------------
// Schweif
const TAIL_PTS = [
  [0, 1.58, -0.8],
  [0, 1.5, -0.95],
  [0, 1.31, -1.045],
  [0, 1.04, -1.075],
  [0, 0.78, -1.065],
  [0, 0.55, -1.03],
];
const TAILT = table([
  [0.0, 0.052],
  [0.12, 0.056],
  [0.26, 0.072],
  [0.5, 0.098],
  [0.75, 0.092],
  [0.92, 0.066],
  [1.0, 0.03],
]);

function makeTail() {
  const curve = new THREE.CatmullRomCurve3(TAIL_PTS.map(V), false, 'centripetal');
  const joints = REST.tail.map((p) => nearestU(curve, V(p)));
  const len = curve.getLength();
  return new Loft({
    frame: curveFrames(curve),
    section: (u, a) => {
      const [r] = TAILT(u);
      const strands = 1 + smoothstep(0.15, 0.4, u) * (0.08 * Math.sin(9 * a + 2 * u) + 0.05 * Math.sin(17 * a + 5));
      return [Math.cos(a) * r * 0.86 * strands, Math.sin(a) * r * strands];
    },
    weights: (u) =>
      chainWeights(
        u,
        joints,
        ['spineRear', 'tail1', 'tail2', 'tail3', 'tail4', 'tail5'],
        0.07 / len,
      ),
    attrs: () => ({ aMat: [1, 0, 0, 0] }),
  });
}

// ------------------------------------------------------------------------------------------

/** Baut die Fell-Geometrie (skinned, ein Draw-Call). */
export function buildBodyGeometry(boneIndex, level = 'medium') {
  const D = DETAIL[level] || DETAIL.medium;
  const b = new MeshBuilder(boneIndex, { aMat: 4, aFace: 3 });
  b.setDefaults({ aMat: [0, 0, 0, 0], aFace: [-1, 0, 0] });

  const torso = makeTorso();
  torso.build(b, {
    uSamples: samples(D.torso[0]),
    radial: D.torso[1],
    capStart: { len: 0.035, rings: 2 },
    capEnd: { len: 0.035, rings: 2 },
    aOffset: Math.PI / 2,
  });

  const neck = makeNeck();
  neck.build(b, {
    uSamples: samples(D.neck[0]),
    radial: D.neck[1],
    capStart: { len: 0.05, rings: 1 },
    aOffset: Math.PI / 2,
  });

  const head = makeHead();
  head.build(b, {
    uSamples: samples(D.head[0]),
    radial: D.head[1],
    capStart: { len: 0.02, rings: 1 },
    capEnd: { len: 0.035, rings: 2 },
    aOffset: Math.PI / 2,
  });

  for (const front of [true, false]) {
    for (const side of SIDES) {
      const { loft } = makeLeg(front, side);
      loft.build(b, {
        uSamples: samples(D.leg[0]),
        radial: D.leg[1],
        capStart: { len: 0.04, rings: 1 },
        aOffset: Math.PI / 2,
      });
      addHoof(b, front, side, D.hoof[0], D.hoof[1]);
    }
  }

  // Ohren
  for (const side of SIDES) {
    const base = EAR.base(side);
    const dir = EAR.dir(side);
    const tip = base.clone().addScaledVector(dir, EAR.length);
    const p0 = base.clone().addScaledVector(dir, -0.03);
    const bone = side > 0 ? 'Lear' : 'Rear';
    const ear = new Loft({
      frame: lineFrames(p0, tip),
      section: (u, a) => {
        const prof = Math.pow(Math.sin(Math.PI * (0.18 + 0.82 * u)), 0.75) * (1 - 0.15 * u);
        const back = 0.027 * prof;
        const front = 0.006 * prof;
        return oval(a, { w: 0.043 * prof, up: back, down: front, nUp: 2.2, nDown: 1.6 });
      },
      weights: (u) => chainWeights(u, [0.18], ['head', bone], 0.08),
      attrs: (u, a) => ({ aMat: [0, 0, 0, a >= 0 ? 0 : smoothstep(0.2, 0.8, -Math.sin(a)) * smoothstep(0.1, 0.3, u)] }),
    });
    ear.build(b, {
      uSamples: samples(D.ear[0], 0, 0.97),
      radial: D.ear[1] * 2,
      capEnd: { len: 0.008, rings: 1 },
      capStart: { len: 0.01, rings: 1 },
    });
  }

  // Augen
  const eye = ellipsoidData(0.026, 0.023, 0.03, D.eye[0], D.eye[1]);
  for (const side of SIDES) {
    const m = new THREE.Matrix4()
      .makeRotationY(0.35 * side)
      .setPosition(headPoint(0.168, 0.038, 0.108 * side));
    b.addIndexed(eye.p, eye.idx, m, () => [['head', 1]], () => ({ aMat: [0, 0, 1, 0] }));
  }

  // Mähne (liegt nach rechts) und Schopf
  const [mn, mv] = D.mane;
  buildShell(b, neck, {
    nu: mn,
    nv: mv,
    map: (su, sv) => {
      const row = Math.round(su * mn);
      const u = lerp(0.07, 0.985, su);
      const jag = 0.12 * (hash(row) - 0.5) + 0.06 * Math.sin(row * 1.7);
      const spread = (0.78 + jag) * lerp(0.55, 1, smoothstep(0, 0.12, su));
      return [u, Math.PI / 2 - 0.14 + sv * (spread + 0.14)];
    },
    thickness: (su, sv) =>
      (0.006 + 0.024 * (1 - sv) * (1 - 0.35 * sv)) * lerp(0.4, 1, smoothstep(0, 0.1, su)),
    inset: -0.004,
    attrs: () => ({ aMat: [1, 0, 0, 0] }),
  });
  const [fn, fv] = D.forelock;
  buildShell(b, head, {
    nu: fn,
    nv: fv * 2,
    map: (su, sv) => {
      const col = Math.round(sv * fv * 2);
      const sEnd = 0.1 + 0.025 * (hash(col + 7) - 0.5) - 0.03 * Math.abs(sv - 0.5);
      return [headU(lerp(-0.035, sEnd, su)), Math.PI / 2 + (sv - 0.5) * 1.0];
    },
    thickness: (su) => 0.004 + 0.016 * (1 - su * 0.7),
    inset: -0.003,
    attrs: () => ({ aMat: [1, 0, 0, 0] }),
  });

  makeTail().build(b, {
    uSamples: samples(D.tail[0]),
    radial: D.tail[1],
    capStart: { len: 0.03, rings: 1 },
    capEnd: { len: 0.05, rings: 1 },
  });

  const geo = b.build();
  geo.setAttribute('aRest', geo.attributes.position.clone());
  return geo;
}

// ------------------------------------------------------------------------------------------
// Sattelzeug

const COL = {
  pad: [0.93, 0.93, 0.92],
  trim: [0.08, 0.12, 0.3],
  leather: [0.2, 0.11, 0.06],
  leatherDark: [0.12, 0.07, 0.04],
  girth: [0.1, 0.07, 0.05],
  steel: [0.72, 0.73, 0.75],
  brow: [0.85, 0.85, 0.88],
};
const lin = (c) => {
  const col = new THREE.Color().setRGB(c[0], c[1], c[2], THREE.SRGBColorSpace);
  return [col.r, col.g, col.b];
};

const tuOf = (z) => (z - TZ0) / (TZ1 - TZ0);

export function buildTackGeometry(boneIndex, level = 'medium') {
  const D = DETAIL[level] || DETAIL.medium;
  const b = new MeshBuilder(boneIndex, { color: 3 });
  const C = Object.fromEntries(Object.entries(COL).map(([k, v]) => [k, lin(v)]));
  const torso = makeTorso();
  const head = makeHead();

  // Schabracke
  const [pn, pv] = D.pad;
  buildShell(b, torso, {
    nu: pn,
    nv: pv,
    map: (su, sv) => {
      const z = lerp(-0.25, 0.5, su);
      const e = Math.max(0, Math.abs(2 * su - 1) - 0.75) / 0.25;
      const A = 1.42 * (1 - 0.12 * e * e) - 0.1 * su;
      return [tuOf(z), Math.PI / 2 + (2 * sv - 1) * A];
    },
    thickness: (su, sv) => 0.014 + 0.005 * (1 - Math.abs(2 * sv - 1)),
    inset: 0.002,
    attrs: (su, sv) => {
      const edge = Math.min(su, 1 - su, sv, 1 - sv);
      return { color: edge < 0.035 ? C.trim : C.pad };
    },
  });

  // Sattel: Sitz mit Vorder- und Hinterzwiesel
  const [sn, sv0] = D.saddle;
  buildShell(b, torso, {
    nu: sn,
    nv: sv0,
    map: (su, sv) => [tuOf(lerp(-0.13, 0.4, su)), Math.PI / 2 + (2 * sv - 1) * 0.72],
    thickness: (su, sv) => {
      const cen = Math.exp(-(((sv - 0.5) / 0.26) ** 2));
      const cantle = 0.075 * (1 - smoothstep(0.0, 0.26, su)) * cen;
      const pommel = 0.05 * smoothstep(0.72, 1, su) * cen;
      return 0.02 + 0.028 * (1 - Math.abs(2 * sv - 1) ** 2) + cantle + pommel;
    },
    inset: 0.016,
    attrs: (su, sv) => ({
      color: Math.min(su, 1 - su, sv, 1 - sv) < 0.06 ? C.leatherDark : C.leather,
    }),
  });

  // Sattelblätter (nach vorn geschnitten) links und rechts
  const [fn, fv] = D.flap;
  for (const side of SIDES) {
    buildShell(b, torso, {
      nu: fn * 2,
      nv: fv * 2,
      map: (su, sv) => {
        const z0 = -0.05 + 0.12 * sv;
        const z1 = 0.33 + 0.15 * sv;
        const a = 0.55 + sv * 0.85;
        return [tuOf(lerp(z0, z1, su)), side > 0 ? Math.PI / 2 - a : Math.PI / 2 + a];
      },
      thickness: (su) => 0.03 + 0.016 * smoothstep(0.78, 1, su),
      inset: 0.02,
      attrs: (su, sv) => ({
        color: Math.min(su, 1 - su, 1 - sv) < 0.05 ? C.leatherDark : C.leather,
      }),
    });
  }

  // Sattelgurt
  buildShell(b, torso, {
    nu: 1,
    nv: D.band[1] * 2,
    map: (su, sv) => [tuOf(lerp(0.3, 0.37, su)), Math.PI / 2 + 1.3 + sv * (TAU - 2.6)],
    thickness: () => 0.014,
    inset: 0.004,
    attrs: () => ({ color: C.girth }),
  });

  // Trense: Nasenriemen, Kopfstück, Stirnriemen, Backenstücke
  const ringBand = (s0, s1, color, th = 0.009) =>
    buildShell(b, head, {
      nu: D.band[0],
      nv: D.band[1],
      closedV: true,
      map: (su, sv) => [headU(lerp(s0, s1, su)), sv * TAU],
      thickness: () => th,
      inset: 0.002,
      attrs: () => ({ color }),
    });
  ringBand(0.395, 0.432, C.leather, 0.011);
  ringBand(-0.012, 0.012, C.leather);
  buildShell(b, head, {
    nu: 1,
    nv: D.band[1],
    map: (su, sv) => [headU(lerp(0.04, 0.062, su)), 0.15 + sv * (Math.PI - 0.3)],
    thickness: () => 0.009,
    inset: 0.002,
    attrs: () => ({ color: C.brow }),
  });
  const [cn, cv] = D.cheek;
  for (const side of SIDES) {
    buildShell(b, head, {
      nu: cn,
      nv: cv,
      map: (su, sv) => {
        const s = lerp(0.0, 0.575, su);
        const ac = lerp(0.12, -0.42, su) + (sv - 0.5) * 0.2;
        return [headU(s), side > 0 ? ac : Math.PI - ac];
      },
      thickness: () => 0.008,
      inset: 0.002,
      attrs: () => ({ color: C.leather }),
    });
  }
  // Gebissringe
  const ring = torusData(0.026, 0.0045, D.ring[0], D.ring[1]);
  for (const side of SIDES) {
    const p = bitRingPoint(side);
    const m = new THREE.Matrix4().makeRotationY(Math.PI / 2).setPosition(p);
    b.addIndexed(ring.p, ring.idx, m, () => [['head', 1]], () => ({ color: C.steel }));
  }
  return b.build();
}

/** Gebissring (Zügel-Ansatz), Modellraum der Ruhepose. */
export function bitRingPoint(side) {
  const s = 0.585;
  const [w] = HEADT(s);
  return headPoint(s, -0.05, (w - 0.004) * side);
}

/** Auflagepunkt der Zügel am Hals (ohne Reiter), Modellraum der Ruhepose. */
export function reinRestPoint(side) {
  return new THREE.Vector3(0.11 * side, 1.66, 0.62);
}
