# `:adapters:input` – controls without a UI toolkit

Port of `src/adapters/input/` (web). Package `app.zoeshorsefarm.input`. Depends on `:core:application`
only (`Tuning` and `clamp` come through it). No DOM, no Compose: the UI feeds events in, the game polls one
`InputState` per frame.

| Kotlin | Web |
|---|---|
| `InputState`, `mergeInputs` | `input.js` (the contract between input and game) |
| `StickOutput`, `mapStick`, `mapStickXY` | `joystick-mapping.js` (hybrid dead zone, axial zones, steer gain; values from `TUNING.control`) |
| `TouchInput` | state of `touch-controls.js` (stick, gallop toggle, jump/pause/camera edges, visibility) |
| `KeyboardInput`, `GameKey` | `keyboard.js` + the key list of `platform/game-keys.js` |
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
val state = input.poll()             // once per frame; jump/pause/camera are one-shot edges
```

## Deviations from the web app

- `GameKey` is defined here for now (enum of the 13 game keys with their web `code`). It is meant to move
  into `:adapters:platform` (web: `platform/game-keys.js`, also used by the input-mode detection) once that
  module is ported; `:adapters:input` does not depend on `:adapters:platform` yet.
- DOM-only parts are not ported: `focus-trap.js` (and its tests), `isControlTarget` (focused buttons and text
  fields keep their keys: Compose handles focus itself), `readStickEvent` (nipplejs event format). The
  keyboard consumes a key by returning `true` from `onKeyDown` instead of calling `preventDefault`.
- The web test "a touch-mode switch caused by an arrow key also counts" tests the input-mode detector of
  the platform module, not the input; it belongs to `:adapters:platform`.
