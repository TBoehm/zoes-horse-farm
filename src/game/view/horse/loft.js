// Prozedurale Geometrie-Bausteine für Pferd und Reiter: Lofts (Röhren mit variablem Querschnitt
// entlang eines Pfads), Schalen (Auflagen auf einer Loft-Oberfläche, z. B. Mähne, Sattel, Trense)
// und ein Builder, der alle Teile zu EINER skinned BufferGeometry zusammenfasst (ein Draw-Call).
import * as THREE from 'three';

export const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);
export const lerp = (a, b, t) => a + (b - a) * t;
export function smoothstep(a, b, x) {
  const t = clamp((x - a) / (b - a), 0, 1);
  return t * t * (3 - 2 * t);
}
const TAU = Math.PI * 2;

/**
 * Glatte Interpolation einer Tabelle [[k, v1, v2, ...], ...] (kubisch, Catmull-Rom-artige
 * Tangenten auf ungleichmäßigen Stützstellen). Gibt eine Funktion k → [v1, v2, ...] zurück.
 */
export function table(rows) {
  const n = rows.length;
  const m = rows[0].length - 1;
  return function (k) {
    const out = new Array(m);
    if (k <= rows[0][0]) {
      for (let j = 0; j < m; j++) out[j] = rows[0][j + 1];
      return out;
    }
    if (k >= rows[n - 1][0]) {
      for (let j = 0; j < m; j++) out[j] = rows[n - 1][j + 1];
      return out;
    }
    let i = 0;
    while (k > rows[i + 1][0]) i++;
    const r0 = rows[Math.max(0, i - 1)];
    const r1 = rows[i];
    const r2 = rows[i + 1];
    const r3 = rows[Math.min(n - 1, i + 2)];
    const h = r2[0] - r1[0];
    const t = (k - r1[0]) / h;
    const t2 = t * t;
    const t3 = t2 * t;
    const h00 = 2 * t3 - 3 * t2 + 1;
    const h10 = t3 - 2 * t2 + t;
    const h01 = -2 * t3 + 3 * t2;
    const h11 = t3 - t2;
    for (let j = 1; j <= m; j++) {
      const m1 = ((r2[j] - r0[j]) / (r2[0] - r0[0])) * h;
      const m2 = ((r3[j] - r1[j]) / (r3[0] - r1[0])) * h;
      out[j - 1] = h00 * r1[j] + h10 * m1 + h01 * r2[j] + h11 * m2;
    }
    return out;
  };
}

/**
 * Ovaler Querschnitt (Superellipse) mit getrennter oberer/unterer Ausdehnung.
 * a: Winkel, 0 = +b (lateral, links), π/2 = +n (oben/vorn). Rückgabe [x (entlang b), y (entlang n)].
 */
export function oval(a, s) {
  const c = Math.cos(a);
  const sn = Math.sin(a);
  const upper = sn >= 0;
  const ex = 2 / (upper ? s.nUp || 2 : s.nDown || 2);
  const cx = Math.sign(c) * Math.pow(Math.abs(c), ex);
  const sy = Math.sign(sn) * Math.pow(Math.abs(sn), ex);
  const s2 = sn * sn;
  const wf = upper ? lerp(1, s.upW ?? 1, s2) : lerp(1, s.downW ?? 1, s2);
  return [s.w * cx * wf, (upper ? s.up : s.down) * sy + (s.yOff || 0)];
}

/** Frame entlang einer Kurve in einer Sagittalebene: b = +X, n = t × b. */
export function curveFrames(curve, xAxis = new THREE.Vector3(1, 0, 0)) {
  return function (u) {
    const o = curve.getPointAt(clamp(u, 0, 1));
    const t = curve.getTangentAt(clamp(u, 0, 1)).normalize();
    const n = new THREE.Vector3().crossVectors(t, xAxis).normalize();
    const b = new THREE.Vector3().crossVectors(n, t).normalize();
    return { o, t, n, b };
  };
}

/** Frame entlang einer Geraden von p0 nach p1. */
export function lineFrames(p0, p1, xAxis = new THREE.Vector3(1, 0, 0)) {
  const t = new THREE.Vector3().subVectors(p1, p0).normalize();
  const n = new THREE.Vector3().crossVectors(t, xAxis).normalize();
  const b = new THREE.Vector3().crossVectors(n, t).normalize();
  return function (u) {
    return { o: new THREE.Vector3().lerpVectors(p0, p1, u), t, n, b };
  };
}

/**
 * Loft: Röhre entlang frame(u), u ∈ [0, 1], Querschnitt section(u, a) → [x, y].
 * weights(u, a, p) → [[boneName, w], ...]; attrs(u, a, p, xy) → zusätzliche Attribute je Vertex.
 */
export class Loft {
  constructor(def) {
    this.def = def;
    this.frameCache = new Map();
  }

  frame(u) {
    const key = Math.round(u * 1e6);
    let f = this.frameCache.get(key);
    if (!f) {
      f = this.def.frame(u);
      this.frameCache.set(key, f);
    }
    return f;
  }

  point(u, a, offset = 0, out = new THREE.Vector3()) {
    const f = this.frame(u);
    const [x, y] = this.def.section(u, a);
    out.copy(f.o).addScaledVector(f.b, x).addScaledVector(f.n, y);
    if (offset) out.addScaledVector(this.normal(u, a), offset);
    return out;
  }

  normal(u, a) {
    const e = 1e-3;
    const pu0 = this.point(clamp(u - e, 0, 1), a);
    const pu1 = this.point(clamp(u + e, 0, 1), a);
    const pa0 = this.point(u, a - e);
    const pa1 = this.point(u, a + e);
    const du = pu1.sub(pu0);
    const da = pa1.sub(pa0);
    const nrm = new THREE.Vector3().crossVectors(da, du);
    if (nrm.lengthSq() < 1e-14) {
      const f = this.frame(u);
      const [x, y] = this.def.section(u, a);
      nrm.copy(f.b).multiplyScalar(x).addScaledVector(f.n, y);
    }
    nrm.normalize();
    // nach außen zeigen lassen
    const f = this.frame(u);
    const p = this.point(u, a);
    if (nrm.dot(p.sub(f.o)) < 0) nrm.negate();
    return nrm;
  }

  /** Erzeugt die Röhre als Teil (positions, indices, ...) für den MeshBuilder. */
  build(builder, { uSamples, radial, capStart = null, capEnd = null, aOffset = 0 }) {
    const def = this.def;
    const rings = [];
    const pushRing = (u, scale, shift, dirSign) => {
      const f = this.frame(u);
      const ring = [];
      for (let j = 0; j < radial; j++) {
        const a = aOffset + (TAU * j) / radial;
        const [x, y] = def.section(u, a);
        const p = new THREE.Vector3()
          .copy(f.o)
          .addScaledVector(f.b, x * scale)
          .addScaledVector(f.n, y * scale)
          .addScaledVector(f.t, shift * dirSign);
        ring.push(builder.vertex(p, def.weights(u, a, p), def.attrs ? def.attrs(u, a, p, x, y) : null));
      }
      rings.push(ring);
    };
    const capRings = (cap, u, dirSign, reverse) => {
      const list = [];
      for (let k = 1; k <= cap.rings; k++) {
        const th = (k / (cap.rings + 1)) * (Math.PI / 2);
        list.push([Math.cos(th), Math.sin(th) * cap.len]);
      }
      if (reverse) list.reverse();
      for (const [sc, sh] of list) pushRing(u, sc, sh, dirSign);
    };
    let startPole = -1;
    let endPole = -1;
    if (capStart) {
      const f = this.frame(uSamples[0]);
      const [x0, y0] = def.section(uSamples[0], Math.PI / 2);
      const [x1, y1] = def.section(uSamples[0], -Math.PI / 2);
      const p = new THREE.Vector3()
        .copy(f.o)
        .addScaledVector(f.n, (y0 + y1) / 2)
        .addScaledVector(f.b, (x0 + x1) / 2)
        .addScaledVector(f.t, -capStart.len);
      startPole = builder.vertex(
        p,
        def.weights(uSamples[0], 0, p),
        def.attrs ? def.attrs(uSamples[0], -1, p, 0, 0) : null,
      );
      capRings(capStart, uSamples[0], -1, true);
    }
    for (const u of uSamples) pushRing(u, 1, 0, 1);
    if (capEnd) {
      const uE = uSamples[uSamples.length - 1];
      capRings(capEnd, uE, 1, false);
      const f = this.frame(uE);
      const [x0, y0] = def.section(uE, Math.PI / 2);
      const [x1, y1] = def.section(uE, -Math.PI / 2);
      const p = new THREE.Vector3()
        .copy(f.o)
        .addScaledVector(f.n, (y0 + y1) / 2)
        .addScaledVector(f.b, (x0 + x1) / 2)
        .addScaledVector(f.t, capEnd.len);
      endPole = builder.vertex(p, def.weights(uE, 0, p), def.attrs ? def.attrs(uE, -1, p, 0, 0) : null);
    }
    const start = builder.beginPart();
    for (let i = 0; i < rings.length - 1; i++) {
      const r0 = rings[i];
      const r1 = rings[i + 1];
      for (let j = 0; j < radial; j++) {
        const j1 = (j + 1) % radial;
        builder.tri(r0[j], r0[j1], r1[j]);
        builder.tri(r1[j], r0[j1], r1[j1]);
      }
    }
    if (startPole >= 0) {
      const r = rings[0];
      for (let j = 0; j < radial; j++) builder.tri(startPole, r[(j + 1) % radial], r[j]);
    }
    if (endPole >= 0) {
      const r = rings[rings.length - 1];
      for (let j = 0; j < radial; j++) builder.tri(endPole, r[j], r[(j + 1) % radial]);
    }
    builder.endPart(start);
  }
}

/**
 * Schale auf einer Loft-Oberfläche: Gitter (nu × nv) über map(su, sv) → [u, a], außen um
 * thickness(su, sv) nach außen versetzt, innen um inset; Ränder geschlossen. closedV: Ring.
 */
export function buildShell(builder, loft, opts) {
  const { nu, nv, map, thickness, inset = 0.002, closedV = false, attrs = null } = opts;
  const weightsOf = opts.weights || ((u, a, p) => loft.def.weights(u, a, p));
  const cols = closedV ? nv : nv + 1;
  const outer = [];
  const inner = [];
  for (let i = 0; i <= nu; i++) {
    const su = i / nu;
    const ro = [];
    const ri = [];
    for (let j = 0; j < cols; j++) {
      const sv = j / nv;
      const [u, a] = map(su, sv);
      const base = loft.point(u, a);
      const nrm = loft.normal(u, a);
      const w = weightsOf(u, a, base);
      const th = thickness(su, sv);
      const po = base.clone().addScaledVector(nrm, th);
      const pi = base.clone().addScaledVector(nrm, inset);
      ro.push(builder.vertex(po, w, attrs ? attrs(su, sv, true) : null));
      ri.push(builder.vertex(pi, w, attrs ? attrs(su, sv, false) : null));
    }
    outer.push(ro);
    inner.push(ri);
  }
  const start = builder.beginPart();
  const jMax = closedV ? cols : cols - 1;
  const quads = [];
  for (let i = 0; i < nu; i++) {
    for (let j = 0; j < jMax; j++) {
      const j1 = (j + 1) % cols;
      quads.push([outer[i][j], outer[i + 1][j], outer[i + 1][j1], outer[i][j1]]);
      quads.push([inner[i][j], inner[i][j1], inner[i + 1][j1], inner[i + 1][j]]);
    }
  }
  // Ränder (Umlaufsinn passend zu Außen-/Innenfläche)
  for (let j = 0; j < jMax; j++) {
    const j1 = (j + 1) % cols;
    quads.push([outer[0][j], outer[0][j1], inner[0][j1], inner[0][j]]);
    quads.push([outer[nu][j1], outer[nu][j], inner[nu][j], inner[nu][j1]]);
  }
  if (!closedV) {
    const c = cols - 1;
    for (let i = 0; i < nu; i++) {
      quads.push([outer[i + 1][0], outer[i][0], inner[i][0], inner[i + 1][0]]);
      quads.push([outer[i][c], outer[i + 1][c], inner[i + 1][c], inner[i][c]]);
    }
  }
  // Außenfläche soll von der Loft-Achse weg zeigen
  const mi = Math.floor(nu / 2);
  const mj = Math.floor(jMax / 2);
  const [uc] = map(mi / nu, mj / nv);
  const center = loft.frame(uc).o;
  const q = [outer[mi][mj], outer[mi + 1][mj], outer[mi + 1][(mj + 1) % cols]];
  const flip = builder.faceFacing(q[0], q[1], q[2], center) < 0;
  for (const [p0, p1, p2, p3] of quads) {
    if (flip) {
      builder.tri(p0, p2, p1);
      builder.tri(p0, p3, p2);
    } else {
      builder.tri(p0, p1, p2);
      builder.tri(p0, p2, p3);
    }
  }
  builder.endPart(start);
}

/**
 * Sammelt Vertices aller Teile. Jeder Vertex: Position, Gewichte (Knochen-Namen), Attribute.
 * Normalen werden je Teil aus den Flächen berechnet (glatt innerhalb eines Teils).
 */
export class MeshBuilder {
  constructor(boneIndex, attrSpec = {}) {
    this.boneIndex = boneIndex;
    this.attrSpec = attrSpec; // name → itemSize
    this.pos = [];
    this.skinIndex = [];
    this.skinWeight = [];
    this.attrs = {};
    for (const k of Object.keys(attrSpec)) this.attrs[k] = [];
    this.index = [];
    this.parts = [];
    this.current = {};
  }

  /** Setzt Standardwerte für Attribute, die folgende Teile verwenden. */
  setDefaults(values) {
    this.current = { ...values };
  }

  vertex(p, weights, attrs) {
    const id = this.pos.length / 3;
    this.pos.push(p.x, p.y, p.z);
    const list = (weights || [])
      .filter((w) => w[1] > 1e-4)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 4);
    let sum = 0;
    for (const w of list) sum += w[1];
    for (let k = 0; k < 4; k++) {
      const w = list[k];
      if (w) {
        const bi = this.boneIndex[w[0]];
        if (bi === undefined) throw new Error(`Unbekannter Knochen ${w[0]}`);
        this.skinIndex.push(bi);
        this.skinWeight.push(w[1] / sum);
      } else {
        this.skinIndex.push(0);
        this.skinWeight.push(0);
      }
    }
    for (const [name, size] of Object.entries(this.attrSpec)) {
      const v = (attrs && attrs[name]) || this.current[name];
      for (let k = 0; k < size; k++) this.attrs[name].push(v ? v[k] : 0);
    }
    return id;
  }

  beginPart() {
    return this.index.length;
  }

  tri(a, b, c) {
    this.index.push(a, b, c);
  }

  endPart(start) {
    this.parts.push([start, this.index.length]);
  }

  /** Vorzeichen: zeigt die Fläche (a, b, c) von center weg? */
  faceFacing(a, b, c, center) {
    const P = this.pos;
    const ax = P[a * 3];
    const ay = P[a * 3 + 1];
    const az = P[a * 3 + 2];
    const e1 = [P[b * 3] - ax, P[b * 3 + 1] - ay, P[b * 3 + 2] - az];
    const e2 = [P[c * 3] - ax, P[c * 3 + 1] - ay, P[c * 3 + 2] - az];
    const n = [
      e1[1] * e2[2] - e1[2] * e2[1],
      e1[2] * e2[0] - e1[0] * e2[2],
      e1[0] * e2[1] - e1[1] * e2[0],
    ];
    return n[0] * (ax - center.x) + n[1] * (ay - center.y) + n[2] * (az - center.z);
  }

  /** Einfache Teile aus vorgegebenen Positionen/Indizes (lokal) mit Transformation. */
  addIndexed(positions, indices, matrix, weightsFn, attrsFn) {
    const v = new THREE.Vector3();
    const base = [];
    for (let i = 0; i < positions.length; i += 3) {
      v.set(positions[i], positions[i + 1], positions[i + 2]).applyMatrix4(matrix);
      base.push(this.vertex(v, weightsFn(v), attrsFn ? attrsFn(v) : null));
    }
    const start = this.beginPart();
    for (let i = 0; i < indices.length; i += 3) {
      this.tri(base[indices[i]], base[indices[i + 1]], base[indices[i + 2]]);
    }
    this.endPart(start);
  }

  get triangleCount() {
    return this.index.length / 3;
  }

  build() {
    const geo = new THREE.BufferGeometry();
    const pos = new Float32Array(this.pos);
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('skinIndex', new THREE.Uint16BufferAttribute(this.skinIndex, 4));
    geo.setAttribute('skinWeight', new THREE.Float32BufferAttribute(this.skinWeight, 4));
    for (const [name, size] of Object.entries(this.attrSpec)) {
      geo.setAttribute(name, new THREE.Float32BufferAttribute(this.attrs[name], size));
    }
    const vcount = this.pos.length / 3;
    const index = vcount > 65535 ? new Uint32Array(this.index) : new Uint16Array(this.index);
    geo.setIndex(new THREE.BufferAttribute(index, 1));
    geo.setAttribute('normal', new THREE.BufferAttribute(computeNormals(pos, this.index), 3));
    geo.computeBoundingSphere();
    return geo;
  }
}

function computeNormals(pos, index) {
  const nrm = new Float32Array(pos.length);
  for (let i = 0; i < index.length; i += 3) {
    const a = index[i] * 3;
    const b = index[i + 1] * 3;
    const c = index[i + 2] * 3;
    const e1x = pos[b] - pos[a];
    const e1y = pos[b + 1] - pos[a + 1];
    const e1z = pos[b + 2] - pos[a + 2];
    const e2x = pos[c] - pos[a];
    const e2y = pos[c + 1] - pos[a + 1];
    const e2z = pos[c + 2] - pos[a + 2];
    const nx = e1y * e2z - e1z * e2y;
    const ny = e1z * e2x - e1x * e2z;
    const nz = e1x * e2y - e1y * e2x;
    for (const k of [a, b, c]) {
      nrm[k] += nx;
      nrm[k + 1] += ny;
      nrm[k + 2] += nz;
    }
  }
  for (let i = 0; i < nrm.length; i += 3) {
    const l = Math.hypot(nrm[i], nrm[i + 1], nrm[i + 2]) || 1;
    nrm[i] /= l;
    nrm[i + 1] /= l;
    nrm[i + 2] /= l;
  }
  return nrm;
}

/**
 * Gewichte entlang einer Knochenkette: joints = Bogenlängen der Gelenke (aufsteigend),
 * bones = [vorGelenk0, nachGelenk0, nachGelenk1, ...], blend = halbe Übergangsbreite je Gelenk.
 */
export function chainWeights(s, joints, bones, blend) {
  const sm = (k) => {
    const b = Array.isArray(blend) ? blend[k] : blend;
    return smoothstep(joints[k] - b, joints[k] + b, s);
  };
  const out = [];
  for (let k = 0; k <= joints.length; k++) {
    const a = k === 0 ? 1 : sm(k - 1);
    const b = k < joints.length ? sm(k) : 0;
    out.push([bones[k], Math.max(0, a - b)]);
  }
  return out;
}

/** Gewichtslisten mischen: (1 − t)·A + t·B. */
export function mixWeights(a, b, t) {
  const map = new Map();
  for (const [n, w] of a) map.set(n, (map.get(n) || 0) + w * (1 - t));
  for (const [n, w] of b) map.set(n, (map.get(n) || 0) + w * t);
  return [...map.entries()];
}

/** Kugel-/Ellipsoid-Teil, lokal um den Ursprung (für Augen u. Ä.). */
export function ellipsoidData(rx, ry, rz, ws, hs) {
  const g = new THREE.SphereGeometry(1, ws, hs);
  g.scale(rx, ry, rz);
  const p = Array.from(g.attributes.position.array);
  const idx = Array.from(g.index.array);
  g.dispose();
  return { p, idx };
}

/** Torus-Teil (z. B. Gebissring, Steigbügel), Achse lokal +Z. */
export function torusData(r, tube, rs, ts) {
  const g = new THREE.TorusGeometry(r, tube, rs, ts);
  const p = Array.from(g.attributes.position.array);
  const idx = Array.from(g.index.array);
  g.dispose();
  return { p, idx };
}
