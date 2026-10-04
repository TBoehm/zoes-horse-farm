// Hoof dust (SRT-011): soft puffs that rise from the sand under the hooves and drift away.
//
//   const dust = createDust({ quality: 'medium', release });   // release: see createGpuEpoch
//   scene.add(dust.object);                 // one THREE.Points (one draw call), invisible while idle
//   dust.emit(x, y, z, strength);           // world position of a footfall, strength 0..1
//   dust.update(dt);                        // every frame
//   dust.setQuality('high');                // rebuilds the pool (releases the old buffers)
//   dust.dispose();
//
// Wiring: the horse reports footfalls (horse.footfalls after horse.update(), 'step' and 'landing'
// events with a strength, see horse/index.js); for each one call
// `dust.emit(...horse.footfallWorld(event) components, event.strength)` once the horse object has
// been placed for the frame. Dust belongs to the sand, so the caller should only emit when the
// hoof is on the sand (not on grass).
//
// Quality: low has no dust at all (the object is an empty group: no geometry, no draw call);
// medium and high use a fixed pool (no allocation per frame). The puffs are round soft sprites made
// in the fragment shader (no textures, rule 2).
import * as THREE from 'three';
import { releaseNow } from './resilience.js';
import { createRng } from './textures.js';

/** Technical constants (pool size and look); nothing here is a game value. */
const POOL = Object.freeze({ low: 0, medium: 36, high: 90 });
// share of the puffs that an emit creates per level (low would be 0)
const EMIT_SHARE = Object.freeze({ low: 0, medium: 0.7, high: 1 });
const PUFF = Object.freeze({
  count: [1, 4], // puffs per emit for strength 0 … 1
  jitter: 0.1, // m, start position scatter
  speed: [0.3, 1.5], // m/s, outwards (scaled by strength)
  rise: [0.5, 1.3], // m/s, upwards at the start (scaled by strength)
  life: [0.5, 1.2], // s
  size: [0.18, 0.7], // m, grows from the first to the second value
  alpha: 0.6, // peak opacity (scaled by strength)
  drag: 2.2, // 1/s
  buoyancy: 0.25, // m/s², slow rise of the cloud
  gravity: 1.2, // m/s², pulls the rising dust back down
  maxDt: 0.1, // s
});
const COLOR = 0xe8dcc2; // dry sand dust: lighter than the footing, so that it shows against it

/**
 * Particle pool as plain arrays (pure; no three.js). `rng` gives numbers in [0, 1).
 * Slot i has position (x, y, z), velocity, age and life; a dead slot has life 0.
 */
export function createDustPool(capacity, rng, level = 'high') {
  const n = capacity;
  const pool = {
    capacity: n,
    pos: new Float32Array(n * 3),
    vel: new Float32Array(n * 3),
    age: new Float32Array(n),
    life: new Float32Array(n), // 0 = free
    size: new Float32Array(n), // current size (m)
    alpha: new Float32Array(n), // current opacity
    peak: new Float32Array(n), // peak opacity of the puff
    next: 0, // ring buffer: the next slot to (re)use
    alive: 0,
  };
  const share = EMIT_SHARE[level] ?? 1;
  const between = ([a, b]) => a + (b - a) * rng();

  /** Starts the puffs of one footfall; the oldest puffs are overwritten when the pool is full. */
  pool.emit = (x, y, z, strength) => {
    if (n === 0) return 0;
    const s = Math.max(0, Math.min(1, strength));
    if (s <= 0) return 0;
    const count = Math.max(
      1,
      Math.round((PUFF.count[0] + (PUFF.count[1] - PUFF.count[0]) * s) * share),
    );
    for (let c = 0; c < count; c++) {
      const i = pool.next;
      pool.next = (i + 1) % n;
      const a = rng() * Math.PI * 2;
      const out = between(PUFF.speed) * (0.35 + 0.65 * s);
      pool.pos[i * 3] = x + (rng() - 0.5) * 2 * PUFF.jitter;
      pool.pos[i * 3 + 1] = y + 0.03 + rng() * 0.05;
      pool.pos[i * 3 + 2] = z + (rng() - 0.5) * 2 * PUFF.jitter;
      pool.vel[i * 3] = Math.cos(a) * out;
      pool.vel[i * 3 + 1] = between(PUFF.rise) * (0.3 + 0.7 * s);
      pool.vel[i * 3 + 2] = Math.sin(a) * out;
      pool.age[i] = 0;
      pool.life[i] = between(PUFF.life) * (0.6 + 0.4 * s);
      pool.peak[i] = PUFF.alpha * (0.4 + 0.6 * s);
      pool.size[i] = PUFF.size[0];
      pool.alpha[i] = 0;
    }
    return count;
  };

  /** Moves the puffs on; returns the number of living puffs. */
  pool.update = (dt) => {
    const h = Math.max(0, Math.min(dt, PUFF.maxDt));
    let alive = 0;
    const damp = Math.exp(-PUFF.drag * h);
    for (let i = 0; i < n; i++) {
      if (pool.life[i] <= 0) {
        pool.alpha[i] = 0;
        continue;
      }
      pool.age[i] += h;
      const t = pool.age[i] / pool.life[i];
      if (t >= 1) {
        pool.life[i] = 0;
        pool.alpha[i] = 0;
        pool.size[i] = 0;
        continue;
      }
      alive++;
      const k = i * 3;
      pool.vel[k] *= damp;
      pool.vel[k + 2] *= damp;
      pool.vel[k + 1] = pool.vel[k + 1] * damp - PUFF.gravity * h * (1 - t) + PUFF.buoyancy * h * t;
      pool.pos[k] += pool.vel[k] * h;
      pool.pos[k + 1] = Math.max(0.02, pool.pos[k + 1] + pool.vel[k + 1] * h);
      pool.pos[k + 2] += pool.vel[k + 2] * h;
      pool.size[i] = PUFF.size[0] + (PUFF.size[1] - PUFF.size[0]) * Math.sqrt(t);
      // quick fade in (no popping into view), long fade out
      pool.alpha[i] = pool.peak[i] * Math.min(1, t / 0.08) * Math.pow(1 - t, 1.6);
    }
    pool.alive = alive;
    return alive;
  };
  return pool;
}

const VERTEX = /* glsl */ `
attribute float aSize;
attribute float aAlpha;
uniform float uPixelScale;
varying float vAlpha;
void main() {
  vAlpha = aAlpha;
  vec4 mv = modelViewMatrix * vec4(position, 1.0);
  gl_PointSize = clamp(aSize * uPixelScale / max(-mv.z, 0.1), 0.0, 256.0);
  gl_Position = projectionMatrix * mv;
}
`;

const FRAGMENT = /* glsl */ `
uniform vec3 uColor;
varying float vAlpha;
void main() {
  // round, soft edge (a little denser in the middle)
  float d = length(gl_PointCoord - 0.5) * 2.0;
  float a = (1.0 - smoothstep(0.35, 1.0, d)) * vAlpha;
  if (a < 0.004) discard;
  gl_FragColor = vec4(uColor, a);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
}
`;

/** Builds the Points object for a pool; returns { points, geometry, material, sync }. */
function buildPoints(pool) {
  const geometry = new THREE.BufferGeometry();
  const dyn = (array, size) =>
    new THREE.BufferAttribute(array, size).setUsage(THREE.DynamicDrawUsage);
  geometry.setAttribute('position', dyn(pool.pos, 3));
  geometry.setAttribute('aSize', dyn(pool.size, 1));
  geometry.setAttribute('aAlpha', dyn(pool.alpha, 1));
  const material = new THREE.ShaderMaterial({
    uniforms: {
      uColor: { value: new THREE.Color(COLOR) },
      uPixelScale: { value: 600 },
    },
    vertexShader: VERTEX,
    fragmentShader: FRAGMENT,
    transparent: true,
    depthWrite: false,
    fog: false,
  });
  const points = new THREE.Points(geometry, material);
  points.name = 'hoof-dust';
  points.frustumCulled = false;
  points.visible = false;
  points.renderOrder = 2;
  // pixels per metre at distance 1 for the camera that renders (size attenuation)
  points.onBeforeRender = (renderer, scene, camera) => {
    const h = renderer.getDrawingBufferSize(sizeTmp).y;
    material.uniforms.uPixelScale.value = camera.isPerspectiveCamera
      ? h / (2 * Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2))
      : h;
  };
  return { points, geometry, material };
}
const sizeTmp = new THREE.Vector2();

export function createDust({ quality = 'medium', release = releaseNow, rng = createRng(21) } = {}) {
  const object = new THREE.Group();
  object.name = 'dust';
  let level = quality in POOL ? quality : 'medium';
  let pool = null;
  let built = null;

  function build() {
    if (built) {
      object.remove(built.points);
      release(built.geometry);
      release(built.material);
      built = null;
    }
    pool = null;
    const capacity = POOL[level];
    if (capacity <= 0) return; // low: no geometry, no draw call
    pool = createDustPool(capacity, rng, level);
    built = buildPoints(pool);
    object.add(built.points);
  }
  build();

  return {
    object,
    /** Pool size of the current level (0 = off). */
    get capacity() {
      return pool ? pool.capacity : 0;
    },
    /** Number of puffs alive (for tests / debugging). */
    get alive() {
      if (!pool) return 0;
      let n = 0;
      for (let i = 0; i < pool.capacity; i++) if (pool.life[i] > 0) n++;
      return n;
    },
    emit(x, y, z, strength = 0.5) {
      if (!pool) return;
      pool.emit(x, y, z, strength);
      built.points.visible = true;
    },
    update(dt) {
      if (!pool || !built) return;
      const alive = pool.update(dt);
      // nothing to draw: no draw call
      built.points.visible = alive > 0;
      if (alive > 0) {
        built.geometry.attributes.position.needsUpdate = true;
        built.geometry.attributes.aSize.needsUpdate = true;
        built.geometry.attributes.aAlpha.needsUpdate = true;
      }
    },
    setQuality(next) {
      if (!(next in POOL) || next === level) return;
      level = next;
      build();
    },
    dispose() {
      if (built) {
        object.remove(built.points);
        // through `release`, so that objects of a lost context are not freed with GL calls
        release(built.geometry);
        release(built.material);
        built = null;
      }
      pool = null;
      object.removeFromParent();
    },
  };
}
