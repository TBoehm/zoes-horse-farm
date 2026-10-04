// Debug box of the ride (`?debug`, see adapters/platform/debug-info.js): GPU, graphics level,
// pixel ratios, drawing buffer, context losses and the last errors. The text is pure; the box
// refreshes about twice per second like the fps display, so nothing is built per frame.
import { h } from './dom.js';
import { createFpsMeter } from './fps-display.js';

const round = (n, digits = 2) => (Number.isFinite(n) ? Number(n.toFixed(digits)) : n);

/**
 * Text of the box. `info` is engine.diagnostics(), `errors` the entries of the error log (oldest
 * first). Every word comes from `t` (keys debug.*); only numbers and the technical strings (GPU
 * name, error messages) are inserted.
 */
export function formatDebugText(info, errors, t) {
  const at = (seconds) =>
    seconds === null || seconds === undefined
      ? t('debug.none')
      : t('debug.atSeconds', { s: Math.round(seconds) });
  const lines = [
    t('debug.gpu', { gpu: info.gpu || t('debug.none') }),
    t(info.auto ? 'debug.levelAuto' : 'debug.level', { level: t(`graphics.${info.level}`) }),
    t('debug.pixels', {
      device: round(info.devicePixelRatio),
      renderer: round(info.pixelRatio),
    }),
    t('debug.buffer', { width: info.bufferWidth, height: info.bufferHeight }),
    t('debug.maxTexture', { size: info.maxTextureSize }),
    t('debug.context', {
      lost: info.contextLost ?? 0,
      restored: info.contextRestored ?? 0,
      lostAt: at(info.lostAtS),
      restoredAt: at(info.restoredAtS),
    }),
  ];
  if (info.stagesPending > 0) lines.push(t('debug.stages', { count: info.stagesPending }));
  const list = errors ?? [];
  if (list.length === 0) {
    lines.push(t('debug.noErrors'));
  } else {
    lines.push(t('debug.errors', { count: list.length }));
    for (let i = list.length - 1; i >= 0; i -= 1) {
      lines.push(t('debug.error', { s: Math.round(list[i].atS), message: list[i].message }));
    }
  }
  return lines.join('\n');
}

/**
 * The box: `el` goes into the ride HUD, `frame(rawDt)` is called every frame and redraws about
 * twice per second, `renderTexts()` after a language change.
 * @param {{ diagnostics: () => object, errorLog: { entries: object[] }, t: Function }} deps
 */
export function createDebugBox({ diagnostics, errorLog, t }) {
  const el = h('div', { class: 'ride-debug', dataset: { hud: 'debug' } });
  const meter = createFpsMeter({ intervalS: 0.5, maxFrameS: Infinity });
  function render() {
    el.textContent = formatDebugText(diagnostics(), errorLog.entries, t);
  }
  render();
  return {
    el,
    frame(rawDt) {
      if (meter.frame(rawDt) !== null) render();
    },
    renderTexts: render,
  };
}
