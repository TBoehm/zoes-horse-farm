// Developer demo for the procedural horse (not shipped).
// URL parameters for screenshots: coat, marking, quality, gait, speed, turn, rider=0, cam=side|
// front|back|head|top|threequarter, ui=0, move=0
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { COATS, MARKINGS, createHorse } from '../src/adapters/view3d/horse/index.js';

const params = new URLSearchParams(location.search);
const $ = (id) => document.getElementById(id);

const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.setSize(innerWidth, innerHeight);
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.info.autoReset = false;
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x9cc3e6);
scene.fog = new THREE.Fog(0x9cc3e6, 30, 90);
const camera = new THREE.PerspectiveCamera(40, innerWidth / innerHeight, 0.05, 200);
const controls = new OrbitControls(camera, renderer.domElement);
controls.target.set(0, 1.1, 0);

scene.add(new THREE.HemisphereLight(0xdfefff, 0x8a6f4a, 1.1));
const sun = new THREE.DirectionalLight(0xfff2dd, 2.4);
sun.position.set(6, 10, 4);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
Object.assign(sun.shadow.camera, { left: -4, right: 4, top: 4, bottom: -4, near: 1, far: 30 });
sun.shadow.bias = -0.0005;
scene.add(sun, sun.target);

// sand ground with lines (to check foot sliding)
const cv = document.createElement('canvas');
cv.width = cv.height = 256;
const g = cv.getContext('2d');
g.fillStyle = '#c9ad84';
g.fillRect(0, 0, 256, 256);
for (let i = 0; i < 3000; i++) {
  const c = 150 + Math.random() * 60;
  g.fillStyle = `rgba(${c},${c * 0.85},${c * 0.62},0.5)`;
  g.fillRect(Math.random() * 256, Math.random() * 256, 2, 2);
}
g.strokeStyle = 'rgba(90,70,40,0.5)';
g.lineWidth = 2;
g.strokeRect(0, 0, 256, 256);
const tex = new THREE.CanvasTexture(cv);
tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
tex.repeat.set(100, 100);
tex.colorSpace = THREE.SRGBColorSpace;
const ground = new THREE.Mesh(
  new THREE.PlaneGeometry(200, 200),
  new THREE.MeshStandardMaterial({ map: tex, roughness: 1 }),
);
ground.rotation.x = -Math.PI / 2;
ground.receiveShadow = true;
scene.add(ground);

// fill the UI
for (const c of COATS) $('coat').add(new Option(c, c));
for (const m of MARKINGS) $('marking').add(new Option(m, m));
$('coat').value = params.get('coat') || 'bay';
$('marking').value = params.get('marking') || 'star';
$('quality').value = params.get('quality') || 'medium';
$('rider').checked = params.get('rider') !== '0';
$('move').checked = params.get('move') !== '0';
$('gait').value = params.get('gait') || 'halt';
if (params.get('ui') === '0') $('ui').classList.add('hidden');

const DEFAULT_SPEED = { halt: 0, walk: 1.6, trot: 3.2, canter: 6 };
$('speed').value = params.get('speed') ?? DEFAULT_SPEED[$('gait').value];
$('turn').value = params.get('turn') ?? 0;

let horse = null;
function build() {
  horse?.dispose();
  horse = createHorse({
    coat: $('coat').value,
    marking: $('marking').value,
    quality: $('quality').value,
    rider: $('rider').checked,
  });
  horse.object.traverse((o) => (o.castShadow = o.castShadow ?? false));
  horse.onFootfall = (gait, leg) => {
    footfalls.push(`${gait[0]}${leg}`);
    if (footfalls.length > 12) footfalls.shift();
  };
  scene.add(horse.object);
}
const footfalls = [];
build();

$('coat').onchange = $('marking').onchange = () =>
  horse.setAppearance({ coat: $('coat').value, marking: $('marking').value });
$('quality').onchange = () => horse.setQuality($('quality').value);
$('rider').onchange = build;
$('gait').onchange = () => ($('speed').value = DEFAULT_SPEED[$('gait').value]);

// simulated sim state
const state = {
  speed: 0,
  gait: 'halt',
  turnRate: 0,
  y: 0,
  jump: null,
  hop: null,
  refusal: null,
  heading: 0,
  x: 0,
  z: 0,
};
let action = null; // { type, t, dur }
$('jump').onclick = () => (action = { type: 'jump', t: 0, dur: 0.85, peak: 1.0 });
$('hop').onclick = () => (action = { type: 'hop', t: 0, dur: 0.4 });
$('stop').onclick = () => (action = { type: 'stop', t: 0, dur: 1.2, v0: Number($('speed').value) });
$('runout').onclick = () => (action = { type: 'runout', t: 0, dur: 1.2 });

function simStep(dt) {
  state.gait = $('gait').value;
  state.speed = Number($('speed').value);
  state.turnRate = Number($('turn').value);
  state.jump = null;
  state.hop = null;
  state.refusal = null;
  state.y = 0;
  if (action) {
    action.t += dt;
    const s = Math.min(1, action.t / action.dur);
    if (action.type === 'jump') {
      const phase = s < 0.2 ? 'takeoff' : s < 0.75 ? 'flight' : 'landing';
      const progress = s < 0.2 ? s / 0.2 : s < 0.75 ? (s - 0.2) / 0.55 : (s - 0.75) / 0.25;
      state.jump = { phase, progress };
      state.y = action.peak * Math.sin(Math.PI * s);
      state.turnRate = 0;
    } else if (action.type === 'hop') {
      state.hop = { progress: s };
      state.y = 0.2 * Math.sin(Math.PI * s);
    } else if (action.type === 'stop') {
      state.refusal = { type: 'stop', progress: s };
      state.speed = action.v0 * Math.max(0, 1 - s * 4);
      if (s > 0.25) state.gait = 'halt';
    } else if (action.type === 'runout') {
      state.refusal = { type: 'runout', progress: s };
      state.turnRate = 1.2 * Math.sin(Math.PI * s);
    }
    if (s >= 1) {
      if (action.type === 'stop') {
        $('gait').value = 'halt';
        $('speed').value = 0;
      }
      action = null;
    }
  }
  if ($('move').checked) {
    state.heading -= state.turnRate * dt;
    state.x += Math.sin(state.heading) * state.speed * dt;
    state.z += Math.cos(state.heading) * state.speed * dt;
  }
  $('speedv').textContent = state.speed.toFixed(1);
  $('turnv').textContent = state.turnRate.toFixed(2);
}

function placeCamera(mode) {
  const h = horse.object;
  const f = new THREE.Vector3(Math.sin(state.heading), 0, Math.cos(state.heading));
  const r = new THREE.Vector3(-f.z, 0, f.x);
  const c = h.position.clone().addScaledVector(f, -0.7); // body centre
  const presets = {
    side: [
      r
        .clone()
        .multiplyScalar(-6)
        .add(new THREE.Vector3(0, 1.3, 0)),
      new THREE.Vector3(0, 1.05, 0),
    ],
    sideR: [
      r
        .clone()
        .multiplyScalar(6)
        .add(new THREE.Vector3(0, 1.3, 0)),
      new THREE.Vector3(0, 1.05, 0),
    ],
    front: [
      f
        .clone()
        .multiplyScalar(5.5)
        .addScaledVector(r, -1.5)
        .add(new THREE.Vector3(0, 1.6, 0)),
      new THREE.Vector3(0, 1.2, 0),
    ],
    back: [
      f
        .clone()
        .multiplyScalar(-5.5)
        .addScaledVector(r, 1.2)
        .add(new THREE.Vector3(0, 2.2, 0)),
      new THREE.Vector3(0, 1.3, 0),
    ],
    threequarter: [
      f
        .clone()
        .multiplyScalar(3.5)
        .addScaledVector(r, -4)
        .add(new THREE.Vector3(0, 1.9, 0)),
      new THREE.Vector3(0, 1.1, 0),
    ],
    head: [
      f
        .clone()
        .multiplyScalar(2.75)
        .addScaledVector(r, -0.45)
        .add(new THREE.Vector3(0, 1.95, 0)),
      f
        .clone()
        .multiplyScalar(1.5)
        .add(new THREE.Vector3(0, 1.82, 0)),
    ],
    headSide: [
      f
        .clone()
        .multiplyScalar(1.5)
        .addScaledVector(r, -1.5)
        .add(new THREE.Vector3(0, 1.95, 0)),
      f
        .clone()
        .multiplyScalar(1.45)
        .add(new THREE.Vector3(0, 1.8, 0)),
    ],
    top: [
      f
        .clone()
        .multiplyScalar(-0.5)
        .add(new THREE.Vector3(0, 7, 0)),
      new THREE.Vector3(0, 1, 0),
    ],
    rider: [
      f
        .clone()
        .multiplyScalar(-4)
        .addScaledVector(r, -1.5)
        .add(new THREE.Vector3(0, 2.6, 0)),
      new THREE.Vector3(0, 1.6, 0),
    ],
  };
  const p = presets[mode] || presets.threequarter;
  camera.position.copy(c).add(p[0]);
  controls.target.copy(c).add(p[1]);
  camera.lookAt(controls.target);
}
const camMode = params.get('cam');

let last = performance.now();
let fpsAcc = 0;
let fpsN = 0;
let fps = 0;
function frame(dt) {
  simStep(dt);
  horse.update(dt, state);
  horse.object.position.set(state.x, state.y, state.z);
  horse.object.rotation.y = state.heading;
  if (camMode) placeCamera(camMode);
  else if ($('move').checked) {
    // camera follows
    const dp = horse.object.position.clone().sub(prevPos);
    camera.position.add(dp);
    controls.target.add(dp);
  }
  prevPos.copy(horse.object.position);
  sun.position.set(state.x + 6, 10, state.z + 4);
  sun.target.position.set(state.x, 0, state.z);
  controls.update();
  renderer.info.reset();
  renderer.render(scene, camera);
}
const prevPos = new THREE.Vector3();
placeCamera(camMode || 'threequarter');

function loop(now) {
  let dt = Math.min(0.05, (now - last) / 1000);
  last = now;
  fpsAcc += dt;
  fpsN++;
  if (fpsAcc > 0.5) {
    fps = fpsN / fpsAcc;
    fpsAcc = 0;
    fpsN = 0;
  }
  if ($('slow').checked) dt *= 0.2;
  if (!window.__manual) frame(dt);
  const i = renderer.info.render;
  $('info').textContent =
    `Triangles ${i.triangles} · draw calls ${i.calls} · ${fps.toFixed(0)} fps\n` +
    `Footfalls: ${footfalls.join(' ')}`;
  requestAnimationFrame(loop);
}
requestAnimationFrame(loop);

addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});

// interface for automated screenshots
window.demo = {
  get horse() {
    return horse;
  },
  state,
  renderer,
  /** Advance the simulation deterministically (stops the real-time loop). */
  advance(seconds, step = 1 / 60) {
    window.__manual = true;
    for (let t = 0; t < seconds - 1e-9; t += step) frame(step);
    return renderer.info.render;
  },
  action(type) {
    $(type).click();
  },
  set(ids) {
    for (const [k, v] of Object.entries(ids)) {
      const el = $(k);
      if (el.type === 'checkbox') el.checked = v;
      else el.value = v;
      el.dispatchEvent(new Event('change'));
    }
  },
  cam: placeCamera,
};
