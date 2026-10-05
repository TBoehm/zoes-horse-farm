# `:adapters:input` – controls without a UI toolkit

Port of `src/adapters/input/` (web). Package `app.zoeshorsefarm.input`. Depends on `:core:application` (`Tuning` and
`clamp` come through it) and `:adapters:platform` (`GameKey`, the one list of game keys, shared with the
touch-mode detection). No DOM, no Compose: the UI feeds events in, the game polls one
`InputState` per frame.

| Kotlin | Web |
|---|---|
| `InputState`, `mergeInputs` | `input.js` (the contract between input and game) |
| `StickOutput`, `mapStick`, `mapStickXY` | `joystick-mapping.js` (hybrid dead zone, axial zones, steer gain; values from `TUNING.control`) |
| `TouchInput` | state of `touch-controls.js` (stick, gallop toggle, jump/pause/camera edges, visibility) |
| `KeyboardInput`, `GameKey` | `keyboard.js`; the key list `GameKey` lives in `:adapters:platform` (`game-keys.js`) |
| `Input` | `createInput` (aggregator: `poll`, `endGallop`, `resetTouchGallop`, `clearEdges`, touch-mode switch) |

## Use

```kotlin
val input = Input(touchMode = false, isActive = { ride.isRunning })
// Compose touch layer
input.touch.moveStickXY(x, yUp)      // thumb in stick-radius units, y UP; or moveStick(force, radian)
input.touch.releaseStick()
input.touch.toggleGallop(); input.touch.pressJump()   // pressPause(), pressCamera()
// hardware keyboard: map the platform key event to a GameKey (GameKey.fromCode for web codes)
if (key != null) consumed = input.keyboard.onKeyDown(key, repeat) // onKeyUp(key), onFocusLost()
// input mode changed (touch <-> keyboard): call BEFORE passing the key press that caused it
input.onTouchModeChange(on)
val state = input.poll()             // once per frame; jump/pause/camera are one-shot edges.
                                     // The state is reused by the next poll (no allocation per frame): copy what you keep
```

## Deviations from the web app

- `GameKey` (enum of the 13 game keys with their web `code`) is defined in `:adapters:platform`, like
  `platform/game-keys.js`, and used by `KeyboardInput` and `InputMode`, so the two cannot drift apart.
- DOM-only parts are not ported: `focus-trap.js` (and its tests), `isControlTarget` (focused buttons and text
  fields keep their keys: Compose handles focus itself), `readStickEvent` (nipplejs event format). The
  keyboard consumes a key by returning `true` from `onKeyDown` instead of calling `preventDefault`.
- The web tests of `input-mode.test.js` "shared game keys" are ported in `InputTest` (they need both modules):
  the keyboard handles exactly the keys that end the touch mode, and a switch caused by an arrow key counts.
