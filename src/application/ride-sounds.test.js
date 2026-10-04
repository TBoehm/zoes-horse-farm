import { describe, expect, it } from 'vitest';
import { createSoundMapper } from './ride-sounds.js';

const names = (commands) => commands.map((c) => c.name);

describe('sound mapper', () => {
  it('maps takeoff, landing and rail-down events to sound commands, in event order', () => {
    const mapper = createSoundMapper();
    const out = mapper.commandsFor([
      { type: 'takeoff', elementId: 'a', dir: 1 },
      { type: 'railDown', elementId: 'a', rail: 0, dir: 1 },
      { type: 'landed', elementId: 'a', dir: 1, knocked: true },
      { type: 'hop' },
      { type: 'swerve', elementId: 'a' },
    ]);
    expect(out).toEqual([
      { type: 'sound', name: 'takeoff' },
      { type: 'sound', name: 'railDown' },
      { type: 'sound', name: 'landing' },
    ]);
  });

  it('returns one shared empty list when no sound is due', () => {
    const mapper = createSoundMapper();
    const a = mapper.commandsFor([{ type: 'hop' }]);
    expect(a).toHaveLength(0);
    expect(mapper.commandsFor([])).toBe(a);
  });

  it('plays at most one rail-down sound per element per jump', () => {
    const mapper = createSoundMapper();
    const out = mapper.commandsFor([
      { type: 'takeoff', elementId: 'o', dir: 1 },
      { type: 'railDown', elementId: 'o', rail: 0, dir: 1 },
      { type: 'railDown', elementId: 'o', rail: 1, dir: 1 },
    ]);
    expect(names(out)).toEqual(['takeoff', 'railDown']);
  });

  it('dedupes across steps of the same jump, but sounds again at the next jump', () => {
    const mapper = createSoundMapper();
    expect(names(mapper.commandsFor([{ type: 'takeoff', elementId: 'o', dir: 1 }]))).toEqual([
      'takeoff',
    ]);
    expect(
      names(mapper.commandsFor([{ type: 'railDown', elementId: 'o', rail: 0, dir: 1 }])),
    ).toEqual(['railDown']);
    expect(
      names(mapper.commandsFor([{ type: 'railDown', elementId: 'o', rail: 1, dir: 1 }])),
    ).toEqual([]);
    mapper.commandsFor([{ type: 'takeoff', elementId: 'o', dir: -1 }]);
    expect(
      names(mapper.commandsFor([{ type: 'railDown', elementId: 'o', rail: 1, dir: -1 }])),
    ).toEqual(['railDown']);
  });

  it('keeps a separate count for each element', () => {
    const mapper = createSoundMapper();
    const out = mapper.commandsFor([
      { type: 'railDown', elementId: 'a', rail: 0, dir: 1 },
      { type: 'railDown', elementId: 'b', rail: 0, dir: 1 },
    ]);
    expect(names(out)).toEqual(['railDown', 'railDown']);
  });

  it('forgets everything on reset', () => {
    const mapper = createSoundMapper();
    mapper.commandsFor([{ type: 'railDown', elementId: 'a', rail: 0, dir: 1 }]);
    mapper.reset();
    expect(
      names(mapper.commandsFor([{ type: 'railDown', elementId: 'a', rail: 0, dir: 1 }])),
    ).toEqual(['railDown']);
  });
});
