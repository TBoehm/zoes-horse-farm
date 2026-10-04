// Detail geometry of the rider, merged into the one skinned rider mesh (no extra draw call):
// face (eyes, brows, nose, smile, ears, cheeks), helmet chin strap, ponytail with a hair bow, and
// jacket details (buttons, stock tie, collar tips, piping). All parts are weighted to existing
// bones; `level` (low / medium / high) only decides how much is added and how finely.
import * as THREE from 'three';
import {
  Loft,
  curveFrames,
  chainWeights,
  ellipsoidData,
  oval,
  table,
  torusData,
} from './horse/loft.js';

/** Head ellipsoid the face is mapped onto (matches the head part in rider.js). */
export const HEAD = { cx: 0, cy: 0.815, cz: 0, rx: 0.083, ry: 0.112, rz: 0.1 };

/** Ponytail bone chain (rider space, children of the head bone). */
export const PONY_JOINTS = {
  pony1: [0, 0.795, -0.098],
  pony2: [0, 0.735, -0.138],
  pony3: [0, 0.665, -0.153],
};
const PONY_END = [0, 0.6, -0.15];
const PONY_STUB = [0, 0.815, -0.085];

// segment counts per level: [ellipsoid width, height], tube samples, tube radial
const FACE_DETAIL = {
  low: { eye: [5, 4], small: [5, 3], tube: [5, 3], strap: [8, 3], tail: [4, 5] },
  medium: { eye: [8, 6], small: [7, 5], tube: [8, 4], strap: [14, 4], tail: [6, 7] },
  high: { eye: [10, 8], small: [9, 6], tube: [12, 5], strap: [20, 5], tail: [8, 8] },
};
const detailOf = (level) => FACE_DETAIL[level] || FACE_DETAIL.medium;

const V3 = (a) => new THREE.Vector3(a[0], a[1], a[2]);

/**
 * Point on (or, with off > 0, outside of) the head ellipsoid: lat = elevation (0 = equator, + up),
 * lon = azimuth (0 = straight ahead, + towards +X = the rider's left side).
 */
export function headPoint(lat, lon, off = 0, out = new THREE.Vector3()) {
  const k = 1 + off;
  return out.set(
    HEAD.cx + HEAD.rx * Math.cos(lat) * Math.sin(lon) * k,
    HEAD.cy + HEAD.ry * Math.sin(lat) * k,
    HEAD.cz + HEAD.rz * Math.cos(lat) * Math.cos(lon) * k,
  );
}

const tmpEuler = new THREE.Euler();
const tmpQuat = new THREE.Quaternion();
const tmpScale = new THREE.Vector3(1, 1, 1);

/** Ellipsoid (radii rx, ry, rz; z = outward) sitting on the head surface at (lat, lon). */
function surfaceBlob(b, bone, color, radii, lat, lon, { off = 0, push = 0, roll = 0, seg }) {
  const pos = headPoint(lat, lon, off);
  // local frame: z along the surface normal (approximated by lon / lat), roll about the normal
  tmpEuler.set(-lat * 0.85, lon, roll, 'YXZ');
  tmpQuat.setFromEuler(tmpEuler);
  const m = new THREE.Matrix4().compose(pos, tmpQuat, tmpScale);
  if (push) m.multiply(new THREE.Matrix4().makeTranslation(0, 0, push));
  const d = ellipsoidData(radii[0], radii[1], radii[2], seg[0], seg[1]);
  b.addIndexed(
    d.p,
    d.idx,
    m,
    () => [[bone, 1]],
    () => ({ color }),
  );
}

/** Round tube along `points` with a radius table, all weighted to one bone. */
function simpleTube(b, bone, color, points, radii, { samples, radial, xAxis }) {
  const curve = new THREE.CatmullRomCurve3(points, false, 'centripetal');
  const rt = table(radii);
  new Loft({
    frame: curveFrames(curve, xAxis),
    section: (u, a) => {
      const [r] = rt(u);
      return oval(a, { w: r, up: r, down: r });
    },
    weights: () => [[bone, 1]],
    attrs: () => ({ color }),
  }).build(b, {
    uSamples: Array.from({ length: samples + 1 }, (_, i) => i / samples),
    radial,
    capStart: { len: radii[0][1], rings: 1 },
    capEnd: { len: radii[radii.length - 1][1], rings: 1 },
  });
}

/** Eyes, brows, nose, smile, ears (and blush on high). */
export function addFace(b, level, C) {
  const D = detailOf(level);
  const low = level === 'low';
  for (const s of [1, -1]) {
    const lon = 0.36 * s;
    if (low) {
      // one dark ellipsoid per eye
      surfaceBlob(b, 'head', C.pupil, [0.0105, 0.0135, 0.006], -0.134, lon, {
        off: 0.02,
        seg: D.eye,
      });
    } else {
      surfaceBlob(b, 'head', C.eyeWhite, [0.0135, 0.0165, 0.0055], -0.134, lon, {
        off: 0.0,
        seg: D.eye,
      });
      surfaceBlob(b, 'head', C.iris, [0.0092, 0.0118, 0.0045], -0.134, lon, {
        off: 0.0,
        push: 0.0025,
        seg: D.eye,
      });
      surfaceBlob(b, 'head', C.pupil, [0.0055, 0.0075, 0.004], -0.134, lon, {
        off: 0.0,
        push: 0.0046,
        seg: D.small,
      });
      if (level === 'high') {
        // a small catch light makes the eyes look alive
        surfaceBlob(b, 'head', C.eyeWhite, [0.0028, 0.0028, 0.0022], -0.134, lon, {
          off: 0.0,
          push: 0.0068,
          seg: [5, 4],
        });
      }
      surfaceBlob(b, 'head', C.brow, [0.0165, 0.0032, 0.004], 0.115, lon * 1.02, {
        off: 0.012,
        roll: -0.12 * s,
        seg: D.small,
      });
    }
    // ears (below the helmet rim)
    surfaceBlob(b, 'head', C.skin, [0.0065, 0.022, 0.015], -0.12, 1.5 * s, {
      off: 0.015,
      push: 0.0,
      roll: 0,
      seg: D.small,
    });
    if (level === 'high') {
      surfaceBlob(b, 'head', C.blush, [0.014, 0.0095, 0.004], -0.52, 0.62 * s, {
        off: 0.004,
        seg: D.small,
      });
    }
  }
  // nose
  surfaceBlob(b, 'head', C.nose, [0.011, 0.0092, 0.011], -0.43, 0, { off: 0.04, seg: D.small });
  // smile: corners up, centre low
  const pts = [-0.38, -0.19, 0, 0.19, 0.38].map((lon) =>
    headPoint(-0.84 + 0.1 * (lon / 0.38) ** 2, lon, 0.014),
  );
  simpleTube(
    b,
    'head',
    C.lip,
    pts,
    [
      [0, 0.0022],
      [0.5, 0.0034],
      [1, 0.0022],
    ],
    { samples: D.tube[0], radial: D.tube[1], xAxis: new THREE.Vector3(0, 1, 0) },
  );
}

/** Helmet harness: a strap from both temples under the chin (buckle on high). */
export function addChinStrap(b, level, C) {
  const D = detailOf(level);
  const side = (s) => [
    headPoint(0.1, 1.25 * s, 0.04),
    headPoint(-0.25, 1.15 * s, 0.04),
    headPoint(-0.75, 0.85 * s, 0.04),
    headPoint(-1.15, 0.45 * s, 0.04),
  ];
  const left = side(1);
  const right = side(-1).reverse();
  const pts = [...left, headPoint(-1.3, 0, 0.04), ...right];
  simpleTube(
    b,
    'head',
    C.strap,
    pts,
    [
      [0, 0.0042],
      [1, 0.0042],
    ],
    { samples: D.strap[0], radial: D.strap[1], xAxis: new THREE.Vector3(0, 0, 1) },
  );
  if (level === 'high') {
    // small steel buckle on the left cheek
    const p = headPoint(-0.4, 1.0, 0.075);
    const d = ellipsoidData(0.0075, 0.0085, 0.004, 6, 4);
    b.addIndexed(
      d.p,
      d.idx,
      new THREE.Matrix4().compose(
        p,
        tmpQuat.setFromEuler(tmpEuler.set(0, 1.0, 0, 'YXZ')),
        tmpScale,
      ),
      () => [['head', 1]],
      () => ({ color: C.steel }),
    );
  }
}

/** Ponytail on the three pony bones, with a scrunchie (low) or a bow (medium, high). */
export function addPonytail(b, level, C) {
  const D = detailOf(level);
  const pts = [PONY_STUB, PONY_JOINTS.pony1, PONY_JOINTS.pony2, PONY_JOINTS.pony3, PONY_END].map(
    V3,
  );
  const curve = new THREE.CatmullRomCurve3(pts, false, 'centripetal');
  const len = curve.getLength();
  const joints = [];
  let acc = 0;
  for (let i = 1; i < pts.length - 1; i++) {
    acc += pts[i].distanceTo(pts[i - 1]);
    if (i >= 2) joints.push(acc / len);
  }
  const rt = table([
    [0, 0.026],
    [0.25, 0.03],
    [0.6, 0.024],
    [1, 0.007],
  ]);
  const tip = C.hairTip;
  new Loft({
    frame: curveFrames(curve),
    section: (u, a) => {
      const [r] = rt(u);
      return oval(a, { w: r, up: r * 0.85, down: r * 0.85 });
    },
    weights: (u) => chainWeights(u, joints, ['pony1', 'pony2', 'pony3'], 0.07),
    attrs: (u) => ({
      color: [0, 1, 2].map((k) => C.hair[k] + (tip[k] - C.hair[k]) * Math.max(0, u - 0.3) * 1.4),
    }),
  }).build(b, {
    uSamples: Array.from({ length: D.tail[0] + 1 }, (_, i) => i / D.tail[0]),
    radial: D.tail[1],
    capStart: { len: 0.02, rings: 1 },
    capEnd: { len: 0.02, rings: 1 },
  });

  // scrunchie / bow at the root of the ponytail, facing backwards
  const root = V3(PONY_JOINTS.pony1).add(new THREE.Vector3(0, -0.006, -0.012));
  const dir = V3(PONY_JOINTS.pony2).sub(V3(PONY_JOINTS.pony1)).normalize();
  const q = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 0, 1), dir);
  const ring = torusData(0.03, 0.0115, D.small[1] > 3 ? 5 : 4, level === 'low' ? 6 : 9);
  b.addIndexed(
    ring.p,
    ring.idx,
    new THREE.Matrix4().compose(root, q, tmpScale),
    () => [['pony1', 1]],
    () => ({ color: C.bow }),
  );
  if (level !== 'low') {
    // bow: two loops and a knot, tilted away from the ponytail
    for (const s of [1, -1]) {
      const loop = ellipsoidData(0.03, 0.017, 0.011, D.small[0], D.small[1]);
      const pos = root.clone().add(new THREE.Vector3(0.03 * s, 0.006, -0.012));
      const rot = new THREE.Quaternion().setFromEuler(new THREE.Euler(0.2, 0, -0.45 * s));
      b.addIndexed(
        loop.p,
        loop.idx,
        new THREE.Matrix4().compose(pos, rot, tmpScale),
        () => [['pony1', 1]],
        () => ({ color: C.bow }),
      );
    }
    const knot = ellipsoidData(0.012, 0.012, 0.011, D.small[0], D.small[1]);
    b.addIndexed(
      knot.p,
      knot.idx,
      new THREE.Matrix4().makeTranslation(root.x, root.y + 0.006, root.z - 0.016),
      () => [['pony1', 1]],
      () => ({ color: C.bowKnot }),
    );
  }
}

/** Buttons, stock tie, collar tips (medium) and lapel piping (high) on the jacket front. */
export function addJacketDetails(b, torso, level, C) {
  if (level === 'low') return;
  const FRONT = -Math.PI / 2; // 'down' side of the torso loft = front (+Z)
  const wt = (u, a, p) => torso.def.weights(u, a, p);
  const part = (data, u, a, color, quat, lift = 0.0025) => {
    const pos = torso.point(u, a, lift);
    const w = wt(u, a, pos);
    b.addIndexed(
      data.p,
      data.idx,
      new THREE.Matrix4().compose(pos, quat || new THREE.Quaternion(), tmpScale),
      () => w,
      () => ({ color }),
    );
  };
  const buttons = level === 'high' ? [0.3, 0.4, 0.5, 0.6] : [0.34, 0.47, 0.6];
  const seg = level === 'high' ? [8, 6] : [6, 4];
  for (const u of buttons) {
    part(ellipsoidData(0.0085, 0.0085, 0.0045, seg[0], seg[1]), u, FRONT, C.button);
  }
  // stock tie / shirt front at the neck
  part(ellipsoidData(0.011, 0.028, 0.007, seg[0], seg[1]), 0.9, FRONT, C.collar);
  // collar tips
  for (const s of [1, -1]) {
    const tip = ellipsoidData(0.014, 0.0035, 0.01, seg[0], seg[1]);
    const q = new THREE.Quaternion().setFromEuler(new THREE.Euler(0.35, 0, -0.45 * s));
    part(tip, 0.955, FRONT + 0.55 * s, C.collar, q, 0.004);
  }
  if (level === 'high') {
    // contrasting piping along the lapel edges
    for (const s of [1, -1]) {
      const pts = [0.34, 0.5, 0.66, 0.82, 0.93].map((u) => torso.point(u, FRONT + 0.3 * s, 0.002));
      const curve = new THREE.CatmullRomCurve3(pts, false, 'centripetal');
      new Loft({
        frame: curveFrames(curve, new THREE.Vector3(0, 0, 1)),
        section: (u, a) => oval(a, { w: 0.0032, up: 0.0032, down: 0.0032 }),
        weights: (u, a, p) => wt(0.34 + u * 0.59, FRONT, p),
        attrs: () => ({ color: C.piping }),
      }).build(b, {
        uSamples: [0, 0.25, 0.5, 0.75, 1],
        radial: 4,
        capStart: { len: 0.003, rings: 1 },
        capEnd: { len: 0.003, rings: 1 },
      });
    }
  }
}
