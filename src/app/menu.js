// Hauptmenü mit erweiterbarer Eintrags-Liste (Regeln 53, 54).
// Bereiche melden Einträge mit registerMenuEntry an; die Reihenfolge ergibt sich aus `order`.
import { h } from './dom.js';

const entries = [];

/** @param {{ id: string, order: number, labelKey: string, onSelect: (ctx) => void, visible?: (ctx) => boolean }} entry */
export function registerMenuEntry(entry) {
  const i = entries.findIndex((e) => e.id === entry.id);
  if (i >= 0) entries.splice(i, 1);
  entries.push(entry);
  entries.sort((a, b) => a.order - b.order);
}

export function getMenuEntries() {
  return [...entries];
}

/** Zusätzliche Inhalte über den Einträgen (z. B. Pferdename, SRT-005). */
const headerParts = [];
export function registerMenuHeader(render) {
  headerParts.push(render);
}

export function createMainMenuScreen(ctx) {
  const { t } = ctx;
  const list = h(
    'nav',
    { class: 'menu-list', 'aria-label': t('app.title') },
    getMenuEntries()
      .filter((e) => !e.visible || e.visible(ctx))
      .map((e) =>
        h(
          'button',
          {
            class: 'btn btn-menu',
            type: 'button',
            dataset: { entry: e.id },
            onclick: () => e.onSelect(ctx),
          },
          t(e.labelKey),
        ),
      ),
  );
  const el = h(
    'section',
    { class: 'panel panel-menu' },
    h(
      'header',
      { class: 'menu-header' },
      h('h1', { class: 'game-title' }, t('app.title')),
      h('p', { class: 'game-subtitle' }, t('app.subtitle')),
      headerParts.map((render) => render(ctx)),
    ),
    list,
  );
  return { el, music: true };
}
