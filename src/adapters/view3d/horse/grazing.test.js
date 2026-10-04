import * as THREE from 'three';
import { describe, expect, it } from 'vitest';
import { createRng } from '../textures.js';
import { createGrazingHorses } from './grazing.js';
import { insideArea } from './grazing-logic.js';

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

  it('the horses stay in the paddock, graze with the head down and sometimes walk', () => {
    const paddock = createGrazingHorses({
      quality: 'low',
      area: AREA,
      count: 2,
      rng: createRng(3),
    });
    const start = paddock.horses.map((h) => h.object.position.clone());
    let headDown = 0;
    let walking = 0;
    let frames = 0;
    for (let t = 0; t < 400; t += 1 / 30) {
      paddock.update(1 / 30);
      frames++;
      for (const [i, h] of paddock.horses.entries()) {
        const p = h.object.position;
        expect(insideArea(AREA, p.x, p.z, -0.6)).toBe(true);
        expect(p.y).toBe(0);
        if (paddock.grazers[i].graze > 0.9) headDown++;
        if (paddock.grazers[i].speed > 0.2) walking++;
      }
    }
    expect(headDown / (frames * 2)).toBeGreaterThan(0.5);
    expect(walking).toBeGreaterThan(30);
    const moved = paddock.horses.some((h, i) => h.object.position.distanceTo(start[i]) > 1);
    expect(moved).toBe(true);
    paddock.dispose();
  });

  it('the head goes down to the grass', () => {
    const paddock = createGrazingHorses({
      quality: 'low',
      area: AREA,
      count: 1,
      rng: createRng(5),
    });
    const horse = paddock.horses[0];
    const head = horse.object.getObjectByName('head');
    const muzzle = (out) => {
      horse.object.updateMatrixWorld(true);
      return out.set(0, -0.5, 0.35).applyMatrix4(head.matrixWorld);
    };
    paddock.grazers[0].timer = 100;
    const v = new THREE.Vector3();
    for (let t = 0; t < 6; t += 1 / 30) paddock.update(1 / 30);
    expect(paddock.grazers[0].graze).toBeGreaterThan(0.95);
    expect(muzzle(v).y).toBeLessThan(0.6);
    paddock.dispose();
  });

  it('is deterministic for a seed', () => {
    const run = () => {
      const p = createGrazingHorses({ quality: 'low', area: AREA, count: 2, rng: createRng(9) });
      for (let t = 0; t < 120; t += 1 / 30) p.update(1 / 30);
      const out = p.horses.map((h) => [h.object.position.x, h.object.position.z]);
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
    paddock.dispose();
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
