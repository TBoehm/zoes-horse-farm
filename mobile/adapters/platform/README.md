# `:adapters:platform` – clock, lifecycle and device facts

Port of `src/adapters/platform/`. Package `app.zoeshorsefarm.platform`, depends on `:core:application`.

| Kotlin | Web | Role |
| --- | --- | --- |
| `SystemClock` | `clock.js` | `Clock` port on `kotlin.time.Clock` (no new dependency) |
| `appVersionOf`, `DEV_VERSION` | `app-version.js` | Version text; the app shell passes what its build knows |
| `AppLifecycle`, `ManualAppLifecycle`, `bindCrashGuardLifecycle` | `page-lifecycle.js` | Foreground/background port for the crash guard; the shells call `ManualAppLifecycle.update` |
| `DeviceInfo`, `DeviceInfoSource`, `SystemDeviceInfo` | (new) | Memory, cores, touch, screen pixels, GPU name for the graphics budget; iOS via NSProcessInfo, UIScreen and Metal, JVM for development |
| `ErrorLog`, `describeError`, `describeDevice` | `debug-info.js` | Pure debug-box text and the ring of the last errors |
| `GameKey` | `game-keys.js` | The one list of keys the game reacts to; `:adapters:input` uses it too |
| `InputMode` (`onKey(GameKey?)`), `classifyDevice`, `isPortrait` | `input-mode.js` | Touch mode; `InputMode.mobile()` is always touch, `HYBRID` for tablets with a keyboard |

No tab id: `tab-id.js` gives every browser tab an id that survives the reload after a crash. The native app
has one instance per install and no such storage, and a per-process id would be new after a crash, which
makes the crash guard treat the crashed run as another live instance. The app therefore passes
`tabId = null` to `CrashGuard`.

Android actuals (`SystemDeviceInfo`, lifecycle observer) come with the Android target.

Dropped as browser-only: `webgl.js` (WebGL check, Filament decides on the device), `test-hooks.js`
(`window.__zhfTest`, `?testhooks`), `?debug` URL flag and the console/window error capture of
`debug-info.js` (the shell feeds `ErrorLog.record`), DOM event wiring of `input-mode.js` and
`page-lifecycle.js`, PWA parts.
