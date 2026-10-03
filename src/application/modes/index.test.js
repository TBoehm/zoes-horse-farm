import { describe, expect, it } from 'vitest';
import { createRideMode } from './index.js';

describe('createRideMode', () => {
  it('defaults to the free mode', () => {
    expect(createRideMode().id).toBe('free');
    expect(createRideMode({}).id).toBe('free');
  });

  it('creates the course mode for the given course', () => {
    const mode = createRideMode({ mode: 'course', courseId: 2 });
    expect(mode.id).toBe('course');
    expect(mode.course.id).toBe(2);
  });

  it('rejects unknown modes', () => {
    expect(() => createRideMode({ mode: 'nope' })).toThrow(/unknown ride mode/i);
  });
});
