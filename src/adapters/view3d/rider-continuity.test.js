// Continuity of the rider pose from frame to frame (SRT-011, rule 24): a scripted ride is stepped
// at 60 fps through the real horse (motion, poses) and rider (seat, IK); no joint angle or
// hand/hip/head position may jump by more than a sane amount between two frames, whatever the
// sim does (gait changes, take-off, flight, landing, refusal, rein-back, hop, turns).
import * as THREE from 'three';
import { describe, expect, it } from 'vitest';
import { createHorse } from './horse/index.js';

const DT = 1 / 60;

const ANGLE_BONES = [
  'pelvis',
  'spine',
  'chest',
  'neck',
  'head',
  'LupperArm',
  'Lforearm',
  'Lhand',
  'Rhand',
  'Lthigh',
  'Lshin',
  'Lfoot',
  'Rfoot',
  'pony1',
  'pony2',
  'pony3',
];
const POINT_BONES = ['pelvis', 'head', 'Lhand', 'Rhand', 'Lfoot', 'Rfoot'];

// Largest accepted change between two frames (60 fps). The scripted ride peaks at about 0.08 rad
// and 3 cm; the limits leave room for tuning but catch a visible pop (a hand or head that
// moves 4+ cm or a joint that turns 6° in one frame).
const MAX_ANGLE_STEP = 0.1;
const MAX_POINT_STEP = {
  pelvis: 0.04,
  head: 0.05,
  Lhand: 0.04,
  Rhand: 0.04,
  Lfoot: 0.01,
  Rfoot: 0.01,
};

/** One segment of the scripted ride: duration (s) and state(t) → sim.horse-like state. */
const hold = (duration, base) => ({ duration, at: () => ({ ...base }) });
const ramp = (duration, from, to, gait, extra = {}) => ({
  duration,
  at: (t) => ({ gait, speed: from + ((to - from) * t) / duration, ...extra }),
});
const JUMP = { total: 1.0, takeoff: 0.2, landing: 0.25 };
const jumpSegment = (speed = 4) => ({
  duration: JUMP.total,
  at: (t) => {
    const s = t / JUMP.total;
    let phase = 'takeoff';
    let progress = s / JUMP.takeoff;
    if (s >= 1 - JUMP.landing) {
      phase = 'landing';
      progress = (s - (1 - JUMP.landing)) / JUMP.landing;
    } else if (s >= JUMP.takeoff) {
      phase = 'flight';
      progress = (s - JUMP.takeoff) / (1 - JUMP.takeoff - JUMP.landing);
    }
    return {
      gait: 'canter',
      speed,
      y: 0.9 * Math.sin(Math.PI * s),
      jump: { phase, progress: Math.min(1, progress) },
    };
  },
});
const refusalSegment = () => ({
  duration: 1.2,
  at: (t) => ({
    gait: t < 0.5 ? 'canter' : 'halt',
    speed: Math.max(0, 4 - 9 * t),
    refusal: { type: 'stop', progress: t / 1.2 },
  }),
});
const runoutSegment = () => ({
  duration: 1.2,
  at: (t) => ({
    gait: 'canter',
    speed: 4,
    turnRate: 1.2 * Math.sin((Math.PI * t) / 1.2),
    refusal: { type: 'runout', progress: t / 1.2 },
  }),
});
const hopSegment = () => ({
  duration: 0.4,
  at: (t) => ({
    gait: 'canter',
    speed: 4,
    hop: { progress: t / 0.4 },
    y: 0.2 * Math.sin((Math.PI * t) / 0.4),
  }),
});

const SCRIPT = [
  hold(1.5, { gait: 'halt', speed: 0 }),
  ramp(1.5, 0.5, 1.6, 'walk'),
  hold(0.5, { gait: 'halt', speed: 0 }),
  ramp(1.0, 1.6, 3.2, 'walk'),
  ramp(2.0, 3.2, 3.6, 'trot'),
  ramp(1.5, 3.6, 5.2, 'canter'),
  jumpSegment(5.2), // index 6
  hold(1.5, { gait: 'canter', speed: 5.2 }),
  ramp(1.0, 5.2, 3.0, 'trot'),
  hold(0.5, { gait: 'canter', speed: 5.2, turnRate: 0.5 }),
  { duration: 1.0, at: () => ({ gait: 'canter', speed: 5.2, turnRate: -0.8 }) },
  refusalSegment(),
  hold(2.5, { gait: 'halt', speed: 0 }),
  // rein-back, then straight into a canter, a hop and a run-out
  hold(1.0, { gait: 'back', speed: -1.2 }),
  ramp(1.5, 0.5, 4, 'canter'),
  hopSegment(),
  hold(1, { gait: 'canter', speed: 4 }),
  runoutSegment(),
  // jump in trot, stop after the landing: pat on the neck
  ramp(1.0, 3.4, 3.4, 'trot'),
  jumpSegment(3.4),
  hold(0.3, { gait: 'trot', speed: 3 }),
  hold(4, { gait: 'halt', speed: 0 }), // last segment: the pat
];
const FIRST_JUMP = 6;

/**
 * Rides the script at 60 fps. Returns the largest per-frame change per tracked quantity, the
 * track of both hands (rider space) over time and the frame at which each segment starts.
 */
function ride(script = SCRIPT, quality = 'low') {
  const horse = createHorse({ quality });
  const rider = horse.rider;
  const base = new THREE.Matrix4();
  const worst = {};
  const prevQ = {};
  const prevP = {};
  const left = new THREE.Vector3();
  const right = new THREE.Vector3();
  const track = [];
  const starts = [];
  let frame = 0;
  const note = (key, value) => {
    if (!worst[key] || value > worst[key].value) worst[key] = { value, frame };
  };
  for (const seg of script) {
    starts.push(frame);
    for (let t = 0; t < seg.duration - 1e-9; t += DT, frame++) {
      horse.update(DT, { turnRate: 0, y: 0, jump: null, hop: null, refusal: null, ...seg.at(t) });
      rider.object.updateMatrixWorld(true);
      base.copy(rider.object.matrixWorld).invert();
      for (const name of ANGLE_BONES) {
        const b = rider.bones[name];
        if (prevQ[name]) note(`angle:${name}`, prevQ[name].angleTo(b.quaternion));
        prevQ[name] = b.quaternion.clone();
      }
      for (const name of POINT_BONES) {
        const p = new THREE.Vector3()
          .setFromMatrixPosition(rider.bones[name].matrixWorld)
          .applyMatrix4(base);
        if (prevP[name]) note(`pos:${name}`, prevP[name].distanceTo(p));
        prevP[name] = p;
      }
      left.setFromMatrixPosition(rider.bones.Lhand.matrixWorld).applyMatrix4(base);
      right.setFromMatrixPosition(rider.bones.Rhand.matrixWorld).applyMatrix4(base);
      track.push({ lz: left.z, ly: left.y, rz: right.z, ry: right.y });
    }
  }
  return { worst, track, starts };
}

describe('rider pose continuity at 60 fps', () => {
  const result = ride();

  it('no joint angle jumps between two frames anywhere in the ride', () => {
    for (const name of ANGLE_BONES) {
      const w = result.worst[`angle:${name}`];
      expect(w.value, `${name} at frame ${w.frame}`).toBeLessThan(MAX_ANGLE_STEP);
    }
  });

  it('no pelvis, head, hand or foot position jumps between two frames', () => {
    for (const name of POINT_BONES) {
      const w = result.worst[`pos:${name}`];
      expect(w.value, `${name} at frame ${w.frame}`).toBeLessThan(MAX_POINT_STEP[name]);
    }
  });

  it('crest release: the hands go forward along the neck in flight and back after landing', () => {
    const jumpStart = result.starts[FIRST_JUMP];
    const frames = (s) => Math.round(s * 60);
    const before = result.track[jumpStart - 2];
    let peak = -Infinity;
    for (let i = 0; i < frames(JUMP.total); i++) {
      peak = Math.max(peak, result.track[jumpStart + i].lz);
    }
    const after = result.track[jumpStart + frames(JUMP.total) + frames(1.2)];
    expect(peak).toBeGreaterThan(before.lz + 0.1);
    expect(Math.abs(after.lz - before.lz)).toBeLessThan(0.05);
    // the hands follow the neck down, they do not rise above the approach height
    const flightY = result.track[jumpStart + frames(0.45)].ly;
    expect(flightY).toBeLessThan(before.ly);
  });

  it('pats the neck with the right hand when the horse stands after a jump', () => {
    const last = result.starts[result.starts.length - 1];
    let reach = -Infinity;
    for (let i = last; i < result.track.length; i++) reach = Math.max(reach, result.track[i].rz);
    const rest = result.track[result.track.length - 1];
    expect(reach).toBeGreaterThan(rest.rz + 0.08);
    // the left hand stays on the reins
    expect(Math.abs(result.track[last + 120].lz - rest.lz)).toBeLessThan(0.02);
  });
});
