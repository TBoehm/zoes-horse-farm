// Decoration of the arena and the paddock: bunting along the fence (fluttering in the wind), flower
// pots at the gate and the props in the paddock (field shelter, water trough, hay rack). Two
// meshes, hidden on the levels without `decor`.
import * as THREE from 'three';
import { createGeometryBuilder, boxOnGround, jitterVertices, createRng } from './textures.js';
import { planBunting, planPots, planPaddockProps } from './decor-plan.js';
import { patchBunting } from './plant-shaders.js';

// Direction the wind blows towards, for the side the pennants lean to
const WIND_DIRECTION = new THREE.Vector2(1, 0.4).normalize();
const STRING_COLOR = 0xe8e4d8;
const STRING_WIDTH = 0.016;
const STRING_PIECES = 4; // straight pieces per string

const rgb = (hex) => {
  const c = new THREE.Color(hex);
  return [c.r, c.g, c.b];
};

/**
 * Geometry of the whole bunting: first the strings, then the pennants (every second one first).
 * Returns { geometry, stringVertices, pennantCount, coreCount }; drawing the first
 * stringVertices + 3 * n vertices gives the strings and the first n pennants.
 */
export function buildBuntingGeometry(plan = planBunting()) {
  const positions = [];
  const colors = [];
  const flutter = [];
  const push = (point, color, push3) => {
    positions.push(point[0], point[1], point[2]);
    colors.push(color[0], color[1], color[2]);
    flutter.push(push3[0], push3[1], push3[2]);
  };
  const still = [0, 0, 0];

  // strings: thin vertical ribbons along the sagging curve
  const stringColor = rgb(STRING_COLOR);
  for (const { a, b, sag } of plan.strings) {
    const at = (s) => [a.x + (b.x - a.x) * s, a.y - sag * 4 * s * (1 - s), a.z + (b.z - a.z) * s];
    for (let i = 0; i < STRING_PIECES; i += 1) {
      const p0 = at(i / STRING_PIECES);
      const p1 = at((i + 1) / STRING_PIECES);
      const q0 = [p0[0], p0[1] - STRING_WIDTH, p0[2]];
      const q1 = [p1[0], p1[1] - STRING_WIDTH, p1[2]];
      for (const tri of [
        [p0, q0, p1],
        [p1, q0, q1],
      ]) {
        for (const point of tri) push(point, stringColor, still);
      }
    }
  }
  const stringVertices = positions.length / 3;

  // pennants: a triangle hanging from the string, flutters across its plane
  for (const p of plan.pennants) {
    const hw = p.width / 2;
    const normal = new THREE.Vector2(-p.tz, p.tx);
    const sign = normal.dot(WIND_DIRECTION) >= 0 ? 1 : -1;
    const wave = [normal.x * sign, 0, normal.y * sign];
    const color = rgb(p.color);
    const left = [p.x - p.tx * hw, p.y, p.z - p.tz * hw];
    const right = [p.x + p.tx * hw, p.y, p.z + p.tz * hw];
    const tip = [p.x, p.y - p.length, p.z];
    push(left, color, still);
    push(right, color, still);
    push(tip, color, wave);
  }

  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
  geometry.setAttribute('aFlutter', new THREE.Float32BufferAttribute(flutter, 3));
  geometry.computeVertexNormals();
  geometry.computeBoundingSphere();
  return {
    geometry,
    stringVertices,
    pennantCount: plan.pennants.length,
    coreCount: plan.coreCount,
  };
}

/** Number of vertices to draw for a share of the pennants (0 = nothing, below 1 = every second). */
export function buntingDrawCount(bunting, share) {
  if (!(share > 0)) return 0;
  const pennants = share >= 1 ? bunting.pennantCount : bunting.coreCount;
  return bunting.stringVertices + pennants * 3;
}

// --- Static decoration ---------------------------------------------------------------------------

/** Adds parts in the local frame of a prop, moved to its place and turned about Y. */
function propAdder(builder, { x, z, rotation }) {
  const frame = new THREE.Matrix4().compose(
    new THREE.Vector3(x, 0, z),
    new THREE.Quaternion().setFromAxisAngle(THREE.Object3D.DEFAULT_UP, rotation),
    new THREE.Vector3(1, 1, 1),
  );
  const local = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const e = new THREE.Euler();
  return (geometry, color, t = {}, options) => {
    e.set(t.rx || 0, t.ry || 0, t.rz || 0);
    q.setFromEuler(e);
    local.compose(
      new THREE.Vector3(t.x || 0, t.y || 0, t.z || 0),
      q,
      new THREE.Vector3(t.sx ?? 1, t.sy ?? 1, t.sz ?? 1),
    );
    return builder.add(
      geometry,
      color,
      new THREE.Matrix4().multiplyMatrices(frame, local),
      options,
    );
  };
}

function addPot(builder, pot, rng) {
  const add = propAdder(builder, { x: pot.x, z: pot.z, rotation: rng() * Math.PI * 2 });
  const s = pot.scale;
  const body = new THREE.CylinderGeometry(0.2 * s, 0.14 * s, 0.34 * s, 9, 1, true);
  body.translate(0, 0.17 * s, 0);
  add(body, 0xb8643b, {}, { jitter: 0.1, rng });
  add(new THREE.CylinderGeometry(0.215 * s, 0.215 * s, 0.05 * s, 9), 0xa5562f, { y: 0.335 * s });
  add(new THREE.CylinderGeometry(0.18 * s, 0.18 * s, 0.02 * s, 9), 0x3b2c20, { y: 0.36 * s });
  // foliage and blossoms
  for (const [fx, fy, fz, r] of [
    [0, 0.5, 0, 0.19],
    [0.12, 0.44, 0.06, 0.13],
    [-0.1, 0.45, -0.08, 0.14],
  ]) {
    const ico = new THREE.IcosahedronGeometry(r * s, 0);
    jitterVertices(ico, r * s * 0.3, rng);
    add(ico, 0x477a33, { x: fx * s, y: fy * s, z: fz * s }, { jitter: 0.15, rng });
  }
  for (const [fx, fy, fz] of [
    [0.05, 0.66, 0.08],
    [-0.1, 0.6, 0.05],
    [0.12, 0.58, -0.07],
    [-0.04, 0.63, -0.12],
    [0.0, 0.7, 0.0],
  ]) {
    add(new THREE.OctahedronGeometry(0.055 * s), pot.flower, { x: fx * s, y: fy * s, z: fz * s });
  }
}

/** Open field shelter: back and side walls, posts, a sloping roof. Opening towards local +Z. */
function addShelter(builder, spot) {
  const add = propAdder(builder, spot);
  const wood = 0x8a6a4a;
  const dark = 0x6e5238;
  const w = 4.4;
  const d = 2.8;
  const back = 2.5;
  const front = 2.9;
  // back wall and low side walls of boards
  add(boxOnGround(w, 2.2, 0.1), wood, { z: -d / 2 });
  for (const sx of [-1, 1]) {
    add(boxOnGround(0.1, 1.9, d), wood, { x: (sx * w) / 2 });
  }
  // posts at the front corners and the middle
  for (const sx of [-1, 0, 1]) {
    add(boxOnGround(0.16, front - 0.2, 0.16), dark, { x: (sx * (w - 0.2)) / 2, z: d / 2 - 0.1 });
  }
  // roof: slopes down to the back
  const slope = Math.atan2(front - back, d);
  const length = Math.hypot(d + 0.8, front - back);
  add(new THREE.BoxGeometry(w + 0.7, 0.12, length), 0x5a3a2c, {
    y: (front + back) / 2 + 0.05,
    z: 0,
    rx: -slope,
  });
  // straw on the floor
  add(boxOnGround(w - 0.5, 0.06, d - 0.5), 0xc9ad66, { z: -0.05 });
}

function addTrough(builder, spot) {
  const add = propAdder(builder, spot);
  add(boxOnGround(2.0, 0.6, 0.6), 0x8a9099, {});
  const water = new THREE.PlaneGeometry(1.85, 0.45);
  water.rotateX(-Math.PI / 2);
  add(water, 0x3d6f8a, { y: 0.55 });
}

function addRack(builder, spot) {
  const add = propAdder(builder, spot);
  const wood = 0x7a5c44;
  for (const sx of [-1, 1]) {
    add(boxOnGround(0.12, 1.1, 0.12), wood, { x: sx * 0.8, z: 0.3 });
    add(boxOnGround(0.12, 1.1, 0.12), wood, { x: sx * 0.8, z: -0.3 });
  }
  add(boxOnGround(1.8, 0.1, 0.7), wood, { y: 0.7 });
  add(boxOnGround(1.6, 0.4, 0.55), 0xc9ad66, { y: 0.78 });
}

/**
 * materialFactory(kind, params) → { standard, lambert }; wind from createWind().
 * Returns { group, meshes, setDetail(share) }; `share` is the share of the bunting that
 * is drawn (0 hides the whole decoration, below 1 draws every second pennant).
 */
export function createArenaDecor({ materialFactory, wind, seed = 11 }) {
  const rng = createRng(seed + 29);
  const group = new THREE.Group();
  group.name = 'arena-decor';

  const buntingMats = materialFactory('bunting', {
    vertexColors: true,
    roughness: 0.85,
    side: THREE.DoubleSide,
  });
  patchBunting(buntingMats.standard, wind);
  patchBunting(buntingMats.lambert, wind);
  const bunting = buildBuntingGeometry();
  const buntingMesh = new THREE.Mesh(bunting.geometry, buntingMats.standard);
  buntingMesh.name = 'bunting';
  buntingMesh.frustumCulled = false; // spans the whole arena; one draw call either way

  const builder = createGeometryBuilder();
  for (const pot of planPots()) addPot(builder, pot, rng);
  const props = planPaddockProps();
  addShelter(builder, props.shelter);
  addTrough(builder, props.trough);
  addRack(builder, props.rack);
  const decorMats = materialFactory('decor', { vertexColors: true, roughness: 0.85 });
  const decorMesh = new THREE.Mesh(builder.build(), decorMats.standard);
  decorMesh.name = 'decor-props';

  for (const mesh of [buntingMesh, decorMesh]) {
    mesh.visible = false;
    group.add(mesh);
  }

  return {
    group,
    meshes: [
      { mesh: buntingMesh, mats: buntingMats, shadow: 'none' },
      { mesh: decorMesh, mats: decorMats, shadow: 'all' },
    ],
    setDetail(share) {
      const count = buntingDrawCount(bunting, share);
      buntingMesh.visible = count > 0;
      bunting.geometry.setDrawRange(0, count);
      decorMesh.visible = share > 0;
    },
  };
}
