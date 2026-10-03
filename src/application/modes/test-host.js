// Test double for the host that the ride session gives to a mode.
export function fakeHost() {
  const calls = { feedback: [], rebuildIn: [], rebuildNow: [] };
  return {
    calls,
    feedback: (key) => calls.feedback.push(key),
    rebuildIn: (id, seconds) => calls.rebuildIn.push([id, seconds]),
    rebuildNow: (id) => calls.rebuildNow.push(id),
  };
}
