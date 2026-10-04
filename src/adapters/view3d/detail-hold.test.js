import { describe, expect, it } from 'vitest';
import { createDetailHold } from './detail-hold.js';

const mesh = (visible) => ({ visible });

describe('createDetailHold', () => {
  it('hides the visible meshes while the stages are out of step, and shows them again', () => {
    const hold = createDetailHold();
    const shown = mesh(true);
    const hidden = mesh(false);
    hold.sync([shown, hidden], false);
    expect(shown.visible).toBe(false);
    expect(hold.size).toBe(1);
    hold.sync([shown, hidden], true);
    expect(shown.visible).toBe(true);
    expect(hidden.visible).toBe(false); // never shown by the hold
    expect(hold.size).toBe(0);
  });

  it('tells which meshes are held for a moment (and not hidden for good)', () => {
    const hold = createDetailHold();
    const held = mesh(true);
    const hidden = mesh(false);
    hold.sync([held, hidden], false);
    expect(hold.has(held)).toBe(true);
    expect(hold.has(hidden)).toBe(false);
    hold.restore();
    expect(hold.has(held)).toBe(false);
  });

  it('does nothing when the stages are in step', () => {
    const hold = createDetailHold();
    const shown = mesh(true);
    hold.sync([shown, mesh(false)], true);
    expect(shown.visible).toBe(true);
    expect(hold.size).toBe(0);
  });

  it('can be synced repeatedly without losing what is held', () => {
    const hold = createDetailHold();
    const a = mesh(true);
    hold.sync([a], false);
    hold.sync([a], false);
    expect(hold.size).toBe(1);
    hold.sync([a], true);
    expect(a.visible).toBe(true);
  });

  it('restores the held meshes before the density decides what is visible', () => {
    const hold = createDetailHold();
    const flowers = mesh(true);
    const birds = mesh(true);
    hold.sync([flowers, birds], false);
    hold.restore();
    expect(flowers.visible && birds.visible).toBe(true);
    // the density stage of a downgrade then hides them for good
    flowers.visible = false;
    birds.visible = false;
    hold.sync([flowers, birds], true);
    expect(flowers.visible || birds.visible).toBe(false);
  });
});
