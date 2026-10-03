import { describe, expect, it } from 'vitest';
import { createRideSession } from './ride-session.js';
import { createFreeMode, REBUILD_DELAY_S } from './modes/free-mode.js';
import { createCourseMode } from './modes/course-mode.js';
import { FREE_LAYOUT } from '../domain/course/courses.js';
import { TUNING, zoneForElement } from '../domain/sim/index.js';
import { fakeStore, fixedClock, seededRng } from './test-ports.js';

const DT = 1 / 60;
const NOW = '2026-01-02T03:04:05.000Z';

/** One cross in the middle of the arena; the horse starts at the given distance in front of it. */
const cross = { id: 'c', kind: 'cross', height: 0.45, spread: 0, x: 0, z: 0, rot: 0 };
const crossObstacles = [{ number: null, elements: [cross], directed: false }];

function crossMode({ distance, speed = 0, gallop = false, ...override } = {}) {
  return {
    ...createFreeMode(),
    obstacles: crossObstacles,
    startPose: () => ({ x: 0, z: -distance, heading: 0, speed, gallop }),
    ...override,
  };
}

function setup({ mode = createFreeMode(), settings, progress, rng = seededRng(1) } = {}) {
  const store = fakeStore({ settings, progress });
  const session = createRideSession({ mode, store, clock: fixedClock(NOW), rng });
  return { store, session, mode };
}

/** Steps until `done(out)` or the time is up; returns all commands and events collected. */
function run(session, input, { maxT = 8, done = () => false } = {}) {
  const all = { events: [], commands: [] };
  for (let t = 0; t < maxT; t += DT) {
    const out = session.step(DT, typeof input === 'function' ? input(session.view) : input);
    all.events.push(...out.events);
    all.commands.push(...out.commands);
    if (done(out)) break;
  }
  return all;
}

const ofType = (list, type) => list.filter((x) => x.type === type);

/** Presses Space once, in the first step, and then rides on until the stop condition. */
function pressOnce(session, done) {
  let pressed = false;
  return run(
    session,
    () => {
      const press = !pressed;
      pressed = true;
      return { jump: press };
    },
    { done },
  );
}

const landedIn = (out) => ofType(out.events, 'landed').length > 0;
const railDownIn = (out) => ofType(out.events, 'railDown').length > 0;

/** Trot at the cross and press Space in the takeoff zone so it is jumped. */
function jumpTheCross({ settings, progress, rng } = {}) {
  const z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING);
  const mode = crossMode({ distance: z.near + 0.3, speed: TUNING.speeds.trotMax });
  const ctx = setup({ mode, settings, progress, rng });
  return { ...ctx, out: pressOnce(ctx.session, landedIn) };
}

describe('view', () => {
  it('starts at the mode start pose with all rails up and nothing highlighted (free mode)', () => {
    const { session } = setup();
    const { horse, rails, aid, highlight, finishMarked, lines, hud } = session.view;
    expect(horse).toMatchObject({
      x: FREE_LAYOUT.startPose.x,
      z: FREE_LAYOUT.startPose.z,
      heading: FREE_LAYOUT.startPose.heading,
      speed: 0,
    });
    expect(rails.get('f1')).toEqual([true]);
    expect(aid).toBeNull();
    expect(highlight).toBeNull();
    expect(finishMarked).toBe(false);
    expect(lines).toBeNull();
    expect(hud).toBeNull();
  });

  it('describes the mode for the screen: obstacles, flags, quit target', () => {
    const { session } = setup({ mode: createCourseMode({ courseId: 2 }) });
    expect(session.modeId).toBe('course');
    expect(session.flags).toBe(true);
    expect(session.quitScreen).toBe('courseSelect');
    expect(session.quitLabelKey).toBe('pause.toSelect');
    expect(session.obstacles.length).toBeGreaterThan(0);
  });

  it('exposes mode data of a course: lines with label keys, highlight and HUD model', () => {
    const { session } = setup({ mode: createCourseMode({ courseId: 1 }) });
    const { lines, highlight, hud, finishMarked } = session.view;
    expect(lines.labelKeys).toEqual({
      start: 'prestart.legendStart',
      finish: 'prestart.legendFinish',
    });
    expect(highlight.number).toBe(1);
    expect(hud).toMatchObject({ phase: 'prestart', faults: 0 });
    expect(finishMarked).toBe(false);
  });
});

describe('step', () => {
  it('moves the horse with the input and returns sim events', () => {
    const { session } = setup();
    const z0 = session.view.horse.z;
    const out = session.step(DT, { throttle: 1 });
    expect(out).toEqual({ events: [], commands: [] });
    run(session, { throttle: 1 }, { maxT: 1 });
    expect(session.view.horse.z).toBeGreaterThan(z0);
  });

  it('ignores a step with dt 0 (nothing moves, no events)', () => {
    const { session } = setup();
    const snapshot = { ...session.view.horse };
    expect(session.step(0, { throttle: 1 })).toEqual({ events: [], commands: [] });
    expect(session.view.horse).toEqual(snapshot);
  });
});

describe('jump counting and instant badges (rules 40, 45, 49)', () => {
  it('counts a jump at once, saves it and announces the first-jump badge', () => {
    const { store, out } = jumpTheCross();
    expect(store.data.progress.jumps).toBe(1);
    expect(store.data.progress.badges.firstJump).toBe(NOW);
    expect(ofType(out.commands, 'badges')).toEqual([{ type: 'badges', ids: ['firstJump'] }]);
  });

  it('announces a badge only once and counts every further jump', () => {
    const { store, session } = jumpTheCross();
    session.restart();
    const second = pressOnce(session, landedIn);
    expect(store.data.progress.jumps).toBe(2);
    expect(ofType(second.commands, 'badges')).toEqual([]);
  });

  it('announces the jump mouse with the 100th jump', () => {
    const { out } = jumpTheCross({ progress: { jumps: 99, badges: { firstJump: NOW } } });
    expect(ofType(out.commands, 'badges')).toEqual([{ type: 'badges', ids: ['jumpMouse'] }]);
  });

  it('asks for takeoff and landing sounds', () => {
    const { out } = jumpTheCross();
    const sounds = ofType(out.commands, 'sound').map((c) => c.name);
    expect(sounds).toContain('takeoff');
    expect(sounds).toContain('landing');
  });
});

describe('knockdown, feedback and rebuild timers', () => {
  /** Jumps the cross very close to the takeoff limit with a rng that always knocks the rail. */
  const knockSetup = () => {
    const z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING);
    const mode = crossMode({ distance: z.reach - 0.05, speed: TUNING.speeds.trotMax });
    return setup({ mode, rng: () => 0 });
  };

  it('plays the rail-down sound and gives knockdown feedback', () => {
    const { session } = knockSetup();
    const out = pressOnce(session, landedIn);
    expect(ofType(out.commands, 'sound').map((c) => c.name)).toContain('railDown');
    expect(ofType(out.commands, 'feedback')).toEqual([
      { type: 'feedback', key: 'feedback.knockdown' },
    ]);
  });

  it('rebuilds the rails after the delay the mode asked for', () => {
    const { session } = knockSetup();
    pressOnce(session, railDownIn);
    expect(session.view.rails.get('c')).toEqual([false]);
    run(session, {}, { maxT: REBUILD_DELAY_S - 0.5 });
    expect(session.view.rails.get('c')).toEqual([false]);
    run(session, {}, { maxT: 1 });
    expect(session.view.rails.get('c')).toEqual([true]);
  });

  it('forgets pending rebuilds on restart and puts all rails up', () => {
    const { session } = knockSetup();
    pressOnce(session, railDownIn);
    session.restart();
    expect(session.view.rails.get('c')).toEqual([true]);
  });
});

describe('mode-requested immediate rebuild', () => {
  it('rebuilds at once and cancels a pending delayed rebuild of the same element', () => {
    const z = zoneForElement(cross, TUNING.speeds.trotMax, TUNING);
    let requested = false;
    const base = crossMode({ distance: z.reach - 0.05, speed: TUNING.speeds.trotMax });
    const mode = {
      ...base,
      onEvents(events, host) {
        for (const e of events) {
          if (e.type === 'railDown') host.rebuildIn(e.elementId, 100);
          if (e.type === 'landed') {
            requested = true;
            host.rebuildNow(e.elementId);
          }
        }
      },
    };
    const { session } = setup({ mode, rng: () => 0 });
    pressOnce(session, () => requested);
    expect(session.view.rails.get('c')).toEqual([true]);
  });
});

describe('end of a gallop', () => {
  it('asks the input to end the gallop when the horse is stopped at the fence', () => {
    const z = zoneForElement(cross, TUNING.speeds.canterMin, TUNING);
    const mode = crossMode({ distance: z.far + 2, speed: TUNING.speeds.canterMin, gallop: true });
    const { session } = setup({ mode });
    const out = run(
      session,
      { gallop: true, throttle: 1 },
      {
        maxT: 20,
        done: (o) => ofType(o.commands, 'endGallop').length > 0,
      },
    );
    expect(ofType(out.events, 'gallopEnded').length).toBeGreaterThan(0);
    expect(ofType(out.commands, 'endGallop')).toEqual([{ type: 'endGallop' }]);
  });
});

describe('refusal feedback', () => {
  it('gives feedback when the horse refuses (walking at the cross, free mode)', () => {
    const mode = crossMode({ distance: 4, speed: 1.2 });
    const { session } = setup({ mode });
    const out = run(
      session,
      { throttle: 0 },
      { maxT: 8, done: (o) => ofType(o.events, 'refusal').length > 0 },
    );
    expect(ofType(out.events, 'refusal').length).toBeGreaterThan(0);
    expect(ofType(out.commands, 'feedback')).toEqual([
      { type: 'feedback', key: 'feedback.refusal' },
    ]);
  });
});

describe('jump aid', () => {
  it('is null when the mode shows none', () => {
    const { session } = setup({ mode: crossMode({ distance: 10, aidTarget: () => null }) });
    expect(session.view.aid).toBeNull();
  });

  it('combines the mode target with the sim zone for at least 1 m/s', () => {
    const mode = crossMode({ distance: 10, aidTarget: () => ({ elementId: 'c', dir: 1 }) });
    const { session } = setup({ mode });
    const zone = zoneForElement(cross, 1, TUNING);
    expect(session.view.aid).toEqual({
      elementId: 'c',
      dir: 1,
      zone: { far: zone.far, near: zone.near, lastPoint: zone.lastPoint, reach: zone.reach },
    });
  });

  it('uses the current speed for the zone when faster than 1 m/s', () => {
    const speed = TUNING.speeds.trotMax;
    const mode = crossMode({ distance: 10, speed, aidTarget: () => ({ elementId: 'c', dir: 1 }) });
    const { session } = setup({ mode });
    expect(session.view.aid.zone.far).toBe(zoneForElement(cross, speed, TUNING).far);
  });

  it('hands the approached element and the saved settings to the mode (free mode, rule 42)', () => {
    const mode = {
      ...createFreeMode(),
      obstacles: crossObstacles,
      startPose: () => ({ x: 0, z: -10, heading: 0 }),
    };
    const on = setup({ mode, settings: { aidFree: true } });
    expect(on.session.view.aid).toMatchObject({ elementId: 'c', dir: 1 });
    const off = setup({ mode: { ...mode }, settings: { aidFree: false } });
    expect(off.session.view.aid).toBeNull();
  });

  it('follows a settings change without a restart', () => {
    const mode = {
      ...createFreeMode(),
      obstacles: crossObstacles,
      startPose: () => ({ x: 0, z: -10, heading: 0 }),
    };
    const { session, store } = setup({ mode, settings: { aidFree: false } });
    expect(session.view.aid).toBeNull();
    store.update('settings', (s) => ({ ...s, aidFree: true }));
    expect(session.view.aid).not.toBeNull();
  });
});

describe('restart', () => {
  it('puts the horse back, resets the mode and asks to reset the touch gallop', () => {
    let restarts = 0;
    const mode = { ...createFreeMode(), onRestart: () => (restarts += 1) };
    const { session } = setup({ mode });
    const start = { ...session.view.horse };
    run(session, { throttle: 1 }, { maxT: 2 });
    expect(session.view.horse.z).not.toBe(start.z);
    const before = restarts;
    const out = session.restart();
    expect(out.commands).toEqual([{ type: 'resetTouchGallop' }]);
    expect(restarts).toBe(before + 1);
    expect(session.view.horse).toMatchObject({ x: start.x, z: start.z, speed: 0 });
  });

  it('starts a fresh course run', () => {
    const { session } = setup({ mode: createCourseMode({ courseId: 1 }) });
    run(session, { throttle: 1 }, { maxT: 3 });
    session.restart();
    expect(session.view.hud).toMatchObject({ phase: 'prestart', timeMs: 0 });
  });
});

describe('finishing a course ride', () => {
  const result = {
    courseId: 1,
    timeCs: 5000,
    faults: { knockdowns: 0, refusals: 0, timeFaults: 0, total: 0 },
    stars: 3,
    cleanOxer: false,
    cleanCombination: false,
  };
  const finishingMode = () => ({
    ...createFreeMode(),
    update: () => ({ finished: { screen: 'results', result, params: { courseId: 1 } } }),
  });

  it('saves the ride and emits a finished command with the results params', () => {
    const { session, store } = setup({ mode: finishingMode() });
    const out = session.step(DT, {});
    expect(store.data.progress.finishedRides).toBe(1);
    expect(store.data.progress.unlocked).toBe(2);
    const finished = ofType(out.commands, 'finished');
    expect(finished).toHaveLength(1);
    expect(finished[0].screen).toBe('results');
    expect(finished[0].params).toMatchObject({
      courseId: 1,
      result,
      isNewBest: true,
      unlockedCourse: 2,
    });
    expect(finished[0].params.awarded).toContain('clean');
    expect(store.data.progress.badges.clean).toBe(NOW);
  });

  it('finishes only once, further steps do nothing', () => {
    const { session, store } = setup({ mode: finishingMode() });
    session.step(DT, {});
    expect(session.step(DT, {})).toEqual({ events: [], commands: [] });
    expect(store.data.progress.finishedRides).toBe(1);
  });

  it('can ride again after a restart', () => {
    const { session, store } = setup({ mode: finishingMode() });
    session.step(DT, {});
    session.restart();
    expect(ofType(session.step(DT, {}).commands, 'finished')).toHaveLength(1);
    expect(store.data.progress.finishedRides).toBe(2);
  });
});

describe('course ride with the real course mode', () => {
  it('does not finish or save anything on the way', () => {
    const { session, store } = setup({ mode: createCourseMode({ courseId: 1 }) });
    const out = run(session, { throttle: 1 }, { maxT: 3 });
    expect(ofType(out.commands, 'finished')).toEqual([]);
    expect(store.data.progress.finishedRides).toBe(0);
  });
});
