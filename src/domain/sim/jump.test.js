import { describe, expect, it } from 'vitest';
import { TUNING } from './tuning.js';
import {
  difficultyOf,
  gaitAllows,
  safeAngle,
  selfMinSpeed,
  speedBand,
  takeoffRisk,
  zoneForElement,
  zoneWindow,
} from './jump.js';
import { DEG, makeElement } from '../../../tests/support/sim-utils.js';

const cross = makeElement('cross', 0.45);
const vertical60 = makeElement('vertical', 0.6);
const vertical80 = makeElement('vertical', 0.8);
const oxer70 = makeElement('oxer', 0.7, { spread: 0.6 });
const oxer85 = makeElement('oxer', 0.85, { spread: 0.7 });
const all = [cross, vertical60, vertical80, oxer70, oxer85];

const core = (el, speed) => {
  const zone = zoneForElement(el, speed, TUNING);
  return { gait: 'canter', speed, distance: zone.center, angle: 0 };
};

describe('Takeoff zone, reach, last takeoff point', () => {
  it('reach > far > near > lastPoint > 0.25 m for all kinds and speeds', () => {
    for (const el of all) {
      for (const v of [0, 2.6, 3.2, 4.5, 5.8, 8]) {
        const z = zoneForElement(el, v, TUNING);
        expect(z.reach).toBeGreaterThan(z.far);
        expect(z.far).toBeGreaterThan(z.near);
        expect(z.near).toBeGreaterThan(z.lastPoint);
        expect(z.lastPoint).toBeGreaterThan(0.25);
        expect(z.reach).toBeLessThan(TUNING.approachDistance);
      }
    }
  });

  it('zone center is realistically about 1.3–1.8 m (40–85 cm, working pace)', () => {
    expect(zoneForElement(cross, 3.2, TUNING).center).toBeGreaterThan(1.2);
    for (const el of [vertical60, vertical80, oxer70, oxer85]) {
      const c = zoneForElement(el, 5.8, TUNING).center;
      expect(c).toBeGreaterThanOrEqual(1.3);
      expect(c).toBeLessThanOrEqual(1.85);
    }
  });

  it('an oxer is approached slightly closer than a vertical of the same height', () => {
    const v = makeElement('vertical', 0.85);
    expect(zoneForElement(oxer85, 5.8, TUNING).center).toBeLessThan(
      zoneForElement(v, 5.8, TUNING).center,
    );
  });

  it('reach starts well before the zone', () => {
    const z = zoneForElement(vertical60, 5.8, TUNING);
    expect(z.reach - z.far).toBeGreaterThanOrEqual(0.35 * 5.8 - 1e-9);
  });

  it('time window: generous for a cross, narrower when higher/wider', () => {
    expect(zoneWindow(cross, TUNING)).toBeCloseTo(0.22, 9);
    expect(zoneWindow(vertical80, TUNING)).toBeLessThan(zoneWindow(vertical60, TUNING));
    expect(zoneWindow(oxer85, TUNING)).toBeLessThan(zoneWindow(vertical80, TUNING));
    expect(zoneWindow(oxer85, TUNING)).toBeGreaterThan(0.09);
    expect(zoneWindow(oxer85, TUNING)).toBeLessThan(0.12);
  });

  it('the window narrows only above the tuning height reference', () => {
    const raised = {
      ...TUNING,
      jump: { ...TUNING.jump, window: { ...TUNING.jump.window, heightRef: 0.6 } },
    };
    expect(zoneWindow(vertical60, raised)).toBeCloseTo(TUNING.jump.window.base, 9);
    expect(zoneWindow(vertical80, raised)).toBeGreaterThan(zoneWindow(vertical80, TUNING));
  });

  it('the last takeoff point is capped by the tuning share of the near edge', () => {
    const strict = {
      ...TUNING,
      jump: { ...TUNING.jump, lastPoint: { lead: 0, min: 0.1, maxShareOfNear: 0.5 } },
    };
    const z = zoneForElement(vertical80, 5.8, strict);
    expect(z.lastPoint).toBeCloseTo(z.near * 0.5, 9);
  });

  it('the zone adapts to speed (farther away and deeper at higher speed)', () => {
    const slow = zoneForElement(vertical80, 4.5, TUNING);
    const fast = zoneForElement(vertical80, 7, TUNING);
    expect(fast.far).toBeGreaterThan(slow.far);
    expect(fast.center).toBeGreaterThan(slow.center);
    expect(fast.far - fast.near).toBeGreaterThan(slow.far - slow.near);
  });
});

describe('Jumpability and target ranges', () => {
  it('gait: halt/walk never, trot only crosses, canter everything (rule 16)', () => {
    for (const el of all) {
      expect(gaitAllows(el, 'halt')).toBe(false);
      expect(gaitAllows(el, 'walk')).toBe(false);
      expect(gaitAllows(el, 'canter')).toBe(true);
      expect(gaitAllows(el, 'trot')).toBe(el.kind === 'cross');
    }
  });

  it('target speed range rises with height and spread', () => {
    expect(speedBand(cross, TUNING).min).toBeLessThanOrEqual(2.6);
    expect(speedBand(cross, TUNING).min).toBeLessThan(TUNING.speeds.trotMedium);
    expect(speedBand(vertical60, TUNING).min).toBeGreaterThanOrEqual(4.6);
    expect(speedBand(vertical80, TUNING).min).toBeGreaterThan(speedBand(vertical60, TUNING).min);
    expect(speedBand(oxer85, TUNING).min).toBeGreaterThanOrEqual(5.55);
    for (const el of all) {
      const b = speedBand(el, TUNING);
      expect(b.max).toBeGreaterThan(b.min);
      expect(b.max).toBeLessThanOrEqual(TUNING.speeds.canterMax);
      // medium canter speed is always within the safe core
      expect(TUNING.speeds.canterMedium).toBeGreaterThanOrEqual(b.min);
      expect(TUNING.speeds.canterMedium).toBeLessThanOrEqual(b.max);
    }
  });

  it('self-jump minimum speed is below the target range', () => {
    for (const el of all) expect(selfMinSpeed(el, TUNING)).toBeLessThan(speedBand(el, TUNING).min);
    expect(selfMinSpeed(oxer85, TUNING)).toBeGreaterThan(TUNING.speeds.canterMin);
  });

  it('angle tolerance 10–12°, narrower for heavy obstacles', () => {
    expect(safeAngle(cross, TUNING) / DEG).toBeLessThanOrEqual(12);
    expect(safeAngle(oxer85, TUNING) / DEG).toBeGreaterThanOrEqual(10);
    expect(safeAngle(oxer85, TUNING)).toBeLessThan(safeAngle(cross, TUNING));
    expect(difficultyOf(oxer85, TUNING)).toBeGreaterThan(difficultyOf(cross, TUNING));
  });
});

describe('Knockdown risk (rules 15, 18, 19, 20)', () => {
  it('safe core: exactly 0 across the whole zone, target range and angle tolerance', () => {
    for (const el of all) {
      const band = speedBand(el, TUNING);
      for (const v of [band.min, (band.min + band.max) / 2, band.max]) {
        const z = zoneForElement(el, v, TUNING);
        for (const d of [z.near, z.center, z.far]) {
          for (const a of [0, safeAngle(el, TUNING)]) {
            const gait = v <= TUNING.speeds.trotMax && el.kind === 'cross' ? 'trot' : 'canter';
            expect(takeoffRisk(el, { gait, speed: v, distance: d, angle: a }, TUNING)).toBe(0);
          }
        }
      }
    }
  });

  it('rises monotonically with the speed deviation', () => {
    const band = speedBand(vertical60, TUNING);
    let prev = 0;
    for (const dv of [0.2, 0.5, 1.0]) {
      const s = { ...core(vertical60, band.max + dv), speed: band.max + dv };
      s.distance = zoneForElement(vertical60, s.speed, TUNING).center;
      const r = takeoffRisk(vertical60, s, TUNING);
      expect(r).toBeGreaterThan(prev);
      prev = r;
    }
  });

  it('rises monotonically with the distance deviation (too early and too close)', () => {
    const z = zoneForElement(vertical60, 5.8, TUNING);
    let prevEarly = 0;
    for (const dd of [0.2, 0.6, 1.2]) {
      const r = takeoffRisk(vertical60, { ...core(vertical60, 5.8), distance: z.far + dd }, TUNING);
      expect(r).toBeGreaterThan(prevEarly);
      prevEarly = r;
    }
    const tooClose = takeoffRisk(
      vertical60,
      { ...core(vertical60, 5.8), distance: z.lastPoint },
      TUNING,
    );
    expect(tooClose).toBeGreaterThan(0);
  });

  it('rises monotonically with the angle beyond the tolerance', () => {
    let prev = 0;
    for (const a of [15, 22, 30]) {
      const r = takeoffRisk(vertical60, { ...core(vertical60, 5.8), angle: a * DEG }, TUNING);
      expect(r).toBeGreaterThan(prev);
      prev = r;
    }
  });

  it('85 cm oxer is riskier than a cross at the same deviation', () => {
    const v = 6.5;
    const cases = [
      (el) => ({ distance: zoneForElement(el, v, TUNING).far + 0.5, angle: 0, speed: v }),
      (el) => ({ distance: zoneForElement(el, v, TUNING).center, angle: 20 * DEG, speed: v }),
      (el) => ({
        distance: zoneForElement(el, speedBand(el, TUNING).max + 0.4, TUNING).center,
        angle: 0,
        speed: speedBand(el, TUNING).max + 0.4,
      }),
    ];
    for (const mk of cases) {
      const rc = takeoffRisk(cross, { gait: 'canter', ...mk(cross) }, TUNING);
      const ro = takeoffRisk(oxer85, { gait: 'canter', ...mk(oxer85) }, TUNING);
      expect(rc).toBeGreaterThan(0);
      expect(ro).toBeGreaterThan(rc);
    }
  });

  it('a self jump always carries clearly increased risk', () => {
    for (const el of all) {
      const s = core(el, speedBand(el, TUNING).min + 0.3);
      const self = takeoffRisk(el, { ...s, self: true }, TUNING);
      expect(self).toBeGreaterThanOrEqual(0.3);
    }
  });

  it('is capped', () => {
    const r = takeoffRisk(
      oxer85,
      { gait: 'canter', speed: 4.5, distance: 6, angle: 29 * DEG, self: true },
      TUNING,
    );
    expect(r).toBeLessThanOrEqual(TUNING.jump.risk.max);
    expect(r).toBeGreaterThan(0.8);
  });
});
