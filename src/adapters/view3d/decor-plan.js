// Decoration of the riding facility: the bunting along the arena fence, the flower pots at the gate
// and the props in the paddock (pure, no three.js; the meshes are built in arena-decor.js).
import { FENCE, GATE, PADDOCK, paddockPoint, planFence } from './world-layout.js';
import { ARENA } from '../../domain/sim/tuning.js';

/** Colours of the pennants (sRGB hex). */
export const BUNTING_COLORS = Object.freeze([
  0xd94a4a, 0xf2c744, 0x3f7fd0, 0x4aa05a, 0xf4f1ea, 0xe98a2c,
]);

// Technical values of the look (no game play)
const BUNTING = Object.freeze({
  pennantWidth: 0.26,
  pennantLength: 0.32,
  pitch: 0.46, // distance between two pennants along the string
  stringHeight: FENCE.height + 0.08, // at the post tops
  sagShare: 0.07, // sag of the string as a share of the span
  maxSag: 0.2,
});

/**
 * Bunting along the arena fence, one string per span between two posts (none across the gate):
 * `strings` ({ a, b, sag }, a and b = { x, y, z } at the post tops) and `pennants` ({ x, y, z } at
 * the string, unit tangent tx/tz along the string, width, length, color, span, index, core }).
 * Every second pennant (`core`) comes first: the first `coreCount` pennants are an evenly thinner
 * string, used by the medium level.
 */
export function planBunting() {
  const spans = planFence().segments.filter((s) => s.style === 'arena');
  const strings = [];
  const all = [];
  spans.forEach((span, spanIndex) => {
    const tx = Math.sin(span.ang);
    const tz = Math.cos(span.ang);
    const y = BUNTING.stringHeight;
    const a = { x: span.x - (tx * span.len) / 2, y, z: span.z - (tz * span.len) / 2 };
    const b = { x: span.x + (tx * span.len) / 2, y, z: span.z + (tz * span.len) / 2 };
    const sag = Math.min(BUNTING.maxSag, span.len * BUNTING.sagShare);
    strings.push({ a, b, sag });
    const n = Math.max(1, Math.floor(span.len / BUNTING.pitch));
    for (let index = 0; index < n; index += 1) {
      const s = (index + 0.5) / n;
      all.push({
        x: a.x + (b.x - a.x) * s,
        y: y - sag * 4 * s * (1 - s),
        z: a.z + (b.z - a.z) * s,
        tx,
        tz,
        width: BUNTING.pennantWidth,
        length: BUNTING.pennantLength,
        color: BUNTING_COLORS[(spanIndex * 3 + index) % BUNTING_COLORS.length],
        span: spanIndex,
        index,
        core: index % 2 === 0,
      });
    }
  });
  const pennants = [...all.filter((p) => p.core), ...all.filter((p) => !p.core)];
  return { strings, pennants, coreCount: all.filter((p) => p.core).length };
}

/** Flower pots against the outside of the arena fence on both sides of the gate. */
export function planPots() {
  const x = GATE.side * (ARENA.width / 2 + FENCE.offset) - 0.34;
  const z0 = GATE.z - GATE.width / 2;
  const z1 = GATE.z + GATE.width / 2;
  return [
    { x, z: z0 - 0.75, scale: 1.1, flower: 0xe9719f },
    { x: x - 0.05, z: z0 - 1.65, scale: 0.85, flower: 0xf2cf2e },
    { x, z: z1 + 0.75, scale: 1.1, flower: 0xd8453b },
    { x: x - 0.05, z: z1 + 1.65, scale: 0.9, flower: 0xf4f1ea },
  ];
}

/**
 * Props inside the paddock: a field shelter against the far fence (its open side faces the middle
 * of the paddock), a water trough and a hay rack. `rotation` is a yaw about +Y.
 */
export function planPaddockProps() {
  const toCenter = (p) => Math.atan2(PADDOCK.x - p.x, PADDOCK.z - p.z);
  const shelter = paddockPoint(-0.8, 0);
  const trough = paddockPoint(0.74, 0.8);
  const rack = paddockPoint(-0.35, -0.8);
  return {
    shelter: { ...shelter, rotation: toCenter(shelter) },
    trough: { ...trough, rotation: PADDOCK.rotation },
    rack: { ...rack, rotation: PADDOCK.rotation },
  };
}
