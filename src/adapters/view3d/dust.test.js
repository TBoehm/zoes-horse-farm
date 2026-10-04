import * as THREE from 'three';
import { describe, expect, it } from 'vitest';
import { createDust, createDustPool } from './dust.js';
import { createRng } from './textures.js';

const DT = 1 / 60;

describe('dust pool', () => {
  it('emits more puffs for a stronger footfall and never beyond the capacity', () => {
    const pool = createDustPool(20, createRng(1));
    const weak = pool.emit(0, 0, 0, 0.1);
    const strong = pool.emit(0, 0, 0, 1);
    expect(strong).toBeGreaterThan(weak);
    for (let i = 0; i < 100; i++) pool.emit(0, 0, 0, 1);
    pool.update(0);
    expect(pool.update(0)).toBeLessThanOrEqual(20);
    expect(pool.pos.length).toBe(60);
  });

  it('ignores a footfall without strength and an empty pool', () => {
    const pool = createDustPool(8, createRng(1));
    expect(pool.emit(0, 0, 0, 0)).toBe(0);
    expect(pool.update(DT)).toBe(0);
    const none = createDustPool(0, createRng(1));
    expect(none.emit(0, 0, 0, 1)).toBe(0);
    expect(none.update(DT)).toBe(0);
  });

  it('puffs rise from the sand, drift apart, grow, fade out and are free again', () => {
    const pool = createDustPool(16, createRng(2));
    pool.emit(5, 0, 7, 1);
    let alive = pool.update(DT);
    expect(alive).toBeGreaterThan(0);
    const start = Array.from(pool.size);
    let maxAlpha = 0;
    let maxY = 0;
    for (let t = 0; t < 3; t += DT) {
      alive = pool.update(DT);
      for (let i = 0; i < 16; i++) {
        maxAlpha = Math.max(maxAlpha, pool.alpha[i]);
        maxY = Math.max(maxY, pool.pos[i * 3 + 1]);
        // never under the sand
        if (pool.life[i] > 0) expect(pool.pos[i * 3 + 1]).toBeGreaterThanOrEqual(0.02 - 1e-6);
      }
    }
    expect(maxAlpha).toBeGreaterThan(0.15);
    expect(maxAlpha).toBeLessThan(0.65);
    expect(maxY).toBeGreaterThan(0.1);
    expect(maxY).toBeLessThan(2);
    expect(alive).toBe(0);
    expect(Math.max(...pool.alpha)).toBe(0);
    expect(Math.max(...start)).toBeGreaterThan(0); // they had a size while alive
  });

  it('puffs start where the footfall is (within the scatter)', () => {
    const pool = createDustPool(8, createRng(3));
    pool.emit(10, 0, -4, 0.6);
    for (let i = 0; i < 8; i++) {
      if (pool.life[i] > 0) {
        expect(Math.abs(pool.pos[i * 3] - 10)).toBeLessThan(0.11);
        expect(Math.abs(pool.pos[i * 3 + 2] + 4)).toBeLessThan(0.11);
      }
    }
  });

  it('a very long frame does not throw the puffs away', () => {
    const pool = createDustPool(16, createRng(4));
    pool.emit(0, 0, 0, 1);
    pool.update(5);
    for (let i = 0; i < 16; i++) {
      expect(Number.isFinite(pool.pos[i * 3])).toBe(true);
      expect(Math.abs(pool.pos[i * 3])).toBeLessThan(5);
    }
  });

  it('fewer puffs at medium than at high for the same footfall', () => {
    const med = createDustPool(40, createRng(5), 'medium');
    const high = createDustPool(40, createRng(5), 'high');
    expect(med.emit(0, 0, 0, 1)).toBeLessThan(high.emit(0, 0, 0, 1));
  });
});

describe('dust object', () => {
  it('low: no dust, no geometry, nothing to draw', () => {
    const dust = createDust({ quality: 'low' });
    expect(dust.capacity).toBe(0);
    expect(dust.object.children).toHaveLength(0);
    dust.emit(0, 0, 0, 1);
    dust.update(DT);
    expect(dust.alive).toBe(0);
    expect(dust.object.children).toHaveLength(0);
    dust.dispose();
  });

  it('medium and high: one Points object, invisible while idle, visible while puffs live', () => {
    for (const quality of ['medium', 'high']) {
      const dust = createDust({ quality });
      expect(dust.object.children).toHaveLength(1);
      const points = dust.object.children[0];
      expect(points.isPoints).toBe(true);
      expect(points.visible).toBe(false);
      expect(dust.capacity).toBeGreaterThan(0);
      dust.emit(1, 0, 2, 0.8);
      expect(points.visible).toBe(true);
      dust.update(DT);
      expect(dust.alive).toBeGreaterThan(0);
      for (let t = 0; t < 3; t += DT) dust.update(DT);
      expect(dust.alive).toBe(0);
      expect(points.visible).toBe(false);
      dust.dispose();
    }
    expect(createDust({ quality: 'high' }).capacity).toBeGreaterThan(
      createDust({ quality: 'medium' }).capacity,
    );
  });

  it('uses no textures and a fixed-size buffer', () => {
    const dust = createDust({ quality: 'high' });
    const points = dust.object.children[0];
    expect(points.material.isShaderMaterial).toBe(true);
    const n = dust.capacity;
    for (let i = 0; i < 500; i++) dust.emit(0, 0, 0, 1);
    dust.update(DT);
    expect(points.geometry.attributes.position.count).toBe(n);
    expect(points.geometry.attributes.aSize.count).toBe(n);
    expect(points.geometry.attributes.aAlpha.count).toBe(n);
    expect(Object.values(points.material.uniforms).some((u) => u.value?.isTexture)).toBe(false);
  });

  it('releases its GPU objects on a quality change and on dispose (release hook)', () => {
    const released = [];
    const dust = createDust({ quality: 'medium', release: (o) => released.push(o) });
    const points = dust.object.children[0];
    dust.setQuality('high');
    expect(released).toContain(points.geometry);
    expect(released).toContain(points.material);
    expect(dust.object.children[0]).not.toBe(points);
    dust.setQuality('low');
    expect(dust.object.children).toHaveLength(0);
    dust.setQuality('medium');
    expect(dust.object.children).toHaveLength(1);
    const last = dust.object.children[0];
    released.length = 0;
    dust.dispose();
    // the final dispose goes through the hook, too (objects of a lost context are not freed)
    expect(released).toContain(last.geometry);
    expect(released).toContain(last.material);
    expect(dust.object.parent).toBe(null);
    expect(last.parent).toBe(null);
  });

  it('keeps size attenuation per camera (onBeforeRender)', () => {
    const dust = createDust({ quality: 'medium' });
    const points = dust.object.children[0];
    const renderer = { getDrawingBufferSize: (v) => v.set(800, 600) };
    const camera = new THREE.PerspectiveCamera(60, 4 / 3, 0.1, 100);
    points.onBeforeRender(renderer, null, camera);
    expect(points.material.uniforms.uPixelScale.value).toBeCloseTo(
      600 / (2 * Math.tan(Math.PI / 6)),
      3,
    );
  });
});
