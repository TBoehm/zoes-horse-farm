import { describe, it, expect } from 'vitest';
import * as THREE from 'three';
import { sceneStats } from './scene-stats.js';

const material = new THREE.MeshBasicMaterial();
const box = () => new THREE.BoxGeometry(1, 1, 1); // 12 triangles, indexed

describe('sceneStats', () => {
  it('counts one draw call and the triangles per mesh', () => {
    const scene = new THREE.Scene();
    scene.add(new THREE.Mesh(box(), material), new THREE.Mesh(box(), material));
    expect(sceneStats(scene)).toMatchObject({ calls: 2, triangles: 24, instances: 0 });
  });

  it('counts an instanced mesh once, times its instances', () => {
    const scene = new THREE.Scene();
    const mesh = new THREE.InstancedMesh(box(), material, 50);
    mesh.count = 30;
    scene.add(mesh);
    expect(sceneStats(scene)).toMatchObject({ calls: 1, triangles: 360, instances: 30 });
  });

  it('skips hidden meshes, hidden parents and empty instanced meshes', () => {
    const scene = new THREE.Scene();
    const hidden = new THREE.Mesh(box(), material);
    hidden.visible = false;
    const group = new THREE.Group();
    group.visible = false;
    group.add(new THREE.Mesh(box(), material));
    const empty = new THREE.InstancedMesh(box(), material, 5);
    empty.count = 0;
    scene.add(hidden, group, empty, new THREE.Mesh(box(), material));
    expect(sceneStats(scene)).toMatchObject({ calls: 1, triangles: 12 });
  });

  it('follows the draw range of non-indexed geometry', () => {
    const scene = new THREE.Scene();
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(new Float32Array(90), 3));
    geometry.setDrawRange(0, 30);
    scene.add(new THREE.Mesh(geometry, material));
    expect(sceneStats(scene).triangles).toBe(10);
    geometry.setDrawRange(0, 0);
    expect(sceneStats(scene)).toMatchObject({ calls: 0, triangles: 0 });
  });

  it('counts a call per material group', () => {
    const scene = new THREE.Scene();
    const geometry = box();
    geometry.clearGroups();
    geometry.addGroup(0, 18, 0);
    geometry.addGroup(18, 18, 1);
    scene.add(new THREE.Mesh(geometry, [material, material]));
    expect(sceneStats(scene).calls).toBe(2);
  });

  it('adds the shadow pass of the meshes that cast shadows', () => {
    const scene = new THREE.Scene();
    const caster = new THREE.Mesh(box(), material);
    caster.castShadow = true;
    scene.add(caster, new THREE.Mesh(box(), material));
    expect(sceneStats(scene).shadowPass).toEqual({ calls: 1, triangles: 12 });
  });

  it('counts a sprite as a quad', () => {
    const scene = new THREE.Scene();
    scene.add(new THREE.Sprite(new THREE.SpriteMaterial()));
    expect(sceneStats(scene)).toMatchObject({ calls: 1, triangles: 2 });
  });
});
