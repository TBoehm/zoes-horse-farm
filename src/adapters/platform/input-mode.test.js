import { describe, expect, it } from 'vitest';
import { classifyDevice, createInputMode } from './input-mode.js';

describe('touch mode (rule 11)', () => {
  it('classifies devices', () => {
    expect(classifyDevice({ maxTouchPoints: 0 })).toBe('keyboard');
    expect(classifyDevice({ maxTouchPoints: 5, finePointer: false })).toBe('touch');
    expect(classifyDevice({ maxTouchPoints: 10, finePointer: true })).toBe('hybrid');
  });

  it('touch-only device: always active, keys change nothing', () => {
    const m = createInputMode({ device: 'touch' });
    expect(m.touch).toBe(true);
    m.handleKey({ code: 'KeyW' });
    expect(m.touch).toBe(true);
  });

  it('keyboard-only device: never active', () => {
    const m = createInputMode({ device: 'keyboard' });
    m.handlePointer({ pointerType: 'touch', type: 'pointerdown' });
    expect(m.touch).toBe(false);
  });

  it('hybrid: starts off, touch turns it on, game key turns it off', () => {
    const m = createInputMode({ device: 'hybrid' });
    const changes = [];
    m.onChange((v) => changes.push(v));
    expect(m.touch).toBe(false);
    m.handlePointer({ pointerType: 'mouse', type: 'pointerdown' });
    expect(m.touch).toBe(false);
    m.handlePointer({ pointerType: 'touch', type: 'pointerdown' });
    expect(m.touch).toBe(true);
    m.handleKey({ code: 'KeyX' });
    expect(m.touch).toBe(true);
    m.handleKey({ code: 'Space' });
    expect(m.touch).toBe(false);
    expect(changes).toEqual([true, false]);
  });
});
