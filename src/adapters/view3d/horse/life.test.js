import { describe, expect, it } from 'vitest';
import { createRng } from '../textures.js';
import { createLife, gestureHead, stepLife } from './life.js';

const DT = 1 / 60;
const HALT = { halt: 1, walk: 0, trot: 0, canter: 0, back: 0 };
const CANTER = { halt: 0, walk: 0, trot: 0, canter: 1, back: 0 };

function input(over = {}) {
  return { speed: 0, turnRate: 0, bodyY: 0, neckAngle: 0, weights: HALT, alert: 0, ...over };
}

function run(life, seconds, make) {
  for (let t = 0; t < seconds; t += DT) stepLife(life, DT, make(t));
}

describe('tail and mane follow the motion of the horse', () => {
  it('a start throws the tail back; it swings after and settles when the speed is constant', () => {
    const life = createLife({ rng: createRng(1) });
    let speed = 0;
    let peak = 0;
    for (let t = 0; t < 1; t += DT) {
      speed = Math.min(6, speed + 6 * DT); // 1 s from 0 to 6 m/s
      stepLife(life, DT, input({ speed, weights: CANTER }));
      peak = Math.max(peak, life.tail.segments[3].pitch.x);
    }
    // while accelerating the tail was pushed back (+ pitch)
    expect(peak).toBeGreaterThan(0.1);
    // at constant speed it swings over and comes to rest again, apart from the breeze
    let tipMin = 0;
    for (let t = 0; t < 2; t += DT) {
      stepLife(life, DT, input({ speed: 6, weights: CANTER }));
      tipMin = Math.min(tipMin, life.tail.segments[4].pitch.x);
    }
    expect(tipMin).toBeLessThan(0.05);
    run(life, 4, () => input({ speed: 6, weights: CANTER }));
    for (const seg of life.tail.segments) expect(Math.abs(seg.pitch.x)).toBeLessThan(0.12);
  });

  it('a stop swings the tail forward', () => {
    const life = createLife({ rng: createRng(2) });
    run(life, 2, () => input({ speed: 6, weights: CANTER }));
    run(life, 0.5, (t) => input({ speed: Math.max(0, 6 - 24 * t), weights: CANTER }));
    const min = Math.min(...life.tail.segments.map((s) => s.pitch.x));
    expect(min).toBeLessThan(-0.05);
  });

  it('a turn swings tail and mane to the outside', () => {
    const life = createLife({ rng: createRng(3) });
    run(life, 1, () => input({ speed: 5, turnRate: 1.2, weights: CANTER }));
    // right turn (+): acceleration to the right, hair to the left (+ sway)
    expect(life.tail.segments[3].sway.x).toBeGreaterThan(0.03);
    expect(life.mane.segments[2].sway.x).toBeGreaterThan(0);
  });

  it('a landing makes the hair jump: vertical acceleration moves tail and mane', () => {
    const life = createLife({ rng: createRng(4) });
    run(life, 2, () => input({ speed: 6, weights: CANTER }));
    let calm = 0;
    for (const seg of [...life.tail.segments, ...life.mane.segments]) {
      calm = Math.max(calm, Math.abs(seg.pitch.x), Math.abs(seg.sway.x));
    }
    let moved = 0;
    for (let t = 0; t < 0.6; t += DT) {
      // down from 0.6 m at 4 m/s, then the ground stops the body
      stepLife(life, DT, input({ speed: 6, weights: CANTER, bodyY: Math.max(0, 0.6 - 4 * t) }));
      for (const seg of [...life.tail.segments, ...life.mane.segments]) {
        moved = Math.max(moved, Math.abs(seg.pitch.x), Math.abs(seg.sway.x));
      }
    }
    expect(moved).toBeGreaterThan(calm + 0.05);
    expect(moved).toBeLessThan(1.3); // and it stays within sane limits
  });

  it('hair is calm at halt (only the light breeze) and the mane stays near its rest pose', () => {
    const life = createLife({ rng: createRng(5) });
    run(life, 3, () => input());
    for (const seg of [...life.tail.segments, ...life.mane.segments, ...life.forelock.segments]) {
      expect(Math.abs(seg.pitch.x)).toBeLessThan(0.1);
      expect(Math.abs(seg.sway.x)).toBeLessThan(0.15);
    }
  });

  it('survives long frames and wild input', () => {
    const life = createLife({ rng: createRng(6) });
    for (let i = 0; i < 100; i++) {
      stepLife(life, i % 7 === 0 ? 0.1 : DT, input({ speed: (i % 5) * 40, bodyY: (i % 3) * 3 }));
      for (const seg of [...life.tail.segments, ...life.mane.segments]) {
        expect(Number.isFinite(seg.pitch.x) && Number.isFinite(seg.sway.x)).toBe(true);
        expect(Math.abs(seg.pitch.x)).toBeLessThan(3);
      }
    }
  });

  it('swishes the tail now and then at halt, not while galloping', () => {
    let swishes = 0;
    let prevSway = 0;
    // count the swishes: the angular velocity of the tip jumps when the tail is kicked
    const l = createLife({ rng: createRng(7) });
    for (let t = 0; t < 120; t += DT) {
      stepLife(l, DT, input());
      const v = l.tail.segments[4].sway.v;
      if (Math.abs(v) > 3 && Math.abs(prevSway) <= 3) swishes++;
      prevSway = v;
    }
    expect(swishes).toBeGreaterThan(5);
    const gallop = createLife({ rng: createRng(7) });
    let max = 0;
    for (let t = 0; t < 60; t += DT) {
      stepLife(gallop, DT, input({ speed: 6, weights: CANTER }));
      max = Math.max(max, Math.abs(gallop.tail.segments[4].sway.v));
    }
    expect(max).toBeLessThan(3);
  });
});

describe('blink, breathing, nostrils', () => {
  it('blinks every few seconds and the lid closes completely', () => {
    const life = createLife({ rng: createRng(8) });
    let blinks = 0;
    let prev = 0;
    let peak = 0;
    for (let t = 0; t < 120; t += DT) {
      stepLife(life, DT, input());
      if (life.blinkClosure > 0.5 && prev <= 0.5) blinks++;
      prev = life.blinkClosure;
      peak = Math.max(peak, life.blinkClosure);
    }
    expect(peak).toBeGreaterThan(0.95);
    expect(blinks).toBeGreaterThan(8);
    expect(blinks).toBeLessThan(45);
  });

  it('breathes faster in the canter than at halt and the nostrils flare with it', () => {
    const rate = (weights) => {
      const life = createLife({ rng: createRng(9) });
      let crossings = 0;
      let prev = 0;
      let minFlare = 1;
      let maxFlare = 0;
      for (let t = 0; t < 30; t += DT) {
        stepLife(life, DT, input({ weights, speed: weights === HALT ? 0 : 6 }));
        if (life.breath > 0 && prev <= 0) crossings++;
        prev = life.breath;
        if (t > 5) {
          minFlare = Math.min(minFlare, life.flare);
          maxFlare = Math.max(maxFlare, life.flare);
        }
      }
      return { perSecond: crossings / 30, minFlare, maxFlare };
    };
    const halt = rate(HALT);
    const canter = rate(CANTER);
    expect(canter.perSecond).toBeGreaterThan(halt.perSecond * 3);
    expect(halt.maxFlare - halt.minFlare).toBeGreaterThan(0.15);
    expect(canter.minFlare).toBeGreaterThan(halt.minFlare);
    expect(canter.maxFlare).toBeLessThanOrEqual(1);
    expect(halt.minFlare).toBeGreaterThanOrEqual(0);
  });
});

describe('gesture shapes', () => {
  const g = (id, t, weight = 1, duration = 1.5) => ({ id, t, weight, duration, leg: 0 });

  it('are zero without a gesture or at weight 0', () => {
    expect(gestureHead(null)).toEqual({ yaw: 0, pitch: 0, neck: 0 });
    expect(gestureHead(g('shake', 0.5, 0))).toEqual({ yaw: 0, pitch: 0, neck: 0 });
    expect(gestureHead({ id: null, weight: 1, t: 0, duration: 1 })).toEqual({
      yaw: 0,
      pitch: 0,
      neck: 0,
    });
  });

  it('a head shake swings from side to side and dies away; a toss lifts the head', () => {
    let maxYaw = 0;
    let minYaw = 0;
    for (let t = 0; t < 1.5; t += DT) {
      const o = gestureHead(g('shake', t));
      maxYaw = Math.max(maxYaw, o.yaw);
      minYaw = Math.min(minYaw, o.yaw);
    }
    expect(maxYaw).toBeGreaterThan(0.15);
    expect(minYaw).toBeLessThan(-0.15);
    expect(Math.abs(gestureHead(g('shake', 1.49)).yaw)).toBeLessThan(0.05);
    expect(gestureHead(g('toss', 0.3)).pitch).toBeLessThan(-0.3);
    expect(gestureHead(g('toss', 1.3)).pitch).toBeGreaterThan(-0.05);
  });

  it('change gently from frame to frame', () => {
    for (const id of ['shake', 'toss', 'paw']) {
      let prev = gestureHead(g(id, 0));
      for (let t = DT; t < 1.5; t += DT) {
        const o = gestureHead(g(id, t));
        expect(Math.abs(o.yaw - prev.yaw)).toBeLessThan(0.13);
        expect(Math.abs(o.pitch - prev.pitch)).toBeLessThan(0.1);
        prev = o;
      }
    }
  });
});
