// System clock port implementation: { nowIso() } for badge dates etc., { nowMs() } for durations.
export const systemClock = Object.freeze({
  nowIso: () => new Date().toISOString(),
  nowMs: () => Date.now(),
});
