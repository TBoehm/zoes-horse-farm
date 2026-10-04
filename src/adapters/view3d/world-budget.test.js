// The built world per quality level (rule 3): what is drawn, what it costs in draw calls and
// triangles, and that the details of SRT-011 appear, disappear and are freed correctly. No WebGL:
// the numbers come from the scene graph (tests/support/scene-stats.js), the textures from a canvas double.
import { beforeAll, describe, expect, it } from 'vitest';
import * as THREE from 'three';
import { installFakeCanvas } from '../../../tests/support/fake-canvas.js';
import { createWorld } from './world.js';
import { estimateGpuMemoryMB, QUALITY_PRESETS } from './quality.js';
import { sceneStats } from '../../../tests/support/scene-stats.js';
import { createGpuTracker } from '../../../tests/support/gpu-tracker.js';
import { collectGpuObjects, createGpuEpoch } from './resilience.js';
import { PADDOCK, paddockContains } from './world-layout.js';
import { planPaddockKeepOut } from './decor-plan.js';

const el = (id, kind, height, x, z, rot = 0, spread = 0) => ({
  id,
  kind,
  height,
  spread,
  x,
  z,
  rot,
});

// a small course with every kind of element and a combination
const OBSTACLES = [
  { number: 1, elements: [el('a', 'vertical', 0.8, -10, -20)] },
  { number: 2, elements: [el('b', 'oxer', 1.0, 10, -20, 0, 1.2)] },
  { number: 3, elements: [el('c', 'cross', 0.6, 10, 0, Math.PI)] },
  {
    number: 4,
    elements: [el('d1', 'vertical', 0.9, -10, 8), el('d2', 'oxer', 1.0, -10, 14, 0, 1.4)],
  },
  { number: 5, elements: [el('e', 'oxer', 1.1, 0, 25, Math.PI / 2, 1.5)] },
];
// stand rows: vertical 1, oxer 2, cross 1, vertical 1 + oxer 2, oxer 2 → 9 rows, two stands each
const STANDS = 18;

// What the world cost before SRT-011 (same course, measured at the commit before the change,
// 5e240fc). "low" must never get more than this (acceptance criterion of SRT-011), "medium" only a
// little more (SRT-013: the tablet lost its context with the full set of details); a deliberate
// change of the base scenery updates these numbers. `programs`: distinct shader programs of what is
// drawn (tests/support/gpu-tracker.js); `memoryMB`: estimateGpuMemoryMB on the tablet of
// quality.test.js.
const BEFORE = Object.freeze({
  low: { calls: 17, triangles: 23924, programs: 10, memoryMB: 30.79 },
  medium: { calls: 17, triangles: 48796, programs: 11, memoryMB: 122.99 },
  high: { calls: 18, triangles: 94846, programs: 12, memoryMB: 148.89 },
});
const TABLET = { cssWidth: 1280, cssHeight: 800, devicePixelRatio: 1.5 };

// "A handful" of additional draw calls on high; the limit leaves room for the hoof dust (+1)
const HANDFUL_OF_CALLS = 10;
// What medium may add to what it was before the details: a couple of draw calls (the paddock props
// and the bunting), one shader program (the bunting is double-sided), a few per cent of triangles
// and under a megabyte of memory
const MEDIUM_EXTRA = Object.freeze({ calls: 2, programs: 1, triangles: 1.1, memoryMB: 1 });

// Budgets per level from the research (three.js forum, mobile practice): ~100 draw calls on mobile
const BUDGET = Object.freeze({
  low: { calls: 60, triangles: 60_000 },
  medium: { calls: 100, triangles: 90_000 },
  high: { calls: 150, triangles: 250_000 },
});

const renderer = { shadowMap: { enabled: false }, capabilities: { getMaxAnisotropy: () => 8 } };
const camera = new THREE.PerspectiveCamera();

function buildWorld(quality = 'low') {
  const world = createWorld(renderer, { quality });
  world.setObstacles(OBSTACLES, { flags: true });
  world.setLines({
    start: { a: [-3, -30], b: [3, -30] },
    finish: { a: [-3, 30], b: [3, 30] },
    labels: { start: 'S', finish: 'F' },
  });
  return world;
}

const shownLevel = new WeakMap(); // world → level the helper below has shown last
const rankOf = (level) => ['low', 'medium', 'high'].indexOf(level ?? 'low');

/** The preset of a level without the environment map (it needs a real GL context to be built). */
const testPreset = (level) => ({ ...QUALITY_PRESETS[level], envMap: false });

/**
 * The stages of a level change in the order of the engine: a downgrade goes shadows → materials →
 * density, a climb the other way round.
 */
function showLevel(world, level) {
  const preset = testPreset(level);
  const down = rankOf(level) < rankOf(shownLevel.get(world));
  const order = down ? ['shadows', 'materials', 'density'] : ['density', 'materials', 'shadows'];
  for (const id of order) world.applyQualityStage(id, preset);
  shownLevel.set(world, level);
  world.update(0.016, camera);
}

const visibleNames = (world) => {
  const names = new Set();
  world.scene.traverseVisible((o) => {
    if (o.isMesh && o.name) names.add(o.name);
  });
  return names;
};

describe('the world per level', () => {
  let world;
  const stats = {};
  const names = {};

  beforeAll(() => {
    installFakeCanvas();
    world = buildWorld('low');
    for (const level of ['low', 'medium', 'high']) {
      showLevel(world, level);
      stats[level] = sceneStats(world.scene);
      names[level] = visibleNames(world);
      // a fresh tracker per level: what a ride at this level has on the GPU
      const tracker = createGpuTracker({ scene: world.scene, renderer });
      tracker.compile(world.compileRoot);
      stats[level].programs = tracker.snapshot().programs;
    }
  });

  it('low draws no more calls and triangles than before the details were added', () => {
    expect(stats.low.calls).toBeLessThanOrEqual(BEFORE.low.calls);
    expect(stats.low.triangles).toBeLessThanOrEqual(BEFORE.low.triangles);
  });

  it('low has no more shader programs than before', () => {
    expect(stats.low.programs).toBeLessThanOrEqual(BEFORE.low.programs);
  });

  it('low shows none of the new details', () => {
    for (const name of ['flowers', 'birds', 'butterflies', 'bunting', 'decor-props', 'planters']) {
      expect(names.low.has(name), name).toBe(false);
    }
  });

  it('medium and high stay inside the budget of their level', () => {
    for (const level of ['medium', 'high']) {
      expect(stats[level].calls, `${level} calls`).toBeLessThanOrEqual(BUDGET[level].calls);
      expect(stats[level].triangles, `${level} triangles`).toBeLessThanOrEqual(
        BUDGET[level].triangles,
      );
    }
  });

  it('medium stays close to what it was before the details (SRT-013, commit 5e240fc)', () => {
    const before = BEFORE.medium;
    expect(stats.medium.calls).toBeLessThanOrEqual(before.calls + MEDIUM_EXTRA.calls);
    expect(stats.medium.triangles).toBeLessThanOrEqual(before.triangles * MEDIUM_EXTRA.triangles);
    expect(stats.medium.programs).toBeLessThanOrEqual(before.programs + MEDIUM_EXTRA.programs);
    const memory = estimateGpuMemoryMB(QUALITY_PRESETS.medium, TABLET);
    expect(memory).toBeLessThanOrEqual(before.memoryMB + MEDIUM_EXTRA.memoryMB);
  });

  it('high stays below the budget with its details, and costs more programs than medium', () => {
    expect(stats.high.programs).toBeGreaterThan(stats.medium.programs);
    expect(estimateGpuMemoryMB(QUALITY_PRESETS.high, TABLET)).toBeGreaterThan(
      estimateGpuMemoryMB(QUALITY_PRESETS.medium, TABLET),
    );
  });

  it('the levels get richer: low < medium < high', () => {
    expect(stats.low.triangles).toBeLessThan(stats.medium.triangles);
    expect(stats.medium.triangles).toBeLessThan(stats.high.triangles);
    expect(stats.low.calls).toBeLessThanOrEqual(stats.medium.calls);
    expect(stats.medium.calls).toBeLessThanOrEqual(stats.high.calls);
    expect(stats.medium.triangles).toBeGreaterThan(BEFORE.medium.triangles);
    expect(stats.high.triangles).toBeGreaterThan(BEFORE.high.triangles);
  });

  it('medium adds only the static bunting and the paddock props (cheap, rule 4)', () => {
    for (const name of ['bunting', 'decor-props']) {
      expect(names.medium.has(name), name).toBe(true);
    }
    for (const name of ['flowers', 'birds', 'butterflies', 'planters', 'grass-tufts']) {
      expect(names.medium.has(name), name).toBe(false);
    }
  });

  it('high has all the details, also the butterflies', () => {
    for (const name of ['flowers', 'birds', 'butterflies', 'bunting', 'decor-props', 'planters']) {
      expect(names.high.has(name), name).toBe(true);
    }
  });

  it('adds only a handful of draw calls on high', () => {
    // 8 on high today; the hoof dust adds one more while it is alive
    expect(stats.high.calls - BEFORE.high.calls).toBeLessThanOrEqual(HANDFUL_OF_CALLS);
  });

  it('has the wind only on high: medium builds the plain programs of the trees and bushes', () => {
    const windy = (level) => {
      showLevel(world, level);
      return ['trees-deciduous', 'bushes'].map((name) => {
        const material = world.scene.getObjectByName(name).material;
        return material.customProgramCacheKey().startsWith('wind-');
      });
    };
    expect(windy('high')).toEqual([true, true]);
    expect(windy('medium')).toEqual([false, false]);
    expect(windy('low')).toEqual([false, false]);
    showLevel(world, 'high');
  });

  it('thins the bunting out on medium: every second pennant, no grass tufts', () => {
    const count = (name, level) => {
      showLevel(world, level);
      const mesh = world.scene.getObjectByName(name);
      return mesh.isInstancedMesh ? mesh.count : mesh.geometry.drawRange.count;
    };
    const medium = count('bunting', 'medium');
    const high = count('bunting', 'high');
    expect(medium).toBeGreaterThan(0);
    expect(medium).toBeLessThan(high);
    // the grass tufts are the biggest cost of the details and only high has them
    expect(count('grass-tufts', 'medium')).toBe(0);
    expect(count('grass-tufts', 'high')).toBeGreaterThan(0);
    // the flowers and the birds are high only
    expect(count('flowers', 'medium')).toBe(0);
    expect(count('birds', 'medium')).toBe(0);
    showLevel(world, 'high');
  });

  it('is back to the old cost after high → low, nothing stays behind', () => {
    showLevel(world, 'high');
    showLevel(world, 'low');
    const again = sceneStats(world.scene);
    expect(again.calls).toBe(stats.low.calls);
    expect(again.triangles).toBe(stats.low.triangles);
    expect(visibleNames(world)).toEqual(names.low);
  });

  it('puts the paddock fence in only where decoration is on', () => {
    const posts = world.scene.getObjectByName('fence-posts');
    showLevel(world, 'low');
    const lowCount = posts.count;
    showLevel(world, 'medium');
    expect(posts.count).toBeGreaterThan(lowCount);
    showLevel(world, 'low');
    expect(posts.count).toBe(lowCount);
  });

  it('keeps the meadow flowers out of the paddock', () => {
    const flowers = world.scene.getObjectByName('flowers');
    const m = new THREE.Matrix4();
    const p = new THREE.Vector3();
    for (let i = 0; i < flowers.userData.total; i += 1) {
      flowers.getMatrixAt(i, m);
      p.setFromMatrixPosition(m);
      expect(paddockContains(p.x, p.z, -1)).toBe(false);
    }
  });
});

describe('the flower boxes at the stands', () => {
  let world;
  let planters;

  beforeAll(() => {
    installFakeCanvas();
    world = buildWorld('low');
    planters = world.scene.getObjectByName('planters');
  });

  it('stand at every foot of every stand, shown on high only', () => {
    expect(planters.count).toBe(STANDS);
    expect(planters.visible).toBe(false);
    showLevel(world, 'medium');
    expect(planters.visible).toBe(false);
    showLevel(world, 'high');
    expect(planters.visible).toBe(true);
    showLevel(world, 'low');
    expect(planters.visible).toBe(false);
  });

  it('follow the obstacles when a new course is set or the course is cleared', () => {
    showLevel(world, 'high');
    world.setObstacles([OBSTACLES[0], OBSTACLES[2]]);
    expect(planters.count).toBe(4);
    expect(planters.visible).toBe(true);
    world.setObstacles(OBSTACLES);
    expect(planters.count).toBe(STANDS);
    world.setObstacles([]);
    expect(planters.count).toBe(0);
    expect(planters.visible).toBe(false);
    world.setObstacles(OBSTACLES);
    expect(planters.visible).toBe(true);
  });

  it('sit outside the opening between the stands, beside every stand', () => {
    world.setObstacles([OBSTACLES[0]]);
    const m = new THREE.Matrix4();
    const p = new THREE.Vector3();
    const xs = [];
    for (let i = 0; i < planters.count; i += 1) {
      planters.getMatrixAt(i, m);
      p.setFromMatrixPosition(m);
      xs.push(p.x - OBSTACLES[0].elements[0].x);
      expect(p.y).toBe(0);
    }
    xs.sort((a, b) => a - b);
    expect(xs[0]).toBeLessThan(-1.8);
    expect(xs[1]).toBeGreaterThan(1.8);
  });

  it('give each obstacle its own blossom colour and keep it for all its boxes', () => {
    world.setObstacles(OBSTACLES);
    const colors = [];
    const c = new THREE.Color();
    for (let i = 0; i < planters.count; i += 1) {
      planters.getColorAt(i, c);
      colors.push(c.getHex());
    }
    // the first obstacle has one element with one row: two boxes of one colour
    expect(colors[0]).toBe(colors[1]);
    expect(new Set(colors).size).toBeGreaterThan(2);
  });
});

describe('a staged level change and the shader programs', () => {
  const DETAILS = [
    'grass-tufts',
    'flowers',
    'birds',
    'butterflies',
    'bunting',
    'decor-props',
    'planters',
  ];
  const shownDetails = (world) =>
    DETAILS.filter((name) => world.scene.getObjectByName(name)?.visible);

  it('draws no detail between the materials and the density stage of a downgrade', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    showLevel(world, 'high');
    expect(shownDetails(world)).toEqual(DETAILS);
    const low = testPreset('low');
    world.applyQualityStage('shadows', low);
    expect(shownDetails(world)).toEqual(DETAILS);
    // the engine compiles after this stage: the details must not be part of it, they go next
    world.applyQualityStage('materials', low);
    expect(shownDetails(world)).toEqual([]);
    world.applyQualityStage('density', low);
    expect(shownDetails(world)).toEqual([]);
    world.dispose();
  });

  it('shows no detail with the old materials during a climb, only with the new ones', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    const high = testPreset('high');
    world.applyQualityStage('density', high);
    expect(shownDetails(world)).toEqual([]);
    world.applyQualityStage('materials', high);
    expect(shownDetails(world)).toEqual(DETAILS);
    for (const name of DETAILS) {
      expect(world.scene.getObjectByName(name).material.isMeshStandardMaterial, name).toBe(true);
    }
    world.dispose();
  });

  it('keeps the details visible when only the density changes (medium → high)', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    showLevel(world, 'medium');
    const before = shownDetails(world);
    expect(before.length).toBeGreaterThan(0);
    // the same shader programs (no wind code), more details: nothing is held back
    world.applyQualityStage('density', { ...testPreset('high'), wind: false });
    expect(shownDetails(world)).toEqual(DETAILS);
    world.dispose();
  });

  it('does not show the flower boxes of a new course while the stages are out of step', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    showLevel(world, 'high');
    world.applyQualityStage('materials', testPreset('low'));
    world.setObstacles(OBSTACLES);
    expect(shownDetails(world)).toEqual([]);
    world.applyQualityStage('density', testPreset('low'));
    expect(shownDetails(world)).toEqual([]);
    world.dispose();
  });
});

describe('a level change after a lost and restored context', () => {
  it('marks the materials for a rebuild although their dispose is skipped (no stale wind)', () => {
    installFakeCanvas();
    const epoch = createGpuEpoch();
    const world = createWorld(renderer, { quality: testPreset('high'), release: epoch.release });
    world.setObstacles(OBSTACLES, { flags: true });
    world.update(0.016, camera);
    const trees = world.scene.getObjectByName('trees-deciduous').material;
    const bushes = world.scene.getObjectByName('bushes').material;
    expect(trees.customProgramCacheKey()).toMatch(/^wind-/);
    const disposed = [];
    for (const material of [trees, bushes]) {
      material.addEventListener('dispose', () => disposed.push(material));
    }

    epoch.contextLost(world.gpuObjects());
    epoch.contextRestored();
    const versions = [trees.version, bushes.version];
    for (const id of ['shadows', 'materials', 'density']) {
      world.applyQualityStage(id, testPreset('medium'));
    }

    // three.js recompiles a material only when its version changed
    expect([trees.version, bushes.version].map((v, i) => v > versions[i])).toEqual([true, true]);
    expect(trees.customProgramCacheKey().startsWith('wind-')).toBe(false);
    expect(bushes.customProgramCacheKey().startsWith('wind-')).toBe(false);
    // the dispose of a pre-loss material would delete handles of the lost context
    expect(disposed).toEqual([]);
    world.dispose();
  });
});

describe('animation and cleanup', () => {
  it('moves the birds and the wind with every frame', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    showLevel(world, 'high');
    const birds = world.scene.getObjectByName('birds');
    const m = new THREE.Matrix4();
    birds.getMatrixAt(0, m);
    const before = new THREE.Vector3().setFromMatrixPosition(m);
    for (let i = 0; i < 120; i += 1) world.update(1 / 60, camera);
    birds.getMatrixAt(0, m);
    const after = new THREE.Vector3().setFromMatrixPosition(m);
    expect(after.distanceTo(before)).toBeGreaterThan(3);
    // altitude: birds circle high above the meadow, at a distance
    expect(after.y).toBeGreaterThan(8);
    expect(Math.hypot(after.x, after.z)).toBeGreaterThan(20);
  });

  it('hides the flowers again on low, and nothing of them is drawn', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    const flowers = world.scene.getObjectByName('flowers');
    showLevel(world, 'high');
    expect(flowers.visible).toBe(true);
    showLevel(world, 'low');
    expect(flowers.visible).toBe(false);
    expect(flowers.count).toBe(0);
  });

  it('frees every geometry, material and instanced mesh when the world is disposed', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    showLevel(world, 'high');
    const freed = new Set();
    const objects = collectGpuObjects(world.scene);
    for (const object of objects) {
      object.addEventListener('dispose', () => freed.add(object));
    }
    expect(objects.length).toBeGreaterThan(20);
    world.dispose();
    const missed = objects.filter((o) => !freed.has(o)).map((o) => o.name || o.type);
    expect(missed).toEqual([]);
  });

  it('lists the details among the objects handed to the context loss handling', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    const objects = new Set(world.gpuObjects());
    for (const name of ['flowers', 'birds', 'butterflies', 'planters']) {
      const mesh = world.scene.getObjectByName(name);
      expect(objects.has(mesh), name).toBe(true);
      expect(objects.has(mesh.geometry), `${name} geometry`).toBe(true);
    }
  });
});

describe('grazing horses and hoof dust', () => {
  // a camera that looks at the paddock from the meadow, and one that looks away from it
  const towards = new THREE.PerspectiveCamera(50, 1.6, 0.1, 900);
  towards.position.set(PADDOCK.x, 6, PADDOCK.z + 22);
  towards.lookAt(PADDOCK.x, 0, PADDOCK.z);
  towards.updateMatrixWorld(true);
  const away = new THREE.PerspectiveCamera(50, 1.6, 0.1, 900);
  away.position.set(0, 6, 0);
  away.lookAt(60, 0, 40);
  away.updateMatrixWorld(true);

  /** A world at a level, reached the way the tests above do (no environment map in the test). */
  const worldAt = (level) => {
    const world = buildWorld('low');
    showLevel(world, level);
    return world;
  };
  const horsesOf = (world) => {
    const group = world.scene.getObjectByName('paddock-horses');
    return group ? group.children : [];
  };
  const dustOf = (world) => world.scene.getObjectByName('hoof-dust');

  it('has no grazing horses and no dust on low and medium, two horses and the dust on high', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    expect(horsesOf(world)).toHaveLength(0);
    expect(dustOf(world)).toBeUndefined();
    for (const level of ['medium', 'high', 'low', 'high', 'medium']) {
      showLevel(world, level);
      const wanted = level === 'high' ? 2 : 0;
      expect(horsesOf(world), level).toHaveLength(wanted);
      expect(Boolean(dustOf(world)), level).toBe(level === 'high');
    }
    world.dispose();
  });

  it('draws one call per grazing horse and the dust only while it is alive', () => {
    installFakeCanvas();
    const world = buildWorld('low');
    const calls = (level) => {
      showLevel(world, level);
      return sceneStats(world.scene).calls;
    };
    const high = calls('high');
    // two horses are in the high budget (see BEFORE.high); no puff yet, so no dust call
    expect(high - BEFORE.high.calls).toBeLessThanOrEqual(HANDFUL_OF_CALLS);
    world.emitHoofDust(0, 0, 0, 1);
    world.update(0.016, camera);
    expect(sceneStats(world.scene).calls).toBe(high + 1);
    for (let i = 0; i < 200; i += 1) world.update(0.016, camera);
    expect(sceneStats(world.scene).calls).toBe(high);
    world.dispose();
  });

  it('keeps the horses inside the paddock and away from the props', () => {
    installFakeCanvas();
    const world = worldAt('high');
    const group = world.scene.getObjectByName('paddock-horses');
    const keepOut = planPaddockKeepOut();
    for (let i = 0; i < 60 * 240; i += 1) {
      world.update(1 / 30, towards);
      if (i % 30 !== 0) continue;
      for (const horse of group.children) {
        const { x, z } = horse.position;
        expect(paddockContains(x, z, 0.5)).toBe(true);
        for (const c of keepOut) expect(Math.hypot(x - c.x, z - c.z)).toBeGreaterThan(c.r - 0.8);
      }
    }
    world.dispose();
  });

  it('animates the horses only while the paddock is in view', () => {
    installFakeCanvas();
    const world = worldAt('high');
    const group = world.scene.getObjectByName('paddock-horses');
    const state = () => JSON.stringify(group.children.map((h) => h.position.toArray()));
    const before = state();
    for (let i = 0; i < 60 * 120; i += 1) world.update(1 / 30, away);
    expect(state()).toBe(before);
    for (let i = 0; i < 60 * 120; i += 1) world.update(1 / 30, towards);
    expect(state()).not.toBe(before);
    world.dispose();
  });

  it('raises dust for strong footfalls on the sand only', () => {
    installFakeCanvas();
    const world = worldAt('high');
    const dust = dustOf(world);
    const puffs = () => {
      world.update(0.016, camera);
      return dust.visible;
    };
    expect(puffs()).toBe(false);
    world.emitHoofDust(0, 0, 5, 0.12); // a step at the walk
    expect(puffs()).toBe(false);
    world.emitHoofDust(30, 0, 5, 0.9); // on the meadow
    world.emitHoofDust(-30, 0, 22, 0.9); // on the path to the stable
    expect(puffs()).toBe(false);
    world.emitHoofDust(0, 0, 5, 0.5); // a trot step on the sand
    expect(puffs()).toBe(true);
    world.dispose();
  });

  it('follows the level: the dust pool and the horses go with a switch to low and back', () => {
    installFakeCanvas();
    const world = worldAt('high');
    world.emitHoofDust(0, 0, 0, 1);
    showLevel(world, 'low');
    expect(dustOf(world)).toBeUndefined();
    world.emitHoofDust(0, 0, 0, 1); // nothing to emit into
    showLevel(world, 'high');
    expect(dustOf(world)).toBeDefined();
    expect(horsesOf(world)).toHaveLength(2);
    world.dispose();
  });

  it('releases the GPU objects of the horses and the dust through the release hook', () => {
    installFakeCanvas();
    const released = new Set();
    const world = createWorld(renderer, { quality: 'low', release: (o) => released.add(o) });
    showLevel(world, 'high');
    const objects = world.gpuObjects();
    const horses = horsesOf(world);
    const geometries = horses.flatMap((h) => {
      const list = [];
      h.traverse((o) => o.isMesh && list.push(o.geometry));
      return list;
    });
    expect(geometries).toHaveLength(2);
    expect(geometries.every((g) => objects.includes(g))).toBe(true);
    showLevel(world, 'low');
    expect(geometries.every((g) => released.has(g))).toBe(true);
    expect(horsesOf(world)).toHaveLength(0);
    world.dispose();
  });

  it('lists the horses and the dust among the objects of the context loss handling', () => {
    installFakeCanvas();
    const world = worldAt('high');
    world.emitHoofDust(0, 0, 0, 1);
    const objects = new Set(world.gpuObjects());
    expect(objects.has(dustOf(world).geometry)).toBe(true);
    for (const horse of horsesOf(world)) {
      horse.traverse((o) => o.isMesh && expect(objects.has(o.geometry)).toBe(true));
    }
    world.dispose();
  });
});
