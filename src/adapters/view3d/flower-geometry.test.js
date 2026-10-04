import { describe, it, expect } from 'vitest';
import * as THREE from 'three';
import { buildFlowerGeometry, buildPlanterGeometry } from './flower-geometry.js';

const triangles = (g) => g.attributes.position.count / 3;
const petalVertices = (g) => [...g.attributes.petal.array].filter((v) => v === 1).length;

describe('buildFlowerGeometry', () => {
  const g = buildFlowerGeometry();

  it('is a cheap flower: stem, leaf, five petals and a heart', () => {
    expect(triangles(g)).toBe(11);
    expect(petalVertices(g)).toBe(15);
  });

  it('has the attributes the blossom shader needs, one value per vertex', () => {
    const n = g.attributes.position.count;
    expect(g.attributes.color.count).toBe(n);
    expect(g.attributes.petal.count).toBe(n);
    expect(g.attributes.normal.count).toBe(n);
    expect(g.index).toBeNull();
  });

  it('stands on the ground, a little taller than the grass tufts', () => {
    g.computeBoundingBox();
    expect(g.boundingBox.min.y).toBeCloseTo(0, 6);
    expect(g.boundingBox.max.y).toBeGreaterThan(0.55);
    expect(g.boundingBox.max.y).toBeLessThan(0.75);
    expect(g.boundingBox.max.x - g.boundingBox.min.x).toBeLessThan(0.3);
  });

  it('lights like the ground (normals up)', () => {
    for (let i = 0; i < g.attributes.normal.count; i += 1) {
      expect(g.attributes.normal.getY(i)).toBe(1);
    }
  });

  it('keeps petals bright and stem and leaves green', () => {
    const color = g.attributes.color;
    for (let i = 0; i < color.count; i += 1) {
      const r = color.getX(i);
      const gr = color.getY(i);
      const bl = color.getZ(i);
      if (g.attributes.petal.getX(i) === 1) {
        expect(Math.min(r, gr, bl)).toBeGreaterThan(0.7);
      }
    }
    // the first vertices belong to the stem: greener than red
    expect(color.getY(0)).toBeGreaterThan(color.getX(0));
  });
});

describe('buildPlanterGeometry', () => {
  const g = buildPlanterGeometry();

  it('is a flower box with a row of blossoms, cheap enough for every jump', () => {
    expect(triangles(g)).toBeGreaterThan(60);
    expect(triangles(g)).toBeLessThan(190);
    expect(petalVertices(g)).toBeGreaterThan(0);
    expect(petalVertices(g)).toBeLessThan(g.attributes.position.count);
  });

  it('is a low box, long along the jump, standing on the ground', () => {
    g.computeBoundingBox();
    const size = g.boundingBox.getSize(new THREE.Vector3());
    expect(g.boundingBox.min.y).toBeCloseTo(0, 6);
    expect(size.z).toBeGreaterThan(0.9);
    expect(size.z).toBeLessThan(1.15);
    expect(size.x).toBeLessThan(0.4);
    expect(size.y).toBeGreaterThan(0.25);
    expect(size.y).toBeLessThan(0.45);
  });

  it('keeps some blossoms in their own colour for a mixed planting', () => {
    const colors = g.attributes.color;
    const own = new Set();
    for (let i = 0; i < colors.count; i += 1) {
      if (g.attributes.petal.getX(i) === 0 && colors.getX(i) > 0.8 && colors.getZ(i) < 0.9) {
        own.add(`${colors.getX(i).toFixed(2)}`);
      }
    }
    expect(own.size).toBeGreaterThan(0);
  });
});
