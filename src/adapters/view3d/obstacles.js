// Obstacle meshes: stands, striped poles (instanced, visibly falling), fillers, direction flags,
// number boards and the highlight of the obstacle that is due next.
import * as THREE from 'three';
import { STAND_WIDTH } from '../../domain/sim/tuning.js';
import {
  createGeometryBuilder,
  boxOnGround,
  createLabelAtlas,
  createCanvas,
  fitText,
  SYSTEM_FONT,
  createRng,
} from './textures.js';
import { makeSignQuad } from './arena.js';
import { releaseNow } from './resilience.js';
import { buildPlanterGeometry, PLANTER_BOX_HEIGHT } from './flower-geometry.js';
import { createWind, patchBlossoms } from './plant-shaders.js';
import {
  POLE_RADIUS,
  POLE_GEOM_LENGTH,
  STAND_X,
  FALL_DURATION,
  RISE_DURATION,
  standHeight,
  standRows,
  polesOf,
  labelOf,
  highlightText,
  flagSides,
  easeOut,
  easeInOut,
  endProgressOf,
  fallPointInto,
  fallTarget,
} from './world-layout.js';

const STRIPES = 11; // odd: white ends
const MAX_POLES = 128;
const MAX_PLANTERS = 128; // flower boxes: two per stand row
const PLANTER_OFFSET = 0.12; // box centre outside the centre of the stand (m)

// obstacle colors (pole stripes, stand sections, plank)
const OBSTACLE_COLORS = [0xc62828, 0x1e56b8, 0x2e7d32, 0xef8f00, 0x6a3fa0, 0x00838f];
// blossom colors of the flower boxes: one per obstacle, so that its boxes match
const PLANTER_COLORS = [0xe9719f, 0xf2cf2e, 0xd8453b, 0x8a5bc8, 0xf08a3c, 0x4f7fe0];

/** Pole geometries (along X, centered): white and colored stripes separately. */
function buildPoleGeometries(radialSegments = 10) {
  const white = createGeometryBuilder();
  const colored = createGeometryBuilder();
  const seg = POLE_GEOM_LENGTH / STRIPES;
  for (let i = 0; i < STRIPES; i += 1) {
    const end = i === 0 || i === STRIPES - 1;
    const g = new THREE.CylinderGeometry(POLE_RADIUS, POLE_RADIUS, seg, radialSegments, 1, !end);
    g.rotateZ(Math.PI / 2);
    g.translate(-POLE_GEOM_LENGTH / 2 + seg * (i + 0.5), 0, 0);
    (i % 2 === 0 ? white : colored).add(g, 0xffffff);
  }
  return { white: white.build(), colored: colored.build() };
}

function localMatrix(element) {
  return new THREE.Matrix4().compose(
    new THREE.Vector3(element.x, 0, element.z),
    new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), element.rot || 0),
    new THREE.Vector3(1, 1, 1),
  );
}

/** Writes the static parts of an element into the builder (world space via matrix). */
function addElementStatic(builder, element, color, { flags, board }) {
  const M = localMatrix(element);
  const add = (geom, col, t) => {
    const g = builder.add(geom, col, t);
    g.applyMatrix4(M);
  };
  const H = standHeight(element);
  const W = STAND_WIDTH;
  const rails = polesOf(element);
  for (const z of standRows(element)) {
    for (const sx of [-1, 1]) {
      const x = sx * STAND_X;
      // post: white with colored sections
      const bands = [0, 0.3, 0.55, 0.8, 1.05, 1.3, H];
      for (let i = 0; i < bands.length - 1; i += 1) {
        const y0 = bands[i];
        const y1 = i === bands.length - 2 ? H : Math.min(bands[i + 1], H);
        if (y1 <= y0) continue;
        add(boxOnGround(W, y1 - y0, W), i % 2 === 1 ? color : 0xf5f5f2, { x, y: y0, z });
      }
      add(new THREE.BoxGeometry(W + 0.03, 0.04, W + 0.03), color, { x, y: H + 0.02, z });
      // feet (T-shape pointing outwards)
      add(boxOnGround(0.1, 0.07, 0.95), 0xf0f0ec, { x, z });
      add(boxOnGround(0.55, 0.07, 0.1), 0xf0f0ec, { x: x + sx * 0.25, z });
      // cups below the pole ends of this row
      for (const p of rails) {
        for (const end of [p.a, p.b]) {
          if (Math.sign(end[0]) !== sx || Math.abs(end[2] - z) > 0.2) continue;
          add(new THREE.BoxGeometry(0.07, 0.035, 0.12), 0x3a3a3a, {
            x: sx * (STAND_X - W / 2 - 0.035),
            y: end[1] - POLE_RADIUS - 0.018,
            z: end[2],
          });
        }
      }
      // direction flag: red on the +t side (local −X), white on −t
      if (flags) {
        const red = sx === flagSides().red;
        add(new THREE.CylinderGeometry(0.012, 0.012, 0.55, 5), 0x9e9e9e, {
          x,
          y: H + 0.27,
          z,
        });
        add(new THREE.BoxGeometry(0.4, 0.27, 0.014), red ? 0xd50000 : 0xfafafa, {
          x: x + sx * 0.21,
          y: H + 0.4,
          z,
          rz: sx * 0.05,
        });
      }
    }
  }
  // fixed fillers
  if (element.kind === 'vertical') {
    add(new THREE.BoxGeometry(POLE_GEOM_LENGTH, 0.24, 0.05), color, { y: 0.19 });
    add(new THREE.BoxGeometry(POLE_GEOM_LENGTH - 0.3, 0.06, 0.055), 0xf5f5f2, { y: 0.19 });
  }
  // number board post (left of the obstacle, front side)
  if (board) {
    const zFront = standRows(element)[0] - 0.25;
    add(boxOnGround(0.05, 1.05, 0.05), 0xe0e0e0, { x: STAND_X + 0.75, z: zFront });
    add(new THREE.BoxGeometry(0.66, 0.56, 0.03), color, { x: STAND_X + 0.75, y: 1.0, z: zFront });
  }
}

function boardMatrix(element) {
  const zFront = standRows(element)[0] - 0.25;
  const local = new THREE.Matrix4().compose(
    new THREE.Vector3(STAND_X + 0.75, 1.0, zFront),
    new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), Math.PI),
    new THREE.Vector3(1, 1, 1),
  );
  return localMatrix(element).multiply(local);
}

function drawNumber(text) {
  return (ctx, w, h) => {
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, w, h);
    ctx.fillStyle = '#1b1b1b';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const size = fitText(ctx, text, w * 0.86, 800, h * 0.72);
    ctx.font = `800 ${size}px ${SYSTEM_FONT}`;
    ctx.fillText(text, w / 2, h * 0.54);
  };
}

// --- Fall animation -----------------------------------------------------------------------------

const X_AXIS = new THREE.Vector3(1, 0, 0);
const v1 = new THREE.Vector3();
const v3 = new THREE.Vector3();
const q1 = new THREE.Quaternion();
const q2 = new THREE.Quaternion();

function poseFromEnds(a, b, roll, outPos, outQuat) {
  outPos.addVectors(a, b).multiplyScalar(0.5);
  v3.subVectors(b, a).normalize();
  q1.setFromUnitVectors(X_AXIS, v3);
  q2.setFromAxisAngle(X_AXIS, roll);
  outQuat.multiplyQuaternions(q1, q2);
}

/** A single pole with state and animation (element-local coordinates). */
function createPole(def, element, index, rng) {
  const restA = new THREE.Vector3(...def.a);
  const restB = new THREE.Vector3(...def.b);
  const length = restA.distanceTo(restB);
  const pole = {
    def,
    element,
    index,
    length,
    state: 'up',
    t: 0,
    pos: new THREE.Vector3(),
    quat: new THREE.Quaternion(),
    restPos: new THREE.Vector3(),
    restQuat: new THREE.Quaternion(),
    fromA: new THREE.Vector3(),
    fromB: new THREE.Vector3(),
    toA: new THREE.Vector3(),
    toB: new THREE.Vector3(),
    fromPos: new THREE.Vector3(),
    fromQuat: new THREE.Quaternion(),
    roll: 0,
    lead: 0,
    rng,
  };
  poseFromEnds(restA, restB, 0, pole.restPos, pole.restQuat);
  pole.pos.copy(pole.restPos);
  pole.quat.copy(pole.restQuat);
  return pole;
}

function startFall(pole, side) {
  // current ends from the current pose
  v1.set(pole.length / 2, 0, 0).applyQuaternion(pole.quat);
  pole.fromA.copy(pole.pos).sub(v1);
  pole.fromB.copy(pole.pos).add(v1);
  const target = fallTarget(pole.pos.toArray(), pole.length, side, pole.rng);
  pole.toA.fromArray(target.a);
  pole.toB.fromArray(target.b);
  pole.roll = target.roll;
  pole.lead = target.lead;
  pole.state = 'falling';
  pole.t = 0;
}

function startRise(pole) {
  pole.fromPos.copy(pole.pos);
  pole.fromQuat.copy(pole.quat);
  pole.state = 'rising';
  pole.t = 0;
}

const ea = new THREE.Vector3();
const eb = new THREE.Vector3();

/** Advances the animation; returns true if the pose changed. */
function stepPole(pole, dt) {
  if (pole.state === 'falling') {
    pole.t = Math.min(1, pole.t + dt / FALL_DURATION);
    fallPointInto(ea, pole.fromA, pole.toA, endProgressOf(pole.t, pole.lead, true));
    fallPointInto(eb, pole.fromB, pole.toB, endProgressOf(pole.t, pole.lead, false));
    poseFromEnds(ea, eb, pole.roll * easeOut(pole.t), pole.pos, pole.quat);
    if (pole.t >= 1) pole.state = 'down';
    return true;
  }
  if (pole.state === 'rising') {
    pole.t = Math.min(1, pole.t + dt / RISE_DURATION);
    const k = easeInOut(pole.t);
    pole.pos.lerpVectors(pole.fromPos, pole.restPos, k);
    pole.pos.y += Math.sin(pole.t * Math.PI) * 0.25;
    pole.quat.slerpQuaternions(pole.fromQuat, pole.restQuat, k);
    if (pole.t >= 1) pole.state = 'up';
    return true;
  }
  return false;
}

// --- Highlight ------------------------------------------------------------------------------

function roundedRectShape(hw, hd, r) {
  const s = new THREE.Shape();
  s.moveTo(-hw + r, -hd);
  s.lineTo(hw - r, -hd);
  s.quadraticCurveTo(hw, -hd, hw, -hd + r);
  s.lineTo(hw, hd - r);
  s.quadraticCurveTo(hw, hd, hw - r, hd);
  s.lineTo(-hw + r, hd);
  s.quadraticCurveTo(-hw, hd, -hw, hd - r);
  s.lineTo(-hw, -hd + r);
  s.quadraticCurveTo(-hw, -hd, -hw + r, -hd);
  return s;
}

function ringGeometry(hw, hd, stroke) {
  const outer = roundedRectShape(hw, hd, 0.9);
  const inner = roundedRectShape(hw - stroke, hd - stroke, 0.9 - stroke * 0.5);
  outer.holes.push(new THREE.Path(inner.getPoints(6).reverse()));
  const g = new THREE.ShapeGeometry(outer, 6);
  g.rotateX(-Math.PI / 2);
  return g;
}

function createBadge() {
  const canvas = createCanvas(256, 320);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  const material = new THREE.SpriteMaterial({
    map: texture,
    transparent: true,
    depthWrite: false,
    fog: false,
    toneMapped: false,
  });
  const sprite = new THREE.Sprite(material);
  sprite.center.set(0.5, 0);
  sprite.renderOrder = 5;
  let current = null;
  function draw(text) {
    if (text === current) return;
    current = text;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, 256, 320);
    // pin: circle with a point at the bottom
    ctx.beginPath();
    ctx.moveTo(128, 314);
    ctx.lineTo(70, 200);
    ctx.lineTo(186, 200);
    ctx.closePath();
    ctx.fillStyle = '#1d3b8f';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(128, 124, 116, 0, Math.PI * 2);
    ctx.fillStyle = '#1d3b8f';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(128, 124, 100, 0, Math.PI * 2);
    ctx.fillStyle = '#ffd21f';
    ctx.fill();
    ctx.fillStyle = '#152a66';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const size = fitText(ctx, text, 170, 900, 130);
    ctx.font = `900 ${size}px ${SYSTEM_FONT}`;
    ctx.fillText(text, 128, 132);
    texture.needsUpdate = true;
  }
  return { sprite, draw, texture, material };
}

function createHighlight(release) {
  const group = new THREE.Group();
  group.name = 'highlight';
  group.visible = false;
  const ringMaterial = new THREE.MeshBasicMaterial({
    color: 0xffd21f,
    transparent: true,
    opacity: 0.85,
    depthWrite: false,
    toneMapped: false,
    polygonOffset: true,
    polygonOffsetFactor: -3,
    polygonOffsetUnits: -3,
  });
  const ring = new THREE.Mesh(new THREE.BufferGeometry(), ringMaterial);
  ring.position.y = 0.02;
  ring.renderOrder = 3;
  group.add(ring);
  const badge = createBadge();
  group.add(badge.sprite);
  let time = 0;
  let baseY = 2;
  return {
    group,
    show(element, text) {
      const hw = STAND_X + STAND_WIDTH / 2 + 0.7;
      const hd = (element.kind === 'oxer' ? (element.spread || 0) / 2 : 0) + 1.1;
      release(ring.geometry);
      ring.geometry = ringGeometry(hw, hd, 0.24);
      group.position.set(element.x, 0, element.z);
      group.rotation.y = element.rot || 0;
      baseY = standHeight(element) + 0.75;
      badge.draw(text);
      badge.sprite.visible = Boolean(text);
      group.visible = true;
    },
    hide() {
      group.visible = false;
    },
    update(dt, camera) {
      if (!group.visible) return;
      time += dt;
      const pulse = 0.5 + 0.5 * Math.sin(time * 4);
      ringMaterial.opacity = 0.55 + 0.4 * pulse;
      ring.scale.setScalar(1 + 0.03 * pulse);
      badge.sprite.position.y = baseY + Math.sin(time * 2.4) * 0.12;
      // do not get too small from afar
      let s = 1.1;
      if (camera) {
        badge.sprite.getWorldPosition(v1);
        s = Math.max(1.1, camera.position.distanceTo(v1) * 0.05);
      }
      badge.sprite.scale.set(s, s * 1.25, 1);
    },
    dispose() {
      ring.geometry.dispose();
      ringMaterial.dispose();
      badge.texture.dispose();
      badge.material.dispose();
    },
  };
}

// --- Manager --------------------------------------------------------------------------

/**
 * materialFactory(kind, params) → { standard, lambert }; wind from createWind() moves the flowers.
 * Returns the obstacle manager. `setDecor(on)` shows or hides the flower boxes at the stands.
 */
export function createObstacles({ materialFactory, release = releaseNow, wind = createWind() }) {
  const group = new THREE.Group();
  group.name = 'obstacles';
  const staticMats = materialFactory('obstacle-static', { vertexColors: true, roughness: 0.55 });
  const poleMats = materialFactory('poles', { color: 0xffffff, roughness: 0.45 });
  const boardMats = materialFactory('boards', { roughness: 0.6 });

  const poleGeoms = buildPoleGeometries(10);
  const whitePoles = new THREE.InstancedMesh(poleGeoms.white, poleMats.standard, MAX_POLES);
  const colorPoles = new THREE.InstancedMesh(poleGeoms.colored, poleMats.standard, MAX_POLES);
  for (const m of [whitePoles, colorPoles]) {
    m.count = 0;
    m.frustumCulled = false;
    m.castShadow = true;
    m.receiveShadow = true;
    m.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    group.add(m);
  }
  whitePoles.name = 'poles-white';
  colorPoles.name = 'poles-colored';
  colorPoles.setColorAt(0, new THREE.Color(1, 1, 1));

  // flower boxes at the feet of the stands: one instanced mesh, kept for the life of the manager
  // (the instances are rewritten with every course); hidden while `decor` is off
  const planterMats = materialFactory('planters', {
    vertexColors: true,
    roughness: 0.85,
    side: THREE.DoubleSide,
  });
  // the wooden box does not bend, only the plants above it
  patchBlossoms(planterMats.standard, wind, { base: PLANTER_BOX_HEIGHT });
  patchBlossoms(planterMats.lambert, wind, { base: PLANTER_BOX_HEIGHT });
  const planterGeometry = buildPlanterGeometry();
  const planters = new THREE.InstancedMesh(planterGeometry, planterMats.standard, MAX_PLANTERS);
  planters.name = 'planters';
  planters.count = 0;
  planters.frustumCulled = false;
  planters.setColorAt(0, new THREE.Color(1, 1, 1));
  planters.receiveShadow = false;
  group.add(planters);
  let decor = false;

  let staticMesh = null;
  let boardMesh = null;
  let atlas = null;
  let poles = [];
  /** elementId → { element, obstacle, index, label, rails: Map rail → pole[], color } */
  const elements = new Map();
  const highlight = createHighlight(release);
  group.add(highlight.group);
  let shownHighlight = null; // key of the highlight that is currently built

  const meshes = [
    { mesh: null, mats: staticMats, shadow: 'obstacles' },
    { mesh: whitePoles, mats: poleMats, shadow: 'obstacles' },
    { mesh: colorPoles, mats: poleMats, shadow: 'obstacles' },
    { mesh: null, mats: boardMats, shadow: 'none' },
    { mesh: planters, mats: planterMats, shadow: 'none', detail: true },
  ];

  const mat = new THREE.Matrix4();
  const one = new THREE.Vector3(1, 1, 1);
  const wq = new THREE.Quaternion();
  const wp = new THREE.Vector3();

  function writePole(pole) {
    const el = pole.element;
    wq.setFromAxisAngle(THREE.Object3D.DEFAULT_UP, el.rot || 0);
    wp.copy(pole.pos).applyQuaternion(wq);
    wp.x += el.x;
    wp.z += el.z;
    wq.multiply(pole.quat);
    one.set(pole.length / POLE_GEOM_LENGTH, 1, 1);
    mat.compose(wp, wq, one);
    whitePoles.setMatrixAt(pole.index, mat);
    colorPoles.setMatrixAt(pole.index, mat);
  }

  function clear() {
    if (staticMesh) {
      group.remove(staticMesh);
      release(staticMesh.geometry);
      staticMesh = null;
    }
    if (boardMesh) {
      group.remove(boardMesh);
      release(boardMesh.geometry);
      boardMats.standard.map = null;
      boardMats.lambert.map = null;
      boardMesh = null;
    }
    if (atlas) {
      release(atlas.texture);
      atlas = null;
    }
    elements.clear();
    poles = [];
    whitePoles.count = 0;
    colorPoles.count = 0;
    planters.count = 0;
    planters.visible = false;
    highlight.hide();
    shownHighlight = null;
  }

  /** One flower box at each foot of every stand row, in the blossom colour of its obstacle. */
  function placePlanters(obstacles) {
    let n = 0;
    const c = new THREE.Color();
    const m = new THREE.Matrix4();
    const local = new THREE.Matrix4();
    obstacles.forEach((obstacle, oi) => {
      c.set(PLANTER_COLORS[oi % PLANTER_COLORS.length]);
      for (const element of obstacle.elements) {
        const base = localMatrix(element);
        for (const z of standRows(element)) {
          for (const sx of [-1, 1]) {
            if (n >= MAX_PLANTERS) return;
            local.makeTranslation(sx * (STAND_X + PLANTER_OFFSET), 0, z);
            m.multiplyMatrices(base, local);
            planters.setMatrixAt(n, m);
            planters.setColorAt(n, c);
            n += 1;
          }
        }
      }
    });
    planters.count = n;
    planters.visible = decor && n > 0;
    planters.instanceMatrix.needsUpdate = true;
    if (planters.instanceColor) planters.instanceColor.needsUpdate = true;
  }

  function setObstacles(obstacles, { flags = false } = {}) {
    clear();
    const builder = createGeometryBuilder();
    const labels = [];
    const c = new THREE.Color();
    (obstacles || []).forEach((obstacle, oi) => {
      const color = OBSTACLE_COLORS[oi % OBSTACLE_COLORS.length];
      obstacle.elements.forEach((element, ei) => {
        const label = labelOf(obstacle, ei);
        addElementStatic(builder, element, color, { flags: Boolean(flags), board: Boolean(label) });
        if (label) labels.push({ element, label });
        const info = {
          element,
          obstacle,
          index: ei,
          label,
          color,
          rails: new Map(),
          railLists: [], // same lists, indexed by rail (allocation-free per-frame iteration)
        };
        const rng = createRng(hashId(element.id));
        for (const def of polesOf(element)) {
          if (poles.length >= MAX_POLES) break;
          const pole = createPole(def, element, poles.length, rng);
          poles.push(pole);
          writePole(pole);
          c.set(color);
          colorPoles.setColorAt(pole.index, c);
          if (def.rail >= 0) {
            if (!info.rails.has(def.rail)) {
              const list = [];
              info.rails.set(def.rail, list);
              info.railLists[def.rail] = list;
            }
            info.rails.get(def.rail).push(pole);
          }
        }
        elements.set(element.id, info);
      });
    });
    whitePoles.count = poles.length;
    colorPoles.count = poles.length;
    placePlanters(obstacles || []);
    whitePoles.instanceMatrix.needsUpdate = true;
    colorPoles.instanceMatrix.needsUpdate = true;
    if (colorPoles.instanceColor) colorPoles.instanceColor.needsUpdate = true;

    staticMesh = new THREE.Mesh(builder.build(), staticMats.standard);
    staticMesh.name = 'obstacle-static';
    staticMesh.castShadow = true;
    staticMesh.receiveShadow = true;
    group.add(staticMesh);
    meshes[0].mesh = staticMesh;

    if (labels.length) {
      const unique = [...new Set(labels.map((l) => l.label))];
      atlas = createLabelAtlas(
        unique.map((key) => ({ key, draw: drawNumber(key) })),
        { cellW: 128, cellH: 112 },
      );
      const boards = createGeometryBuilder();
      for (const { element, label } of labels) {
        boards.addPainted(
          makeSignQuad(0.6, 0.5, atlas.rects.get(label), 0.017),
          boardMatrix(element),
        );
      }
      boardMats.standard.map = atlas.texture;
      boardMats.lambert.map = atlas.texture;
      boardMats.standard.needsUpdate = true;
      boardMats.lambert.needsUpdate = true;
      boardMesh = new THREE.Mesh(boards.build(), boardMats.standard);
      boardMesh.name = 'number-boards';
      group.add(boardMesh);
      meshes[3].mesh = boardMesh;
    } else {
      meshes[3].mesh = null;
    }
    return meshes;
  }

  // Map#forEach with a stable callback does not allocate (for…of would create an iterator and an
  // entry array per frame); the current call's direction maps are handed over through these slots.
  let syncFallDirs = null;
  let syncApproachDirs = null;
  function syncElementRails(states, id) {
    const info = elements.get(id);
    if (!info || !states) return;
    const lists = info.railLists;
    for (let railIndex = 0; railIndex < lists.length; railIndex += 1) {
      const list = lists[railIndex];
      if (!list) continue;
      const up = states[railIndex] !== false;
      for (let i = 0; i < list.length; i += 1) {
        const pole = list[i];
        const isUp = pole.state === 'up' || pole.state === 'rising';
        if (up && !isUp) startRise(pole);
        else if (!up && isUp) {
          const side =
            (syncFallDirs && syncFallDirs.get(id)) ||
            (syncApproachDirs && syncApproachDirs.get(id)) ||
            (pole.rng() < 0.5 ? -1 : 1);
          startFall(pole, Math.sign(side) || 1);
        }
      }
    }
  }

  /**
   * rails: Map elementId → boolean[] (true = up). fallDirs / approachDirs: optional Maps
   * elementId → ±1 (side to fall to along n; fallDirs wins). Runs every frame: no allocations.
   */
  function syncRails(rails, dt = 0, fallDirs = null, approachDirs = null) {
    if (rails) {
      syncFallDirs = fallDirs;
      syncApproachDirs = approachDirs;
      rails.forEach(syncElementRails);
      syncFallDirs = null;
      syncApproachDirs = null;
    }
    let dirty = false;
    for (let i = 0; i < poles.length; i += 1) {
      const pole = poles[i];
      if (stepPole(pole, dt)) {
        writePole(pole);
        dirty = true;
      }
    }
    if (dirty) {
      whitePoles.instanceMatrix.needsUpdate = true;
      colorPoles.instanceMatrix.needsUpdate = true;
    }
  }

  return {
    group,
    meshes,
    setObstacles,
    syncRails,
    getElement(id) {
      return elements.get(id)?.element ?? null;
    },
    /** Shows or hides the flower boxes at the stands (the detail levels medium and high). */
    setDecor(on) {
      decor = Boolean(on);
      planters.visible = decor && planters.count > 0;
    },
    highlight(elementId, number) {
      const key = elementId ? `${elementId}|${number ?? ''}` : null;
      // called every frame: only rebuild the ring and badge when something changed
      if (key === shownHighlight) return;
      const info = elementId ? elements.get(elementId) : null;
      if (!info) {
        highlight.hide();
        shownHighlight = null;
        return;
      }
      highlight.show(info.element, highlightText(info.obstacle, info.index, number));
      shownHighlight = key;
    },
    update(dt, camera) {
      highlight.update(dt, camera);
    },
    dispose() {
      clear();
      highlight.dispose();
      poleGeoms.white.dispose();
      poleGeoms.colored.dispose();
      whitePoles.dispose();
      colorPoles.dispose();
      planterGeometry.dispose();
      planters.dispose();
    },
  };
}

function hashId(id) {
  let h = 2166136261;
  const s = String(id);
  for (let i = 0; i < s.length; i += 1) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}
