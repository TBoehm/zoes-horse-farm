import { describe, expect, it } from 'vitest';
import { getTabId, TAB_ID_KEY } from './tab-id.js';

const memory = (initial = {}) => {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    map,
  };
};

describe('tab id', () => {
  it('creates an id once and returns the same one afterwards (a reload of the tab)', () => {
    const storage = memory();
    let n = 0;
    const createId = () => `id-${(n += 1)}`;
    expect(getTabId({ storage, createId })).toBe('id-1');
    expect(getTabId({ storage, createId })).toBe('id-1');
    expect(storage.map.get(TAB_ID_KEY)).toBe('id-1');
  });

  it('another tab (own session storage) gets another id', () => {
    const a = getTabId({ storage: memory() });
    const b = getTabId({ storage: memory() });
    expect(a).toBeTruthy();
    expect(a).not.toBe(b);
  });

  it('is unknown (null) without usable session storage', () => {
    expect(getTabId({ storage: null })).toBeNull();
    const broken = {
      getItem: () => {
        throw new Error('SecurityError');
      },
      setItem: () => {},
    };
    expect(getTabId({ storage: broken })).toBeNull();
    const full = {
      getItem: () => null,
      setItem: () => {
        throw new Error('QuotaExceededError');
      },
    };
    expect(getTabId({ storage: full })).toBeNull();
  });
});
