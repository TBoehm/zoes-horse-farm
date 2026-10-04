// Diagnostics for tests on real devices: `?debug` in the URL shows a small text box in the ride
// (adapters/ui/debug-display.js). This module detects the flag and collects the errors the box
// lists. Never active without `?debug` (the composition root only installs the capture then).

// Number of errors the box lists, and the longest message kept (the box is small)
const MAX_ERRORS = 5;
const MAX_MESSAGE_LENGTH = 160;

export function debugRequested(search = globalThis.location?.search ?? '') {
  return new URLSearchParams(search).has('debug');
}

/** Text for anything that can be thrown, logged or passed to an error event. */
export function describeError(value) {
  try {
    if (value instanceof Error) return `${value.name}: ${value.message}`;
    return String(value);
  } catch {
    return 'unprintable error';
  }
}

/**
 * Ring of the last errors: `entries` is `[{ atS, message }]`, oldest first; `count` includes the
 * dropped ones. `now` returns seconds since the page started.
 */
export function createErrorLog({
  max = MAX_ERRORS,
  maxLength = MAX_MESSAGE_LENGTH,
  now = () => performance.now() / 1000,
} = {}) {
  const entries = [];
  let count = 0;
  return {
    add(message) {
      const text = String(message);
      count += 1;
      entries.push({
        atS: now(),
        message: text.length > maxLength ? `${text.slice(0, maxLength - 1)}…` : text,
      });
      if (entries.length > max) entries.shift();
    },
    get entries() {
      return entries;
    },
    get count() {
      return count;
    },
  };
}

/**
 * Feeds the log from console.error (our own error reporter writes there), window errors and
 * unhandled promise rejections. console.error still prints. Returns the function that undoes it.
 */
export function installErrorCapture(log, { win = window, con = console } = {}) {
  const record = (message) => {
    try {
      log.add(message);
    } catch {
      // the diagnostics must never break the game
    }
  };
  const original = con.error;
  const patched = (...args) => {
    record(args.map(describeError).join(' '));
    return original.apply(con, args);
  };
  const onError = (event) => record(event.message || describeError(event.error));
  const onRejection = (event) => record(describeError(event.reason));
  con.error = patched;
  win.addEventListener('error', onError);
  win.addEventListener('unhandledrejection', onRejection);
  return () => {
    if (con.error === patched) con.error = original;
    win.removeEventListener('error', onError);
    win.removeEventListener('unhandledrejection', onRejection);
  };
}
