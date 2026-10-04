// Flight paths of the birds and butterflies (pure, no three.js): closed-form functions of time, so
// a frame costs no state and the paths can be tested. Heading h points along (sin h, cos h), like
// the rotations of the obstacles.

// Technical values of the paths (look, no game play)
export const BIRD_LIMITS = Object.freeze({
  radius: Object.freeze([55, 95]), // orbit radius of a flock (m)
  altitude: Object.freeze([14, 30]), // above the meadow (m)
  speed: Object.freeze([4.5, 7]), // m/s: circling slowly
  bob: Object.freeze([1.2, 2.8]), // slow rise and fall of a flock (m)
  spread: 8, // no bird is farther than this from the center of its flock (m, horizontal)
});

const BUTTERFLY = Object.freeze({
  reachShare: Object.freeze([0.4, 0.6]), // wander radius as a share of the patch radius
  maxWander: 2.2, // m
  height: Object.freeze([0.45, 0.95]),
  bob: 0.18,
  frequency: Object.freeze([0.25, 0.45]), // rad/s of the wander
});

const between = (rng, [a, b]) => a + rng() * (b - a);

/**
 * Plans `count` flocks of `size` birds. A flock circles around (cx, cz) at `radius`, slowly and
 * at its own height; `dir` is the sense of rotation (±1). Each bird has a fixed place in the
 * flock: f forward, r to the right, u up (m) and a phase for its wobble.
 */
export function planFlocks(rng, count, size) {
  const flocks = [];
  for (let i = 0; i < count; i += 1) {
    const birds = [];
    for (let k = 0; k < size; k += 1) {
      birds.push({
        f: (rng() - 0.5) * 7,
        r: (rng() - 0.5) * 6,
        u: (rng() - 0.5) * 2,
        phase: rng() * Math.PI * 2,
      });
    }
    flocks.push({
      cx: (rng() - 0.5) * 50,
      cz: (rng() - 0.5) * 50,
      radius: between(rng, BIRD_LIMITS.radius),
      altitude: between(rng, BIRD_LIMITS.altitude),
      bob: between(rng, BIRD_LIMITS.bob),
      speed: between(rng, BIRD_LIMITS.speed),
      dir: i % 2 === 0 ? 1 : -1,
      angle0: rng() * Math.PI * 2,
      phase: rng() * Math.PI * 2,
      birds,
    });
  }
  return flocks;
}

/** Center of a flock at time t: writes { x, y, z, heading } into `out` and returns it. */
export function flockPose(flock, t, out) {
  const angle = flock.angle0 + (flock.dir * flock.speed * t) / flock.radius;
  out.x = flock.cx + Math.cos(angle) * flock.radius;
  out.z = flock.cz + Math.sin(angle) * flock.radius;
  out.y = flock.altitude + Math.sin(t * 0.21 + flock.phase) * flock.bob;
  // tangent of the circle
  out.heading = Math.atan2(-Math.sin(angle) * flock.dir, Math.cos(angle) * flock.dir);
  return out;
}

/**
 * Pose of one bird of a flock: its place in the flock, drifting a little, turned along the path
 * and banking into the turn. Writes { x, y, z, heading, roll } into `out` and returns it.
 */
export function birdPose(flock, index, t, out) {
  const bird = flock.birds[index];
  flockPose(flock, t, out);
  const forward = bird.f + Math.sin(t * 0.4 + bird.phase) * 0.6;
  const right = bird.r + Math.sin(t * 0.33 + bird.phase * 1.7) * 0.5;
  const sin = Math.sin(out.heading);
  const cos = Math.cos(out.heading);
  out.x += sin * forward + cos * right;
  out.z += cos * forward - sin * right;
  out.y += bird.u + Math.sin(t * 0.9 + bird.phase) * 0.35;
  out.heading += Math.sin(t * 0.5 + bird.phase) * 0.08;
  out.roll = -flock.dir * 0.3 + Math.sin(t * 0.7 + bird.phase) * 0.1;
  return out;
}

/**
 * Plans `count` butterflies that hover over the given patches ([{ x, z, radius }], used in turn).
 * Each wanders on a slightly irregular ellipse around (ax, az); `reach` is the farthest it gets.
 */
export function planButterflies(rng, anchors, count) {
  if (!anchors.length) return [];
  const out = [];
  for (let i = 0; i < count; i += 1) {
    const anchor = anchors[i % anchors.length];
    const wander = () =>
      Math.min(BUTTERFLY.maxWander, anchor.radius * between(rng, BUTTERFLY.reachShare));
    const rx = wander();
    const rz = wander();
    out.push({
      ax: anchor.x + (rng() - 0.5) * anchor.radius * 0.6,
      az: anchor.z + (rng() - 0.5) * anchor.radius * 0.6,
      rx,
      rz,
      reach: 1.25 * Math.hypot(rx, rz),
      height: between(rng, BUTTERFLY.height),
      freq: between(rng, BUTTERFLY.frequency),
      p1: rng() * Math.PI * 2,
      p2: rng() * Math.PI * 2,
      p3: rng() * Math.PI * 2,
    });
  }
  return out;
}

/**
 * Pose of a butterfly at time t: writes { x, y, z, heading } into `out` and returns it. The
 * heading follows the base ellipse, which never stands still, so it turns smoothly even where the
 * small irregularities of the position slow the butterfly down.
 */
export function butterflyPose(b, t, out) {
  const a = b.freq * t + b.p1;
  out.x = b.ax + b.rx * (Math.sin(a) + 0.25 * Math.sin(2.3 * a + b.p2));
  out.z = b.az + b.rz * (Math.cos(a) + 0.25 * Math.sin(1.7 * a + b.p3));
  out.y = b.height + BUTTERFLY.bob * Math.sin(b.freq * 3.1 * t + b.p3);
  out.heading = Math.atan2(b.rx * Math.cos(a), -b.rz * Math.sin(a));
  return out;
}
