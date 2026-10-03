// 2D-Bein-IK in der Sagittalebene (lokale y/z-Ebene des Bein-Elternknochens). Rein, ohne three.js.
// Winkel-Konvention: angD(dz, dy) = atan2(dz, −dy), 0 = senkrecht nach unten, positiv = nach vorn.
// Eine Knochen-Rotation rotation.x = r ändert angD eines Segments um −r.

export const angD = (dz, dy) => Math.atan2(dz, -dy);
export const wrap = (a) => {
  while (a > Math.PI) a -= 2 * Math.PI;
  while (a < -Math.PI) a += 2 * Math.PI;
  return a;
};
/** Maximaler Vorwärtswinkel des Oberschenkels (angD, ≈ 77°). */
export const FEMUR_MAX = 1.35;
const dirZ = (t) => Math.sin(t);
const dirY = (t) => -Math.cos(t);

function seg(a, b) {
  const dz = b.z - a.z;
  const dy = b.y - a.y;
  return { len: Math.hypot(dz, dy), ang: angD(dz, dy) };
}

/** Vorderbein: Punkte {y, z} für Schulterblatt-Oberkante, Bug, Ellbogen, Karpus, Fessel, Huf. */
export function makeFrontRig(A, S, E, K, F, H) {
  const sc = seg(A, S);
  const s1 = seg(S, E);
  const s2 = seg(E, K);
  const s3 = seg(K, F);
  const s4 = seg(F, H);
  const sf = seg(S, F);
  return {
    A,
    H,
    lsc: sc.len,
    tsc: sc.ang,
    l1: s1.len,
    l2: s2.len,
    l3: s3.len,
    l4: s4.len,
    t1: s1.ang,
    t2: s2.ang,
    t3: s3.ang,
    t4: s4.ang,
    d0: wrap(s3.ang - s2.ang),
    sigma: Math.sign(wrap(s1.ang - sf.ang)) || -1,
  };
}

/** Hinterbein: Hüfte, Knie (Stifle), Sprunggelenk, Fessel, Huf. */
export function makeHindRig(P, T, K, F, H) {
  const s1 = seg(P, T);
  const s2 = seg(T, K);
  const s3 = seg(K, F);
  const s4 = seg(F, H);
  const sk = seg(P, K);
  const sf = seg(P, F);
  return {
    P,
    H,
    l1: s1.len,
    l2: s2.len,
    l3: s3.len,
    l4: s4.len,
    t1: s1.ang,
    t2: s2.ang,
    t3: s3.ang,
    t4: s4.ang,
    tLeg: sf.ang,
    sigma: Math.sign(wrap(s1.ang - sk.ang)) || 1,
  };
}

function twoBone(rootZ, rootY, tz, ty, l1, l2, sigma) {
  const dz = tz - rootZ;
  const dy = ty - rootY;
  let d = Math.hypot(dz, dy);
  const dMax = l1 + l2 - 1e-4;
  const dMin = Math.abs(l1 - l2) + 1e-4;
  const reach = d <= dMax;
  d = Math.min(dMax, Math.max(dMin, d));
  const c = (l1 * l1 + d * d - l2 * l2) / (2 * l1 * d);
  const alpha = Math.acos(Math.max(-1, Math.min(1, c)));
  const base = angD(dz, dy);
  const t1 = base + sigma * alpha;
  const mz = rootZ + l1 * dirZ(t1);
  const my = rootY + l1 * dirY(t1);
  return { t1, mz, my, t2: angD(tz - mz, ty - my), reach };
}

/**
 * Vorderbein lösen. hz/hy: Hufpunkt (lokal), past: absoluter Fesselwinkel (angD, lokal),
 * knee: Beugung des Karpus (rad, 0 = gestreckt), scap: Schulterblatt-Drehung (angD-Delta).
 * Rückgabe: rotation.x für [Schulterblatt, Oberarm, Unterarm, Röhre, Fessel].
 */
export function solveFront(rig, hz, hy, past, knee, scap, out = new Array(5)) {
  const tsc = rig.tsc + scap;
  const sz = rig.A.z + rig.lsc * dirZ(tsc);
  const sy = rig.A.y + rig.lsc * dirY(tsc);
  const fz = hz - rig.l4 * dirZ(past);
  const fy = hy - rig.l4 * dirY(past);
  const delta = rig.d0 - knee;
  const L = Math.sqrt(rig.l2 * rig.l2 + rig.l3 * rig.l3 + 2 * rig.l2 * rig.l3 * Math.cos(delta));
  const psi = Math.atan2(rig.l3 * Math.sin(delta), rig.l2 + rig.l3 * Math.cos(delta));
  const ik = twoBone(sz, sy, fz, fy, rig.l1, L, rig.sigma);
  const t1 = ik.t1;
  const t2 = ik.t2 - psi;
  const t3 = t2 + delta;
  const d1 = wrap(t1 - rig.t1);
  const d2 = wrap(t2 - rig.t2);
  const d3 = wrap(t3 - rig.t3);
  const d4 = wrap(past - rig.t4);
  out[0] = -scap;
  out[1] = -(d1 - scap);
  out[2] = -(d2 - d1);
  out[3] = -(d3 - d2);
  out[4] = -(d4 - d3);
  return out;
}

/**
 * Hinterbein lösen. cannon: absoluter Winkel der Röhre (angD, lokal).
 * Rückgabe: rotation.x für [Oberschenkel, Unterschenkel, Röhre, Fessel].
 */
export function solveHind(rig, hz, hy, past, cannon, out = new Array(4)) {
  const fz = hz - rig.l4 * dirZ(past);
  const fy = hy - rig.l4 * dirY(past);
  const kz = fz - rig.l3 * dirZ(cannon);
  const ky = fy - rig.l3 * dirY(cannon);
  const ik = twoBone(rig.P.z, rig.P.y, kz, ky, rig.l1, rig.l2, rig.sigma);
  let t1 = ik.t1;
  let t2 = ik.t2;
  if (t1 > FEMUR_MAX) {
    // Oberschenkel begrenzen; Unterschenkel zeigt dann nur Richtung Sprunggelenk
    t1 = FEMUR_MAX;
    t2 = angD(kz - (rig.P.z + rig.l1 * dirZ(t1)), ky - (rig.P.y + rig.l1 * dirY(t1)));
  }
  const d1 = wrap(t1 - rig.t1);
  const d2 = wrap(t2 - rig.t2);
  const d3 = wrap(cannon - rig.t3);
  const d4 = wrap(past - rig.t4);
  out[0] = -d1;
  out[1] = -(d2 - d1);
  out[2] = -(d3 - d2);
  out[3] = -(d4 - d3);
  return out;
}

/** Winkel der Linie Hüfte → Fessel für einen Hufpunkt (für die Röhren-Neigung hinten). */
export function hindSweep(rig, hz, hy, past) {
  const fz = hz - rig.l4 * dirZ(past);
  const fy = hy - rig.l4 * dirY(past);
  return wrap(angD(fz - rig.P.z, fy - rig.P.y) - rig.tLeg);
}
