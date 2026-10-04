// The built world per quality level (rule 3): what is drawn, what it costs in draw calls and
// triangles, and that the details of SRT-011 appear, disappear and are freed correctly. No WebGL:
// the numbers come from the scene graph (scene-stats.js), the textures from a canvas double.
import { beforeAll, describe, expect, it } from 'vitest';
import * as THREE from 'three';
import { installFakeCanvas } from '../../../tests/support/fake-canvas.js';
import { createWorld } from './world.js';
import { QUALITY_PRESETS } from './quality.js';
import { sceneStats } from './scene-stats.js';
import { collectGpuObjects } from './resilience.js';
import { paddockContains } from './world-layout.js';

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

// What the world cost before SRT-011 (same course, measured at the commit before the change).
// "low" must never get more than this (acceptance criterion of SRT-011); a deliberate change of
// the base scenery updates these numbers.
const BEFORE = Object.freeze({
  low: { calls: 17, triangles: 23924 },
  medium: { calls: 17, triangles: 48796 },
  high: { calls: 18, triangles: 94846 },
});

// Budgets per level from the research (three.js forum, mobile practice): ~100 draw calls on mobile
const BUDGET = Object.freeze({
  low: { calls: 60, triangles: 60_000 },
  medium: { calls: 100, triangles: 120_000 },
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

/** The shadow and density stages, as the engine applies them on a level change. */
function showLevel(world, level) {
  const preset = QUALITY_PRESETS[level];
  world.applyQualityStage('shadows', preset);
  world.applyQualityStage('density', preset);
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
    }
  });

  it('low draws no more calls and triangles than before the details were added', () => {
    expect(stats.low.calls).toBeLessThanOrEqual(BEFORE.low.calls);
    expect(stats.low.triangles).toBeLessThanOrEqual(BEFORE.low.triangles);
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

  it('the levels get richer: low < medium < high', () => {
    expect(stats.low.triangles).toBeLessThan(stats.medium.triangles);
    expect(stats.medium.triangles).toBeLessThan(stats.high.triangles);
    expect(stats.low.calls).toBeLessThanOrEqual(stats.medium.calls);
    expect(stats.medium.calls).toBeLessThanOrEqual(stats.high.calls);
    expect(stats.medium.triangles).toBeGreaterThan(BEFORE.medium.triangles);
    expect(stats.high.triangles).toBeGreaterThan(BEFORE.high.triangles);
  });

  it('medium adds flowers, birds, bunting, flower boxes, the paddock and the props', () => {
    for (const name of ['flowers', 'birds', 'bunting', 'decor-props', 'planters']) {
      expect(names.medium.has(name), name).toBe(true);
    }
    expect(names.medium.has('butterflies')).toBe(false);
  });

  it('only high has butterflies', () => {
    expect(names.high.has('butterflies')).toBe(true);
    for (const name of ['flowers', 'birds', 'bunting', 'decor-props', 'planters']) {
      expect(names.high.has(name), name).toBe(true);
    }
  });

  it('adds only a handful of draw calls per level', () => {
    expect(stats.medium.calls - BEFORE.medium.calls).toBeLessThanOrEqual(8);
    expect(stats.high.calls - BEFORE.high.calls).toBeLessThanOrEqual(8);
  });

  it('thins the details out on medium: fewer flowers, grass tufts and pennants than on high', () => {
    const count = (name, level) => {
      showLevel(world, level);
      const mesh = world.scene.getObjectByName(name);
      return mesh.isInstancedMesh ? mesh.count : mesh.geometry.drawRange.count;
    };
    for (const name of ['flowers', 'grass-tufts', 'birds', 'bunting']) {
      const medium = count(name, 'medium');
      const high = count(name, 'high');
      expect(medium, name).toBeGreaterThan(0);
      expect(medium, name).toBeLessThan(high);
    }
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

  it('stand at every foot of every stand, hidden on low', () => {
    expect(planters.count).toBe(STANDS);
    expect(planters.visible).toBe(false);
    showLevel(world, 'medium');
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
