// Grazing horses of the paddock (SRT-011): cheap horses without rider and tack that graze, look up
// now and then and walk a few slow steps. The behaviour is in grazing-logic.js (pure, tested).
//
//   const paddock = createGrazingHorses({
//     quality: 'medium',                         // level name or preset (characterDetail)
//     area: { x, z, width, depth, rotation },    // the paddock: a rectangle around (x, z), turned about Y
//     count: 2,
//     coats: ['chestnut', 'bay', 'grey'],        // coats to pick from (default: all)
//     rng,                                       // random numbers (default: seeded)
//     release,                                   // release hook of the GPU epoch (createGpuEpoch)
//     groundY: (x, z) => 0,                      // height of the grass (default: flat)
//   });
//   scene.add(paddock.group);
//   paddock.update(dt);                          // every frame
//   paddock.setQuality('high');                  // geometry detail of the horses changes
//   paddock.dispose();
//
// Level of detail: low and medium use the "low" horse (one draw call, ≈ 3.2 k triangles each),
// high the "medium" horse (one draw call, ≈ 8.4 k triangles). The horses cast no shadows and are
// culled when they are out of view. Wiring is left to the engine.
import * as THREE from 'three';
import { COATS, MARKINGS } from '../../../domain/horse/appearance.js';
import { releaseNow } from '../resilience.js';
import { createRng } from '../textures.js';
import {
  GRAZER_STATES,
  GRAZING,
  createGrazer,
  insideArea,
  stepGrazer,
  toWorld,
} from './grazing-logic.js';
import { createHorse } from './index.js';

/** Detail of the horse model per graphics level (the paddock is far from the camera). */
const HORSE_LEVEL = Object.freeze({ low: 'low', medium: 'low', high: 'medium' });
// Bounding sphere of a horse in its own frame (it moves its head down to the grass, so the sphere
// is larger than the standing horse); the skinned mesh is culled with it
const BOUNDS = Object.freeze({ center: [0, 1.0, 0], radius: 2.3 });
const MIN_SPACING = 2.5; // m between the horses at the start

function levelOf(quality) {
  const name = typeof quality === 'string' ? quality : quality?.characterDetail || quality?.level;
  return HORSE_LEVEL[name] ?? 'low';
}

export function createGrazingHorses({
  quality = 'medium',
  area,
  count = 2,
  coats = COATS,
  rng = createRng(31),
  release = releaseNow,
  groundY = () => 0,
} = {}) {
  const group = new THREE.Group();
  group.name = 'paddock-horses';
  const grazers = [];
  const horses = [];
  let level = levelOf(quality);

  // start positions: spread over the paddock, apart from each other
  const starts = [];
  for (let i = 0; i < count; i++) {
    let best = null;
    let bestGap = -1;
    for (let tries = 0; tries < 12; tries++) {
      const p = toWorld(
        area,
        (rng() - 0.5) * (area.width - 2 * GRAZING.margin),
        (rng() - 0.5) * (area.depth - 2 * GRAZING.margin),
      );
      const gap = Math.min(...starts.map((s) => Math.hypot(s.x - p.x, s.z - p.z)), Infinity);
      if (gap > bestGap) {
        best = p;
        bestGap = gap;
      }
      if (gap >= MIN_SPACING) break;
    }
    starts.push(best);
  }

  starts.forEach((p, i) => {
    const horseRng = createRng(Math.floor(rng() * 1e9));
    const horse = createHorse({
      coat: coats[Math.floor(rng() * coats.length)],
      marking: MARKINGS[Math.floor(rng() * MARKINGS.length)],
      quality: level,
      rider: false,
      tack: false,
      castShadow: false,
      release,
      rng: horseRng,
    });
    horse.object.name = `paddock-horse-${i}`;
    cullByBounds(horse.object);
    group.add(horse.object);
    horses.push(horse);
    grazers.push(createGrazer({ x: p.x, z: p.z, heading: (rng() - 0.5) * 2 * Math.PI, rng }));
  });

  const state = { gait: 'halt', speed: 0, turnRate: 0, y: 0, graze: 0, jump: null };
  function update(dt) {
    const step = Math.min(dt, 0.1);
    grazers.forEach((g, i) => {
      const others = grazers.filter((o) => o !== g);
      stepGrazer(g, step, area, others, rng);
      // keep the horse in the paddock whatever happens
      if (!insideArea(area, g.x, g.z, -0.5)) {
        const back = toWorld(area, 0, 0);
        g.x += (back.x - g.x) * 0.02;
        g.z += (back.z - g.z) * 0.02;
      }
      const horse = horses[i];
      horse.object.position.set(g.x, groundY(g.x, g.z), g.z);
      horse.object.rotation.y = g.heading;
      state.gait = g.speed >= 0.15 ? 'walk' : 'halt';
      state.speed = g.speed;
      state.turnRate = g.turnRate;
      state.graze = g.graze;
      horse.update(step, state);
    });
  }

  return {
    group,
    /** The grazers (state of the behaviour; for tests and debugging). */
    grazers,
    horses,
    update,
    setQuality(next) {
      const wanted = levelOf(next);
      if (wanted === level) return;
      level = wanted;
      for (const horse of horses) horse.setQuality(level);
    },
    dispose() {
      for (const horse of horses) horse.dispose();
      horses.length = 0;
      grazers.length = 0;
      group.removeFromParent();
    },
  };
}

/** Lets the skinned meshes of a horse be culled with a fixed bounding sphere. */
function cullByBounds(object) {
  object.traverse((o) => {
    if (!o.isSkinnedMesh) return;
    o.frustumCulled = true;
    o.boundingSphere = new THREE.Sphere(new THREE.Vector3(...BOUNDS.center), BOUNDS.radius);
  });
}

export { GRAZER_STATES };
