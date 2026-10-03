import { describe, expect, it } from 'vitest';
import { classifyDevice, createInputMode } from './input-mode.js';

describe('Touch-Modus (Regel 11)', () => {
  it('klassifiziert Geräte', () => {
    expect(classifyDevice({ maxTouchPoints: 0 })).toBe('keyboard');
    expect(classifyDevice({ maxTouchPoints: 5, finePointer: false })).toBe('touch');
    expect(classifyDevice({ maxTouchPoints: 10, finePointer: true })).toBe('hybrid');
  });

  it('reines Touch-Gerät: immer aktiv, Tasten ändern nichts', () => {
    const m = createInputMode({ device: 'touch' });
    expect(m.touch).toBe(true);
    m.handleKey({ code: 'KeyW' });
    expect(m.touch).toBe(true);
  });

  it('reines Tastatur-Gerät: nie aktiv', () => {
    const m = createInputMode({ device: 'keyboard' });
    m.handlePointer({ pointerType: 'touch', type: 'pointerdown' });
    expect(m.touch).toBe(false);
  });

  it('Hybrid: startet aus, Berührung an, Spieltaste aus', () => {
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
