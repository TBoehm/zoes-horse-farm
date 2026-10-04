// Animals of the meadow: flocks of birds circling in the sky and butterflies over the flower
// patches. Both are small instanced meshes; the wings beat in the vertex shader, the paths come
// from flight-paths.js and only the instance matrices are written per frame (a few dozen).
import * as THREE from 'three';
import { createRng } from './textures.js';
import { planFlocks, birdPose, planButterflies, butterflyPose } from './flight-paths.js';
import { patchWings } from './plant-shaders.js';

// Technical values (look, no game play)
const FLOCKS = 4;
const BIRDS_PER_FLOCK = 6;
const BUTTERFLIES = 14;

const BIRD_COLORS = Object.freeze({ body: 0x2b2927, wing: 0x3b3835, tip: 0x1f1d1c });
const BUTTERFLY_COLORS = Object.freeze([0xf6f2e4, 0xf3d54a, 0xee8a2e, 0x8fb8f0, 0xf08fb0]);

/** Pushes one triangle with a colour per vertex into the arrays. */
function pushTri(out, a, b, c, ca, cb, cc) {
  out.positions.push(...a, ...b, ...c);
  out.colors.push(...ca, ...cb, ...cc);
}

function finish(out) {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(out.positions, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(out.colors, 3));
  g.computeVertexNormals();
  g.computeBoundingSphere();
  return g;
}

const rgb = (hex) => {
  const c = new THREE.Color(hex);
  return [c.r, c.g, c.b];
};

/**
 * A bird seen from the side and above (7 triangles): slim body, short tail and two swept wings
 * along X, about 1.1 m across. Flies towards +Z.
 */
export function buildBirdGeometry() {
  const out = { positions: [], colors: [] };
  const body = rgb(BIRD_COLORS.body);
  const wing = rgb(BIRD_COLORS.wing);
  const tip = rgb(BIRD_COLORS.tip);
  // body: a flat diamond with a ridge (4 triangles)
  const nose = [0, 0, 0.32];
  const tail = [0, 0, -0.26];
  const left = [-0.05, 0, 0.02];
  const right = [0.05, 0, 0.02];
  const top = [0, 0.05, 0.05];
  pushTri(out, nose, left, top, body, body, body);
  pushTri(out, nose, top, right, body, body, body);
  pushTri(out, tail, top, left, body, body, body);
  pushTri(out, tail, right, top, body, body, body);
  // wings: swept back triangles, tips darker
  pushTri(out, [-0.04, 0.01, 0.16], [-0.56, 0.02, -0.16], [-0.04, 0.01, -0.12], wing, tip, wing);
  pushTri(out, [0.04, 0.01, 0.16], [0.04, 0.01, -0.12], [0.56, 0.02, -0.16], wing, wing, tip);
  // tail fan
  pushTri(out, [-0.07, 0, -0.2], [0.07, 0, -0.2], [0, 0, -0.4], wing, wing, tip);
  return finish(out);
}

/** A butterfly (5 triangles): two pairs of wings along X and a thin body, 0.14 m across. */
export function buildButterflyGeometry() {
  const out = { positions: [], colors: [] };
  const white = [1, 1, 1];
  const dark = rgb(0x2a2420);
  for (const s of [-1, 1]) {
    // forward wing and hind wing
    pushTri(
      out,
      [0, 0, 0.01],
      [s * 0.075, 0.005, 0.05],
      [s * 0.055, 0, -0.005],
      white,
      white,
      white,
    );
    pushTri(out, [0, 0, 0], [s * 0.055, 0, -0.005], [s * 0.04, 0, -0.06], white, white, white);
  }
  pushTri(out, [-0.008, 0, 0.05], [0.008, 0, 0.05], [0, 0, -0.07], dark, dark, dark);
  return finish(out);
}

const yawAxis = THREE.Object3D.DEFAULT_UP;
const zAxis = new THREE.Vector3(0, 0, 1);

/**
 * materialFactory(kind, params) → { standard, lambert }; wind from createWind(); anchors: the
 * patches butterflies hover over ([{ x, z, radius }]).
 * Returns { group, meshes, setDetail({ birds, butterflies }), update(dt) } with the
 * shares of the animals that are drawn (0 hides the mesh).
 */
export function createWildlife({ materialFactory, wind, anchors = [], seed = 11 }) {
  const rng = createRng(seed + 13);
  const group = new THREE.Group();
  group.name = 'wildlife';

  const flocks = planFlocks(rng, FLOCKS, BIRDS_PER_FLOCK);
  const butterflies = planButterflies(rng, anchors, BUTTERFLIES);

  const birdMats = materialFactory('birds', {
    vertexColors: true,
    roughness: 0.9,
    side: THREE.DoubleSide,
  });
  patchWings(birdMats.standard, wind, { rate: 14, amplitude: 0.6, glide: 0.85 });
  patchWings(birdMats.lambert, wind, { rate: 14, amplitude: 0.6, glide: 0.85 });
  const butterflyMats = materialFactory('butterflies', {
    vertexColors: true,
    roughness: 0.8,
    side: THREE.DoubleSide,
  });
  patchWings(butterflyMats.standard, wind, { rate: 26, amplitude: 1.7 });
  patchWings(butterflyMats.lambert, wind, { rate: 26, amplitude: 1.7 });

  const birdGeometry = buildBirdGeometry();
  const butterflyGeometry = buildButterflyGeometry();
  const birdTotal = FLOCKS * BIRDS_PER_FLOCK;
  const birdMesh = new THREE.InstancedMesh(birdGeometry, birdMats.standard, birdTotal);
  birdMesh.name = 'birds';
  const butterflyMesh = new THREE.InstancedMesh(
    butterflyGeometry,
    butterflyMats.standard,
    Math.max(1, butterflies.length),
  );
  butterflyMesh.name = 'butterflies';
  const c = new THREE.Color();
  butterflies.forEach((_, i) => {
    c.set(BUTTERFLY_COLORS[i % BUTTERFLY_COLORS.length]).multiplyScalar(0.92 + rng() * 0.16);
    butterflyMesh.setColorAt(i, c);
  });
  if (butterflyMesh.instanceColor) butterflyMesh.instanceColor.needsUpdate = true;
  for (const mesh of [birdMesh, butterflyMesh]) {
    mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    mesh.frustumCulled = false; // the instances move; their matrices change every frame
    mesh.count = 0;
    mesh.visible = false;
    group.add(mesh);
  }

  const pose = {};
  const m = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const qRoll = new THREE.Quaternion();
  const p = new THREE.Vector3();
  const s = new THREE.Vector3();
  let time = 0;

  function writeBirds() {
    for (let i = 0; i < birdMesh.count; i += 1) {
      // bird k of flock f is instance k * FLOCKS + f: a smaller share thins every flock
      const flock = flocks[i % FLOCKS];
      birdPose(flock, Math.floor(i / FLOCKS), time, pose);
      q.setFromAxisAngle(yawAxis, pose.heading);
      qRoll.setFromAxisAngle(zAxis, pose.roll);
      q.multiply(qRoll);
      p.set(pose.x, pose.y, pose.z);
      s.setScalar(1.15 + ((i * 0.618) % 1) * 0.4);
      m.compose(p, q, s);
      birdMesh.setMatrixAt(i, m);
    }
    birdMesh.instanceMatrix.needsUpdate = true;
  }

  function writeButterflies() {
    for (let i = 0; i < butterflyMesh.count; i += 1) {
      butterflyPose(butterflies[i], time, pose);
      q.setFromAxisAngle(yawAxis, pose.heading);
      p.set(pose.x, pose.y, pose.z);
      s.setScalar(0.9 + ((i * 0.37) % 1) * 0.3);
      m.compose(p, q, s);
      butterflyMesh.setMatrixAt(i, m);
    }
    butterflyMesh.instanceMatrix.needsUpdate = true;
  }

  const meshes = [
    { mesh: birdMesh, mats: birdMats, shadow: 'none' },
    { mesh: butterflyMesh, mats: butterflyMats, shadow: 'none' },
  ];

  return {
    group,
    meshes,
    setDetail({ birds = 0, butterflies: butterflyShare = 0 } = {}) {
      const clamp = (v) => Math.min(1, Math.max(0, v));
      birdMesh.count = Math.round(birdTotal * clamp(birds));
      birdMesh.visible = birdMesh.count > 0;
      butterflyMesh.count = Math.round(butterflies.length * clamp(butterflyShare));
      butterflyMesh.visible = butterflyMesh.count > 0;
      writeBirds();
      writeButterflies();
    },
    update(dt) {
      time += dt;
      if (birdMesh.visible) writeBirds();
      if (butterflyMesh.visible) writeButterflies();
    },
  };
}
