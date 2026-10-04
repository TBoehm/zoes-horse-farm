// Frame-rate display (rule 4): the averaging and the text are pure, the ride screen only shows them.

/**
 * Averages the frame rate over a fixed interval, so that the number does not flicker.
 * frame(dtSeconds) returns the new rounded fps once per interval, otherwise null. A frame longer
 * than maxFrameS is a real interruption (e.g. a suspended tab), not a slow game: it starts a new
 * interval instead of dragging the average down.
 */
export function createFpsMeter({ intervalS = 0.5, maxFrameS = 1 } = {}) {
  let elapsed = 0;
  let frames = 0;
  return {
    frame(dtSeconds) {
      if (!(dtSeconds > 0) || !Number.isFinite(dtSeconds)) return null;
      if (dtSeconds > maxFrameS) {
        elapsed = 0;
        frames = 0;
        return null;
      }
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
 * Text of the display, built entirely from translations (word order, separator and placeholder
 * belong to the language files): "58 fps · Medium", "58 fps · Medium (auto)" for an automatically
 * chosen level, "58 fps" without a level. `t` is the translate function; fps null = not measured
 * yet.
 */
export function formatFpsText({ fps, level, auto }, t) {
  const value = fps === null || fps === undefined ? t('ride.fpsNone') : fps;
  if (!level) return t('ride.fps', { fps: value });
  return t(auto ? 'ride.fpsLevelAuto' : 'ride.fpsLevel', {
    fps: value,
    level: t(`graphics.${level}`),
  });
}
