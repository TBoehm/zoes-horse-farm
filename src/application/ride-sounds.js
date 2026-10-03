// Sound commands of a ride: which sim events are heard (the audio adapter plays the names).
// No DOM, no WebAudio.

const SOUND_BY_EVENT = { takeoff: 'takeoff', landed: 'landing' };

export function createSoundMapper() {
  // elements whose rail-down sound already played in the current jump
  const railSounded = new Set();
  return {
    /** Sound commands for the events of one step (a jump makes at most one rail-down sound). */
    commandsFor(events) {
      const commands = [];
      for (const e of events) {
        if (e.type === 'takeoff') railSounded.delete(e.elementId);
        if (e.type === 'railDown') {
          if (railSounded.has(e.elementId)) continue;
          railSounded.add(e.elementId);
          commands.push({ type: 'sound', name: 'railDown' });
        } else if (SOUND_BY_EVENT[e.type]) {
          commands.push({ type: 'sound', name: SOUND_BY_EVENT[e.type] });
        }
      }
      return commands;
    },
    reset() {
      railSounded.clear();
    },
  };
}
