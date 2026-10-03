// System clock port implementation: { nowIso() } for badge dates etc.
export const systemClock = Object.freeze({
  nowIso: () => new Date().toISOString(),
});
