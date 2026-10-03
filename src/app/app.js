// App-Rahmen: Ebenen (3D, Oberfläche, Hinweise) und Bildschirm-Stapel.
import { createEmitter } from '../core/events.js';
import { onLangChange, t } from '../core/i18n.js';
import { h, clear } from './dom.js';

/**
 * Ein Bildschirm wird per register(name, factory) angemeldet.
 * factory(ctx, params) → { el, music?: bool, rerenderOnLang?: bool, screenClass?: string,
 *   destroy?(), onShow?(), onCover?() }
 * - music: Menü-Melodie soll laufen (Regel 51)
 * - rerenderOnLang (Standard true): bei Sprachwechsel neu aufbauen
 */
export function createApp({ root, store, inputMode }) {
  const emitter = createEmitter();
  const factories = new Map();
  const stack = [];
  const services = {};

  clear(root);
  const sceneLayer = h('div', { class: 'layer layer-scene', id: 'scene' });
  const uiLayer = h('div', { class: 'layer layer-ui', id: 'ui' });
  const overlayLayer = h('div', { class: 'layer layer-overlay', id: 'overlay' });
  root.append(sceneLayer, uiLayer, overlayLayer);

  const ctx = {
    get app() {
      return app;
    },
    store,
    inputMode,
    services,
    t,
    h,
  };

  function mount(entry) {
    entry.instance = factories.get(entry.name)(ctx, entry.params ?? {});
    entry.wrapper = h(
      'div',
      { class: `screen ${entry.instance.screenClass ?? ''}`, dataset: { screen: entry.name } },
      entry.instance.el,
    );
    uiLayer.append(entry.wrapper);
  }

  function unmount(entry) {
    entry.instance?.destroy?.();
    entry.wrapper?.remove();
    entry.instance = null;
    entry.wrapper = null;
  }

  function top() {
    return stack[stack.length - 1] ?? null;
  }

  function changed() {
    const current = top();
    for (const entry of stack) entry.wrapper.hidden = entry !== current;
    current?.instance.onShow?.();
    emitter.emit('screen', {
      name: current?.name ?? null,
      music: Boolean(current?.instance.music),
      stack: stack.map((e) => e.name),
    });
  }

  const app = {
    ctx,
    services,
    layers: { scene: sceneLayer, ui: uiLayer, overlay: overlayLayer },
    register(name, factory) {
      factories.set(name, factory);
    },
    has(name) {
      return factories.has(name);
    },
    /** Ersetzt den ganzen Stapel durch einen Bildschirm. */
    go(name, params) {
      while (stack.length) unmount(stack.pop());
      const entry = { name, params };
      stack.push(entry);
      mount(entry);
      changed();
    },
    /** Legt einen Bildschirm über den aktuellen (z. B. Einstellungen aus dem Pausemenü). */
    push(name, params) {
      top()?.instance.onCover?.();
      const entry = { name, params };
      stack.push(entry);
      mount(entry);
      changed();
    },
    pop() {
      if (stack.length <= 1) return;
      unmount(stack.pop());
      changed();
    },
    get current() {
      return top()?.name ?? null;
    },
    get stack() {
      return stack.map((e) => e.name);
    },
    on: emitter.on,
    emit: emitter.emit,
  };

  onLangChange(() => {
    for (const entry of stack) {
      if (entry.instance.rerenderOnLang === false) continue;
      const old = entry.instance;
      const next = factories.get(entry.name)(ctx, entry.params ?? {});
      old.el.replaceWith(next.el);
      old.destroy?.();
      entry.instance = next;
    }
    changed();
  });

  return app;
}
