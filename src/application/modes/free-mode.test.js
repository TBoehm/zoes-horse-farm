import { describe, expect, it } from 'vitest';
import { createFreeMode, REBUILD_DELAY_S } from './free-mode.js';
import { fakeApi, fakeStore } from './test-ports.js';

describe('free mode', () => {
  it('is DOM-free data: no HUD, no lines, quits to the menu', () => {
    const mode = createFreeMode({ store: fakeStore() });
    expect(mode.hudModel).toBeUndefined();
    expect(mode.lines).toBeNull();
    expect(mode.quitScreen).toBe('menu');
    expect(mode.quitLabelKey).toBe('pause.toMenu');
  });

  it('allows refusals at every element in both directions', () => {
    const mode = createFreeMode({ store: fakeStore() });
    expect(mode.rules.canRefuse('f1', 1)).toBe(true);
    expect(mode.rules.canRefuse('f1', -1)).toBe(true);
  });

  it('rebuilds fallen rails after the delay and gives feedback', () => {
    const mode = createFreeMode({ store: fakeStore() });
    const api = fakeApi();
    mode.onEvents(
      [
        { type: 'railDown', elementId: 'f2', rail: 0 },
        { type: 'refusal', elementId: 'f2', dir: 1, reason: 'gait' },
        { type: 'landed', elementId: 'f1', dir: 1, knocked: true },
        { type: 'landed', elementId: 'f3', dir: 1, knocked: false },
      ],
      api,
    );
    expect(api.calls.rebuildIn).toEqual([['f2', REBUILD_DELAY_S]]);
    expect(api.calls.feedback).toEqual(['feedback.refusal', 'feedback.knockdown']);
  });

  it('shows the jump aid at the approached element only when enabled', () => {
    const api = fakeApi();
    api.sim.approach = { elementId: 'f4', dir: -1 };
    expect(createFreeMode({ store: fakeStore({ aidFree: true }) }).aidTarget(api)).toEqual({
      elementId: 'f4',
      dir: -1,
    });
    expect(createFreeMode({ store: fakeStore({ aidFree: false }) }).aidTarget(api)).toBeNull();
    api.sim.approach = null;
    expect(createFreeMode({ store: fakeStore({ aidFree: true }) }).aidTarget(api)).toBeNull();
  });
});
