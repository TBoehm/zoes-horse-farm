// Meadow flowers: where the patches grow and which flower stands where (pure, no three.js).
// The placement is deterministic for a seeded rng and keeps off everything in `isBlocked`
// (sand, paths, buildings, benches, paddock).
import { isBlocked } from './world-layout.js';

/** Flower colours (sRGB hex); the index is what the flowers refer to. */
export const FLOWER_COLORS = Object.freeze([
  Object.freeze({ name: 'white', hex: 0xf4f1e6 }),
  Object.freeze({ name: 'yellow', hex: 0xf2cf2e }),
  Object.freeze({ name: 'pink', hex: 0xe9719f }),
  Object.freeze({ name: 'violet', hex: 0x8a5bc8 }),
  Object.freeze({ name: 'blue', hex: 0x4f7fe0 }),
  Object.freeze({ name: 'red', hex: 0xd8453b }),
]);

// Technical values of the placement (look of the meadow, no game play)
const DEFAULTS = Object.freeze({
  patchCount: 44,
  perPatch: [55, 110], // flowers per patch (min, max)
  innerRadius: 24, // random patches grow from here …
  outerRadius: 72, // … to here, measured from the arena center
  patchRadius: [2.2, 4.8],
  accentShare: 0.4, // share of patches with a second colour
  accentFlowers: 0.22, // share of the flowers of such a patch in the second colour
  scale: [0.75, 1.2],
});

// Fixed patches by the buildings, the paths and the paddock: [x, z, radius]
const ANCHORS = Object.freeze([
  [33, -19.5, 3],
  [29.5, -30.5, 3.2],
  [34.5, -27, 2.6],
  [-31, 9, 3],
  [-31, 34, 3.4],
  [-26.5, 30, 2.4],
  [-47, -9, 3.2],
  [-34, -19.8, 3.4],
  [-45.5, -14.5, 2.6],
  [26.5, 4, 2.8],
]);

const between = (rng, [a, b]) => a + rng() * (b - a);

/** Picks the main and the second colour of a patch. */
function pickColors(rng, accentShare) {
  const color = Math.floor(rng() * FLOWER_COLORS.length);
  let accent = null;
  if (rng() < accentShare) {
    accent = (color + 1 + Math.floor(rng() * (FLOWER_COLORS.length - 1))) % FLOWER_COLORS.length;
  }
  return { color, accent };
}

function planPatches(rng, o) {
  const patches = [];
  const fits = (x, z, radius) =>
    !isBlocked(x, z, radius * 0.5) &&
    patches.every((p) => Math.hypot(p.x - x, p.z - z) >= (p.radius + radius) * 0.8);
  const add = (x, z, radius) => {
    patches.push({ x, z, radius, ...pickColors(rng, o.accentShare) });
  };
  for (const [x, z, radius] of ANCHORS) {
    if (patches.length < o.patchCount && !isBlocked(x, z, radius * 0.5)) add(x, z, radius);
  }
  let guard = 0;
  while (patches.length < o.patchCount && guard < o.patchCount * 80) {
    guard += 1;
    const a = rng() * Math.PI * 2;
    // more patches close to the arena, where the camera looks
    const r = o.innerRadius + (o.outerRadius - o.innerRadius) * rng() ** 1.4;
    const x = Math.cos(a) * r;
    const z = Math.sin(a) * r;
    const radius = between(rng, o.patchRadius);
    if (fits(x, z, radius)) add(x, z, radius);
  }
  return patches;
}

/**
 * Plans the meadow: `patches` ({ x, z, radius, color, accent }) and `flowers` ({ x, z, scale,
 * yaw, color, patch }). The flowers are ordered round-robin over the patches, so that the first
 * n flowers are a thinner copy of the whole meadow: a lower quality level just draws fewer.
 * options: patchCount, perPatch [min, max], and the other values of DEFAULTS.
 */
export function planMeadow(rng, options = {}) {
  const o = { ...DEFAULTS, ...options };
  const patches = planPatches(rng, o);
  const lists = patches.map((patch, index) => {
    const count = Math.round(between(rng, o.perPatch));
    const list = [];
    for (let i = 0; i < count; i += 1) {
      let x = 0;
      let z = 0;
      let d = 0;
      let placed = false;
      for (let tries = 0; tries < 8 && !placed; tries += 1) {
        const a = rng() * Math.PI * 2;
        d = patch.radius * Math.sqrt(rng());
        x = patch.x + Math.cos(a) * d;
        z = patch.z + Math.sin(a) * d;
        placed = !isBlocked(x, z, 0.4);
      }
      if (!placed) continue;
      const center = 1 - d / patch.radius; // a little taller in the middle of the patch
      const useAccent = patch.accent !== null && rng() < o.accentFlowers;
      list.push({
        x,
        z,
        scale: between(rng, o.scale) + center * 0.15,
        yaw: rng() * Math.PI * 2,
        color: useAccent ? patch.accent : patch.color,
        patch: index,
      });
    }
    return list;
  });
  const flowers = [];
  const longest = lists.reduce((n, list) => Math.max(n, list.length), 0);
  for (let k = 0; k < longest; k += 1) {
    for (const list of lists) if (k < list.length) flowers.push(list[k]);
  }
  return { patches, flowers };
}

/**
 * Patches butterflies hover over: the ones closest to the arena (within sight), at most `count`.
 * Returns [{ x, z, radius }].
 */
export function butterflyAnchors(patches, count, maxDistance = 60) {
  return patches
    .filter((p) => Math.hypot(p.x, p.z) < maxDistance)
    .sort((a, b) => Math.hypot(a.x, a.z) - Math.hypot(b.x, b.z))
    .slice(0, count)
    .map(({ x, z, radius }) => ({ x, z, radius }));
}
