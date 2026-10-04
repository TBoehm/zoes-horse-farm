// Staged quality changes (rule 4): a change of the graphics level during a ride is split into
// small steps that are applied one after the other, a few frames apart, each followed by an async
// shader precompile. Doing everything in one frame (all materials rebuilt, shadow map and drawing
// buffer reallocated, textures uploaded again) can overload the GPU process of a phone or tablet,
// and the browser then takes the WebGL context away
// (https://www.khronos.org/webgl/wiki/HandlingContextLost: "Another page does something that takes
// the GPU too long and the browser or the OS decides to reset the GPU to get control back").
// Pure, no three.js: the planner and the pacing are tested; world.js and engine.js apply the steps.
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';
import { presetFor } from './quality.js';

// Frames between two stages. A technical value (no game play): a few frames give the GPU process
// time to finish the work of the last stage (uploads, shader links) before the next one starts.
const STAGE_GAP_FRAMES = 6;

const fogKey = (p) => (p.fog ? `${p.fog.near}/${p.fog.far}` : 'none');

/**
 * The stages in the order of a downgrade (cheapest lever for the GPU first). `key` reduces a
 * preset to the values the stage is responsible for: the stage is needed when the keys differ.
 * `compile`: the stage changes shaders, so the engine precompiles them before the next stage.
 */
const STAGES = Object.freeze([
  // Dynamic resolution: the first and biggest lever, no shader involved. The engine applies it in
  // the frame that draws again (resizing clears the drawing buffer).
  Object.freeze({
    id: 'pixelRatio',
    compile: false,
    key: (p) => String(p.pixelRatio),
  }),
  // shadow pass, shadow map target (2048² depth texture on "high"), shadow defines of the shaders
  Object.freeze({
    id: 'shadows',
    compile: true,
    key: (p) => `${p.shadows}|${p.shadowMapSize}|${p.shadowCasters ?? ''}`,
  }),
  // material type (Lambert ↔ standard), normal maps, fog and environment map: all of these change
  // the shader programs, so they are one stage with one compile (separate stages would compile
  // intermediate programs that are replaced right away)
  Object.freeze({
    id: 'materials',
    compile: true,
    key: (p) => `${p.material}|${p.normalMaps}|${p.fog !== null}|${p.envMap}`,
  }),
  // geometry and material of horse and rider
  Object.freeze({
    id: 'characters',
    compile: true,
    key: (p) => p.characterDetail,
  }),
  // instance counts and geometry detail of the scenery, its details (flowers, bunting, flower
  // boxes, birds, butterflies, wind) and the fog distance (a uniform). Details that appear for the
  // first time bring shader programs with them (flowers, wings, bunting, swaying trees), so the
  // engine compiles after this stage, too.
  Object.freeze({
    id: 'density',
    compile: true,
    key: (p) =>
      [
        p.envDensity,
        p.envDetail,
        p.grassTufts,
        p.flowers,
        p.decor,
        p.birds,
        p.butterflies,
        p.wind,
        fogKey(p),
      ].join('|'),
  }),
]);

// A level change never re-uploads textures: there is deliberately no anisotropy stage. three.js
// applies `texture.anisotropy` only inside uploadTexture → setTextureParameters (r186
// WebGLTextures.js), so changing it means `needsUpdate` and a full texImage2D + mipmap generation
// of every ground, sand and wood texture. The world applies the anisotropy of the level when a
// ride starts (world.syncAnisotropy) and never in the middle of one.

/** Ids of all stages, in the order of a downgrade. */
export const QUALITY_STAGE_IDS = Object.freeze(STAGES.map((s) => s.id));

const DESCRIPTORS = Object.freeze(
  Object.fromEntries(STAGES.map((s) => [s.id, Object.freeze({ id: s.id, compile: s.compile })])),
);
// A downgrade steps down the resolution first; a climb ends with it (the most expensive step last)
const UP_ORDER = Object.freeze([...QUALITY_STAGE_IDS].reverse());

// A preset object from a level name, or the object itself (a preset fitted to the GPU budget is a
// copy that keeps its `level`)

function rankOf(preset) {
  return GRAPHICS_LEVELS.indexOf(preset?.level);
}

/**
 * Stages that are still needed to reach `to`, when every stage may be at a different level
 * (`applied`: stage id → level name or preset; an interrupted switch leaves some stages behind).
 * The order is the downgrade order, or its reverse when every stage that has to change goes up.
 */
export function planQualityStagesFromState(applied, to) {
  const target = presetFor(to);
  if (!target) return [];
  const targetRank = rankOf(target);
  let goingUp = targetRank >= 0;
  const needed = new Set();
  for (const stage of STAGES) {
    const current = presetFor(applied?.[stage.id]);
    if (current && stage.key(current) === stage.key(target)) continue;
    needed.add(stage.id);
    const currentRank = current ? rankOf(current) : -1;
    if (currentRank < 0 || currentRank >= targetRank) goingUp = false;
  }
  const order = goingUp ? UP_ORDER : QUALITY_STAGE_IDS;
  return order.filter((id) => needed.has(id)).map((id) => DESCRIPTORS[id]);
}

/**
 * Paces the stages: tick() is called once per drawn frame and returns the next stage when at least
 * `gapFrames` frames have passed since the last one (the first stage of a plan on an idle queue
 * comes on the next frame), otherwise null.
 */
export function createStageQueue({ gapFrames = STAGE_GAP_FRAMES } = {}) {
  let list = [];
  let head = 0;
  let frames = gapFrames; // frames since the last stage (capped): a fresh queue is ready
  return {
    /** Replaces the pending stages; the gap since the last stage keeps running. */
    plan(stages) {
      list = [...stages];
      head = 0;
    },
    tick() {
      frames = Math.min(frames + 1, gapFrames);
      if (head >= list.length || frames < gapFrames) return null;
      frames = 0;
      const stage = list[head];
      head += 1;
      return stage;
    },
    clear() {
      list = [];
      head = 0;
    },
    /** Number of stages still to come. */
    get pending() {
      return list.length - head;
    },
  };
}
