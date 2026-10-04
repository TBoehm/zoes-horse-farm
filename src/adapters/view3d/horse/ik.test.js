import { describe, expect, it } from 'vitest';
import { angD, hindSweep, makeFrontRig, makeHindRig, solveFront, solveHind, wrap } from './ik.js';
import { REST } from './anatomy.js';

const P = (a, parent) => ({ y: a[1] - parent[1], z: a[2] - parent[2] });

// forward kinematics: segment angles from the rotations (rotation.x = −ΔangD)
function fkFront(rig, rot) {
  const A = rig.A;
  let acc = -rot[0];
  const tsc = rig.tsc + acc;
  let z = A.z + rig.lsc * Math.sin(tsc);
  let y = A.y - rig.lsc * Math.cos(tsc);
  const segs = [
    [rig.l1, rig.t1],
    [rig.l2, rig.t2],
    [rig.l3, rig.t3],
    [rig.l4, rig.t4],
  ];
  segs.forEach(([l, t], i) => {
    acc += -rot[i + 1];
    z += l * Math.sin(t + acc);
    y -= l * Math.cos(t + acc);
  });
  return { z, y };
}

function fkHind(rig, rot) {
  let acc = 0;
  let z = rig.P.z;
  let y = rig.P.y;
  [
    [rig.l1, rig.t1],
    [rig.l2, rig.t2],
    [rig.l3, rig.t3],
    [rig.l4, rig.t4],
  ].forEach(([l, t], i) => {
    acc += -rot[i];
    z += l * Math.sin(t + acc);
    y -= l * Math.cos(t + acc);
  });
  return { z, y };
}

const F = REST.front;
const sf = REST.spineFront;
const front = makeFrontRig(
  P(F.scapula, sf),
  P(F.shoulder, sf),
  P(F.elbow, sf),
  P(F.knee, sf),
  P(F.fetlock, sf),
  P(F.hoof, sf),
);
const H = REST.hind;
const sr = REST.spineRear;
const hind = makeHindRig(
  P(H.hip, sr),
  P(H.stifle, sr),
  P(H.hock, sr),
  P(H.fetlock, sr),
  P(H.hoof, sr),
);

describe('leg IK', () => {
  it('rest position yields zero rotation', () => {
    const r = solveFront(front, front.H.z, front.H.y, front.t4, 0, 0);
    for (const x of r) expect(x).toBeCloseTo(0, 6);
    const h = solveHind(hind, hind.H.z, hind.H.y, hind.t4, hind.t3);
    for (const x of h) expect(x).toBeCloseTo(0, 6);
  });

  it('foreleg reaches reachable hoof points exactly (also with flexed carpus)', () => {
    for (const [dz, dy, knee] of [
      [0.3, 0, 0],
      [-0.25, 0, 0],
      [0.1, 0.3, 1.5],
      [0, 0.5, 2.4],
    ]) {
      const tz = front.H.z + dz;
      const ty = front.H.y + dy;
      const rot = solveFront(front, tz, ty, front.t4 - knee * 0.5, knee, 0);
      const p = fkFront(front, rot);
      expect(p.z).toBeCloseTo(tz, 4);
      expect(p.y).toBeCloseTo(ty, 4);
    }
  });

  it('hind leg reaches hoof points exactly', () => {
    for (const [dz, dy, tilt] of [
      [0.3, 0, 0.2],
      [-0.3, 0, -0.2],
      [0.1, 0.12, -0.15],
    ]) {
      const tz = hind.H.z + dz;
      const ty = hind.H.y + dy;
      const rot = solveHind(hind, tz, ty, hind.t4, hind.t3 + tilt);
      const p = fkHind(hind, rot);
      expect(p.z).toBeCloseTo(tz, 4);
      expect(p.y).toBeCloseTo(ty, 4);
    }
  });

  it('femur never tips up beyond its limit (hoof target close to the hip)', () => {
    const rot = solveHind(hind, hind.H.z + 0.4, hind.H.y + 0.7, hind.t4, hind.t3 - 1.2);
    const femurAngle = hind.t1 - rot[0];
    expect(femurAngle).toBeLessThanOrEqual(1.35 + 1e-9);
  });

  it('unreachable targets: leg straight, no NaN', () => {
    const r = solveFront(front, front.H.z + 2, front.H.y - 1, front.t4, 0, 0);
    for (const x of r) expect(Number.isFinite(x)).toBe(true);
    const h = solveHind(hind, hind.H.z, hind.H.y - 2, hind.t4, hind.t3);
    for (const x of h) expect(Number.isFinite(x)).toBe(true);
  });

  describe('soft reach (airborne horse)', () => {
    /** Largest change of any rotation while the hoof target comes in from out of reach. */
    function worstStep(soft) {
      let prev = null;
      let worst = 0;
      // the target rises 1 cm per step from 0.4 m below to 0.1 m above the rest hoof point
      for (let dy = -0.4; dy <= 0.1; dy += 0.01) {
        const r = solveHind(hind, hind.H.z, hind.H.y + dy, hind.t4, hind.t3, new Array(4), soft);
        if (prev) for (let k = 0; k < 4; k++) worst = Math.max(worst, Math.abs(r[k] - prev[k]));
        prev = r;
      }
      return worst;
    }

    it('without it the leg bends in one step when the target comes back into reach', () => {
      expect(worstStep(0)).toBeGreaterThan(0.08);
    });

    it('with it the same sweep bends the joints more gently', () => {
      expect(worstStep(0.08)).toBeLessThan(worstStep(0) * 0.7);
    });

    it('reaches targets that are well within reach exactly', () => {
      const dz = 0.2;
      const dy = 0.15;
      const hard = solveFront(front, front.H.z + dz, front.H.y + dy, front.t4, 0, 0);
      const soft = solveFront(
        front,
        front.H.z + dz,
        front.H.y + dy,
        front.t4,
        0,
        0,
        undefined,
        0.08,
      );
      for (let k = 0; k < 5; k++) expect(soft[k]).toBeCloseTo(hard[k], 6);
    });

    it('stays finite for targets far out of reach', () => {
      const r = solveFront(front, front.H.z, front.H.y - 3, front.t4, 0, 0, undefined, 0.08);
      for (const x of r) expect(Number.isFinite(x)).toBe(true);
      const h = solveHind(hind, hind.H.z, hind.H.y - 3, hind.t4, hind.t3, undefined, 0.08);
      for (const x of h) expect(Number.isFinite(x)).toBe(true);
    });
  });

  it('angle helpers', () => {
    expect(angD(0, -1)).toBeCloseTo(0);
    expect(angD(1, 0)).toBeCloseTo(Math.PI / 2);
    expect(wrap(3 * Math.PI)).toBeCloseTo(Math.PI);
    expect(hindSweep(hind, hind.H.z, hind.H.y, hind.t4)).toBeCloseTo(0, 6);
  });
});
