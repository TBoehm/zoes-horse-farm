// Umgebung der Reitanlage: Wiese mit Hügeln, Bäume, Büsche, Gras-Büschel (instanziert),
// Stall, Richterhäuschen und Kleinkram. Alles prozedural.
import * as THREE from 'three';
import {
  SITE,
  HILL_START,
  terrainHeight,
  scatter,
  instanceCount,
  isBlocked,
} from './world-layout.js';
import {
  createRng,
  createGrassTexture,
  createGeometryBuilder,
  boxOnGround,
  jitterVertices,
  shadeByHeight,
} from './textures.js';

// --- Gelände -----------------------------------------------------------------------------------

function buildTerrain(rng) {
  const radii = [0, 24, 42, 60, 78, 96, 116, 140, 170, 205, 250, 300, 360, 430, 520];
  const seg = 72;
  const positions = [];
  const colors = [];
  const uvs = [];
  const indices = [];
  const c = new THREE.Color();
  const base = new THREE.Color(0xffffff);
  const haze = new THREE.Color(0xa9bcb6);
  const dark = new THREE.Color(0xc9d6b8);
  const vertex = (x, z) => {
    const y = (Math.hypot(x, z) < HILL_START ? -0.03 : 0) + terrainHeight(x, z);
    positions.push(x, y, z);
    uvs.push(x / 6, -z / 6);
    const r = Math.hypot(x, z);
    const n = 0.5 + 0.5 * Math.sin(x * 0.09 + rng() * 0.6) * Math.cos(z * 0.07);
    c.copy(base).lerp(dark, n * 0.6);
    c.lerp(haze, THREE.MathUtils.smoothstep(r, 160, 480) * 0.85);
    colors.push(c.r, c.g, c.b);
  };
  vertex(0, 0);
  for (let i = 1; i < radii.length; i += 1) {
    for (let k = 0; k < seg; k += 1) {
      const a = (k / seg) * Math.PI * 2;
      vertex(Math.cos(a) * radii[i], Math.sin(a) * radii[i]);
    }
  }
  for (let k = 0; k < seg; k += 1) indices.push(0, 1 + ((k + 1) % seg), 1 + k);
  for (let i = 1; i < radii.length - 1; i += 1) {
    const r0 = 1 + (i - 1) * seg;
    const r1 = 1 + i * seg;
    for (let k = 0; k < seg; k += 1) {
      const k1 = (k + 1) % seg;
      indices.push(r0 + k, r0 + k1, r1 + k, r0 + k1, r1 + k1, r1 + k);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
  g.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
  g.setIndex(indices);
  g.computeVertexNormals();
  return g;
}

// --- Pflanzen-Geometrien ------------------------------------------------------------------------

function deciduousGeometry(rng, detail = 1) {
  const b = createGeometryBuilder();
  const trunk = new THREE.CylinderGeometry(0.15, 0.26, 3.4, detail ? 7 : 5, 1, true);
  trunk.translate(0, 1.7, 0);
  b.add(trunk, 0x5a4532);
  const branch = new THREE.CylinderGeometry(0.05, 0.1, 1.6, 5);
  branch.translate(0, 0.8, 0);
  if (detail) {
    b.add(branch.clone(), 0x5a4532, { y: 2.6, rz: 0.8 });
    b.add(branch, 0x5a4532, { y: 2.8, rz: -0.7, ry: 1.2 });
  }
  const blobs = [
    [0, 4.7, 0, 2.4, 0x4d7a2f],
    [1.2, 3.9, 0.5, 1.8, 0x56832f],
    [-1.0, 4.0, -0.6, 1.9, 0x47722b],
    [0.1, 5.7, -0.2, 1.6, 0x5c8a34],
  ];
  for (const [x, y, z, r, col] of detail ? blobs : blobs.slice(0, 3)) {
    const ico = new THREE.IcosahedronGeometry(r, detail);
    jitterVertices(ico, r * 0.35, rng);
    const g = b.add(ico, col, { x, y, z }, { jitter: 0.12, rng });
    shadeByHeight(g, 2.4, 7, 0.5, 1.12);
  }
  return b.build();
}

function coniferGeometry(rng, detail = 1) {
  const b = createGeometryBuilder();
  const trunk = new THREE.CylinderGeometry(0.1, 0.2, 1.8, detail ? 6 : 4, 1, true);
  trunk.translate(0, 0.9, 0);
  b.add(trunk, 0x4e3b2a);
  const tiers = detail
    ? [
        [2.0, 3.2, 1.0],
        [1.55, 2.8, 2.7],
        [1.1, 2.4, 4.3],
        [0.65, 1.9, 5.8],
      ]
    : [
        [1.9, 3.6, 1.0],
        [1.3, 3.2, 3.2],
        [0.75, 2.6, 5.2],
      ];
  for (const [r, h, y] of tiers) {
    const cone = new THREE.ConeGeometry(r, h, detail ? 8 : 6, 1, true);
    jitterVertices(cone, 0.25, rng);
    cone.translate(0, y + h / 2, 0);
    const g = b.add(cone, 0x2e5230, {}, { jitter: 0.1, rng });
    shadeByHeight(g, 1, 7.7, 0.55, 1.15);
  }
  return b.build();
}

function bushGeometry(rng, detail = 1) {
  const b = createGeometryBuilder();
  for (const [x, y, z, r] of [
    [0, 0.55, 0, 0.85],
    [0.55, 0.42, 0.2, 0.62],
  ]) {
    const ico = new THREE.IcosahedronGeometry(r, detail);
    jitterVertices(ico, r * 0.4, rng);
    const g = b.add(ico, 0x4a742d, { x, y, z }, { jitter: 0.15, rng });
    shadeByHeight(g, 0, 1.4, 0.5, 1.1);
  }
  return b.build();
}

function tuftGeometry(rng) {
  const positions = [];
  const colors = [];
  const base = new THREE.Color(0x40602a);
  const tip = new THREE.Color(0x9bb760);
  const blades = 5;
  for (let i = 0; i < blades; i += 1) {
    const a = (i / blades) * Math.PI * 2 + rng() * 0.6;
    const h = 0.22 + rng() * 0.25;
    const lean = 0.06 + rng() * 0.1;
    const w = 0.035;
    const cx = Math.cos(a) * 0.04;
    const cz = Math.sin(a) * 0.04;
    const px = -Math.sin(a) * w;
    const pz = Math.cos(a) * w;
    positions.push(cx - px, 0, cz - pz, cx + px, 0, cz + pz);
    positions.push(cx + Math.cos(a) * lean, h, cz + Math.sin(a) * lean);
    colors.push(base.r, base.g, base.b, base.r, base.g, base.b, tip.r, tip.g, tip.b);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  g.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
  // Normalen nach oben: Büschel wirken wie der Boden beleuchtet
  const normals = new Float32Array(positions.length);
  for (let i = 1; i < normals.length; i += 3) normals[i] = 1;
  g.setAttribute('normal', new THREE.BufferAttribute(normals, 3));
  g.setAttribute(
    'uv',
    new THREE.Float32BufferAttribute(new Float32Array((positions.length / 3) * 2), 2),
  );
  return g;
}

/** Wind für Gras-Büschel: Spitzen schwingen, abhängig von der Instanzposition. */
function patchWind(material, timeUniform) {
  material.onBeforeCompile = (shader) => {
    shader.uniforms.windTime = timeUniform;
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nuniform float windTime;')
      .replace(
        '#include <begin_vertex>',
        `#include <begin_vertex>
        #ifdef USE_INSTANCING
          vec2 ip = vec2(instanceMatrix[3].x, instanceMatrix[3].z);
          float sway = sin(windTime * 1.7 + ip.x * 0.35 + ip.y * 0.21) * 0.6
                     + sin(windTime * 3.1 + ip.y * 0.5) * 0.25;
          transformed.x += sway * position.y * 0.18;
          transformed.z += sway * position.y * 0.08;
        #endif`,
      );
  };
  material.customProgramCacheKey = () => 'wind-v1';
}

// --- Gebäude -----------------------------------------------------------------------------------

function addStable(b) {
  const { x: cx, z: cz, depth, length } = SITE.stable;
  const wallH = 3.6;
  const plinth = 0.5;
  const top = plinth + wallH;
  const rise = 2.9;
  const front = cx + depth / 2; // Seite zum Reitplatz (+x)
  b.add(boxOnGround(depth + 0.2, plinth, length + 0.2), 0x8f8b84, { x: cx, z: cz });
  const wall = b.add(boxOnGround(depth, wallH, length), 0x8d5b37, { x: cx, y: plinth, z: cz });
  shadeByHeight(wall, plinth, top, 0.8, 1.05);
  // Bretter-Leisten an der Vorderseite
  for (let z = cz - length / 2 + 0.3; z < cz + length / 2; z += 0.75) {
    b.add(boxOnGround(0.05, wallH, 0.07), 0x7a4c2c, { x: front + 0.02, y: plinth, z });
  }
  // Giebel
  const tri = new THREE.Shape([
    new THREE.Vector2(-depth / 2, 0),
    new THREE.Vector2(depth / 2, 0),
    new THREE.Vector2(0, rise),
  ]);
  for (const s of [-1, 1]) {
    const gable = new THREE.ExtrudeGeometry(tri, { depth: 0.2, bevelEnabled: false });
    gable.translate(0, 0, -0.1);
    b.add(gable, 0x86542f, { x: cx, y: top, z: cz + s * (length / 2 - 0.1), ry: Math.PI / 2 });
  }
  // Dach
  const half = depth / 2 + 0.7;
  const slab = Math.hypot(half, rise + 0.35);
  const ang = Math.atan2(rise + 0.35, half);
  for (const s of [-1, 1]) {
    b.add(new THREE.BoxGeometry(slab, 0.18, length + 1.2), 0x6b3427, {
      x: cx + (s * half) / 2,
      y: top + rise / 2 - 0.05,
      z: cz,
      rz: -s * ang,
    });
  }
  b.add(new THREE.BoxGeometry(0.35, 0.22, length + 1.3), 0x4f2a20, {
    x: cx,
    y: top + rise + 0.1,
    z: cz,
  });
  // Dachreiter
  b.add(boxOnGround(1.4, 1.1, 1.4), 0xf0ebe0, { x: cx, y: top + rise - 0.1, z: cz });
  const cap = new THREE.ConeGeometry(1.15, 0.9, 4);
  cap.rotateY(Math.PI / 4);
  b.add(cap, 0x4f2a20, { x: cx, y: top + rise + 1.45, z: cz });
  b.add(new THREE.CylinderGeometry(0.02, 0.02, 0.9, 4), 0x333333, {
    x: cx,
    y: top + rise + 2.3,
    z: cz,
  });
  b.add(new THREE.BoxGeometry(0.03, 0.12, 0.45), 0x333333, { x: cx, y: top + rise + 2.55, z: cz });
  // Boxentüren: untere Hälfte geschlossen (grün), oben offen (dunkel), weiße Rahmen
  const doors = 8;
  for (let i = 0; i < doors; i += 1) {
    const z = cz - length / 2 + (i + 0.5) * (length / doors);
    b.add(boxOnGround(0.08, 1.25, 1.2), 0x2f5b3a, { x: front + 0.04, y: plinth, z });
    b.add(boxOnGround(0.06, 1.0, 1.2), 0x17120e, { x: front + 0.02, y: plinth + 1.25, z });
    b.add(boxOnGround(0.1, 0.08, 1.4), 0xece6d8, { x: front + 0.05, y: plinth + 2.25, z });
    for (const dz of [-0.66, 0.66]) {
      b.add(boxOnGround(0.1, 2.33, 0.08), 0xece6d8, { x: front + 0.05, y: plinth, z: z + dz });
    }
    const diag = Math.hypot(1.15, 1.15);
    for (const s of [-1, 1]) {
      b.add(new THREE.BoxGeometry(0.1, 0.06, diag), 0xece6d8, {
        x: front + 0.09,
        y: plinth + 0.62,
        z,
        rx: s * Math.atan2(1.1, 1.15),
      });
    }
  }
  // Scheunentor an der Giebelseite zum Weg
  const gz = cz + length / 2 + 0.06;
  b.add(boxOnGround(3.2, 3.1, 0.1), 0x7e2f22, { x: cx, y: plinth, z: gz });
  for (const s of [-1, 1]) {
    b.add(new THREE.BoxGeometry(0.1, 4.3, 0.06), 0xece6d8, {
      x: cx,
      y: plinth + 1.55,
      z: gz + 0.06,
      rz: s * Math.atan2(3.0, 3.1),
    });
  }
  b.add(boxOnGround(3.4, 0.12, 0.14), 0xece6d8, { x: cx, y: plinth + 3.1, z: gz });
}

function addHut(b) {
  const { x: cx, z: cz } = SITE.hut;
  const s = 3.2;
  const floor = 1.3;
  for (const dx of [-1, 1]) {
    for (const dz of [-1, 1]) {
      b.add(boxOnGround(0.16, floor, 0.16), 0x6e5440, {
        x: cx + dx * (s / 2 - 0.15),
        z: cz + dz * (s / 2 - 0.15),
      });
    }
  }
  b.add(boxOnGround(s + 0.3, 0.18, s + 0.3), 0x7a5c44, { x: cx, y: floor, z: cz });
  const wallY = floor + 0.18;
  const h = 2.2;
  const white = 0xf1ece2;
  // Rück- und Seitenwände
  b.add(boxOnGround(0.12, h, s), white, { x: cx + s / 2, y: wallY, z: cz });
  b.add(boxOnGround(s, h, 0.12), white, { x: cx, y: wallY, z: cz - s / 2 });
  b.add(boxOnGround(s, h, 0.12), white, { x: cx, y: wallY, z: cz + s / 2 });
  // Front zum Platz: Brüstung, großes Fenster, Sturz
  b.add(boxOnGround(0.12, 0.95, s), white, { x: cx - s / 2, y: wallY, z: cz });
  b.add(boxOnGround(0.06, 0.95, s - 0.3), 0x2f3d48, { x: cx - s / 2, y: wallY + 0.95, z: cz });
  b.add(boxOnGround(0.14, 0.3, s), white, { x: cx - s / 2, y: wallY + 1.9, z: cz });
  for (const dz of [-1, 0, 1]) {
    b.add(boxOnGround(0.14, 0.95, 0.1), white, {
      x: cx - s / 2,
      y: wallY + 0.95,
      z: cz + dz * (s / 2 - 0.05),
    });
  }
  // Pultdach
  b.add(new THREE.BoxGeometry(s + 0.9, 0.14, s + 0.9), 0x2d4a3a, {
    x: cx,
    y: wallY + h + 0.2,
    z: cz,
    rz: -0.14,
  });
  // Treppe
  for (let i = 0; i < 5; i += 1) {
    b.add(boxOnGround(0.9, 0.06, 0.3), 0x7a5c44, {
      x: cx + 0.6,
      y: (i + 1) * 0.26,
      z: cz + s / 2 + 0.25 + (4 - i) * 0.3,
    });
  }
  b.add(boxOnGround(0.06, 1.8, 0.06), 0x6e5440, { x: cx + 1.05, z: cz + s / 2 + 1.6 });
  // Blumenkästen
  for (const dz of [-0.9, 0.9]) {
    b.add(boxOnGround(0.25, 0.2, 0.9), 0x7a5c44, {
      x: cx - s / 2 - 0.2,
      y: wallY + 0.75,
      z: cz + dz,
    });
    const fl = new THREE.IcosahedronGeometry(0.22, 0);
    b.add(fl, 0xd6455d, { x: cx - s / 2 - 0.2, y: wallY + 1.05, z: cz + dz, sz: 2 });
  }
}

function addProps(b, rng) {
  // Zuschauerbänke an der Langseite
  for (const z of [5, 12, 19]) {
    const x = 23.6;
    b.add(boxOnGround(0.42, 0.06, 2.1), 0x8b6a4c, { x, y: 0.44, z });
    b.add(boxOnGround(0.06, 0.38, 2.1), 0x8b6a4c, { x: x + 0.22, y: 0.62, z, rz: 0.15 });
    for (const dz of [-0.85, 0.85]) b.add(boxOnGround(0.4, 0.44, 0.08), 0x555555, { x, z: z + dz });
  }
  // Rundballen am Stall
  const bale = (x, y, z, ry) => {
    const g = new THREE.CylinderGeometry(0.72, 0.72, 1.2, 14);
    g.rotateZ(Math.PI / 2);
    b.add(g, 0xc9ad66, { x, y: y + 0.72, z, ry }, { jitter: 0.08, rng });
  };
  bale(-37.2, 0, 36.6, 0.2);
  bale(-35.6, 0, 36.9, -0.1);
  bale(-36.4, 1.3, 36.7, 0.05);
  bale(-34.2, 0, 35.2, 1.4);
  // Tränke am Weg
  b.add(boxOnGround(1.8, 0.6, 0.6), 0x8a9099, { x: -33, z: 26.4 });
  const water = new THREE.PlaneGeometry(1.65, 0.45);
  water.rotateX(-Math.PI / 2);
  b.add(water, 0x3d6f8a, { x: -33, y: 0.55, z: 26.4 });
  // Schubkarre
  b.add(new THREE.BoxGeometry(0.7, 0.35, 1.0), 0x2e6b9c, { x: -40.2, y: 0.55, z: 3.5, rx: 0.15 });
  b.add(new THREE.CylinderGeometry(0.2, 0.2, 0.08, 10), 0x222222, {
    x: -40.2,
    y: 0.2,
    z: 4.1,
    rz: Math.PI / 2,
  });
}

const isBlockedTuft = (x, z) => isBlocked(x, z, -1.3);

// --- Platzierung -------------------------------------------------------------------------------

function makeInstanced(geometry, material, items, rng, { scale = [0.85, 1.3], tint = 0.12 } = {}) {
  const mesh = new THREE.InstancedMesh(geometry, material, Math.max(1, items.length));
  const m = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const s = new THREE.Vector3();
  const p = new THREE.Vector3();
  const c = new THREE.Color();
  items.forEach(([x, z, sc], i) => {
    const k = sc ?? scale[0] + rng() * (scale[1] - scale[0]);
    const squash = 0.9 + rng() * 0.2;
    p.set(x, terrainHeight(x, z) - 0.05, z);
    q.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, rng() * Math.PI * 2);
    s.set(k * squash, k, k * (2 - squash));
    m.compose(p, q, s);
    mesh.setMatrixAt(i, m);
    c.setRGB(
      1 - tint / 2 + rng() * tint,
      1 - tint / 2 + rng() * tint,
      1 - tint / 2 + rng() * tint * 0.6,
    );
    mesh.setColorAt(i, c);
  });
  mesh.count = items.length;
  mesh.instanceMatrix.needsUpdate = true;
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  mesh.computeBoundingSphere();
  mesh.userData.total = items.length;
  return mesh;
}

/**
 * Umgebung. materialFactory(kind, params) → { standard, lambert }.
 * setDensity(envDensity, grassTufts) stellt die Instanzzahlen je Stufe ein.
 */
export function createEnvironment({ materialFactory, seed = 11 }) {
  const rng = createRng(seed);
  const group = new THREE.Group();
  group.name = 'environment';
  const windTime = { value: 0 };

  // Gelände
  const grassMap = createGrassTexture({ size: 512 });
  const groundMats = materialFactory('grass', {
    map: grassMap,
    vertexColors: true,
    roughness: 1,
    metalness: 0,
  });
  const terrain = new THREE.Mesh(buildTerrain(rng), groundMats.standard);
  terrain.name = 'terrain';
  terrain.receiveShadow = true;
  group.add(terrain);

  // Bäume
  const plantMats = materialFactory('plants', { vertexColors: true, roughness: 0.92 });
  const nearDeciduous = [
    [-30, -30, 1.25],
    [-28, 47, 1.35],
    [31, 43, 1.2],
    [33, -6, 1.4],
    [37, -43, 1.3],
    [-34, -52, 1.15],
    [13, 53, 1.3],
    [-11, -53, 1.25],
    [40, 22, 1.1],
  ];
  const alley = [];
  for (let z = -62; z <= 62; z += 12.4) alley.push([47, z, 1.05 + rng() * 0.2]);
  const behindStable = [];
  for (let i = 0; i < 9; i += 1) behindStable.push([-60 - rng() * 12, -4 + i * 6 + rng() * 3]);
  const meadow = scatter(rng, 34, 45, 125, 4);
  const deciduous = [...nearDeciduous, ...alley, ...behindStable, ...meadow];
  const deciduousMesh = makeInstanced(deciduousGeometry(rng), plantMats.standard, deciduous, rng, {
    scale: [0.9, 1.5],
  });
  deciduousMesh.userData.lods = { high: deciduousMesh.geometry, low: deciduousGeometry(rng, 0) };
  deciduousMesh.name = 'trees-deciduous';
  deciduousMesh.userData.priority = nearDeciduous.length;

  const coniferNear = [
    [-62, 2, 1.2],
    [-64, 30, 1.3],
    [-57, 48, 1.1],
    [-25, -60, 1.2],
    [55, -40, 1.25],
    [28, 64, 1.15],
  ];
  const coniferMesh = makeInstanced(
    coniferGeometry(rng),
    plantMats.standard,
    [...coniferNear, ...scatter(rng, 30, 60, 130, 4)],
    rng,
    { scale: [0.9, 1.5] },
  );
  coniferMesh.name = 'trees-conifer';
  const coniferLow = coniferGeometry(rng, 0);
  coniferMesh.userData.lods = { high: coniferMesh.geometry, low: coniferLow };
  coniferMesh.userData.priority = coniferNear.length;

  // Waldsaum auf den Hügeln (ohne Schatten)
  const forestItems = [];
  for (let i = 0; forestItems.length < 520 && i < 6000; i += 1) {
    const a = rng() * Math.PI * 2;
    const r = 135 + rng() * 130;
    const x = Math.cos(a) * r;
    const z = Math.sin(a) * r;
    // in Bändern gruppiert, damit Waldstücke entstehen
    if (Math.sin(a * 7 + 1.3) + Math.sin(a * 3) * 0.6 < -0.2) continue;
    forestItems.push([x, z, 1.6 + rng() * 1.4]);
  }
  const forestMesh = makeInstanced(coniferLow, plantMats.standard, forestItems, rng);
  forestMesh.name = 'forest';
  forestMesh.userData.priority = 60;

  // Büsche
  const bushItems = [];
  for (let z = SITE.stable.z - 14; z <= SITE.stable.z + 14; z += 4.5) {
    bushItems.push([SITE.stable.x - 7, z, 0.9 + rng() * 0.4]);
  }
  for (let x = -16; x <= 16; x += 3.2)
    bushItems.push([x + rng(), -40.5 - rng(), 0.9 + rng() * 0.5]);
  bushItems.push(
    [SITE.hut.x + 2.5, SITE.hut.z - 2.8, 1.1],
    [SITE.hut.x + 2.2, SITE.hut.z + 3.4, 0.9],
  );
  const bushPriority = bushItems.length;
  for (const [x, z] of [...nearDeciduous, ...alley])
    bushItems.push([x + 1.5 + rng() * 2, z + rng() * 2 - 1]);
  bushItems.push(...scatter(rng, 40, 30, 110, 2));
  const bushMesh = makeInstanced(bushGeometry(rng), plantMats.standard, bushItems, rng, {
    scale: [0.7, 1.4],
  });
  bushMesh.name = 'bushes';
  bushMesh.userData.lods = { high: bushMesh.geometry, low: bushGeometry(rng, 0) };
  bushMesh.userData.priority = Math.min(bushPriority, 8);

  // Gras-Büschel (nur Hoch), dichter nahe der Bande
  const tuftMats = materialFactory('tufts', {
    vertexColors: true,
    roughness: 1,
    side: THREE.DoubleSide,
  });
  patchWind(tuftMats.standard, windTime);
  patchWind(tuftMats.lambert, windTime);
  const tuftItems = [];
  for (let i = 0; tuftItems.length < 5200 && i < 60000; i += 1) {
    const near = rng() < 0.55;
    const x = near ? (rng() - 0.5) * 2 * 32 : (rng() - 0.5) * 2 * 75;
    const z = near ? (rng() - 0.5) * 2 * 46 : (rng() - 0.5) * 2 * 90;
    if (isBlockedTuft(x, z)) continue;
    tuftItems.push([x, z, 0.5 + rng() * 0.6]);
  }
  const tuftMesh = makeInstanced(tuftGeometry(rng), tuftMats.standard, tuftItems, rng, {
    tint: 0.25,
  });
  tuftMesh.name = 'grass-tufts';
  tuftMesh.userData.priority = 0;

  // Gebäude
  const buildingMats = materialFactory('buildings', { vertexColors: true, roughness: 0.85 });
  const b = createGeometryBuilder();
  addStable(b);
  addHut(b);
  addProps(b, rng);
  const buildings = new THREE.Mesh(b.build(), buildingMats.standard);
  buildings.name = 'buildings';
  buildings.castShadow = true;
  buildings.receiveShadow = true;

  for (const m of [deciduousMesh, coniferMesh]) {
    m.castShadow = true;
    m.receiveShadow = true;
  }
  forestMesh.receiveShadow = false;
  group.add(deciduousMesh, coniferMesh, forestMesh, bushMesh, tuftMesh, buildings);

  const scalable = [deciduousMesh, coniferMesh, forestMesh, bushMesh];

  return {
    group,
    windTime,
    textures: [grassMap],
    meshes: [
      { mesh: terrain, mats: groundMats, shadow: 'receive' },
      { mesh: deciduousMesh, mats: plantMats, shadow: 'all' },
      { mesh: coniferMesh, mats: plantMats, shadow: 'all' },
      { mesh: forestMesh, mats: plantMats, shadow: 'none' },
      { mesh: bushMesh, mats: plantMats, shadow: 'none' },
      { mesh: tuftMesh, mats: tuftMats, shadow: 'none' },
      { mesh: buildings, mats: buildingMats, shadow: 'all' },
    ],
    /**
     * density 0..1 skaliert Bäume/Büsche oberhalb der Pflicht-Instanzen; tufts 0..1;
     * detail 'high' | 'low' wählt die Geometrie-Feinheit.
     */
    setDensity(density, tufts, detail = 'high') {
      for (const m of scalable) {
        if (m.userData.lods) m.geometry = m.userData.lods[detail] || m.userData.lods.high;
        m.count = instanceCount(m.userData.total, m.userData.priority, density);
      }
      tuftMesh.count = Math.round(tuftMesh.userData.total * tufts);
      tuftMesh.visible = tuftMesh.count > 0;
    },
    update(dt) {
      windTime.value += dt;
    },
  };
}

export { SITE, terrainHeight };
