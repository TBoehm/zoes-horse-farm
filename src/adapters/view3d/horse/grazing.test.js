import * as THREE from 'three';
import { describe, expect, it } from 'vitest';
import { createRng } from '../textures.js';
import { createHorse } from './index.js';
import { createGrazingHorses, dealCoats } from './grazing.js';
import { GRAZING, HORSE_EXTENT, insideArea } from './grazing-logic.js';

const AREA = { x: 40, z: -20, width: 22, depth: 14, rotation: -0.4 };

function meshesOf(group) {
  const list = [];
  group.traverse((o) => o.isMesh && list.push(o));
  return list;
}

describe('grazing horses', () => {
  it('builds cheap horses: no rider, no tack, one draw call each, no shadows', () => {
    const paddock = createGrazingHorses({
      quality: 'high',
      area: AREA,
      count: 3,
      rng: createRng(1),
    });
    expect(paddock.group.children).toHaveLength(3);
    const meshes = meshesOf(paddock.group);
    expect(meshes).toHaveLength(3);
    for (const m of meshes) {
      expect(m.name).toBe('horse-body');
      expect(m.castShadow).toBe(false);
      expect(m.frustumCulled).toBe(true);
      expect(m.boundingSphere.radius).toBeGreaterThan(1.5);
    }
    paddock.dispose();
  });

  it('gives the horses different coats, the same ones for the same seed', () => {
    for (const seed of [1, 2, 3, 4, 5, 6]) {
      const [a, b] = dealCoats(['grey', 'chestnut'], 2, createRng(seed));
      expect(a).not.toBe(b);
      expect(dealCoats(['grey', 'chestnut'], 2, createRng(seed))).toEqual([a, b]);
    }
    // more horses than coats: the coats start over
    expect(dealCoats(['a', 'b'], 3, () => 0)).toEqual(['a', 'b', 'a']);
  });

  it('uses the low horse model on low and medium and the medium model on high', () => {
    const tri = (q) => {
      const p = createGrazingHorses({ quality: q, area: AREA, count: 1 });
      const n = meshesOf(p.group)[0].geometry.index.count / 3;
      p.dispose();
      return n;
    };
    expect(tri('low')).toBe(tri('medium'));
    expect(tri('high')).toBeGreaterThan(tri('medium'));
    // the preset objects of the quality module are accepted too
    const p = createGrazingHorses({ quality: { characterDetail: 'high' }, area: AREA, count: 1 });
    expect(meshesOf(p.group)[0].geometry.index.count / 3).toBe(tri('high'));
    p.dispose();
  });

  it('the horses stand at their spots before the first update and stay in the paddock', () => {
    const paddock = createGrazingHorses({
      quality: 'low',
      area: AREA,
      count: 2,
      rng: createRng(3),
    });
    const horses = paddock.group.children;
    const start = horses.map((h) => h.position.clone());
    for (const p of start) expect(insideArea(AREA, p.x, p.z, GRAZING.margin - 1)).toBe(true);
    let headDown = 0;
    let walking = 0;
    let frames = 0;
    const last = start.map((p) => p.clone());
    const head = horses.map((h) => h.getObjectByName('head'));
    const v = new THREE.Vector3();
    for (let t = 0; t < 400; t += 1 / 30) {
      paddock.update(1 / 30);
      frames++;
      horses.forEach((h, i) => {
        const p = h.position;
        // the object origin is ahead of the body centre: at most bodyOffset plus the tolerance
        expect(insideArea(AREA, p.x, p.z, GRAZING.margin - GRAZING.bodyOffset - 0.3)).toBe(true);
        expect(p.y).toBe(0);
        h.updateMatrixWorld(true);
        if (head[i].getWorldPosition(v).y < 0.9) headDown++;
        if (p.distanceTo(last[i]) * 30 > 0.2) walking++;
        last[i].copy(p);
      });
    }
    expect(headDown / (frames * 2)).toBeGreaterThan(0.5);
    expect(walking).toBeGreaterThan(30);
    expect(horses.some((h, i) => h.position.distanceTo(start[i]) > 1)).toBe(true);
    paddock.dispose();
  });

  it('the head goes down to the grass', () => {
    const paddock = createGrazingHorses({
      quality: 'low',
      area: AREA,
      count: 1,
      rng: createRng(5),
    });
    const horse = paddock.group.children[0];
    const head = horse.getObjectByName('head');
    const v = new THREE.Vector3();
    let lowest = Infinity;
    let highest = 0;
    for (let t = 0; t < 60; t += 1 / 30) {
      paddock.update(1 / 30);
      horse.updateMatrixWorld(true);
      // the muzzle: in the head frame, below and in front of the poll
      const y = v.set(0, -0.5, 0.35).applyMatrix4(head.matrixWorld).y;
      lowest = Math.min(lowest, y);
      highest = Math.max(highest, y);
    }
    expect(lowest).toBeLessThan(0.6);
    expect(highest).toBeGreaterThan(1.0); // it looks up now and then
    paddock.dispose();
  });

  it('is deterministic for a seed', () => {
    const run = () => {
      const p = createGrazingHorses({ quality: 'low', area: AREA, count: 2, rng: createRng(9) });
      for (let t = 0; t < 120; t += 1 / 30) p.update(1 / 30);
      const out = p.group.children.map((h) => [h.position.x, h.position.z]);
      p.dispose();
      return out;
    };
    expect(run()).toEqual(run());
  });

  it('releases every geometry and material through the release hook (GPU epoch)', () => {
    const released = new Set();
    const paddock = createGrazingHorses({
      quality: 'medium',
      area: AREA,
      count: 2,
      release: (o) => released.add(o),
    });
    const before = meshesOf(paddock.group).map((m) => [m.geometry, m.material]);
    paddock.setQuality('high');
    for (const [g, m] of before) {
      expect(released.has(g)).toBe(true);
      expect(released.has(m)).toBe(true);
    }
    const current = meshesOf(paddock.group).map((m) => [m.geometry, m.material]);
    paddock.dispose();
    // the final dispose goes through the hook, too (objects of a lost context are not freed)
    for (const [g, m] of current) {
      expect(released.has(g)).toBe(true);
      expect(released.has(m)).toBe(true);
    }
    expect(paddock.group.parent).toBe(null);
  });

  it('draw calls: one per horse and nothing else', () => {
    const paddock = createGrazingHorses({ quality: 'medium', area: AREA, count: 4 });
    const drawables = [];
    paddock.group.traverse((o) => (o.isMesh || o.isPoints || o.isLine) && drawables.push(o));
    expect(drawables).toHaveLength(4);
    paddock.dispose();
  });
});

describe('the model of a paddock horse fits into the extent that the behaviour plans with', () => {
  /** Extent of the skinned body in the frame of the object (+Z forward) over a series of poses. */
  function measure(level) {
    const horse = createHorse({
      quality: level,
      rider: false,
      tack: false,
      rng: createRng(4),
    });
    const body = horse.object.getObjectByName('horse-body');
    const v = new THREE.Vector3();
    const ext = { front: -Infinity, back: -Infinity, side: 0, radius: 0 };
    const scan = () => {
      horse.object.updateMatrixWorld(true);
      const n = body.geometry.attributes.position.count;
      for (let i = 0; i < n; i++) {
        body.getVertexPosition(i, v);
        v.applyMatrix4(body.matrixWorld);
        ext.front = Math.max(ext.front, v.z);
        ext.back = Math.max(ext.back, -v.z);
        ext.side = Math.max(ext.side, Math.abs(v.x));
        ext.radius = Math.max(ext.radius, Math.hypot(v.x, v.z + GRAZING.bodyOffset));
      }
    };
    const run = (state, seconds) => {
      for (let t = 0; t < seconds; t += 1 / 30) {
        horse.update(1 / 30, state);
        if (Math.round(t * 30) % 15 === 0) scan();
      }
    };
    run({ gait: 'halt', speed: 0, turnRate: 0, y: 0, graze: 1, jump: null }, 40);
    run({ gait: 'halt', speed: 0, turnRate: 0, y: 0, graze: 0, jump: null }, 40);
    run({ gait: 'walk', speed: GRAZING.walkSpeed, turnRate: 0.3, y: 0, graze: 0, jump: null }, 20);
    run({ gait: 'halt', speed: 0, turnRate: GRAZING.turnRate, y: 0, graze: 0, jump: null }, 20);
    horse.dispose();
    return ext;
  }

  for (const level of ['low', 'medium']) {
    it(`${level}: nose, tail, sides and the circle around the body centre`, () => {
      const e = measure(level);
      expect(e.front).toBeLessThanOrEqual(HORSE_EXTENT.front);
      expect(e.back).toBeLessThanOrEqual(HORSE_EXTENT.back);
      expect(e.side).toBeLessThanOrEqual(HORSE_EXTENT.side);
      expect(e.radius).toBeLessThanOrEqual(GRAZING.bodyRadius);
      // and the numbers are not wildly generous (the model would shrink and nobody would notice)
      expect(e.front).toBeGreaterThan(HORSE_EXTENT.front - 0.08);
      expect(e.back).toBeGreaterThan(HORSE_EXTENT.back - 0.08);
    });
  }
});
