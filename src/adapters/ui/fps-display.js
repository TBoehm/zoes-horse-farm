// Frame-rate display (rule 4): the averaging and the text are pure, the ride screen only shows them.

/**
 * Averages the frame rate over a fixed interval, so that the number does not flicker.
 * frame(dtSeconds) returns the new rounded fps once per interval, otherwise null.
 */
export function createFpsMeter({ intervalS = 0.5 } = {}) {
  let elapsed = 0;
  let frames = 0;
  return {
    frame(dtSeconds) {
      if (!(dtSeconds > 0) || !Number.isFinite(dtSeconds)) return null;
      elapsed += dtSeconds;
      frames += 1;
      if (elapsed < intervalS - 1e-9) return null;
      const fps = Math.round(frames / elapsed);
      elapsed = 0;
      frames = 0;
      return fps;
    },
    reset() {
      elapsed = 0;
      frames = 0;
    },
  };
}

/**
 * Text of the display: "58 fps · Medium" or, for an automatically chosen level, "58 fps · Medium
 * (auto)". `t` is the translate function; fps null = not measured yet.
 */
export function formatFpsText({ fps, level, auto }, t) {
  const rate = t('ride.fps', { fps: fps === null || fps === undefined ? '–' : fps });
  if (!level) return rate;
  const name = t(`graphics.${level}`);
  return `${rate} · ${auto ? `${name} ${t('ride.fpsAuto')}` : name}`;
}
