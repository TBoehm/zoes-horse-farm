// App shell: layers (3D, UI, notices) and the screen stack.
import { createEmitter } from '../../shared/events.js';
import { onLangChange, t } from './i18n.js';
import { h, clear } from './dom.js';

/**
 * A screen is registered via register(name, factory).
 * factory(ctx, params) → { el, music?: bool, rerenderOnLang?: bool, screenClass?: string,
 *   destroy?(), onShow?(), onCover?() }
 * - music: the menu melody should play (rule 51)
 * - musicDelayMs: start the melody this long after the screen opened (results: after the finish signal)
 * - redirect: { name, params } instead of showing this screen (guards, e.g. a locked course);
 *   `el` is not used then
 * - rerenderOnLang (default true): rebuild when the language changes
 */
export function createApp({ root, store, inputMode, clock }) {
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
    clock,
    services,
    t,
    h,
  };

  function mount(entry) {
    entry.instance = factories.get(entry.name)(ctx, entry.params ?? {});
    if (entry.instance.redirect) return;
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
      musicDelayMs: current?.instance.musicDelayMs ?? 0,
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
    /** Replaces the whole stack with one screen. */
    go(name, params) {
      while (stack.length) unmount(stack.pop());
      const entry = { name, params };
      stack.push(entry);
      mount(entry);
      const redirect = entry.instance.redirect;
      if (redirect) app.go(redirect.name, redirect.params);
      else changed();
    },
    /** Puts a screen on top of the current one (e.g. settings from the pause menu). */
    push(name, params) {
      top()?.instance.onCover?.();
      const entry = { name, params };
      stack.push(entry);
      mount(entry);
      const redirect = entry.instance.redirect;
      if (redirect) {
        stack.pop();
        unmount(entry);
        app.go(redirect.name, redirect.params);
      } else changed();
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
