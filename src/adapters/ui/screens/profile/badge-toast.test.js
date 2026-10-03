import { describe, expect, it } from 'vitest';
import { stackOffsets, toastGap, toastSlots } from './badge-toast.js';

describe('badge toast stacking', () => {
  it('shows every toast in its own slot while there is room', () => {
    expect(toastSlots(2, 3)).toEqual([
      { index: 0, visible: true },
      { index: 1, visible: true },
    ]);
  });

  it('keeps toasts beyond the limit waiting (hidden) until a slot is free', () => {
    expect(toastSlots(4, 2).map((s) => s.visible)).toEqual([true, true, false, false]);
  });

  it('closes the gap when a toast is gone: the rest moves down by recomputing', () => {
    expect(toastSlots(1, 2)).toEqual([{ index: 0, visible: true }]);
    expect(toastSlots(0, 2)).toEqual([]);
  });
});

describe('badge toast stack offsets', () => {
  it('puts each toast above the previous ones, with a small gap', () => {
    expect(stackOffsets([44, 44, 60], 6)).toEqual([0, 50, 100]);
  });

  it('is empty without toasts', () => {
    expect(stackOffsets([])).toEqual([]);
  });
});

describe('badge toast placement between the touch controls', () => {
  it('is centred in the gap and limited to its width minus the margins', () => {
    // joystick ends at 154, the buttons start at 370 (568 px wide phone)
    expect(toastGap(154, 370, { margin: 8, minWidth: 100 })).toEqual({ x: 262, maxWidth: 200 });
  });

  it('never gets narrower than the minimum width', () => {
    expect(toastGap(200, 260, { margin: 8, minWidth: 120 }).maxWidth).toBe(120);
  });
});
