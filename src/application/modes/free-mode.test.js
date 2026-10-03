import { describe, expect, it } from 'vitest';
import { createFreeMode } from './free-mode.js';
import { TUNING } from '../../domain/sim/tuning.js';
import { fakeHost } from '../../../tests/support/test-host.js';

describe('free mode', () => {
  it('is DOM-free data: no HUD, no lines, quits to the menu', () => {
    const mode = createFreeMode();
    expect(mode.hudModel).toBeUndefined();
    expect(mode.lines).toBeNull();
    expect(mode.quitScreen).toBe('menu');
    expect(mode.quitLabelKey).toBe('pause.toMenu');
    expect(mode.highlight).toBeNull();
    expect(mode.finishMarked).toBe(false);
  });

  it('allows refusals at every element in both directions', () => {
    const mode = createFreeMode();
    expect(mode.rules.canRefuse('f1', 1)).toBe(true);
    expect(mode.rules.canRefuse('f1', -1)).toBe(true);
  });

  it('takes the rebuild delay from the tuning value', () => {
    const mode = createFreeMode({ tuning: { ...TUNING, rebuildDelayS: 9 } });
    const host = fakeHost();
    mode.onEvents([{ type: 'railDown', elementId: 'f2', rail: 0 }], host);
    expect(host.calls.rebuildIn).toEqual([['f2', 9]]);
  });

  it('rebuilds fallen rails after the delay and gives feedback', () => {
    const mode = createFreeMode();
    const host = fakeHost();
    mode.onEvents(
      [
        { type: 'railDown', elementId: 'f2', rail: 0 },
        { type: 'refusal', elementId: 'f2', dir: 1, reason: 'gait' },
        { type: 'landed', elementId: 'f1', dir: 1, knocked: true },
        { type: 'landed', elementId: 'f3', dir: 1, knocked: false },
      ],
      host,
    );
    expect(host.calls.rebuildIn).toEqual([['f2', TUNING.rebuildDelayS]]);
    expect(host.calls.feedback).toEqual(['feedback.refusal', 'feedback.knockdown']);
  });

  it('never ends the ride by itself', () => {
    const mode = createFreeMode();
    expect(
      mode.update(0.1, { horse: { x: 0, z: 0 }, prev: { x: 0, z: 0 } }, fakeHost()),
    ).toBeNull();
  });

  it('shows the jump aid at the approached element only when enabled', () => {
    const mode = createFreeMode();
    const approach = { elementId: 'f4', dir: -1 };
    expect(mode.aidTarget({ approach, settings: { aidFree: true } })).toEqual({
      elementId: 'f4',
      dir: -1,
    });
    expect(mode.aidTarget({ approach, settings: { aidFree: false } })).toBeNull();
    expect(mode.aidTarget({ approach: null, settings: { aidFree: true } })).toBeNull();
  });
});
