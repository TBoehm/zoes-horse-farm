import { describe, expect, it } from 'vitest';
import * as THREE from 'three';
import { createGpuTracker } from './gpu-tracker.js';

function setup() {
  const scene = new THREE.Scene();
  const renderer = { shadowMap: { enabled: false } };
  const tracker = createGpuTracker({ scene, renderer });
  return { scene, renderer, tracker };
}

describe('createGpuTracker', () => {
  it('counts programs by key: equal materials share one, different ones do not', () => {
    const { scene, tracker } = setup();
    const a = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshStandardMaterial());
    const b = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshStandardMaterial());
    const c = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshLambertMaterial());
    scene.add(a, b, c);
    tracker.compile(scene);
    expect(tracker.snapshot()).toEqual({ programs: 2, materials: 3, geometries: 3, instanced: 0 });
  });

  it('keeps the old program of a material that switches variant until it is disposed', () => {
    const { scene, renderer, tracker } = setup();
    const material = new THREE.MeshStandardMaterial();
    scene.add(new THREE.Mesh(new THREE.BoxGeometry(), material));
    tracker.compile(scene);
    renderer.shadowMap.enabled = true;
    scene.children[0].receiveShadow = true;
    tracker.compile(scene);
    expect(tracker.snapshot().programs).toBe(2); // the leak of a switch without dispose
    material.dispose();
    expect(tracker.snapshot().programs).toBe(0);
    tracker.compile(scene);
    expect(tracker.snapshot().programs).toBe(1); // built again on use
  });

  it('frees geometries and instanced meshes by dispose and tracks the peak', () => {
    const { scene, tracker } = setup();
    const geometry = new THREE.BoxGeometry();
    const mesh = new THREE.InstancedMesh(geometry, new THREE.MeshStandardMaterial(), 4);
    scene.add(mesh);
    tracker.compile(scene);
    expect(tracker.holds(geometry)).toBe(true);
    expect(tracker.holds(mesh)).toBe(true);
    geometry.dispose();
    mesh.dispose();
    expect(tracker.holds(geometry)).toBe(false);
    expect(tracker.snapshot().instanced).toBe(0);
    expect(tracker.peak.geometries).toBe(1);
    tracker.resetPeak();
    expect(tracker.peak.geometries).toBe(0);
  });

  it('only draws what the root yields (hidden objects are not uploaded)', () => {
    const { scene, tracker } = setup();
    const hidden = new THREE.Mesh(new THREE.BoxGeometry(), new THREE.MeshStandardMaterial());
    hidden.visible = false;
    scene.add(hidden);
    tracker.compile({ traverse: (fn) => scene.traverseVisible(fn) });
    expect(tracker.snapshot().programs).toBe(0);
  });
});
