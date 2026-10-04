import { describe, expect, it } from 'vitest';
import {
  createStageQueue,
  planQualityStagesFromState,
  QUALITY_STAGE_IDS,
} from './quality-stages.js';
import { QUALITY_PRESETS } from './quality.js';
import { GRAPHICS_LEVELS } from '../../application/graphics-levels.js';

const ids = (plan) => plan.map((s) => s.id);
/** The state of an engine whose stages all are at `levelOrPreset`. */
const applied = (levelOrPreset) =>
  Object.fromEntries(QUALITY_STAGE_IDS.map((id) => [id, levelOrPreset]));
/** Stages for a change from one level (or preset) to another, as the engine plans it. */
const planChange = (from, to) => planQualityStagesFromState(applied(from), to);

describe('planning a change between two levels', () => {
  it('plans nothing when the level stays the same', () => {
    for (const level of GRAPHICS_LEVELS) expect(planChange(level, level)).toEqual([]);
  });

  it('steps down resolution first, then shadows, materials, characters and scenery', () => {
    expect(ids(planChange('medium', 'low'))).toEqual([
      'pixelRatio',
      'shadows',
      'materials',
      'characters',
      'density',
    ]);
    expect(ids(planChange('high', 'low'))).toEqual(ids(planChange('medium', 'low')));
  });

  it('steps up in the opposite order: resolution comes last', () => {
    expect(ids(planChange('low', 'medium'))).toEqual([
      'density',
      'characters',
      'materials',
      'shadows',
      'pixelRatio',
    ]);
  });

  it('leaves out stages whose values do not differ (medium ↔ high keeps the materials)', () => {
    expect(ids(planChange('high', 'medium'))).toEqual([
      'pixelRatio',
      'shadows',
      'characters',
      'density',
    ]);
    expect(ids(planChange('medium', 'high'))).not.toContain('materials');
  });

  it('accepts preset objects as well as level names', () => {
    expect(ids(planChange(QUALITY_PRESETS.high, QUALITY_PRESETS.low))).toEqual(
      ids(planChange('high', 'low')),
    );
  });

  it('plans a preset fitted to the budget like its level: the copy keeps the direction', () => {
    const capped = { ...QUALITY_PRESETS.high, pixelRatio: 1.25, shadowMapSize: 1024 };
    expect(ids(planChange('low', capped))).toEqual(ids(planChange('low', 'high')));
    expect(ids(planChange(capped, 'low'))).toEqual(ids(planChange('high', 'low')));
  });

  it('a capped pixel ratio is a stage of its own, also at the same level', () => {
    const capped = { ...QUALITY_PRESETS.high, pixelRatio: 1.25 };
    expect(ids(planChange('high', capped))).toEqual(['pixelRatio']);
    expect(ids(planChange(capped, 'high'))).toEqual(['pixelRatio']);
  });

  it('marks the stages that change shaders for a precompile', () => {
    const byId = Object.fromEntries(planChange('medium', 'low').map((s) => [s.id, s]));
    expect(byId.pixelRatio.compile).toBe(false);
    expect(byId.shadows.compile).toBe(true);
    expect(byId.materials.compile).toBe(true);
    expect(byId.characters.compile).toBe(true);
    expect(byId.density.compile).toBe(false);
  });

  it('never touches the textures: there is no anisotropy stage', () => {
    for (const from of GRAPHICS_LEVELS) {
      for (const to of GRAPHICS_LEVELS) {
        expect(ids(planChange(from, to))).not.toContain('anisotropy');
        for (const id of ids(planChange(from, to))) {
          expect(QUALITY_STAGE_IDS).toContain(id);
        }
      }
    }
  });

  it('plans every stage at most once, and none of them for an unknown target', () => {
    const plan = ids(planChange('high', 'low'));
    expect(new Set(plan).size).toBe(plan.length);
    expect(planChange('low', undefined)).toEqual([]);
  });

  it('returns frozen descriptors (shared constants, never changed by callers)', () => {
    const plan = planChange('medium', 'low');
    expect(Object.isFrozen(plan[0])).toBe(true);
  });
});

describe('planQualityStagesFromState', () => {
  it('plans only the stages that are still behind after an interrupted switch', () => {
    const state = { ...applied('medium'), pixelRatio: 'low', shadows: 'low' };
    expect(ids(planQualityStagesFromState(state, 'low'))).toEqual([
      'materials',
      'characters',
      'density',
    ]);
  });

  it('turns around when the target changes back during a switch (pending stages fall away)', () => {
    const state = { ...applied('medium'), pixelRatio: 'low' };
    // target is medium again: only the resolution has to go back
    expect(ids(planQualityStagesFromState(state, 'medium'))).toEqual(['pixelRatio']);
  });

  it('plans nothing when everything already is at the target', () => {
    expect(planQualityStagesFromState(applied('low'), 'low')).toEqual([]);
  });

  it('uses the upward order when every stage that differs has to go up', () => {
    expect(ids(planQualityStagesFromState(applied('low'), 'high'))).toEqual([
      'density',
      'characters',
      'materials',
      'shadows',
      'pixelRatio',
    ]);
  });

  it('a missing entry counts as not applied yet', () => {
    expect(ids(planQualityStagesFromState({}, 'low')).length).toBeGreaterThan(0);
  });
});

describe('createStageQueue', () => {
  const stage = (id) => ({ id, compile: false });

  it('hands out the first stage on the next frame and the others after the gap', () => {
    const queue = createStageQueue({ gapFrames: 3 });
    queue.plan([stage('a'), stage('b'), stage('c')]);
    expect(queue.pending).toBe(3);
    expect(queue.tick().id).toBe('a');
    expect(queue.tick()).toBeNull();
    expect(queue.tick()).toBeNull();
    expect(queue.tick().id).toBe('b');
    expect(queue.tick()).toBeNull();
    expect(queue.tick()).toBeNull();
    expect(queue.tick().id).toBe('c');
    expect(queue.pending).toBe(0);
    expect(queue.tick()).toBeNull();
  });

  it('does nothing while it is empty', () => {
    const queue = createStageQueue({ gapFrames: 2 });
    for (let i = 0; i < 5; i += 1) expect(queue.tick()).toBeNull();
    expect(queue.pending).toBe(0);
  });

  it('a new plan replaces the pending stages but keeps the gap since the last stage', () => {
    const queue = createStageQueue({ gapFrames: 3 });
    queue.plan([stage('a'), stage('b')]);
    expect(queue.tick().id).toBe('a');
    queue.plan([stage('x'), stage('y')]);
    expect(queue.pending).toBe(2);
    expect(queue.tick()).toBeNull(); // the gap after "a" is still running
    expect(queue.tick()).toBeNull();
    expect(queue.tick().id).toBe('x');
  });

  it('a plan on an idle queue starts at once, however long ago the last stage ran', () => {
    const queue = createStageQueue({ gapFrames: 4 });
    queue.plan([stage('a')]);
    expect(queue.tick().id).toBe('a');
    for (let i = 0; i < 10; i += 1) queue.tick();
    queue.plan([stage('b')]);
    expect(queue.tick().id).toBe('b');
  });

  it('clear drops everything that is pending', () => {
    const queue = createStageQueue({ gapFrames: 1 });
    queue.plan([stage('a'), stage('b')]);
    queue.clear();
    expect(queue.pending).toBe(0);
    expect(queue.tick()).toBeNull();
  });

  it('works with a gap of 0 (one stage per frame)', () => {
    const queue = createStageQueue({ gapFrames: 0 });
    queue.plan([stage('a'), stage('b')]);
    expect(queue.tick().id).toBe('a');
    expect(queue.tick().id).toBe('b');
  });

  it('copies the plan: changing the array afterwards does not change the queue', () => {
    const queue = createStageQueue({ gapFrames: 0 });
    const plan = [stage('a')];
    queue.plan(plan);
    plan.length = 0;
    expect(queue.pending).toBe(1);
  });
});
