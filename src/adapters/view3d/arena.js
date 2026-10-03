// Arena: sand footing with track, wooden fence (instanced), gate, start/finish lines.
import * as THREE from 'three';
import { ARENA } from '../../domain/sim/tuning.js';
import { FENCE, GATE, linePosts, planFence, planLines } from './world-layout.js';
import {
  createSandTextures,
  createGeometryBuilder,
  boxOnGround,
  createLabelAtlas,
  fitText,
  SYSTEM_FONT,
} from './textures.js';

/** Sand shader patch: worn track along the fence and large-scale variation. */
function patchSandMaterial(material) {
  material.onBeforeCompile = (shader) => {
    shader.uniforms.arenaHalf = { value: new THREE.Vector2(ARENA.width / 2, ARENA.length / 2) };
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec2 vGroundPos;')
      .replace(
        '#include <begin_vertex>',
        '#include <begin_vertex>\nvGroundPos = (modelMatrix * vec4(transformed, 1.0)).xz;',
      );
    shader.fragmentShader = shader.fragmentShader
      .replace(
        '#include <common>',
        `#include <common>
        varying vec2 vGroundPos;
        uniform vec2 arenaHalf;
        float gHash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
        float gNoise(vec2 p) {
          vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
          return mix(mix(gHash(i), gHash(i + vec2(1.0, 0.0)), f.x),
                     mix(gHash(i + vec2(0.0, 1.0)), gHash(i + vec2(1.0, 1.0)), f.x), f.y);
        }`,
      )
      .replace(
        '#include <map_fragment>',
        `#include <map_fragment>
        {
          vec2 gp = vGroundPos;
          float n = gNoise(gp * 0.13) * 0.6 + gNoise(gp * 0.55) * 0.4;
          diffuseColor.rgb *= 0.9 + 0.2 * n;
          // track: band along a rounded rectangle about 1.7 m inside the fence
          vec2 b = arenaHalf - vec2(1.7);
          float R = 5.0;
          vec2 q = abs(gp) - (b - vec2(R));
          float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - R;
          float wob = gNoise(gp * 0.8) * 0.5;
          float band = 1.0 - smoothstep(0.35, 1.25, abs(d) + wob * 0.4);
          diffuseColor.rgb *= mix(vec3(1.0), vec3(0.78, 0.72, 0.66), band);
          // lighter sand pushed up against the fence
          float edge = smoothstep(1.0, 0.0, min(arenaHalf.x - abs(gp.x), arenaHalf.y - abs(gp.y)));
          diffuseColor.rgb *= 1.0 + edge * 0.08;
        }`,
      );
  };
  material.customProgramCacheKey = () => 'sand-v1';
  return material;
}

/** Ground: arena + path to the stable in one geometry (same material). */
function buildSandGeometry(path) {
  const builder = createGeometryBuilder();
  const w = ARENA.width + 2 * (FENCE.offset + 0.5);
  const l = ARENA.length + 2 * (FENCE.offset + 0.5);
  const plane = new THREE.PlaneGeometry(w, l, 4, 6);
  plane.rotateX(-Math.PI / 2);
  setWorldUv(plane, 4);
  builder.add(plane, 0xffffff);
  if (path) {
    for (const seg of path) {
      const g = new THREE.PlaneGeometry(seg.w, seg.l, 1, Math.max(1, Math.round(seg.l / 6)));
      g.rotateX(-Math.PI / 2);
      g.rotateY(seg.ry || 0);
      g.translate(seg.x, 0.004, seg.z);
      setWorldUv(g, 4);
      builder.add(g, seg.color ?? 0xd9cfc0);
    }
  }
  return builder.build();
}

/** UV = world coordinates / tile size (textures repeat evenly). */
function setWorldUv(geometry, tile) {
  const pos = geometry.attributes.position;
  const uv = geometry.attributes.uv;
  for (let i = 0; i < pos.count; i += 1) uv.setXY(i, pos.getX(i) / tile, -pos.getZ(i) / tile);
  uv.needsUpdate = true;
}

const FENCE_COLORS = { arena: 0xf4f1ea, wood: 0x8a6a4a, gate: 0xe9e4d8 };

/** Builds post and board InstancedMeshes for all fences. */
function buildFence(plan, materials) {
  const unit = new THREE.BoxGeometry(1, 1, 1);
  unit.translate(0, 0.5, 0);
  const posts = [];
  const boards = [];
  for (const p of plan.posts) {
    const h = p.style === 'arena' ? FENCE.height + 0.05 : 1.0;
    posts.push({ x: p.x, z: p.z, ry: 0, sx: FENCE.post, sy: h, sz: FENCE.post, color: p.style });
  }
  for (const s of plan.segments) {
    const rails =
      s.style === 'arena'
        ? [
            { y: 0.06, h: 0.28 }, // kick board
            { y: 0.62, h: 0.13 },
            { y: 1.05, h: 0.13 },
          ]
        : [
            { y: 0.45, h: 0.1 },
            { y: 0.85, h: 0.1 },
          ];
    for (const r of rails) {
      boards.push({
        x: s.x,
        y: r.y,
        z: s.z,
        ry: s.ang,
        sx: FENCE.board,
        sy: r.h,
        sz: s.len + FENCE.post,
        color: s.style,
      });
    }
  }
  // gate: two strong posts, two leaves with boards and a brace
  const g = plan.gate;
  if (g) {
    for (const z of [g.z0, g.z1]) {
      posts.push({ x: g.x, z, ry: 0, sx: 0.18, sy: 1.45, sz: 0.18, color: 'gate' });
    }
    const leaf = (g.z1 - g.z0 - 0.3) / 2;
    for (const [zc, sign] of [
      [g.z0 + 0.12 + leaf / 2, 1],
      [g.z1 - 0.12 - leaf / 2, -1],
    ]) {
      for (const y of [0.15, 0.55, 0.95]) {
        boards.push({ x: g.x, y, z: zc, ry: 0, sx: 0.05, sy: 0.12, sz: leaf, color: 'gate' });
      }
      // diagonal brace in the leaf
      boards.push({
        x: g.x,
        y: 0.52,
        z: zc,
        rx: sign * Math.atan2(0.8, leaf),
        sx: 0.045,
        sy: 0.1,
        sz: Math.hypot(0.8, leaf) - 0.1,
        color: 'gate',
      });
      for (const zz of [zc - (sign * leaf) / 2, zc + (sign * leaf) / 2]) {
        boards.push({ x: g.x, y: 0.12, z: zz, ry: 0, sx: 0.06, sy: 1.0, sz: 0.06, color: 'gate' });
      }
    }
  }

  const make = (list, name) => {
    const mesh = new THREE.InstancedMesh(unit, materials.standard, list.length);
    mesh.name = name;
    const m = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const e = new THREE.Euler();
    const s = new THREE.Vector3();
    const p = new THREE.Vector3();
    const c = new THREE.Color();
    list.forEach((it, i) => {
      e.set(it.rx || 0, it.ry || 0, 0);
      q.setFromEuler(e);
      s.set(it.sx, it.sy, it.sz);
      p.set(it.x, it.y || 0, it.z);
      m.compose(p, q, s);
      mesh.setMatrixAt(i, m);
      c.set(FENCE_COLORS[it.color]);
      const f = 0.94 + ((i * 7919) % 13) / 100;
      c.multiplyScalar(f);
      mesh.setColorAt(i, c);
    });
    mesh.instanceMatrix.needsUpdate = true;
    mesh.instanceColor.needsUpdate = true;
    mesh.computeBoundingSphere();
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    return mesh;
  };
  return { posts: make(posts, 'fence-posts'), boards: make(boards, 'fence-boards') };
}

/**
 * Arena. materialFactory(kind, params) returns a material pair { standard, lambert }
 * (the world handles quality switches).
 */
export function createArena({ materialFactory, path, pathFence }) {
  const group = new THREE.Group();
  group.name = 'arena';
  const sand = createSandTextures({ size: 512 });
  const sandMats = materialFactory('sand', {
    color: 0xffffff,
    map: sand.map,
    normalMap: sand.normalMap,
    normalScale: new THREE.Vector2(0.9, 0.9),
    roughness: 0.97,
    metalness: 0,
    vertexColors: true,
  });
  patchSandMaterial(sandMats.standard);
  patchSandMaterial(sandMats.lambert);
  const ground = new THREE.Mesh(buildSandGeometry(path), sandMats.standard);
  ground.name = 'arena-ground';
  ground.receiveShadow = true;
  group.add(ground);

  const fenceMats = materialFactory('fence', { color: 0xffffff, roughness: 0.72 });
  const plan = planFence({ pathFence });
  const fence = buildFence(plan, fenceMats);
  group.add(fence.posts, fence.boards);

  return {
    group,
    ground,
    fence,
    plan,
    textures: [sand.map, sand.normalMap],
    meshes: [
      { mesh: ground, mats: sandMats, shadow: 'receive' },
      { mesh: fence.posts, mats: fenceMats, shadow: 'all' },
      { mesh: fence.boards, mats: fenceMats, shadow: 'all' },
    ],
  };
}

// ---------------------------------------------------------------------------------------------
// Start and finish lines

function drawSign(kind, text) {
  return (ctx, w, h) => {
    const start = kind === 'start';
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = start ? '#1f8f46' : '#c62828';
    roundRect(ctx, 8, 8, w - 16, h - 16, 18);
    ctx.fill();
    if (!start) {
      // finish flag: checkered stripe on top
      const sq = (h - 16) / 6;
      for (let i = 0; i * sq < w - 16; i += 1) {
        ctx.fillStyle = i % 2 ? '#111' : '#fff';
        ctx.fillRect(8 + i * sq, 8, sq, sq);
        ctx.fillStyle = i % 2 ? '#fff' : '#111';
        ctx.fillRect(8 + i * sq, 8 + sq, sq, sq);
      }
    }
    ctx.fillStyle = '#ffffff';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const size = fitText(ctx, text, w - 40, 800, h * 0.5);
    ctx.font = `800 ${size}px ${SYSTEM_FONT}`;
    ctx.fillText(text, w / 2, start ? h / 2 : h * 0.62);
  };
}

export function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/**
 * Start/finish lines: ground line, posts with sign. set(null | { start:{a,b}, finish:{a,b},
 * labels:{start, finish} }), setFinishMarked(bool), update(dt).
 */
export function createCourseLines({ materialFactory }) {
  const group = new THREE.Group();
  group.name = 'course-lines';
  const staticMats = materialFactory('lines', { vertexColors: true, roughness: 0.8 });
  let staticMesh = null;
  let signMesh = null;
  let signMaterial = null;
  let atlas = null;
  const glowMaterial = new THREE.MeshBasicMaterial({
    color: 0xffd21f,
    transparent: true,
    opacity: 0.6,
    depthWrite: false,
    toneMapped: false,
    polygonOffset: true,
    polygonOffsetFactor: -2,
    polygonOffsetUnits: -2,
  });
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), glowMaterial);
  glow.rotation.x = -Math.PI / 2;
  glow.visible = false;
  glow.renderOrder = 2;
  group.add(glow);
  let finishMarked = false;
  let time = 0;
  let finishLine = null;

  function clear() {
    if (staticMesh) {
      group.remove(staticMesh);
      staticMesh.geometry.dispose();
      staticMesh = null;
    }
    if (signMesh) {
      group.remove(signMesh);
      signMesh.geometry.dispose();
      signMaterial.dispose();
      atlas.texture.dispose();
      signMesh = null;
    }
    finishLine = null;
  }

  function set(lines) {
    clear();
    glow.visible = false;
    if (!lines) return;
    const entries = planLines(lines);
    finishLine = entries.find((e) => e.finish)?.seg ?? null;

    atlas = createLabelAtlas(
      entries.map((e) => ({ key: e.kind, draw: drawSign(e.kind, e.text) })),
      { cellW: 256, cellH: 128 },
    );
    const builder = createGeometryBuilder();
    const signs = createGeometryBuilder();
    for (const e of entries) {
      const { a, cx, cz, length, angle: ang } = e.seg;
      // chalk line on the ground
      const line = new THREE.PlaneGeometry(0.14, length);
      line.rotateX(-Math.PI / 2);
      builder.add(line, 0xffffff, { x: cx, y: 0.012, z: cz, ry: ang });
      const color = e.kind === 'start' ? 0x1f8f46 : 0xc62828;
      // posts at both ends with flags: red on the right, white on the left in riding direction
      linePosts(e.seg).forEach((p) => {
        builder.add(boxOnGround(0.07, 1.7, 0.07), 0xf2f2f2, { x: p.x, z: p.z });
        builder.add(new THREE.BoxGeometry(0.02, 0.28, 0.38), p.red ? 0xd32f2f : 0xffffff, {
          x: p.x,
          y: 1.52,
          z: p.z,
          ry: ang + Math.PI / 2,
        });
      });
      // sign on the first post, readable from both sides
      const r = atlas.rects.get(e.kind);
      const mid = a;
      const sign = makeSignQuad(1.1, 0.55, r);
      const m = new THREE.Matrix4().compose(
        new THREE.Vector3(mid.x, 2.0, mid.z),
        new THREE.Quaternion().setFromEuler(new THREE.Euler(0, ang + Math.PI / 2, 0)),
        new THREE.Vector3(1, 1, 1),
      );
      signs.addPainted(sign, m);
      builder.add(new THREE.BoxGeometry(1.16, 0.61, 0.03), color, {
        x: mid.x,
        y: 2.0,
        z: mid.z,
        ry: ang + Math.PI / 2,
      });
    }
    staticMesh = new THREE.Mesh(builder.build(), staticMats.standard);
    staticMesh.castShadow = true;
    staticMesh.receiveShadow = true;
    group.add(staticMesh);
    signMaterial = new THREE.MeshBasicMaterial({ map: atlas.texture, toneMapped: false });
    signMesh = new THREE.Mesh(signs.build(), signMaterial);
    group.add(signMesh);
    meshes[0].mesh = staticMesh;

    if (finishLine) {
      glow.position.set(finishLine.cx, 0.016, finishLine.cz);
      glow.rotation.set(-Math.PI / 2, 0, finishLine.angle);
      glow.scale.set(1.4, finishLine.length + 0.6, 1);
      glow.visible = finishMarked;
    }
  }

  const meshes = [{ mesh: null, mats: staticMats, shadow: 'obstacles' }];

  return {
    group,
    meshes,
    set,
    setFinishMarked(on) {
      finishMarked = Boolean(on);
      glow.visible = finishMarked && Boolean(finishLine);
    },
    update(dt) {
      time += dt;
      if (glow.visible) glowMaterial.opacity = 0.45 + 0.3 * (0.5 + 0.5 * Math.sin(time * 5));
    },
    dispose() {
      clear();
      glow.geometry.dispose();
      glowMaterial.dispose();
    },
  };
}

/** Two-sided sign quad (front and back both read correctly), atlas rect r. */
export function makeSignQuad(w, h, r, thickness = 0.02) {
  const front = new THREE.PlaneGeometry(w, h);
  const back = new THREE.PlaneGeometry(w, h);
  for (const g of [front, back]) {
    const uv = g.attributes.uv;
    for (let i = 0; i < uv.count; i += 1) {
      uv.setXY(i, r.u0 + uv.getX(i) * (r.u1 - r.u0), r.v0 + uv.getY(i) * (r.v1 - r.v0));
    }
  }
  front.translate(0, 0, thickness);
  back.rotateY(Math.PI);
  back.translate(0, 0, -thickness);
  const merged = createGeometryBuilder();
  merged.add(front, 0xffffff);
  merged.add(back, 0xffffff);
  return merged.build();
}

export { FENCE, GATE, planFence };
