import * as THREE from 'three';
import { describe, expect, it, vi } from 'vitest';
import { createRider } from './rider.js';

const LEVELS = ['low', 'medium', 'high'];

const meshOf = (rider) => rider.object.children.find((c) => c.isSkinnedMesh);
const trianglesOf = (rider) => meshOf(rider).geometry.index.count / 3;
const verticesOf = (rider) => meshOf(rider).geometry.attributes.position.count;

// the rider before SRT-011 had 1336 / 2518 / 4036 triangles (low / medium / high); the face, chin
// strap, ponytail bow and jacket details may cost this much more, no more (GPU memory estimate)
const TRIANGLE_BUDGET = { low: 1700, medium: 4300, high: 7400 };

describe('rider geometry', () => {
  for (const level of LEVELS) {
    it(`${level}: one skinned mesh (one draw call), within the triangle budget`, () => {
      const rider = createRider({ quality: level });
      const meshes = [];
      rider.object.traverse((o) => o.isMesh && meshes.push(o));
      expect(meshes).toHaveLength(1);
      expect(trianglesOf(rider)).toBeLessThanOrEqual(TRIANGLE_BUDGET[level]);
      rider.dispose();
    });

    it(`${level}: valid skinning, finite positions and unit normals`, () => {
      const rider = createRider({ quality: level });
      const geo = meshOf(rider).geometry;
      const boneCount = meshOf(rider).skeleton.bones.length;
      const { skinIndex, skinWeight, position, normal } = geo.attributes;
      // pole duplicates of the primitive spheres are not part of any triangle (no normal)
      const used = new Set(geo.index.array);
      for (let i = 0; i < position.count; i++) {
        let sum = 0;
        for (let k = 0; k < 4; k++) {
          sum += skinWeight.getComponent(i, k);
          expect(skinIndex.getComponent(i, k)).toBeLessThan(boneCount);
        }
        expect(sum).toBeCloseTo(1, 4);
        expect(Number.isFinite(position.getX(i) + position.getY(i) + position.getZ(i))).toBe(true);
        const len = Math.hypot(normal.getX(i), normal.getY(i), normal.getZ(i));
        if (used.has(i)) expect(len).toBeCloseTo(1, 3);
      }
      rider.dispose();
    });
  }

  it('more detail on higher levels, and low is the cheapest', () => {
    const counts = LEVELS.map((level) => {
      const rider = createRider({ quality: level });
      const n = trianglesOf(rider);
      rider.dispose();
      return n;
    });
    expect(counts[0]).toBeLessThan(counts[1]);
    expect(counts[1]).toBeLessThan(counts[2]);
  });

  it('the face sits on the front of the head, the ponytail behind it', () => {
    const rider = createRider({ quality: 'high' });
    const geo = meshOf(rider).geometry;
    const { position, skinIndex, skinWeight } = geo.attributes;
    const bones = meshOf(rider).skeleton.bones.map((b) => b.name);
    const ponyIdx = new Set(['pony1', 'pony2', 'pony3'].map((n) => bones.indexOf(n)));
    let frontFace = 0;
    let pony = 0;
    for (let i = 0; i < position.count; i++) {
      const main = skinIndex.getComponent(i, 0);
      if (skinWeight.getComponent(i, 0) > 0.5 && ponyIdx.has(main)) {
        pony++;
        expect(position.getZ(i)).toBeLessThan(-0.05); // behind the head
      }
      if (
        bones[main] === 'head' &&
        position.getZ(i) > 0.085 &&
        position.getY(i) > 0.7 &&
        position.getY(i) < 0.84
      ) {
        frontFace++;
      }
    }
    expect(pony).toBeGreaterThan(30);
    expect(frontFace).toBeGreaterThan(150);
    rider.dispose();
  });
});

describe('rider lifecycle', () => {
  it('hands the replaced geometry and material to release() on a quality change', () => {
    const release = vi.fn();
    const rider = createRider({ quality: 'low', release });
    const oldGeometry = meshOf(rider).geometry;
    const oldMaterial = meshOf(rider).material;
    const before = verticesOf(rider);
    rider.setQuality('high');
    expect(release).toHaveBeenCalledTimes(2);
    expect(release).toHaveBeenCalledWith(oldGeometry);
    expect(release).toHaveBeenCalledWith(oldMaterial);
    expect(verticesOf(rider)).toBeGreaterThan(before);
    rider.setQuality('high');
    expect(release).toHaveBeenCalledTimes(2); // same level: nothing is rebuilt
    rider.dispose();
  });

  it('dispose frees the geometry and the material', () => {
    const rider = createRider({ quality: 'medium' });
    const mesh = meshOf(rider);
    const geometry = vi.spyOn(mesh.geometry, 'dispose');
    const material = vi.spyOn(mesh.material, 'dispose');
    rider.dispose();
    expect(geometry).toHaveBeenCalled();
    expect(material).toHaveBeenCalled();
  });
});

describe('head look', () => {
  const ctx = (g) => ({
    weights: {
      halt: g === 'halt' ? 1 : 0,
      walk: 0,
      trot: 0,
      canter: g === 'canter' ? 1 : 0,
      back: 0,
    },
    phi: 0,
    jumpWeight: 0,
    jumpJ: 0,
    hopWeight: 0,
    stopWeight: 0,
    pitch: 0,
  });
  const headYaw = (rider) =>
    new THREE.Euler().setFromQuaternion(rider.bones.head.quaternion, 'XYZ').y;

  it('turns the head into the curve and back', () => {
    const rider = createRider({ quality: 'low' });
    for (let i = 0; i < 120; i++)
      rider.update(1 / 60, { gait: 'canter', turnRate: 0.9 }, ctx('canter'));
    const right = headYaw(rider);
    expect(right).toBeLessThan(-0.1);
    expect(right).toBeGreaterThan(-0.4); // clamped, the body keeps facing forward
    for (let i = 0; i < 120; i++)
      rider.update(1 / 60, { gait: 'canter', turnRate: -0.9 }, ctx('canter'));
    expect(headYaw(rider)).toBeGreaterThan(0.1);
    for (let i = 0; i < 240; i++)
      rider.update(1 / 60, { gait: 'canter', turnRate: 0 }, ctx('canter'));
    expect(Math.abs(headYaw(rider))).toBeLessThan(0.02);
    rider.dispose();
  });
});

describe('ponytail', () => {
  const swing = (rider) =>
    ['pony1', 'pony2', 'pony3'].reduce(
      (sum, n) => sum + Math.abs(rider.bones[n].rotation.x) + Math.abs(rider.bones[n].rotation.y),
      0,
    );

  function drive(rider, accel, seconds) {
    // moves the whole rider so that the head accelerates, like the horse speeding up
    for (let i = 0; i < Math.round(seconds * 60); i++) {
      const t = i / 60;
      rider.object.position.z = 0.5 * accel * t * t;
      rider.object.updateMatrixWorld(true);
      rider.lateUpdate(1 / 60);
    }
  }

  it('hangs still without motion', () => {
    const rider = createRider({ quality: 'low' });
    drive(rider, 0, 1);
    expect(swing(rider)).toBeLessThan(1e-3);
    rider.dispose();
  });

  it('streams back when the rider speeds up and settles when it stops', () => {
    const rider = createRider({ quality: 'low' });
    drive(rider, 3, 1.5);
    const moving = swing(rider);
    expect(moving).toBeGreaterThan(0.05);
    // stands still again (position stays), the ponytail comes to rest
    for (let i = 0; i < 300; i++) rider.lateUpdate(1 / 60);
    expect(swing(rider)).toBeLessThan(0.01);
    rider.dispose();
  });

  it('ignores a teleport (restart) instead of whipping', () => {
    const rider = createRider({ quality: 'low' });
    drive(rider, 0, 0.5);
    rider.object.position.set(40, 0, -30);
    rider.object.updateMatrixWorld(true);
    rider.lateUpdate(1 / 60);
    expect(swing(rider)).toBeLessThan(1e-3);
    rider.dispose();
  });

  it('survives a zero or negative time step', () => {
    const rider = createRider({ quality: 'low' });
    rider.lateUpdate(0);
    rider.lateUpdate(-1);
    expect(swing(rider)).toBe(0);
    rider.dispose();
  });
});
