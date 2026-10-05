# `:adapters:presentation` – screen logic without a UI toolkit

Port of the logic and state of `src/adapters/ui/**` (web). Package `app.zoeshorsefarm.presentation`. Depends on
`:core:application`, `:adapters:i18n`, `:adapters:input`, `:adapters:platform` and `:adapters:audio`.

The Compose layer (`:adapters:ui`, later) only **renders these models and calls their actions**. A model holds
state and the actions of one screen, calls application code (never game rules) and resolves every text through
`I18n` keys. Models are not thread safe: drive them from the UI thread. Delayed tasks (music delay, toasts) run on
`UiScheduler`: the UI calls `scheduler.tick()` on every frame of its own clock, so the tasks run on the UI thread.
**Never reuse the `Scheduler` of `:adapters:audio` for the UI**: its JVM implementation runs tasks on a daemon thread.

## Pieces

| Kotlin | Web | Role |
|---|---|---|
| `AppContext`, `UiScheduler`, `LocalTimeZone` | `ctx` of `createApp` | store, settings service, input mode, clock, i18n, navigator, `UiScheduler`, lifecycle, badge toasts, sound, version, time zone (default UTC) |
| `nav/Route`, `nav/ScreenModel`, `nav/AppNavigator` | `app.js` | typed routes with params, `go`/`push`/`pop`, redirect (guards), music flag and delay per screen, rebuild on language change, `onScreen` event, rotate-blocked event |
| `Screens.kt` (`registerScreens`, `startRoute`) | `main.js` + the `register*` files | one factory per route; first screen of the start sequence |
| `menu/MainMenuModel`, `MenuRegistry` | `menu.js` | entries with order (courses 10, free 20, horse 30, badges 40, help 45, settings 50), greeting |
| `notice/FullNotice`, `RotateNotice`, `SaveNotice` | `notices.js` | no-3D / start-error notice, portrait notice (touch only), "not saved" note |
| `audio/MusicGate`, `AudioWiring`, `SoundPort` | `music-gate.js`, `audio-wiring.js` | music per screen with delay, settings to volumes, background to hidden |
| `settings/SettingsScreenModel`, rows | `settings-screen.js`, `settings-sections.js`, volume part of `audio-wiring.js` | sections language 10, graphics 20, aid 30, audio 40, reset 90 (not from pause), version 99 |
| `help/HelpContent`, `ControlsHelpModel` | `help-content.js`, `controls-help.js` | rows per mode, default mode, "Got it" (pop or next start screen) |
| `courses/Format`, `CoursePlan`, `CourseHud`, `CourseScreens` | `format.js`, `plan.js`, `course-hud.js`, `screens.js` | `formatCs`, plan as shapes, HUD chips, selection / prestart / results |
| `profile/NamePromptModel`, `MyHorseModel`, `BadgesModel`, `BadgeToastQueue`, `ResetSectionModel`, `BadgeEmblem` | `screens/profile/*` | name question, my horse (+ preview camera), badge overview, toasts, delete progress |
| `ride/RideScreenModel`, `RideEnginePort`, `FpsDisplay` | `ride-screen.js` (non-3D part), `fps-display.js` | pause menu, auto-pause, lost graphics, feedback, hints, input, session commands |
| `theme/DesignTokens`, `ButtonStyles`, `WcagColor` | first `:root` of `main.css`, `palette.test.js`, `tests/support/color.js` | colours (ARGB), button roles, contrast |

## Wiring (composition root)

```kotlin
val i18n = I18n()                                   // + i18n.setLang(settings.get().lang)
val navigator = AppNavigator(i18n)
val badgeToasts = BadgeToastQueue(i18n, scheduler, viewportHeight = { windowHeightPx })
val scheduler = UiScheduler(clock)                  // the UI calls scheduler.tick() every frame
val ctx = AppContext(store, settings, inputMode, clock, i18n, navigator, scheduler, lifecycle,
    badgeToasts, version = appVersionOf(buildVersion), sound = AudioSoundPort(audio),
    timeZone = { epochMs -> platformUtcOffsetMinutes(epochMs) })
AudioWiring(ctx)                                    // volumes, music per screen, background
registerScreens(ctx, rng = { Random.nextDouble() }, engine = filamentRideEngine, horsePreview = preview)
navigator.go(startRoute(store))
// the UI: navigator.onScreen { show navigator.currentModel }  and  when (model) { is MainMenuModel -> ... }
```

The shell calls `Audio.unlock()` / `onUserInteraction()` itself (the web's gesture listeners), forwards the window
size to `RotateNotice.update`, `BadgeToastQueue.relayout`, and `navigator.emitRotateBlocked(blocked)` from the
rotate notice's `onBlockedChange`.

## Models and their state

Every model with state has a `changes: Changes` (`listen { }` returns the unsubscribe function): read the model again
when it fires (`fire` does not allocate). The course HUD changes its time every frame without a change event: the UI
reads `hud.timeCs` / `hud.timeText` when it draws (`hudState` holds everything else and is rebuilt only on change). Texts are properties resolved on read, so a language change shows on the next read. Navigation
rebuilds the models of all screens except the ride (`rerenderOnLang = false`) when the language changes.

## Ride screen: what is ported, what remains

`RideScreenModel(ctx, route, rng, engine)` owns the `RideSession`, the `Input` (`input.touch` is fed by the Compose
touch layer), the pause menu (`pauseButtons`, `focusedPauseAction`, `lostNote`, `onPauseAction`), auto-pause
(lifecycle background, rotate notice, `onFocusLost`), Escape while paused (`onKeyDown`), the lost-graphics state
machine with its watchdog (8 s), the feedback toast and its timer, the frame-rate text, the graphics hints (lost
device, crash guard, low frame rate), the camera toggle, the hoof sound (`onFootfall`), the HUD state and the
mapping of every session command to input, sound, badge toasts and navigation. `createRideScreen` redirects a locked
course to the selection.

The engine adapter calls `model.frame(dt, rawDt)` once per frame and acts on the `FrameResult`:

```kotlin
when (model.frame(dt, rawDt)) {
    PAUSED -> governor.frame(rawDt, false)                       // still draw the scene
    STOP -> Unit
    RUNNING -> { /* update the 3D world from model.view, then */ governor.frame(rawDt, visible, model.busy) }
}
```

**Remains for the engine/UI wave** (all behind `RideEnginePort` or in the Compose layer): the 3D world, horse,
camera rig and shadow updates from `model.view`, `placeHorse`, hoof dust, `world.syncRails`, the graphics governor
and low-fps-hint objects (`lowFpsHintFrame`, `interruptMeasuring`, `onRideRestarted`, `canHintLowerLevel`, `startRestoreWatchdog` / `cancelRestoreWatchdog`), the crash-guard render lease
(`markRendering`/`frame`/`release`), `takeGraphicsHint` / `takeCrashHint`, `reloadGraphics`, the debug box
(`debug-display.js`, `?debug` only, not ported), DOM layout and focus handling, the touch control widgets.

## Deviations from the web app

- Badge dates are formatted from the language files (`date.long`, `date.month.N`, native-only `DATE_STRINGS`) in the zone of `AppContext.timeZone` (the web uses the browser's local zone; the default here is UTC until the shell provides one).
- Colours are `Argb` values, `ButtonRole` has five button roles (the brand colour is a heading colour, never a
  button: `DesignTokens.brand`). `palette.test.js` "every colour token used in the style sheets is defined" is CSS
  specific and not ported.
- The save notice is one note (the web may stack several); the initial keyboard focus of the controls help and the
  `scrollIntoView` of the reset dialog are DOM details (`ResetFocus` and `focusedPauseAction` give the same hints).
- `AudioWiring` does not unlock the audio (shell concern, see above).
