import { describe, expect, it } from 'vitest';
import { createCourseMode } from './course-mode.js';
import { COURSES } from '../../domain/course/courses.js';
import { fakeHost } from './test-host.js';

const course = COURSES[0];

const setup = () => ({ mode: createCourseMode({ courseId: course.id }), host: fakeHost() });

/** Crosses a line in its direction through the middle; returns prev/next horse positions. */
function across(line) {
  const mx = (line.a[0] + line.b[0]) / 2;
  const mz = (line.a[1] + line.b[1]) / 2;
  const [dx, dz] = line.dir;
  return { prev: { x: mx - dx, z: mz - dz }, next: { x: mx + dx, z: mz + dz } };
}

function startRide(mode) {
  const { prev, next } = across(course.start);
  mode.run.onLineCross(prev, next, 0);
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
      overTime: false,
      nextLabel: mode.run.nextLabel,
      missingHint: null,
    });
  });

  it('flags the HUD when the allowed time is exceeded', () => {
    const { mode, host } = setup();
    const start = across(course.start);
    mode.update(0.1, { horse: start.next, prev: start.prev }, host);
    mode.update(course.allowedTimeS - 1, { horse: start.next, prev: start.next }, host);
    expect(mode.hudModel().overTime).toBe(false);
    mode.update(2, { horse: start.next, prev: start.next }, host);
    expect(mode.hudModel().overTime).toBe(true);
  });

  it('updates the HUD model during the ride and resets it on restart', () => {
    const { mode, host } = setup();
    const start = across(course.start);
    mode.update(0.5, { horse: start.next, prev: start.prev }, host);
    expect(mode.hudModel().phase).toBe('riding');
    mode.update(0.5, { horse: start.next, prev: start.next }, host);
    expect(mode.hudModel().timeMs).toBeGreaterThan(0);

    mode.onRestart();
    expect(mode.hudModel().phase).toBe('prestart');
    expect(mode.hudModel().timeMs).toBe(0);
  });

  it('highlights the obstacle that is due and marks the finish after the last one', () => {
    const { mode, host } = setup();
    expect(mode.highlight).toEqual({
      elementId: course.obstacles[0].elements[0].id,
      number: course.obstacles[0].number,
    });
    expect(mode.finishMarked).toBe(false);
    startRide(mode);
    for (const o of course.obstacles) {
      for (const el of o.elements) {
        mode.onEvents([{ type: 'landed', elementId: el.id, dir: 1, knocked: false }], host);
      }
    }
    expect(mode.highlight).toBeNull();
    expect(mode.finishMarked).toBe(true);
  });

  it('counts a knockdown as a fault and asks for feedback', () => {
    const { mode, host } = setup();
    startRide(mode);
    const id = course.obstacles[0].elements[0].id;
    mode.onEvents([{ type: 'landed', elementId: id, dir: 1, knocked: true }], host);
    expect(host.calls.feedback).toContain('feedback.knockdown');
    expect(mode.hudModel().faults).toBeGreaterThan(0);
  });

  it('asks for a delayed rebuild of knocked rails that are not scored', () => {
    const { mode, host } = setup();
    // ride not started yet: the knockdown is not scored, rails come back after a delay
    const id = course.obstacles[0].elements[0].id;
    mode.onEvents([{ type: 'landed', elementId: id, dir: 1, knocked: true }], host);
    expect(host.calls.rebuildIn).toHaveLength(1);
    expect(host.calls.rebuildIn[0][0]).toBe(id);
  });

  it('counts a refusal and gives feedback', () => {
    const { mode, host } = setup();
    startRide(mode);
    const id = course.obstacles[0].elements[0].id;
    mode.onEvents([{ type: 'refusal', elementId: id, dir: 1, reason: 'gait' }], host);
    expect(host.calls.feedback).toEqual(['feedback.refusal']);
    expect(mode.run.faults.refusals).toBe(1);
  });

  it('reports the finished ride with the result and the results screen, without saving', () => {
    const { mode, host } = setup();
    startRide(mode);
    for (const o of course.obstacles) {
      mode.onEvents(
        [{ type: 'landed', elementId: o.elements[0].id, dir: 1, knocked: false }],
        host,
      );
    }
    const finish = across(course.finish);
    const out = mode.update(0.1, { horse: finish.next, prev: finish.prev }, host);
    expect(out.finished.screen).toBe('results');
    expect(out.finished.result).toBe(mode.run.result);
    expect(out.finished.params).toEqual({ courseId: course.id });
  });

  it('does not report a finish while riding', () => {
    const { mode, host } = setup();
    const start = across(course.start);
    expect(mode.update(0.1, { horse: start.next, prev: start.prev }, host)).toBeNull();
  });

  it('shows the jump aid at the current element only when enabled', () => {
    const { mode } = setup();
    expect(mode.aidTarget({ settings: { aidCourse: false } })).toBeNull();
    startRide(mode);
    expect(mode.aidTarget({ settings: { aidCourse: true } })).toEqual({
      elementId: mode.run.current.elementId,
      dir: 1,
    });
  });
});
