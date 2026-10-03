// 2D leg IK in the sagittal plane (local y/z plane of the leg's parent bone). Pure, no three.js.
// Angle convention: angD(dz, dy) = atan2(dz, −dy), 0 = straight down, positive = forwards.
// A bone rotation rotation.x = r changes a segment's angD by −r.

export const angD = (dz, dy) => Math.atan2(dz, -dy);
export const wrap = (a) => {
  while (a > Math.PI) a -= 2 * Math.PI;
  while (a < -Math.PI) a += 2 * Math.PI;
  return a;
};
/** Maximum forward angle of the femur (angD, ≈ 77°). */
export const FEMUR_MAX = 1.35;
const dirZ = (t) => Math.sin(t);
const dirY = (t) => -Math.cos(t);

function seg(a, b) {
  const dz = b.z - a.z;
  const dy = b.y - a.y;
  return { len: Math.hypot(dz, dy), ang: angD(dz, dy) };
}

/** Foreleg: points {y, z} for scapula top, point of shoulder, elbow, carpus, fetlock, hoof. */
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

/** Hind leg: hip, stifle, hock, fetlock, hoof. */
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
 * Solve a foreleg. hz/hy: hoof point (local), past: absolute pastern angle (angD, local),
 * knee: carpus flexion (rad, 0 = straight), scap: scapula rotation (angD delta).
 * Returns rotation.x for [scapula, humerus, forearm, cannon, pastern].
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
 * Solve a hind leg. cannon: absolute cannon angle (angD, local).
 * Returns rotation.x for [femur, tibia, cannon, pastern].
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
    // limit the femur; the tibia then just points towards the hock
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

/** Angle of the hip → fetlock line for a hoof point (drives the hind cannon tilt). */
export function hindSweep(rig, hz, hy, past) {
  const fz = hz - rig.l4 * dirZ(past);
  const fy = hy - rig.l4 * dirY(past);
  return wrap(angD(fz - rig.P.z, fy - rig.P.y) - rig.tLeg);
}
