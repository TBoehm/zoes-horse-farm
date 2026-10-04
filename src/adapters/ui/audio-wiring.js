// Sound wiring (SRT-006): store volumes, music per screen, mute in the background.
import { registerSettingsSection, toggleRow } from './settings-screen.js';
import { createAudio } from '../audio/index.js';
import { createMusicGate } from './music-gate.js';

function volumeRow(ctx, channel) {
  const { t, h, settings } = ctx;
  const s = settings.get();
  const volKey = `${channel}Volume`;
  const muteKey = `${channel}Muted`;
  const slider = h('input', {
    type: 'range',
    min: '0',
    max: '100',
    step: '5',
    value: String(Math.round(s[volKey] * 100)),
    'aria-label': t(`settings.${channel}Volume`),
    dataset: { name: volKey },
  });
  slider.addEventListener('input', () => settings.setVolume(channel, Number(slider.value) / 100));
  // Only the switch (no label of its own): it shows "sound on"; muted = off (volume is kept,
  // rule 52)
  const toggle = toggleRow({
    name: muteKey,
    value: !s[muteKey],
    onChange: (on) => settings.setMuted(channel, !on),
  });
  toggle.setAttribute('aria-label', t(`settings.${channel}On`));
  return h(
    'div',
    { class: 'setting-row setting-volume' },
    h('span', { class: 'setting-label' }, t(`settings.${channel}`)),
    h('div', { class: 'volume-controls' }, slider, toggle),
  );
}

export function registerAudio(app) {
  const { settings } = app.ctx;
  const audio = createAudio(settings.get());
  app.services.audio = audio;
  audio.installUnlock(window);

  settings.onChange((s) => audio.setVolumes(s));
  const musicGate = createMusicGate((wanted) => audio.setMusicWanted(wanted));
  app.on('screen', (screen) => musicGate.onScreen(screen));
  const onVisibility = () => audio.setHidden(document.hidden);
  document.addEventListener('visibilitychange', onVisibility);
  window.addEventListener('pagehide', () => audio.setHidden(true));
  window.addEventListener('pageshow', onVisibility);

  registerSettingsSection({
    id: 'audio',
    order: 40,
    render: (ctx) =>
      ctx.h('div', { class: 'settings-group' }, volumeRow(ctx, 'music'), volumeRow(ctx, 'sfx')),
  });
  return audio;
}
