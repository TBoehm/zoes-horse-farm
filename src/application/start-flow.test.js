import { describe, expect, it } from 'vitest';
import { nextStartScreen, startSequence } from './start-flow.js';
import { fakeStore } from '../../tests/support/test-ports.js';

const store = ({ named = true, helpSeen = false } = {}) =>
  fakeStore({
    settings: { controlsHelpSeen: helpSeen },
    horse: { nameAnswered: named, name: null },
  });

describe('startSequence', () => {
  it('first start: name question, controls help, then the menu (rule 56)', () => {
    expect(startSequence(store({ named: false }))).toEqual(['namePrompt', 'controlsHelp', 'menu']);
  });

  it('existing save that never closed the help: help once, then the menu', () => {
    expect(startSequence(store({ named: true, helpSeen: false }))).toEqual([
      'controlsHelp',
      'menu',
    ]);
  });

  it('name not answered yet but help already seen: the name question comes first', () => {
    expect(startSequence(store({ named: false, helpSeen: true }))).toEqual(['namePrompt', 'menu']);
  });

  it('everything done: straight to the menu', () => {
    expect(startSequence(store({ named: true, helpSeen: true }))).toEqual(['menu']);
  });
});

describe('nextStartScreen', () => {
  it('walks the sequence as the steps get done', () => {
    const s = fakeStore({ settings: { controlsHelpSeen: false } });
    expect(nextStartScreen(s)).toBe('namePrompt');
    s.update('horse', (horse) => ({ ...horse, nameAnswered: true }));
    expect(nextStartScreen(s)).toBe('controlsHelp');
    s.update('settings', (settings) => ({ ...settings, controlsHelpSeen: true }));
    expect(nextStartScreen(s)).toBe('menu');
  });
});
