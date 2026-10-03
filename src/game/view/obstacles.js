// Hindernis-Meshes: Ständer, gestreifte Stangen (instanziert, fallen sichtbar), Füllteile,
// Richtungsfahnen, Nummernschilder und Hervorhebung des Hindernisses, das an der Reihe ist.
import * as THREE from 'three';
import { POLE_LENGTH, STAND_WIDTH } from '../sim/tuning.js';
import {
  createGeometryBuilder,
  boxOnGround,
  createLabelAtlas,
  createCanvas,
  fitText,
  SYSTEM_FONT,
  createRng,
} from './textures.js';
import { makeSignQuad, roundRect } from './arena.js';

export const POLE_RADIUS = 0.05;
const POLE_GEOM_LENGTH = POLE_LENGTH - 0.02;
const STRIPES = 11; // ungerade: weiße Enden
const STAND_X = POLE_LENGTH / 2 + STAND_WIDTH / 2;
const CROSS_LOW_Y = 0.17; // untere Enden der Kreuzstangen in tiefen Auflagen
export const FALL_DURATION = 0.7;
export const RISE_DURATION = 0.45;
const MAX_POLES = 128;

// Farbpaare der Hindernisse (Stangenstreifen, Ständerabschnitte, Planke)
export const OBSTACLE_COLORS = [0xc62828, 0x1e56b8, 0x2e7d32, 0xef8f00, 0x6a3fa0, 0x00838f];

/** Höhe der Ständer für ein Element. */
export function standHeight(element) {
  return Math.max(1.45, element.height + 0.55);
}

/**
 * Lokale Ruhelagen aller Stangen eines Elements (rein, testbar).
 * Lokales System: +Z = Sprungachse n, +X = −t. Liefert [{ rail, a:[x,y,z], b:[x,y,z] }] mit
 * rail = Index der fallenden Stange oder −1 (fest, fällt nie).
 */
export function polesOf(element) {
  const h = element.height;
  const s = element.kind === 'oxer' ? element.spread || 0 : 0;
  const half = POLE_GEOM_LENGTH / 2;
  const top = h - POLE_RADIUS;
  const out = [];
  if (element.kind === 'cross') {
    const y1 = Math.max(CROSS_LOW_Y + 0.1, 2 * top - CROSS_LOW_Y);
    const dz = POLE_RADIUS + 0.004;
    out.push({ rail: 0, a: [-half, CROSS_LOW_Y, -dz], b: [half, y1, -dz] });
    out.push({ rail: 0, a: [-half, y1, dz], b: [half, CROSS_LOW_Y, dz] });
    out.push({ rail: -1, a: [-half, POLE_RADIUS, 0.32], b: [half, POLE_RADIUS, 0.32] });
  } else if (element.kind === 'vertical') {
    out.push({ rail: 0, a: [-half, top, 0], b: [half, top, 0] });
    if (h >= 0.7) {
      const y = (0.32 + top) / 2;
      out.push({ rail: -1, a: [-half, y, 0], b: [half, y, 0] });
    }
  } else {
    out.push({ rail: 0, a: [-half, top, -s / 2], b: [half, top, -s / 2] });
    out.push({ rail: 1, a: [-half, top, s / 2], b: [half, top, s / 2] });
    out.push({ rail: -1, a: [-half, h * 0.45, -s / 2], b: [half, h * 0.45, -s / 2] });
  }
  return out;
}

/** Ständerpaare entlang n (Oxer: zwei). */
function standRows(element) {
  if (element.kind !== 'oxer') return [0];
  const s = element.spread || 0;
  return [-s / 2, s / 2];
}

/** Pole-Geometrien (entlang X, zentriert): weiße und farbige Streifen getrennt. */
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

/** Statische Teile eines Elements in den Builder schreiben (Weltkoordinaten über Matrix). */
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
      // Pfosten: weiß mit farbigen Abschnitten
      const bands = [0, 0.3, 0.55, 0.8, 1.05, 1.3, H];
      for (let i = 0; i < bands.length - 1; i += 1) {
        const y0 = bands[i];
        const y1 = i === bands.length - 2 ? H : Math.min(bands[i + 1], H);
        if (y1 <= y0) continue;
        add(boxOnGround(W, y1 - y0, W), i % 2 === 1 ? color : 0xf5f5f2, { x, y: y0, z });
      }
      add(new THREE.BoxGeometry(W + 0.03, 0.04, W + 0.03), color, { x, y: H + 0.02, z });
      // Füße (T-Form nach außen)
      add(boxOnGround(0.1, 0.07, 0.95), 0xf0f0ec, { x, z });
      add(boxOnGround(0.55, 0.07, 0.1), 0xf0f0ec, { x: x + sx * 0.25, z });
      // Auflagen (Löffel) unter den Stangenenden dieser Reihe
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
      // Richtungsfahne: rot auf der +t-Seite (lokal −X), weiß auf −t
      if (flags) {
        const red = sx < 0;
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
  // feste Füllteile
  if (element.kind === 'vertical') {
    add(new THREE.BoxGeometry(POLE_GEOM_LENGTH, 0.24, 0.05), color, { y: 0.19 });
    add(new THREE.BoxGeometry(POLE_GEOM_LENGTH - 0.3, 0.06, 0.055), 0xf5f5f2, { y: 0.19 });
  }
  // Nummernschild-Pfosten (links neben dem Hindernis, Vorderseite)
  if (board) {
    const zFront = standRows(element)[0] - 0.25;
    add(boxOnGround(0.05, 1.05, 0.05), 0xe0e0e0, { x: STAND_X + 0.75, z: zFront });
    add(new THREE.BoxGeometry(0.58, 0.5, 0.03), color, { x: STAND_X + 0.75, y: 1.0, z: zFront });
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

/** Bezeichnung eines Elements: Nummer, bei Kombinationen mit a/b. */
export function labelOf(obstacle, index) {
  if (obstacle.number === null || obstacle.number === undefined) return null;
  if (obstacle.elements.length > 1) return `${obstacle.number}${index === 0 ? 'a' : 'b'}`;
  return String(obstacle.number);
}

// --- Fallanimation -----------------------------------------------------------------------------

const easeOut = (t) => 1 - (1 - t) * (1 - t);
const easeInOut = (t) => (t < 0.5 ? 2 * t * t : 1 - 2 * (1 - t) * (1 - t));
/** Fallkurve mit kleinem Nachhüpfen: 0 → 1. */
export function fallCurve(t) {
  if (t <= 0) return 0;
  if (t < 0.78) return (t / 0.78) ** 2;
  if (t >= 1) return 1;
  return 1 - 0.1 * Math.sin(((t - 0.78) / 0.22) * Math.PI);
}

const X_AXIS = new THREE.Vector3(1, 0, 0);
const v1 = new THREE.Vector3();
const v2 = new THREE.Vector3();
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

/** Eine einzelne Stange mit Zustand und Animation (lokale Koordinaten des Elements). */
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
  const r = pole.rng;
  // aktuelle Enden aus der aktuellen Lage
  v1.set(pole.length / 2, 0, 0).applyQuaternion(pole.quat);
  pole.fromA.copy(pole.pos).sub(v1);
  pole.fromB.copy(pole.pos).add(v1);
  // Ziel: liegt auf dem Sand, zur Fallseite verschoben und leicht verdreht
  const travel = 0.55 + r() * 0.6;
  const yaw = (r() - 0.5) * 0.5;
  const center = v2.set((r() - 0.5) * 0.35, POLE_RADIUS, pole.pos.z + side * travel);
  v1.set(Math.cos(yaw), 0, -Math.sin(yaw)).multiplyScalar(pole.length / 2);
  pole.toA.copy(center).sub(v1);
  pole.toB.copy(center).add(v1);
  pole.roll = side * (travel / POLE_RADIUS) * (0.6 + r() * 0.3);
  pole.lead = r() < 0.5 ? 0 : 1; // welches Ende zuerst fällt
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

/** Animation fortschreiben; liefert true, wenn sich die Lage geändert hat. */
export function stepPole(pole, dt) {
  if (pole.state === 'falling') {
    pole.t = Math.min(1, pole.t + dt / FALL_DURATION);
    const delay = 0.16;
    const tFirst = Math.min(1, pole.t / (1 - delay));
    const tSecond = Math.max(0, (pole.t - delay) / (1 - delay));
    const tA = pole.lead === 0 ? tFirst : tSecond;
    const tB = pole.lead === 0 ? tSecond : tFirst;
    ea.lerpVectors(pole.fromA, pole.toA, easeOut(tA));
    ea.y = pole.fromA.y + (pole.toA.y - pole.fromA.y) * fallCurve(tA);
    eb.lerpVectors(pole.fromB, pole.toB, easeOut(tB));
    eb.y = pole.fromB.y + (pole.toB.y - pole.fromB.y) * fallCurve(tB);
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

// --- Hervorhebung ------------------------------------------------------------------------------

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
    // Pin: Kreis mit Spitze nach unten
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

function createHighlight() {
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
      ring.geometry.dispose();
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
      // aus der Ferne nicht zu klein werden
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

// --- Gesamtverwaltung --------------------------------------------------------------------------

/**
 * materialFactory(kind, params) → { standard, lambert }. Liefert die Hindernis-Verwaltung.
 */
export function createObstacles({ materialFactory }) {
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

  let staticMesh = null;
  let boardMesh = null;
  let atlas = null;
  let poles = [];
  /** elementId → { element, obstacle, index, label, poles: Map rail → pole[] , color } */
  const elements = new Map();
  const highlight = createHighlight();
  group.add(highlight.group);

  const meshes = [
    { mesh: null, mats: staticMats, shadow: 'obstacles' },
    { mesh: whitePoles, mats: poleMats, shadow: 'obstacles' },
    { mesh: colorPoles, mats: poleMats, shadow: 'obstacles' },
    { mesh: null, mats: boardMats, shadow: 'none' },
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
      staticMesh.geometry.dispose();
      staticMesh = null;
    }
    if (boardMesh) {
      group.remove(boardMesh);
      boardMesh.geometry.dispose();
      boardMats.standard.map = null;
      boardMats.lambert.map = null;
      boardMesh = null;
    }
    if (atlas) {
      atlas.texture.dispose();
      atlas = null;
    }
    elements.clear();
    poles = [];
    whitePoles.count = 0;
    colorPoles.count = 0;
    highlight.hide();
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
        const info = { element, obstacle, index: ei, label, color, rails: new Map() };
        const rng = createRng(hashId(element.id));
        for (const def of polesOf(element)) {
          if (poles.length >= MAX_POLES) break;
          const pole = createPole(def, element, poles.length, rng);
          poles.push(pole);
          writePole(pole);
          c.set(color);
          colorPoles.setColorAt(pole.index, c);
          if (def.rail >= 0) {
            if (!info.rails.has(def.rail)) info.rails.set(def.rail, []);
            info.rails.get(def.rail).push(pole);
          }
        }
        elements.set(element.id, info);
      });
    });
    whitePoles.count = poles.length;
    colorPoles.count = poles.length;
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
        boards.addPainted(makeSignQuad(0.52, 0.44, atlas.rects.get(label), 0.017), boardMatrix(element));
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

  /**
   * rails: Map elementId → boolean[] (true = oben). fallDirOf(elementId) → ±1 (Fallseite
   * entlang n), optional.
   */
  function syncRails(rails, dt = 0, fallDirOf = null) {
    if (rails) {
      for (const [id, states] of rails) {
        const info = elements.get(id);
        if (!info || !states) continue;
        info.rails.forEach((list, railIndex) => {
          const up = states[railIndex] !== false;
          for (const pole of list) {
            const isUp = pole.state === 'up' || pole.state === 'rising';
            if (up && !isUp) startRise(pole);
            else if (!up && isUp) {
              const side = (fallDirOf && fallDirOf(id)) || (pole.rng() < 0.5 ? -1 : 1);
              startFall(pole, Math.sign(side) || 1);
            }
          }
        });
      }
    }
    let dirty = false;
    for (const pole of poles) {
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
    getInfo(id) {
      return elements.get(id) ?? null;
    },
    /** Zustand einer Stange für Tests/Debug: 'up' | 'falling' | 'down' | 'rising'. */
    railState(id, rail) {
      const list = elements.get(id)?.rails.get(rail);
      return list ? list[0].state : null;
    },
    highlight(elementId, number) {
      const info = elementId ? elements.get(elementId) : null;
      if (!info) {
        highlight.hide();
        return;
      }
      let text = number === null || number === undefined ? info.label : String(number);
      if (text && info.obstacle.elements.length > 1 && /^\d+$/.test(text)) {
        text += info.index === 0 ? 'a' : 'b';
      }
      highlight.show(info.element, text);
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
    },
  };
}

function hashId(id) {
  let h = 2166136261;
  const s = String(id);
  for (let i = 0; i < s.length; i += 1) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

export { roundRect };
