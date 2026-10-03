import { describe, expect, it } from 'vitest';
import { badgeSummary, describeBadge, listBadges } from './badge-overview.js';
import { BADGES } from '../domain/progress/badges.js';
import { fakeStore } from './test-ports.js';

const DATE = '2026-01-02T03:04:05.000Z';

describe('listBadges', () => {
  it('lists every badge in the order of rule 49 with text keys', () => {
    const list = listBadges(fakeStore());
    expect(list.map((b) => b.id)).toEqual(BADGES.map((b) => b.id));
    expect(list[0]).toEqual({
      id: 'firstJump',
      nameKey: 'badge.firstJump.name',
      conditionKey: 'badge.firstJump.condition',
      earnedAt: null,
    });
  });

  it('merges the earned date from the progress', () => {
    const list = listBadges(fakeStore({ progress: { badges: { clean: DATE } } }));
    expect(list.find((b) => b.id === 'clean').earnedAt).toBe(DATE);
    expect(list.find((b) => b.id === 'firstJump').earnedAt).toBeNull();
  });
});

describe('badgeSummary', () => {
  it('counts earned and total badges', () => {
    const store = fakeStore({ progress: { badges: { clean: DATE, firstJump: DATE } } });
    expect(badgeSummary(store)).toEqual({ earned: 2, total: BADGES.length });
  });
});

describe('describeBadge', () => {
  it('returns the text keys of one badge, null for unknown ids', () => {
    expect(describeBadge('clean')).toEqual({
      id: 'clean',
      nameKey: 'badge.clean.name',
      conditionKey: 'badge.clean.condition',
    });
    expect(describeBadge('nope')).toBeNull();
  });
});
