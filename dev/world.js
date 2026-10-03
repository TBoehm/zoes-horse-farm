// Demo der 3D-Welt (nicht ausgeliefert): npx vite → /dev/world.html
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { createRenderer, resizeRenderer } from '../src/game/view/renderer.js';
import { createWorld } from '../src/game/view/world.js';
import { pickInitialLevel } from '../src/game/view/quality.js';
import { COMBI_DISTANCE } from '../src/game/sim/tuning.js';

const params = new URLSearchParams(location.search);
const canvas = document.getElementById('c');
const renderer = createRenderer(canvas, { antialias: params.get('aa') !== '0' });
const gl = renderer.getContext();
const dbg = gl.getExtension('WEBGL_debug_renderer_info');
const rendererString = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : '';
const autoLevel = pickInitialLevel({
  hardwareConcurrency: navigator.hardwareConcurrency,
  deviceMemory: navigator.deviceMemory,
  isTouch: matchMedia('(pointer: coarse)').matches,
  rendererString,
  screenPixels: screen.width * screen.height * devicePixelRatio ** 2,
});
let level = params.get('q') || autoLevel;
const world = createWorld(renderer, { quality: level });

const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 1200);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;
controls.maxPolarAngle = Math.PI * 0.495;

// Beispiel-Parcours: alle Arten, mit Nummern und Richtung
const comboRot = Math.PI / 2;
const obstacles = [
  {
    number: 1,
    directed: true,
    elements: [{ id: 'c1', kind: 'cross', height: 0.5, spread: 0, x: -8, z: -18, rot: 0 }],
  },
  {
    number: 2,
    directed: true,
    elements: [{ id: 'v2', kind: 'vertical', height: 0.6, spread: 0, x: 8, z: -6, rot: Math.PI }],
  },
  {
    number: 3,
    directed: true,
    elements: [{ id: 'o3', kind: 'oxer', height: 0.75, spread: 0.9, x: -8, z: 8, rot: 0.25 }],
  },
  {
    number: 4,
    directed: true,
    elements: [
      { id: 'k4a', kind: 'vertical', height: 0.8, spread: 0, x: -6, z: 26, rot: comboRot },
      {
        id: 'k4b',
        kind: 'oxer',
        height: 0.85,
        spread: 1.0,
        x: -6 + Math.sin(comboRot) * COMBI_DISTANCE,
        z: 26 + Math.cos(comboRot) * COMBI_DISTANCE,
        rot: comboRot,
      },
    ],
  },
  {
    number: 5,
    directed: true,
    elements: [{ id: 'v5', kind: 'vertical', height: 0.7, spread: 0, x: 10, z: 18, rot: Math.PI }],
  },
];
const elementIds = obstacles.flatMap((o) => o.elements.map((e) => e.id));
const rails = new Map();
for (const o of obstacles) {
  for (const e of o.elements) rails.set(e.id, e.kind === 'oxer' ? [true, true] : [true]);
}

let flags = params.get('flags') !== '0';
let aidOn = params.get('aid') !== '0';
let linesOn = true;
let finishMarked = false;
let hlIndex = 0; // Index in elementIds
world.setObstacles(obstacles, { flags });
const lines = {
  start: { a: [16, -30], b: [10, -30] },
  finish: { a: [16, 30], b: [10, 30] },
  labels: { start: 'Start', finish: 'Ziel' },
};
world.setLines(lines);

function numberOf(id) {
  return obstacles.find((o) => o.elements.some((e) => e.id === id))?.number ?? null;
}
function applyHighlight() {
  const id = elementIds[hlIndex] ?? null;
  world.highlight(id, id ? numberOf(id) : null);
  world.setFinishMarked(!id || finishMarked);
  world.setAid(aidOn && id ? { elementId: id, dir: 1, zone: { far: 3.2, near: 1.4 } } : null);
}
applyHighlight();

const views = {
  rider: () => {
    // hinter Hindernis 2 in Anreitrichtung (rot = π → n = (0,0,−1)), 2,5 m hoch
    camera.position.set(8.6, 2.5, 4);
    controls.target.set(8, 1.0, -6);
  },
  rider3: () => {
    camera.position.set(-10.8, 2.5, -2.2);
    controls.target.set(-8, 1.0, 8);
  },
  overview: () => {
    camera.position.set(42, 30, -48);
    controls.target.set(0, 0, 0);
  },
  combo: () => {
    camera.position.set(-15, 3.2, 21);
    controls.target.set(-2, 0.8, 26);
  },
  stable: () => {
    camera.position.set(-6, 4, 40);
    controls.target.set(-38, 3, 16);
  },
};
(views[params.get('view')] || views.rider)();
controls.update();

// UI
const ui = document.getElementById('ui');
function button(label, fn) {
  const b = document.createElement('button');
  b.textContent = label;
  b.onclick = fn;
  ui.append(b);
  return b;
}
const select = document.createElement('select');
for (const l of ['low', 'medium', 'high']) select.append(new Option(l, l, false, l === level));
select.onchange = () => setQuality(select.value);
ui.append(select);
function setQuality(l) {
  level = l;
  select.value = l;
  world.setQuality(l);
}
const elSelect = document.createElement('select');
for (const id of elementIds) elSelect.append(new Option(id, id));
ui.append(elSelect);
button('Stange abwerfen', () => {
  const r = rails.get(elSelect.value);
  const i = r.findIndex((v) => v);
  if (i >= 0) r[i] = false;
});
button('Aufbauen', () => {
  for (const [id, r] of rails) rails.set(id, r.map(() => true));
});
button('Nächstes', () => {
  hlIndex = (hlIndex + 1) % (elementIds.length + 1);
  applyHighlight();
});
button('Fahnen', () => {
  flags = !flags;
  world.setObstacles(obstacles, { flags });
  applyHighlight();
});
button('Hilfe', () => {
  aidOn = !aidOn;
  applyHighlight();
});
button('Linien', () => {
  linesOn = !linesOn;
  world.setLines(linesOn ? lines : null);
});
button('Ziel markieren', () => {
  finishMarked = !finishMarked;
  applyHighlight();
});
for (const v of Object.keys(views)) button(v, () => (views[v](), controls.update()));

const stats = document.getElementById('stats');
let frames = 0;
let acc = 0;
let fps = 0;
let last = performance.now();
function frame(now) {
  const dt = Math.min(0.1, (now - last) / 1000);
  last = now;
  resizeRenderer(renderer, camera);
  controls.update();
  world.setShadowFocus(controls.target.x, controls.target.z);
  world.syncRails(rails, dt);
  world.update(dt, camera);
  renderer.render(world.scene, camera);
  frames += 1;
  acc += dt;
  if (acc >= 0.5) {
    fps = frames / acc;
    frames = 0;
    acc = 0;
  }
  const info = renderer.info.render;
  stats.textContent =
    `Stufe ${level} (auto ${autoLevel})\n${fps.toFixed(0)} fps\n` +
    `Dreiecke ${info.triangles}\nDraw-Calls ${info.calls}\n${renderer.getPixelRatio()}x`;
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

// für Screenshot-Skripte
window.demo = {
  world,
  camera,
  controls,
  rails,
  setQuality,
  view(name) {
    views[name]();
    controls.update();
  },
  drop(id, i = 0) {
    rails.get(id)[i] = false;
  },
  info() {
    const r = renderer.info.render;
    return { triangles: r.triangles, calls: r.calls, level, autoLevel, rendererString };
  },
  renderNow() {
    world.syncRails(rails, 0);
    world.update(0.016, camera);
    renderer.render(world.scene, camera);
    return this.info();
  },
};
