// A staged level change must never hold more on the GPU than the larger of the two levels (rule 4,
// SRT-013): what the target level drops is freed BEFORE anything new is compiled or uploaded. The
// stages run in the engine's order (planQualityStagesFromState), each followed by a compile and
// a frame, against the stand-in for the GPU in tests/support/gpu-tracker.js, which follows
// three.js's bookkeeping (a material keeps all its programs until it is disposed).
import { beforeAll, describe, expect, it } from 'vitest';
import * as THREE from 'three';
import { installFakeCanvas } from '../../../tests/support/fake-canvas.js';
import { createGpuTracker } from '../../../tests/support/gpu-tracker.js';
import { createWorld } from './world.js';
import { createHorse } from './horse/index.js';
import { DEFAULT_APPEARANCE } from '../../domain/horse/appearance.js';
import { QUALITY_PRESETS } from './quality.js';
import { planQualityStagesFromState, QUALITY_STAGE_IDS } from './quality-stages.js';

const LEVELS = ['low', 'medium', 'high'];
const camera = new THREE.PerspectiveCamera();

const el = (id, kind, height, x, z, rot = 0, spread = 0) => ({
  id,
  kind,
  height,
  spread,
  x,
  z,
  rot,
});
const COURSE = [
  { number: 1, elements: [el('a', 'vertical', 0.8, -10, -20)] },
  { number: 2, elements: [el('b', 'oxer', 1.0, 10, -20, 0, 1.2)] },
  { number: 3, elements: [el('c', 'cross', 0.6, 10, 0, Math.PI)] },
];

/** The preset of a level as the engine uses it, without the environment map (needs real GL). */
const preset = (level) => ({ ...QUALITY_PRESETS[level], envMap: false });

/**
 * A world with a horse at a level, and what the engine does around it: the stages of a change,
 * each followed by a compile (what the engine's precompile does) and a drawn frame.
 */
function createRig(startLevel) {
  const renderer = { shadowMap: { enabled: false }, capabilities: { getMaxAnisotropy: () => 8 } };
  const start = preset(startLevel);
  const world = createWorld(renderer, { quality: start });
  world.setObstacles(COURSE, { flags: true });
  const horse = createHorse({ ...DEFAULT_APPEARANCE, quality: start.characterDetail });
  world.scene.add(horse.object);
  const tracker = createGpuTracker({ scene: world.scene, renderer });
  const applied = Object.fromEntries(QUALITY_STAGE_IDS.map((id) => [id, start]));
  const frame = () => {
    world.update(0.016, camera);
    tracker.compile(world.compileRoot);
  };
  frame();

  return {
    world,
    tracker,
    /** Runs a whole change; returns the stages it took. */
    change(level) {
      const target = preset(level);
      const stages = planQualityStagesFromState(applied, target);
      for (const stage of stages) {
        if (stage.id === 'characters') horse.setQuality(target.characterDetail);
        else if (stage.id !== 'pixelRatio') world.applyQualityStage(stage.id, target);
        for (const covered of stage.covers) applied[covered] = target;
        frame();
        frame();
      }
      return stages.map((s) => s.id);
    },
  };
}

/** Counts of a world that rests at a level. */
function settled(level) {
  const rig = createRig(level);
  const counts = rig.tracker.snapshot();
  rig.world.dispose();
  return counts;
}

describe('a staged level change', () => {
  const resting = {};

  beforeAll(() => {
    installFakeCanvas();
    for (const level of LEVELS) resting[level] = settled(level);
  });

  it('steps down without ever holding more than before the change', () => {
    for (const [from, to] of [
      ['high', 'medium'],
      ['medium', 'low'],
      ['high', 'low'],
    ]) {
      const rig = createRig(from);
      const before = rig.tracker.snapshot();
      rig.tracker.resetPeak();
      rig.change(to);
      const peak = rig.tracker.peak;
      for (const key of ['programs', 'materials', 'geometries', 'instanced']) {
        expect(peak[key], `${from} → ${to}: ${key}`).toBeLessThanOrEqual(before[key]);
      }
      rig.world.dispose();
    }
  });

  it('steps up without ever holding more than the target level needs', () => {
    for (const [from, to] of [
      ['low', 'medium'],
      ['medium', 'high'],
      ['low', 'high'],
    ]) {
      const rig = createRig(from);
      rig.tracker.resetPeak();
      rig.change(to);
      const peak = rig.tracker.peak;
      const wanted = resting[to];
      for (const key of ['programs', 'materials', 'geometries', 'instanced']) {
        expect(peak[key], `${from} → ${to}: ${key}`).toBeLessThanOrEqual(wanted[key]);
      }
      rig.world.dispose();
    }
  });

  // nine world builds: slower than the default timeout when the whole suite runs in parallel
  it('ends with exactly what a world that started at the target level holds (nothing leaks)', () => {
    for (const from of LEVELS) {
      for (const to of LEVELS) {
        const rig = createRig(from);
        rig.change(to);
        expect(rig.tracker.snapshot(), `${from} → ${to}`).toEqual(resting[to]);
        rig.world.dispose();
      }
    }
  }, 30_000);

  it('survives a round trip high → low → high and comes back to the same counts', () => {
    const rig = createRig('high');
    rig.change('low');
    rig.change('high');
    expect(rig.tracker.snapshot()).toEqual(resting.high);
    rig.world.dispose();
  });

  it('frees the programs of the old materials in the stage that swaps them', () => {
    const rig = createRig('medium');
    const before = rig.tracker.snapshot().programs;
    const stages = rig.change('low');
    // the shadows and the materials go in one stage, so nothing is compiled twice
    expect(stages).toContain('shadowsAndMaterials');
    expect(rig.tracker.snapshot().programs).toBeLessThan(before + 1);
    // the standard programs are gone: only Lambert, basic and shader programs are left
    expect(rig.tracker.programKeys().some((key) => key.startsWith('MeshStandardMaterial'))).toBe(
      false,
    );
    rig.world.dispose();
  });

  it('gives the GPU buffers of the hidden details back (flowers, birds, tufts, boxes ...)', () => {
    const rig = createRig('high');
    const names = ['flowers', 'birds', 'butterflies', 'grass-tufts', 'planters'];
    const meshes = names.map((name) => rig.world.scene.getObjectByName(name));
    for (const mesh of meshes) {
      expect(rig.tracker.holds(mesh.geometry), `${mesh.name} geometry before`).toBe(true);
      expect(rig.tracker.holds(mesh), `${mesh.name} instances before`).toBe(true);
    }
    rig.change('medium');
    for (const mesh of meshes) {
      expect(mesh.visible, mesh.name).toBe(false);
      expect(rig.tracker.holds(mesh.geometry), `${mesh.name} geometry after`).toBe(false);
      expect(rig.tracker.holds(mesh), `${mesh.name} instances after`).toBe(false);
    }
    rig.world.dispose();
  });

  it('gives the buffers of the high tree models back when the scenery goes to the low ones', () => {
    const rig = createRig('high');
    const trees = rig.world.scene.getObjectByName('trees-deciduous');
    const highModel = trees.geometry;
    expect(rig.tracker.holds(highModel)).toBe(true);
    rig.change('low');
    expect(trees.geometry).not.toBe(highModel);
    expect(rig.tracker.holds(highModel)).toBe(false);
    rig.world.dispose();
  });

  it('does not compile the programs of hidden meshes (the compile root yields visible ones)', () => {
    const rig = createRig('low');
    const seen = new Set();
    rig.world.compileRoot.traverse((o) => o.name && seen.add(o.name));
    for (const name of ['flowers', 'birds', 'butterflies', 'bunting', 'planters']) {
      expect(seen.has(name), name).toBe(false);
    }
    expect(seen.has('terrain')).toBe(true);
    // the lights come from the target scene: yielded again, they would count twice in the
    // programs (WebGLRenderer.compile walks `traverseVisible` of a scene that is not the target)
    let lights = 0;
    rig.world.compileRoot.traverseVisible((o) => o.isLight && (lights += 1));
    expect(lights).toBe(0);
    rig.world.dispose();
  });
});
