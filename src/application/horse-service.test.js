import { describe, expect, it } from 'vitest';
import {
  answerName,
  appearanceOptions,
  displayName,
  isValidName,
  needsNamePrompt,
  rename,
  setAppearance,
  skipName,
} from './horse-service.js';
import { fakeStore } from './test-ports.js';

describe('answerName', () => {
  it('saves the trimmed name and marks the question as answered', () => {
    const store = fakeStore();
    expect(answerName(store, '  Blitz  ')).toBe(true);
    expect(store.data.horse).toMatchObject({ name: 'Blitz', nameAnswered: true });
  });

  it('accepts 1 to 16 characters only', () => {
    const store = fakeStore();
    expect(answerName(store, 'A')).toBe(true);
    expect(answerName(store, 'x'.repeat(16))).toBe(true);
    expect(answerName(store, 'x'.repeat(17))).toBe(false);
    expect(store.data.horse.name).toBe('x'.repeat(16));
  });

  it('rejects empty, blank and non-string input without saving', () => {
    const store = fakeStore();
    expect(answerName(store, '')).toBe(false);
    expect(answerName(store, '   ')).toBe(false);
    expect(answerName(store, null)).toBe(false);
    expect(store.data.horse).toMatchObject({ name: null, nameAnswered: false });
  });
});

describe('skipName', () => {
  it('keeps the default name but counts the question as answered', () => {
    const store = fakeStore({ horse: { name: 'Old' } });
    skipName(store);
    expect(store.data.horse).toMatchObject({ name: null, nameAnswered: true });
  });
});

describe('needsNamePrompt', () => {
  it('asks until the question was answered or skipped (rule 43)', () => {
    const store = fakeStore();
    expect(needsNamePrompt(store)).toBe(true);
    skipName(store);
    expect(needsNamePrompt(store)).toBe(false);
  });

  it('does not ask again after an answer', () => {
    const store = fakeStore();
    answerName(store, 'Blitz');
    expect(needsNamePrompt(store)).toBe(false);
  });

  it('counts an old save with a valid name but no answered flag as answered', () => {
    const store = fakeStore({ horse: { name: 'Blitz', nameAnswered: false } });
    expect(needsNamePrompt(store)).toBe(false);
  });

  it('still asks when the saved name is not valid and the flag is missing', () => {
    expect(needsNamePrompt(fakeStore({ horse: { name: '', nameAnswered: false } }))).toBe(true);
    expect(needsNamePrompt(fakeStore({ horse: { name: null, nameAnswered: false } }))).toBe(true);
  });
});

describe('isValidName', () => {
  it('mirrors the name rule', () => {
    expect(isValidName('Blitz')).toBe(true);
    expect(isValidName('  ')).toBe(false);
    expect(isValidName('x'.repeat(17))).toBe(false);
  });
});

describe('rename', () => {
  it('saves a valid name', () => {
    const store = fakeStore({ horse: { name: 'Blitz', nameAnswered: true } });
    expect(rename(store, ' Sturm ')).toBe(true);
    expect(store.data.horse.name).toBe('Sturm');
  });

  it('does not save an invalid name and keeps the last valid one', () => {
    const store = fakeStore({ horse: { name: 'Blitz', nameAnswered: true } });
    expect(rename(store, '')).toBe(false);
    expect(rename(store, 'x'.repeat(40))).toBe(false);
    expect(store.data.horse.name).toBe('Blitz');
  });

  it('does not turn the language default name into a custom name', () => {
    const store = fakeStore();
    expect(rename(store, 'Stern', { defaultName: 'Stern' })).toBe(true);
    expect(store.data.horse).toMatchObject({ name: null, nameAnswered: true });
  });

  it('writes nothing when the name did not change', () => {
    const store = fakeStore({ horse: { name: 'Blitz', nameAnswered: true } });
    let changes = 0;
    store.onChange('horse', () => (changes += 1));
    rename(store, 'Blitz');
    expect(changes).toBe(0);
  });
});

describe('typing the language default name (one rule for the name prompt and renaming)', () => {
  const options = { defaultName: 'Stern' };
  const flows = {
    'name prompt': (store, input) => answerName(store, input, options),
    rename: (store, input) => rename(store, input, options),
  };

  for (const [flow, save] of Object.entries(flows)) {
    it(`${flow}: the default name stays "no custom name" but counts as answered`, () => {
      const store = fakeStore();
      expect(save(store, ' Stern ')).toBe(true);
      expect(store.data.horse).toMatchObject({ name: null, nameAnswered: true });
    });

    it(`${flow}: typing the default name over a custom name removes the custom name`, () => {
      const store = fakeStore({ horse: { name: 'Blitz', nameAnswered: true } });
      expect(save(store, 'Stern')).toBe(true);
      expect(store.data.horse).toMatchObject({ name: null, nameAnswered: true });
    });

    it(`${flow}: any other name is a custom name`, () => {
      const store = fakeStore();
      expect(save(store, 'Blitz')).toBe(true);
      expect(store.data.horse).toMatchObject({ name: 'Blitz', nameAnswered: true });
    });
  }

  it('without a known default name every valid name is a custom name', () => {
    const store = fakeStore();
    answerName(store, 'Stern');
    expect(store.data.horse.name).toBe('Stern');
  });
});

describe('setAppearance', () => {
  it('saves coat and marking and returns the saved appearance', () => {
    const store = fakeStore();
    expect(setAppearance(store, { coat: 'black', marking: 'blaze' })).toEqual({
      coat: 'black',
      marking: 'blaze',
    });
    expect(store.data.horse).toMatchObject({ coat: 'black', marking: 'blaze' });
  });

  it('changes only the given fields', () => {
    const store = fakeStore({ horse: { coat: 'grey', marking: 'snip' } });
    expect(setAppearance(store, { coat: 'pinto' })).toEqual({ coat: 'pinto', marking: 'snip' });
  });

  it('ignores values that are not part of the offered choices', () => {
    const store = fakeStore({ horse: { coat: 'grey', marking: 'snip' } });
    expect(setAppearance(store, { coat: 'purple', marking: 'none' })).toEqual({
      coat: 'grey',
      marking: 'none',
    });
  });
});

describe('appearanceOptions', () => {
  it('offers the coats and markings of the horse domain', () => {
    const { coats, markings } = appearanceOptions();
    expect(coats).toContain('bay');
    expect(markings).toContain('star');
  });
});

describe('displayName', () => {
  it('shows the own name, else the language default name', () => {
    expect(displayName({ name: 'Blitz' }, 'Stern')).toBe('Blitz');
    expect(displayName({ name: null }, 'Stern')).toBe('Stern');
    expect(displayName(undefined, 'Stern')).toBe('Stern');
  });
});
