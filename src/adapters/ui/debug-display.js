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
  const gpu = info.gpu || info.budgetGpu;
  // the memory budget comes from the probe context: show its GPU name when it is another one
  const budgetGpuDiffers = Boolean(info.gpu && info.budgetGpu && info.budgetGpu !== info.gpu);
  const lines = [
    t('debug.gpu', { gpu: gpu || t('debug.none') }),
    ...(budgetGpuDiffers ? [t('debug.gpuBudget', { gpu: info.budgetGpu })] : []),
    t(info.auto ? 'debug.levelAuto' : 'debug.level', { level: t(`graphics.${info.level}`) }),
    t('debug.pixels', {
      device: round(info.devicePixelRatio),
      renderer: round(info.pixelRatio),
    }),
    t('debug.buffer', { width: info.bufferWidth, height: info.bufferHeight }),
    t('debug.maxTexture', { size: info.maxTextureSize }),
    info.antialiasDropped
      ? t('debug.antialiasDropped')
      : t('debug.antialias', { state: t(info.antialias ? 'debug.on' : 'debug.off') }),
    // GPU memory estimate against the budget; a capped pixel ratio belongs to the same line
    info.ratioCap
      ? t('debug.gpuMemoryCapped', {
          estimate: Math.round(info.gpuEstimateMB),
          budget: Math.round(info.gpuBudgetMB),
          from: round(info.ratioCap.from),
          to: round(info.ratioCap.to),
        })
      : t('debug.gpuMemory', {
          estimate: Math.round(info.gpuEstimateMB),
          budget: Math.round(info.gpuBudgetMB),
        }),
    t('debug.context', {
      lost: info.contextLost ?? 0,
      restored: info.contextRestored ?? 0,
      lostAt: at(info.lostAtS),
      restoredAt: at(info.restoredAtS),
    }),
  ];
  if (info.shadowCap) {
    lines.push(t('debug.capShadow', { from: info.shadowCap.from, to: info.shadowCap.to }));
  }
  if (info.sceneryCapped) lines.push(t('debug.capScenery'));
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
