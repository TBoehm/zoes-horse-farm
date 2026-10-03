// Deterministic "autopilot" rider for rideability tests (test-only helper, not production code).
// It rides through the real ride session (sim + course run + course mode): pure pursuit along the
// course track, perpendicular approach, speed hold and a jump press inside the take-off zone.
import { COURSES } from '../../domain/course/courses.js';
import { createCourseMode } from '../modes/course-mode.js';
import { createFreeMode } from '../modes/free-mode.js';
import { createRideSession } from '../ride-session.js';
import { approachInfo, axisOf, headingOf, wrapAngle } from '../../domain/sim/geometry.js';
import { zoneForElement } from '../../domain/sim/jump.js';
import { TUNING } from '../../domain/sim/tuning.js';
import { createRng } from '../../domain/sim/rng.js';
import { fakeStore, fixedClock } from '../test-ports.js';

export const DT = 1 / 60;
const APPROACH_LEN = 14; // straight run-in before the first element of an obstacle (m)
const OVERSHOOT = 4; // the route runs this far beyond the finish line (m)
const NEAR_END = 1; // a route segment counts as passed this far before its end (m)

const mid = (line) => [(line.a[0] + line.b[0]) / 2, (line.a[1] + line.b[1]) / 2];

/**
 * Riding route of a course as a polyline: start pose → start line → track waypoints → straight
 * approach point (perpendicular to the element) → element centers → … → finish line → overshoot.
 */
export function routeOf(course, approachLen = APPROACH_LEN) {
  const points = [[course.startPose.x, course.startPose.z], mid(course.start)];
  course.obstacles.forEach((obstacle, i) => {
    points.push(...(course.track[i] ?? []));
    obstacle.elements.forEach((el, part) => {
      if (part === 0) {
        const n = axisOf(el);
        const prev = points[points.length - 1];
        const room = (el.x - prev[0]) * n.x + (el.z - prev[1]) * n.z;
        const len = Math.min(approachLen, room);
        if (len > 1) points.push([el.x - n.x * len, el.z - n.z * len]);
      }
      points.push([el.x, el.z]);
    });
  });
  points.push(...(course.track[course.obstacles.length] ?? []));
  const end = mid(course.finish);
  points.push(end, [
    end[0] + course.finish.dir[0] * OVERSHOOT,
    end[1] + course.finish.dir[1] * OVERSHOOT,
  ]);
  return points;
}

/** Elements of a course in riding order, all jumped in direction +1. */
export function courseTargets(course) {
  return course.obstacles.flatMap((o) => o.elements.map((el) => ({ el, dir: 1 })));
}

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/** Point `ahead` meters further along the polyline from the projection of p on segment s. */
function lookaheadPoint(points, s, p, ahead) {
  let remaining = ahead;
  let [ax, az] = points[s];
  const [bx, bz] = points[s + 1];
  const len = Math.hypot(bx - ax, bz - az) || 1;
  const t = clamp(((p.x - ax) * (bx - ax) + (p.z - az) * (bz - az)) / (len * len), 0, 1);
  ax += (bx - ax) * t;
  az += (bz - az) * t;
  let i = s;
  for (;;) {
    const [nx, nz] = points[i + 1];
    const d = Math.hypot(nx - ax, nz - az);
    if (d >= remaining || i + 2 >= points.length) {
      const k = d > 0 ? Math.min(1, remaining / d) : 1;
      return [ax + (nx - ax) * k, az + (nz - az) * k];
    }
    remaining -= d;
    [ax, az] = [nx, nz];
    i++;
  }
}

/**
 * @param {object} cfg
 * @param {number[][]} cfg.route polyline [[x, z], …]
 * @param {{ el: object, dir: number }[]} cfg.targets elements to jump, in order
 * @param {number} cfg.speed target speed (m/s)
 * @param {boolean} cfg.canter ride with the gallop input on
 * @param {number} [cfg.jitterS] press time jitter, uniform within ±jitterS seconds (needs rng)
 * @param {() => number} [cfg.rng] seeded random source for the jitter
 * @param {number} [cfg.lookahead] pure pursuit lookahead (m)
 */
export function createAutopilot({
  route,
  targets,
  speed,
  canter,
  jitterS = 0,
  rng = () => 0.5,
  lookahead = 3.5,
}) {
  let segment = 0;
  let index = 0;
  let pressed = false;
  let steps = 0;
  const delays = new Map();

  function delayFor(i) {
    if (!delays.has(i)) delays.set(i, (rng() * 2 - 1) * jitterS);
    return delays.get(i);
  }

  function steer(horse) {
    while (segment + 2 < route.length) {
      const [ax, az] = route[segment];
      const [bx, bz] = route[segment + 1];
      const len = Math.hypot(bx - ax, bz - az) || 1;
      const along = ((horse.x - ax) * (bx - ax) + (horse.z - az) * (bz - az)) / len;
      if (along < len - NEAR_END) break;
      segment++;
    }
    const [tx, tz] = lookaheadPoint(route, segment, horse, lookahead);
    const error = wrapAngle(headingOf(tx - horse.x, tz - horse.z) - horse.heading);
    return clamp(-3 * error, -1, 1);
  }

  /** Space as soon as the horse is in the middle of the take-off zone of the element due next. */
  function wantsJump(horse) {
    const target = targets[index];
    if (!target || pressed || horse.jump || horse.speed < TUNING.speeds.trotMin) return false;
    const info = approachInfo(target.el, horse, TUNING.approachDistance);
    if (!info || !info.approaching || info.dir !== target.dir) return false;
    const zone = zoneForElement(target.el, horse.speed, TUNING);
    const aim = (zone.near + zone.far) / 2 - delayFor(index) * horse.speed;
    if (info.distance > aim) return false;
    pressed = true;
    return true;
  }

  return {
    get done() {
      return index >= targets.length;
    },
    /** Next input for the current horse state. */
    next(horse) {
      const first = steps++ === 0;
      return {
        steer: steer(horse),
        throttle: clamp((speed - horse.speed) * 2, -1, 1),
        // one step without gallop first: after a restart the horse gallops only on a fresh press
        gallop: canter && !first,
        jump: wantsJump(horse),
      };
    },
    /** Feed the events of the last step back (landing → next element, refusal → press again). */
    onEvents(events) {
      for (const e of events) {
        if (e.type === 'landed' && e.elementId === targets[index]?.el.id) {
          index++;
          pressed = false;
        }
        if (e.type === 'refusal') pressed = false;
      }
    },
  };
}

function sessionFor(mode, seed) {
  const store = fakeStore();
  const clock = fixedClock();
  return createRideSession({ mode, store, clock, rng: createRng(seed) });
}

function count(events, type) {
  return events.filter((e) => e.type === type).length;
}

/**
 * Rides a whole course with the autopilot through the real ride session.
 * @param {number} courseId
 * @param {{ speed?: number, canter?: boolean, jitterS?: number, seed?: number, maxT?: number }} [opts]
 *   defaults: the pace of the course (trot → medium trot, canter → medium canter with gallop)
 * @returns {{ finished: boolean, result: object|null, timeS: number, allowedS: number,
 *   events: object[], refusals: number, knockdowns: number, jumps: number }}
 */
export function rideCourse(courseId, opts = {}) {
  const course = COURSES.find((c) => c.id === courseId);
  const trot = course.pace === 'trot';
  const {
    speed = trot ? TUNING.speeds.trotMedium : TUNING.speeds.canterMedium,
    canter = !trot,
    jitterS = 0,
    seed = 1,
    maxT = course.allowedTimeS * 3 + 60,
  } = opts;
  const session = sessionFor(createCourseMode({ courseId }), seed);
  const pilot = createAutopilot({
    route: routeOf(course),
    targets: courseTargets(course),
    speed,
    canter,
    jitterS,
    rng: createRng(seed + 1000),
  });
  const events = [];
  let result = null;
  let t = 0;
  while (t < maxT && !result) {
    const out = session.step(DT, pilot.next(session.view.horse));
    pilot.onEvents(out.events);
    events.push(...out.events);
    result = out.commands.find((c) => c.type === 'finished')?.params.result ?? null;
    t += DT;
  }
  return {
    finished: result !== null,
    result,
    timeS: result ? result.timeCs / 100 : t,
    allowedS: course.allowedTimeS,
    events,
    refusals: count(events, 'refusal'),
    knockdowns: count(events, 'railDown'),
    jumps: count(events, 'landed'),
  };
}

/**
 * Rides one obstacle of the free layout in direction dir (+1 / −1): starts at rest, in front of
 * the obstacle on its straight run-in, and jumps all its elements in order at medium canter.
 * @returns {{ jumped: string[], refusals: number, knockdowns: number, finished: boolean }}
 */
export function rideFreeObstacle(obstacle, dir, { seed = 1, maxT = 30, jitterS = 0 } = {}) {
  const ordered = dir > 0 ? obstacle.elements : [...obstacle.elements].reverse();
  const first = ordered[0];
  const n = axisOf(first);
  const from = [first.x - dir * n.x * APPROACH_LEN, first.z - dir * n.z * APPROACH_LEN];
  const last = ordered[ordered.length - 1];
  const to = [last.x + dir * n.x * 8, last.z + dir * n.z * 8];
  const heading = headingOf(dir * n.x, dir * n.z);
  const mode = { ...createFreeMode(), startPose: () => ({ x: from[0], z: from[1], heading }) };
  const session = sessionFor(mode, seed);
  const pilot = createAutopilot({
    route: [from, to],
    targets: ordered.map((el) => ({ el, dir })),
    speed: TUNING.speeds.canterMedium,
    canter: true,
    jitterS,
    rng: createRng(seed + 1000),
  });
  const events = [];
  let t = 0;
  while (t < maxT && !pilot.done) {
    const out = session.step(DT, pilot.next(session.view.horse));
    pilot.onEvents(out.events);
    events.push(...out.events);
    t += DT;
  }
  return {
    finished: pilot.done,
    jumped: events.filter((e) => e.type === 'landed').map((e) => e.elementId),
    refusals: count(events, 'refusal'),
    knockdowns: count(events, 'railDown'),
  };
}
