import { describe, expect, it } from 'vitest';
import { formatDebugText } from './debug-display.js';

// Marks every translated piece so that the test sees that no visible word is built in code
const t = (key, params = {}) => {
  const entries = Object.entries(params).map(([k, v]) => `${k}=${v}`);
  return `[${key}${entries.length ? ` ${entries.join(' ')}` : ''}]`;
};

const BASE = {
  gpu: 'ANGLE (Mali-G52)',
  budgetGpu: 'ANGLE (Mali-G52)',
  level: 'medium',
  auto: true,
  devicePixelRatio: 2.625,
  pixelRatio: 1.5,
  bufferWidth: 1200,
  bufferHeight: 750,
  maxTextureSize: 4096,
  contextLost: 0,
  contextRestored: 0,
  lostAtS: null,
  restoredAtS: null,
  stagesPending: 0,
  gpuEstimateMB: 123.4,
  gpuBudgetMB: 160,
  ratioCap: null,
  shadowCap: null,
  sceneryCapped: false,
  antialias: true,
  antialiasDropped: false,
};

const lines = (info, errors = [], lastCrash = null) =>
  formatDebugText(info, errors, t, lastCrash).split('\n');

describe('formatDebugText', () => {
  it('shows the GPU, level with the automatic flag, pixel ratios, buffer and texture size', () => {
    const out = lines(BASE);
    expect(out[0]).toBe('[debug.gpu gpu=ANGLE (Mali-G52)]');
    expect(out[1]).toBe('[debug.levelAuto level=[graphics.medium]]');
    expect(out[2]).toBe('[debug.pixels device=2.63 renderer=1.5]');
    expect(out[3]).toBe('[debug.buffer width=1200 height=750]');
    expect(out[4]).toBe('[debug.maxTexture size=4096]');
  });

  it('shows level, mode, seconds and time of the last detected crash', () => {
    const lastCrash = { level: 'medium', auto: true, seconds: 5.4, at: '2026-10-04T13:05:07.123Z' };
    expect(lines(BASE, [], lastCrash)).toContain(
      '[debug.crash level=[graphics.medium] mode=[debug.crashAuto] s=5 at=2026-10-04 13:05]',
    );
    const manual = { ...lastCrash, auto: false, level: 'high' };
    expect(lines(BASE, [], manual)).toContain(
      '[debug.crash level=[graphics.high] mode=[debug.crashManual] s=5 at=2026-10-04 13:05]',
    );
  });

  it('says so when no crash was detected', () => {
    expect(lines(BASE)).toContain('[debug.noCrash]');
  });

  it('shows the GPU memory estimate against the budget', () => {
    expect(lines(BASE)).toContain('[debug.gpuMemory estimate=123 budget=160]');
  });

  it('puts a capped pixel ratio on the memory line', () => {
    const out = lines({ ...BASE, ratioCap: { from: 2, to: 1.25 } });
    expect(out).toContain('[debug.gpuMemoryCapped estimate=123 budget=160 from=2 to=1.25]');
    expect(out.some((l) => l.startsWith('[debug.gpuMemory '))).toBe(false);
  });

  it('lists a capped shadow map and reduced scenery on their own lines', () => {
    const out = lines({ ...BASE, shadowCap: { from: 2048, to: 1024 }, sceneryCapped: true });
    expect(out).toContain('[debug.capShadow from=2048 to=1024]');
    expect(out).toContain('[debug.capScenery]');
    expect(lines(BASE).some((l) => l.includes('debug.cap'))).toBe(false);
  });

  it('says whether antialiasing is on, and when it was dropped for the budget', () => {
    expect(lines(BASE)).toContain('[debug.antialias state=[debug.on]]');
    expect(lines({ ...BASE, antialias: false })).toContain('[debug.antialias state=[debug.off]]');
    expect(lines({ ...BASE, antialias: false, antialiasDropped: true })).toContain(
      '[debug.antialiasDropped]',
    );
  });

  it('leaves out the automatic flag for a manual level', () => {
    expect(lines({ ...BASE, auto: false })[1]).toBe('[debug.level level=[graphics.medium]]');
  });

  it('says so when the GPU name is not known', () => {
    expect(lines({ ...BASE, gpu: '', budgetGpu: '' })[0]).toBe('[debug.gpu gpu=[debug.none]]');
  });

  it('shows the GPU the budget was based on only when it differs from the renderer', () => {
    expect(lines(BASE).some((l) => l.startsWith('[debug.gpuBudget'))).toBe(false);
    expect(lines({ ...BASE, budgetGpu: '' }).some((l) => l.startsWith('[debug.gpuBudget'))).toBe(
      false,
    );
    const out = lines({ ...BASE, budgetGpu: 'Intel(R) UHD Graphics' });
    expect(out[0]).toBe('[debug.gpu gpu=ANGLE (Mali-G52)]');
    expect(out[1]).toBe('[debug.gpuBudget gpu=Intel(R) UHD Graphics]');
  });

  it('falls back to the budget GPU when the renderer name is not available (lost context)', () => {
    expect(lines({ ...BASE, gpu: '' })[0]).toBe('[debug.gpu gpu=ANGLE (Mali-G52)]');
  });

  it('counts context losses and restores with the time since the start', () => {
    const out = lines({
      ...BASE,
      contextLost: 2,
      contextRestored: 1,
      lostAtS: 31.04,
      restoredAtS: 12.3,
    });
    expect(out).toContain(
      '[debug.context lost=2 restored=1 lostAt=[debug.atSeconds s=31] restoredAt=[debug.atSeconds s=12]]',
    );
  });

  it('shows a dash instead of a time when nothing happened yet', () => {
    expect(lines(BASE)).toContain(
      '[debug.context lost=0 restored=0 lostAt=[debug.none] restoredAt=[debug.none]]',
    );
  });

  it('shows pending quality stages only while there are some', () => {
    expect(lines(BASE).some((l) => l.includes('debug.stages'))).toBe(false);
    expect(lines({ ...BASE, stagesPending: 3 })).toContain('[debug.stages count=3]');
  });

  it('says there are no errors, or lists them newest first', () => {
    expect(lines(BASE).slice(-1)[0]).toBe('[debug.noErrors]');
    const out = lines(BASE, [
      { atS: 5.2, message: 'old' },
      { atS: 9.8, message: 'new' },
    ]);
    const at = out.indexOf('[debug.errors count=2]');
    expect(at).toBeGreaterThan(0);
    expect(out.slice(at + 1)).toEqual([
      '[debug.error s=10 message=new]',
      '[debug.error s=5 message=old]',
    ]);
  });

  it('builds no text of its own: every line starts with a translated piece', () => {
    for (const line of lines(BASE, [{ atS: 1, message: 'x' }]))
      expect(line.startsWith('[')).toBe(true);
  });

  it('copes with missing values', () => {
    expect(() => formatDebugText({}, undefined, t)).not.toThrow();
  });
});
