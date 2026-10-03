import { describe, expect, it } from 'vitest';
import { createCourseMode } from './course-mode.js';
import { COURSES } from '../../domain/course/courses.js';
import { fakeApi, fakeStore, fixedClock } from './test-ports.js';

const course = COURSES[0];

function setup(settings) {
  const store = fakeStore(settings);
  const mode = createCourseMode({ store, clock: fixedClock() }, { courseId: course.id });
  return { store, mode, api: fakeApi() };
}

/** Crosses a line in its direction through the middle; returns prev/next horse positions. */
function across(line) {
  const mx = (line.a[0] + line.b[0]) / 2;
  const mz = (line.a[1] + line.b[1]) / 2;
  const [dx, dz] = line.dir;
  return { prev: { x: mx - dx, z: mz - dz }, next: { x: mx + dx, z: mz + dz } };
}

describe('course mode', () => {
  it('exposes the course as data: lines with label keys, quit target', () => {
    const { mode } = setup();
    expect(mode.id).toBe('course');
    expect(mode.obstacles).toBe(course.obstacles);
    expect(mode.lines).toEqual({
      start: course.start,
      finish: course.finish,
      labelKeys: { start: 'prestart.legendStart', finish: 'prestart.legendFinish' },
    });
    expect(mode.quitScreen).toBe('courseSelect');
    expect(mode.quitLabelKey).toBe('pause.toSelect');
  });

  it('has a plain-data HUD model before the start', () => {
    const { mode } = setup();
    expect(mode.hudModel()).toEqual({
      phase: 'prestart',
      timeMs: 0,
      allowedS: course.allowedTimeS,
      faults: 0,
      nextLabel: mode.run.nextLabel,
      missingHint: null,
    });
  });

  it('updates the HUD model during the ride and resets it on restart', () => {
    const { mode, api } = setup();
    const start = across(course.start);
    api.sim.horse = start.next;
    mode.update(0.5, api, start.prev);
    expect(mode.hudModel().phase).toBe('riding');
    mode.update(0.5, api, start.next);
    expect(mode.hudModel().timeMs).toBeGreaterThan(0);

    mode.onRestart();
    expect(mode.hudModel().phase).toBe('prestart');
    expect(mode.hudModel().timeMs).toBe(0);
  });

  it('counts a knockdown as a fault and asks for feedback and a rebuild', () => {
    const { mode, api } = setup();
    mode.run.onLineCross(...Object.values(across(course.start)), 0);
    const id = course.obstacles[0].elements[0].id;
    mode.onEvents([{ type: 'landed', elementId: id, dir: 1, knocked: true }], api);
    expect(api.calls.feedback).toContain('feedback.knockdown');
    expect(mode.hudModel().faults).toBeGreaterThan(0);
  });

  it('hands the finished ride to the host with the clock time for badges', () => {
    const { mode, api, store } = setup();
    mode.run.onLineCross(...Object.values(across(course.start)), 0);
    for (const o of course.obstacles) {
      mode.onEvents([{ type: 'landed', elementId: o.elements[0].id, dir: 1, knocked: false }], api);
    }
    const finish = across(course.finish);
    api.sim.horse = finish.next;
    mode.update(0.1, api, finish.prev);

    expect(api.calls.finish).toHaveLength(1);
    const { screen, params } = api.calls.finish[0];
    expect(screen).toBe('results');
    expect(params.courseId).toBe(course.id);
    expect(params.result).toBe(mode.run.result);
    expect(params.isNewBest).toBe(true);
    expect(store.data.progress.finishedRides).toBe(1);
    expect(store.data.progress.courses[String(course.id)]).toBeDefined();
    for (const id of params.awarded) {
      expect(store.data.progress.badges[id]).toBe('2026-01-02T03:04:05.000Z');
    }
  });

  it('shows the jump aid at the current element only when enabled', () => {
    expect(setup({ aidCourse: false }).mode.aidTarget()).toBeNull();
    const { mode } = setup({ aidCourse: true });
    mode.run.onLineCross(...Object.values(across(course.start)), 0);
    expect(mode.aidTarget()).toEqual({ elementId: mode.run.current.elementId, dir: 1 });
  });
});
