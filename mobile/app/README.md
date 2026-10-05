# `:app` – the composition root of the native app

Port of `src/main.js` plus the engine wiring of `ride-screen.js`. Package `app.zoeshorsefarm.app`. It depends on
everything (presentation, view3d, render-filament, storage and what they bring) and contains no rules: it creates the
ports (store, clock, rng) and hands them to the layers.

| Kotlin | Role |
|---|---|
| `AppPlatform` | everything a shell provides: key-value backend, audio output, render backend factory, device info, clock, time zone, text rasterizer, log sink, languages, version, input device |
| `ZoesHorseFarmApp(platform)` | `main.js`: store (start language from the system, flush), settings service, crash guard (`tabId = null`, bound to the lifecycle), i18n, audio + `AudioWiring` + `MusicGate`, input mode, navigator + `registerScreens`, badge toasts, rotate and save notices, UI scheduler, error log; the entry points of the shells |
| `EngineBridge` (internal) | `RideEnginePort` for the ride model and the part of `ride-screen.js` that touches the engine |
| `FilamentBackendFactory`, `IosAppPlatform` | the real backend (`msaaSamples` from `planStartup`) and the iOS actuals |

## How a shell uses it

```kotlin
val platform = createIosAppPlatform()          // iosMain; Android follows with its own AppPlatform
val app = ZoesHorseFarmApp(platform)
// the UI: app.navigator.onScreen { show app.navigator.currentModel }  (when (model) { is MainMenuModel -> ... })
app.start()                                     // first screen: name question, controls help or main menu

// the 3D view (CAMetalLayer / Surface) as soon as it exists, and keep it for the whole run
app.onSurfaceCreated(iosSurfaceOf(layer), widthPx, heightPx, density)
app.onSurfaceResized(widthPx, heightPx, density)  // rotation, split screen
app.onSurfaceDestroyed()                          // before the platform frees it (Android surfaceDestroyed)

// every display frame (CADisplayLink, Choreographer, Compose frame clock), seconds of any monotonic clock
app.onFrame(nowSeconds)

app.onLifecycle(AppState.BACKGROUND)            // FOREGROUND when it returns
app.onViewportChanged(widthDp, heightDp)        // window size: rotate notice, toasts
app.onTouch()                                   // any touch: touch mode on hybrid devices, unlocks the sound
app.onKey(gameKey, down = true, repeat = false) // hardware keyboard; true = the game used it
app.onFocusLost()                               // split screen, system dialog
app.recordError(error)                          // uncaught errors: debug box and log sink
app.dispose()
```

The touch controls feed `(app.navigator.currentModel as RideScreenModel).input.touch`; every other screen is a model of
`:adapters:presentation` that the UI renders. `app.notice` (no 3D / start error), `app.rotateNotice`, `app.saveNotice`,
`app.badgeToasts` and `app.debugText` (only with `AppPlatform.debug`) are the other things the UI shows.

## Rules for the order of events

- **Background first.** On a switch to the background call `onLifecycle(BACKGROUND)` *before* `onSurfaceDestroyed()`
  (and `onLifecycle(FOREGROUND)` before `onSurfaceCreated()` on the way back). The engine must know that the app is
  hidden before the backend reports the lost surface, otherwise every app switch counts as an overload and lowers or
  blocks the graphics level (rule 4). `AppRideTest` covers both orders.
- **Send the surface early.** The 3D side (backend + `Engine`) is built lazily by the first ride, like `getEngine` in the
  web app, and then kept. A ride that starts without a surface (or whose backend cannot be created) counts as a lost
  device: it starts paused with the "graphics lost" note and continues when the surface arrives. After 8 s the pause menu
  offers "Reload", which builds backend and engine again (`reloadGraphics`).
- **Threads.** Everything runs on one thread: the one that calls `onFrame`. Audio renders on its own thread inside the
  audio module.
- **First touch unlocks the sound** (`onTouch`); `onLifecycle(FOREGROUND)` unlocks again after the system stopped it.

## Ride wiring

Navigation creates the ride model through `EngineBridge.createRide`; the bridge then
`beginRide(obstacles, flags)`, applies what the model said before the engine existed (camera mode, line labels, the
restart with the saved horse look and the start pose), marks the drawing for the crash guard (`markRendering`) and
`engine.run(FrameHandler)`. Per frame: `lease.frame`, `model.frame(dt, rawDt)` and, by its result,
`governorFrame(measuring = false)` (paused), nothing (stopped) or `updateRide` + `governorFrame(measuring = foreground,
busy)`; `engine.setPaused(model.paused)` each frame and on every model change. The engine stops when no ride is left in
the screen stack (the settings on top of the pause menu keep it), `horse.onFootfall` plays the hoof sound, the restore
watchdog runs on the UI scheduler (so it also counts without an engine), `ErrorLog` entries become `DebugError`s of the
debug box, and `antialias` of `planStartup` becomes `msaaSamples` of the Filament backend before the backend exists.
Tone mapping (ACES), shadows and the pixel ratio cap are set by the engine (`configureRenderer`).

## iOS framework

`ZoesHorseFarm` (static) for `iosArm64` and `iosSimulatorArm64`, `:adapters:platform` exported (`AppState`, `GameKey`).
Linking needs macOS: on Linux the link tasks are not created and `./gradlew qa` only compiles the klibs.
`IosAppPlatform.kt` has `createIosAppPlatform` (UserDefaults, AVAudioEngine, `SystemDeviceInfo`, `SystemClock`, Foundation
language/time zone/version, Filament on a `CAMetalLayer`, a file cache for the compiled materials in the caches
directory). Not verified on a device: the Metal surface, the sound output and the material cache have only been compiled.

## Not part of it yet

Compose UI and the Xcode project, the Android shell (`AppPlatform` with `SharedPreferences`, `AudioTrack`, a `Surface`
factory), the 3D preview of "My horse" (`registerScreens` gets the no-op `HorsePreview`), a real text rasterizer for the
signs (the block font is the default `AppPlatform.textRasterizer`), the battery levers of the engine (`wantsFrames`,
`setCapTo30Fps`; the app draws and ticks on every `onFrame` call).
