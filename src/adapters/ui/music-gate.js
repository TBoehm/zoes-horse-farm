// Music per screen (rule 51), with an optional start delay: the results screen waits a moment so
// that the melody does not clash with the finish signal.

/**
 * @param {(wanted: boolean) => void} setWanted switches the menu melody on or off
 * @returns {{ onScreen(screen: { music?: boolean, musicDelayMs?: number }): void }}
 */
export function createMusicGate(setWanted) {
  let timer = null;
  let wanted = false;
  const apply = (value) => {
    wanted = value;
    setWanted(value);
  };
  return {
    onScreen({ music, musicDelayMs = 0 }) {
      clearTimeout(timer);
      timer = null;
      if (!music) {
        apply(false);
      } else if (musicDelayMs > 0 && !wanted) {
        timer = setTimeout(() => {
          timer = null;
          apply(true);
        }, musicDelayMs);
      } else {
        apply(true);
      }
    },
  };
}
