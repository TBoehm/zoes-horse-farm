import { describe, expect, it } from 'vitest';
import { canStart, getCourse, listCourses, nextCourse } from './course-catalog.js';
import { COURSES } from '../domain/course/courses.js';
import { fakeStore } from './test-ports.js';

describe('listCourses', () => {
  it('lists all courses with obstacle count; only course 1 is open at the start', () => {
    const list = listCourses(fakeStore());
    expect(list.map((c) => c.id)).toEqual(COURSES.map((c) => c.id));
    expect(list[0]).toEqual({
      id: 1,
      obstacleCount: COURSES[0].obstacles.length,
      open: true,
      stars: 0,
      best: null,
    });
    expect(list.slice(1).every((c) => !c.open)).toBe(true);
  });

  it('opens every course up to the unlocked one', () => {
    const list = listCourses(fakeStore({ progress: { unlocked: 3 } }));
    expect(list.map((c) => c.open)).toEqual([true, true, true, false, false]);
  });

  it('reports stars and best result of a course', () => {
    const store = fakeStore({
      progress: { unlocked: 2, courses: { 1: { faults: 4, timeCs: 5123, stars: 2 } } },
    });
    const [first, second] = listCourses(store);
    expect(first.stars).toBe(2);
    expect(first.best).toEqual({ faults: 4, timeCs: 5123 });
    expect(second.best).toBeNull();
    expect(second.stars).toBe(0);
  });
});

describe('canStart', () => {
  it('allows open courses only', () => {
    const store = fakeStore({ progress: { unlocked: 2 } });
    expect(canStart(store, 1)).toBe(true);
    expect(canStart(store, 2)).toBe(true);
    expect(canStart(store, 3)).toBe(false);
  });

  it('rejects unknown ids', () => {
    const store = fakeStore({ progress: { unlocked: 5 } });
    expect(canStart(store, 0)).toBe(false);
    expect(canStart(store, 6)).toBe(false);
    expect(canStart(store, 'x')).toBe(false);
  });
});

describe('nextCourse', () => {
  it('returns the following course when it is open', () => {
    expect(nextCourse(fakeStore({ progress: { unlocked: 2 } }), 1)).toBe(2);
  });

  it('returns null when the following course is still locked', () => {
    expect(nextCourse(fakeStore({ progress: { unlocked: 2 } }), 2)).toBeNull();
  });

  it('returns null after the last course', () => {
    expect(nextCourse(fakeStore({ progress: { unlocked: 5 } }), COURSES.length)).toBeNull();
  });
});

describe('getCourse', () => {
  it('returns the course data for the prestart screen', () => {
    expect(getCourse(2)).toBe(COURSES[1]);
    expect(getCourse('2')).toBe(COURSES[1]);
  });
});
