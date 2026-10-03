import { describe, expect, it } from 'vitest';
import { createCourseMode } from './course-mode.js';
import { COURSES } from '../../domain/course/courses.js';
import { toCentiseconds } from '../../domain/course/scoring.js';
import { fakeHost } from '../../../tests/support/test-host.js';

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
      timeCs: 0,
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

  it('provides the time in hundredths (truncated) and flags over-time by the same rule', () => {
    const { mode, host } = setup();
    const start = across(course.start);
    mode.update(0.1, { horse: start.next, prev: start.prev }, host);
    mode.update(1.239, { horse: start.next, prev: start.next }, host);
    expect(mode.hudModel().timeCs).toBe(toCentiseconds(mode.run.timeMs));
    expect(mode.hudModel().timeCs).toBe(123);
    // exactly the allowed time is not over; one hundredth later is
    mode.update(course.allowedTimeS - 1.239, { horse: start.next, prev: start.next }, host);
    expect(mode.hudModel().overTime).toBe(false);
    mode.update(0.0101, { horse: start.next, prev: start.next }, host);
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

  it('gives knockdown feedback only for scored knockdowns', () => {
    const { mode, host } = setup();
    startRide(mode);
    // second obstacle is not due yet: this landing is not scored
    const wrong = course.obstacles[1].elements[0].id;
    mode.onEvents([{ type: 'landed', elementId: wrong, dir: 1, knocked: true }], host);
    expect(host.calls.feedback).not.toContain('feedback.knockdown');
    expect(mode.hudModel().faults).toBe(0);
  });

  it('says "wrong obstacle" for an unscored landing during the ride', () => {
    const { mode, host } = setup();
    startRide(mode);
    const wrong = course.obstacles[1].elements[0].id;
    mode.onEvents([{ type: 'landed', elementId: wrong, dir: 1, knocked: false }], host);
    expect(host.calls.feedback).toEqual(['feedback.wrongObstacle']);
    // the right obstacle in the wrong direction is not scored either
    const due = course.obstacles[0].elements[0].id;
    mode.onEvents([{ type: 'landed', elementId: due, dir: -1, knocked: false }], host);
    expect(host.calls.feedback).toEqual(['feedback.wrongObstacle', 'feedback.wrongObstacle']);
  });

  it('stays quiet for a clean scored jump and for jumps before the start', () => {
    const quiet = setup();
    quiet.mode.onEvents(
      [{ type: 'landed', elementId: course.obstacles[1].elements[0].id, dir: 1, knocked: false }],
      quiet.host,
    );
    expect(quiet.host.calls.feedback).toEqual([]);
    const { mode, host } = setup();
    startRide(mode);
    const id = course.obstacles[0].elements[0].id;
    mode.onEvents([{ type: 'landed', elementId: id, dir: 1, knocked: false }], host);
    expect(host.calls.feedback).toEqual([]);
  });

  it('asks for a delayed rebuild of knocked rails that are not scored', () => {
    const { mode, host } = setup();
    // ride not started yet: the knockdown is not scored, rails come back after a delay
    const id = course.obstacles[0].elements[0].id;
    mode.onEvents([{ type: 'landed', elementId: id, dir: 1, knocked: true }], host);
    expect(host.calls.rebuildIn).toHaveLength(1);
    expect(host.calls.rebuildIn[0][0]).toBe(id);
    expect(host.calls.feedback).toEqual([]);
  });

  it('cancels a pending unscored rebuild when the same element is knocked in a scored jump', () => {
    const { mode, host } = setup();
    const id = course.obstacles[0].elements[0].id;
    // before the start the knockdown is unscored: a rebuild is scheduled
    mode.onEvents([{ type: 'landed', elementId: id, dir: 1, knocked: true }], host);
    expect(host.calls.rebuildIn).toHaveLength(1);
    startRide(mode);
    mode.onEvents([{ type: 'landed', elementId: id, dir: 1, knocked: true }], host);
    expect(host.calls.cancelRebuild).toEqual([id]);
    expect(host.calls.feedback).toContain('feedback.knockdown');
  });

  it('does not cancel rebuilds for clean or unscored landings', () => {
    const { mode, host } = setup();
    startRide(mode);
    const [first, second] = [
      course.obstacles[0].elements[0].id,
      course.obstacles[1].elements[0].id,
    ];
    mode.onEvents([{ type: 'landed', elementId: first, dir: 1, knocked: false }], host);
    mode.onEvents([{ type: 'landed', elementId: second, dir: -1, knocked: true }], host);
    expect(host.calls.cancelRebuild).toEqual([]);
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
