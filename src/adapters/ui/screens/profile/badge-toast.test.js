import { describe, expect, it } from 'vitest';
import { toastSlots } from './badge-toast.js';

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
