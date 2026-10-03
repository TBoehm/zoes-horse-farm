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
import { DEG, makeElement } from './test-utils.js';

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

describe('Absprungzone, Reichweite, letzter Absprungpunkt', () => {
  it('reach > far > near > lastPoint > 0.25 m für alle Arten und Tempi', () => {
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

  it('Zonen-Mitte liegt real bei ca. 1,3–1,8 m (40–85 cm, Arbeitstempo)', () => {
    expect(zoneForElement(cross, 3.2, TUNING).center).toBeGreaterThan(1.2);
    for (const el of [vertical60, vertical80, oxer70, oxer85]) {
      const c = zoneForElement(el, 5.8, TUNING).center;
      expect(c).toBeGreaterThanOrEqual(1.3);
      expect(c).toBeLessThanOrEqual(1.85);
    }
  });

  it('Oxer wird etwas dichter angeritten als ein gleich hoher Steilsprung', () => {
    const v = makeElement('vertical', 0.85);
    expect(zoneForElement(oxer85, 5.8, TUNING).center).toBeLessThan(
      zoneForElement(v, 5.8, TUNING).center,
    );
  });

  it('Reichweite beginnt deutlich vor der Zone', () => {
    const z = zoneForElement(vertical60, 5.8, TUNING);
    expect(z.reach - z.far).toBeGreaterThanOrEqual(0.35 * 5.8 - 1e-9);
  });

  it('Zeitfenster: Kreuz großzügig, höher/breiter enger', () => {
    expect(zoneWindow(cross, TUNING)).toBeCloseTo(0.22, 9);
    expect(zoneWindow(vertical80, TUNING)).toBeLessThan(zoneWindow(vertical60, TUNING));
    expect(zoneWindow(oxer85, TUNING)).toBeLessThan(zoneWindow(vertical80, TUNING));
    expect(zoneWindow(oxer85, TUNING)).toBeGreaterThan(0.09);
    expect(zoneWindow(oxer85, TUNING)).toBeLessThan(0.12);
  });

  it('die Zone passt sich dem Tempo an (weiter weg und tiefer bei mehr Tempo)', () => {
    const slow = zoneForElement(vertical80, 4.5, TUNING);
    const fast = zoneForElement(vertical80, 7, TUNING);
    expect(fast.far).toBeGreaterThan(slow.far);
    expect(fast.center).toBeGreaterThan(slow.center);
    expect(fast.far - fast.near).toBeGreaterThan(slow.far - slow.near);
  });
});

describe('Springbarkeit und Sollbereiche', () => {
  it('Gangart: Halt/Schritt nie, Trab nur Kreuz, Galopp alles (Regel 16)', () => {
    for (const el of all) {
      expect(gaitAllows(el, 'halt')).toBe(false);
      expect(gaitAllows(el, 'walk')).toBe(false);
      expect(gaitAllows(el, 'canter')).toBe(true);
      expect(gaitAllows(el, 'trot')).toBe(el.kind === 'cross');
    }
  });

  it('Tempo-Sollbereich steigt mit Höhe und Spread', () => {
    expect(speedBand(cross, TUNING).min).toBeLessThanOrEqual(2.6);
    expect(speedBand(cross, TUNING).min).toBeLessThan(TUNING.speeds.trotMedium);
    expect(speedBand(vertical60, TUNING).min).toBeGreaterThanOrEqual(4.6);
    expect(speedBand(vertical80, TUNING).min).toBeGreaterThan(speedBand(vertical60, TUNING).min);
    expect(speedBand(oxer85, TUNING).min).toBeGreaterThanOrEqual(5.55);
    for (const el of all) {
      const b = speedBand(el, TUNING);
      expect(b.max).toBeGreaterThan(b.min);
      expect(b.max).toBeLessThanOrEqual(TUNING.speeds.canterMax);
      // mittleres Galopptempo liegt immer im sicheren Kern
      expect(TUNING.speeds.canterMedium).toBeGreaterThanOrEqual(b.min);
      expect(TUNING.speeds.canterMedium).toBeLessThanOrEqual(b.max);
    }
  });

  it('Selbstsprung-Mindesttempo liegt unter dem Sollbereich', () => {
    for (const el of all) expect(selfMinSpeed(el, TUNING)).toBeLessThan(speedBand(el, TUNING).min);
    expect(selfMinSpeed(oxer85, TUNING)).toBeGreaterThan(TUNING.speeds.canterMin);
  });

  it('Winkel-Toleranz 10–12°, enger bei schweren Hindernissen', () => {
    expect(safeAngle(cross, TUNING) / DEG).toBeLessThanOrEqual(12);
    expect(safeAngle(oxer85, TUNING) / DEG).toBeGreaterThanOrEqual(10);
    expect(safeAngle(oxer85, TUNING)).toBeLessThan(safeAngle(cross, TUNING));
    expect(difficultyOf(oxer85, TUNING)).toBeGreaterThan(difficultyOf(cross, TUNING));
  });
});

describe('Abwurfrisiko (Regeln 15, 18, 19, 20)', () => {
  it('sicherer Kern: exakt 0 über die ganze Zone, den Sollbereich und die Winkel-Toleranz', () => {
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

  it('steigt monoton mit der Tempo-Abweichung', () => {
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

  it('steigt monoton mit der Distanz-Abweichung (zu früh und zu dicht)', () => {
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

  it('steigt monoton mit dem Winkel jenseits der Toleranz', () => {
    let prev = 0;
    for (const a of [15, 22, 30]) {
      const r = takeoffRisk(vertical60, { ...core(vertical60, 5.8), angle: a * DEG }, TUNING);
      expect(r).toBeGreaterThan(prev);
      prev = r;
    }
  });

  it('85-cm-Oxer ist bei gleicher Abweichung riskanter als ein Kreuz', () => {
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

  it('Selbstsprung trägt immer deutlich erhöhtes Risiko', () => {
    for (const el of all) {
      const s = core(el, speedBand(el, TUNING).min + 0.3);
      const self = takeoffRisk(el, { ...s, self: true }, TUNING);
      expect(self).toBeGreaterThanOrEqual(0.3);
    }
  });

  it('ist gedeckelt', () => {
    const r = takeoffRisk(
      oxer85,
      { gait: 'canter', speed: 4.5, distance: 6, angle: 29 * DEG, self: true },
      TUNING,
    );
    expect(r).toBeLessThanOrEqual(TUNING.jump.risk.max);
    expect(r).toBeGreaterThan(0.8);
  });
});
