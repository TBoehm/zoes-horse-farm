# `:adapters:view3d` – the 3D view of the web app on the scene model

Port of `src/adapters/view3d/**` onto `:adapters:scene` (the renderer-neutral "three-lite" model, see
`mobile/adapters/scene/README.md`). Same geometry, colours, animation maths, LOD/detail rules; the
tests of the web app are ported 1:1 (`src/commonTest/kotlin/...`). Package root:
`app.zoeshorsefarm.view3d`. Depends on `:adapters:scene`, `:core:application` (and through it
`:core:domain`, `:core:shared`). It never references the renderer (Filament lives in another module).

```
src/commonMain/kotlin/app/zoeshorsefarm/view3d/   one Kotlin file per JS file (more where noted)
src/commonTest/kotlin/app/zoeshorsefarm/view3d/   one test class per JS test file
```

## File map JS -> Kotlin

Later ports add their rows here (keep the table sorted by JS file).

| JS (`src/adapters/view3d/`) | Kotlin (`view3d/`) | Test | Notes |
| --- | --- | --- | --- |
| `aid-marker.js` | `world/AidMarker.kt` | `WorldFingerprintTest.kt` | take-off band; `set(element, dir, zone)` |
| `arena.js` | `world/Arena.kt` (arena, sand, fence), `world/CourseLinesView.kt` (start/finish lines, `makeSignQuad`, `roundRect`) | `WorldBudgetTest.kt`, `WorldFingerprintTest.kt` | no JS test of its own; the sand shader patch is open, see below |
| `arena-decor.js` | `world/ArenaDecor.kt` | `WorldBudgetTest.kt` | bunting, pots, paddock props |
| `camera.js` | `CameraRig.kt` (`CameraRig`) | `CameraRigTest.kt` | uses `CameraMode` of `:core:application`; no JS test; expected poses were computed with the JS rig on three.js r186 |
| `camera-math.js` | `CameraMath.kt` | `CameraMathTest.kt` | |
| `decor-plan.js` | `world/DecorPlan.kt` | `DecorPlanTest.kt` | plain data classes instead of object literals |
| `detail-hold.js` | `DetailHold.kt` (`DetailHold`) | `DetailHoldTest.kt` | `createDetailHold()` is the class constructor |
| `dust.js` | `world/Dust.kt` (`DustPool`, `Dust`) | `DustTest.kt` | `Points` + `ShaderMaterial("hoof-dust")`; levels are `DustQuality` |
| `engine.js` | `engine/Engine.kt` (frame loop, context loss, ride sync, diagnostics), `engine/QualityController.kt` (level, budget fit, stages, governors), `engine/StartupPlan.kt`, `engine/EngineConfig.kt` | `EngineStartTest`, `EngineFrameTest`, `EngineQualityTest`, `EngineContextTest`, `EnginePowerTest`, `EngineRideTest`, `EngineAllocationTest` (jvmTest) | no JS test of its own; see "The engine" below |
| `renderer.js` | `engine/Renderer.kt` (`configureRenderer`, `RenderSizing`, `ViewMetrics`) | `RendererTest` | the WebGL renderer became settings of the scene `RenderBackend` |
| `ui/debug-display.js` (pure parts) | `engine/DebugDisplay.kt` (`formatDebugText`, `DebugBox`), `engine/EngineDiagnostics.kt` | `DebugDisplayTest` (all 21 JS cases), `DebugBoxTest` | the Compose UI shows `DebugBox.text` |
| `ui/fps-display.js` | already in `:adapters:presentation` (`ride/FpsDisplay.kt`, `FpsDisplayTest`) | – | not ported again: view3d may not depend on presentation |
| `environment.js` | `world/Environment.kt`, `world/PlantGeometry.kt` (terrain, trees, bushes, tufts), `world/Buildings.kt` | `WorldFingerprintTest.kt`, `WorldBudgetTest.kt` | the random numbers are drawn in the order of the JS, so the scenery is the same |
| `flight-paths.js` | `FlightPaths.kt` | `FlightPathsTest.kt` | |
| `flower-geometry.js` | `FlowerGeometry.kt` | `FlowerGeometryTest.kt` | |
| `meadow.js` | `world/Meadow.kt` | `WorldBudgetTest.kt` | |
| `meadow-plan.js` | `MeadowPlan.kt` | `MeadowPlanTest.kt` | |
| `obstacles.js` | `world/Obstacles.kt`, `world/ObstacleParts.kt`, `world/PoleAnimation.kt`, `world/Highlight.kt` | `WorldBudgetTest.kt`, `WorldFingerprintTest.kt` | rails are `Map<String, BooleanArray>` |
| `plant-shaders.js` | `PlantShaders.kt` | `PlantShadersTest.kt` | only the wind parameters; the shader maths is in the Filament backend |
| `resilience.js` | `Resilience.kt`, `Scheduler.kt` | `ResilienceTest.kt` | listens on the `RenderBackend` context listeners; `setTimeout` became the `Scheduler` port (`TickScheduler` is driven by the frame loop and by tests); `collectGpuObjects` is in the scene module |
| `sky.js` | `world/Sky.kt` | `WorldFingerprintTest.kt` | `SkyMaterial`; the PMREM map became `EnvironmentLight` (in `World`) |
| `textures.js` (surfaces) | `Textures.kt` | `TexturesTest.kt` | canvas = scene `Raster2D`, labels via `TextRasterizer`; no JS test |
| `textures.js` (geometry helpers) | `GeometryBuilder.kt` | `GeometryBuilderTest.kt` | `createGeometryBuilder` -> `GeometryBuilder` class |
| `wildlife.js` | `world/Wildlife.kt` | `WorldBudgetTest.kt` | |
| `world.js` | `world/World.kt`, `world/Managed.kt` | `WorldBudgetTest.kt`, `WorldStagesTest.kt`, `GrazingWorldTest.kt`, `WorldFingerprintTest.kt`, `WorldAllocationTest.kt` (jvmTest) | `World(backend, preset, release, textRasterizer)`; stages take the stage id string of `QualityStage.id` |
| `world-layout.js` | `WorldLayout.kt`, `SiteLayout.kt`, `FallingPoles.kt` | `WorldLayoutTest.kt` | split by topic: obstacles/stand/lines, fence/site/paddock/scatter, falling poles |
| (helper) | `JsNumbers.kt` | – | `jsRound`: JS `Math.round` semantics (halves go up) |

## Conventions of this module

- Names stay close to the JS (`createWind`, `planMeadow`, `fallTarget` …); JS factory closures with
  state became classes (`CameraRig`, `DetailHold`, `GpuEpoch`, `RenderGate`, `RestoreWatchdog`,
  `ErrorReporter`).
- Randomness comes from the seeded generators of the JS (same numbers as the web app); time comes
  in as a parameter or through the `Scheduler` port.
- Per-frame paths (`CameraRig.update`, flight poses, …) reuse scratch objects and do not allocate.

## The engine

`Engine(backend, settings, EngineConfig)` owns one world, one horse with rider and one camera, and
draws through the scene `RenderBackend`. The platform drives the loop: `engine.run(FrameHandler)`
starts it, the host calls `engine.frame(nowSeconds)` once per display frame (Compose, `CADisplayLink`,
`Choreographer`) and `engine.run(null)` stops it.

- **Before the backend exists:** `planStartup(level, view, device, budgetOverride)` answers the memory
  budget and whether the context gets antialiasing (`chooseAntialias`); `Engine.antialias` says the same
  afterwards. The device facts are the quality module's `DeviceInfo`.
- **Ride:** `beginRide(obstacles, flags)`, `restartRide(appearance, startPose)`, `showLines`,
  `setCameraMode`/`toggleCamera`, then per frame `updateRide(dt, rideView)` (horse, dust, poles, aid,
  shadow focus, camera, world) and `governorFrame(rawDt, measuring, busy)` / `lowFpsHintFrame`.
- **Level changes** (rule 4): applied at once without a running loop, in stages (6 frames apart, shader
  hold of at most 2.5 s) while a ride draws. The engine follows the settings service by itself.
- **Device loss:** `onContextLost` / `onContextRestored`, `takeGraphicsHint`, `takeCrashHint`,
  `startRestoreWatchdog` / `cancelRestoreWatchdog` (8 s), `setVisible` (lifecycle, for the loss rule).
- **Battery and thermal levers** (default: nothing changes): `setPaused(true)` draws one more frame and
  then stops drawing until something visible changes (`wantsFrames` / `demandListener` tell the host it
  may stop its display link); `setCapTo30Fps(true)` skips frames that come too early (the graphics
  automatic and the low-fps hint do not measure then, 30 fps would look like a slow device).
- **Diagnostics:** `diagnostics()` fills one `EngineDiagnostics` object; `formatDebugText` turns it into
  the lines of the debug box.
- The frame loop is allocation free (`EngineAllocationTest`); the level automatic reads the frame times
  the host passes in, it has no clock of its own.

## Open points of the world

- **Sand shader.** The sand material carries a `SandEffect` (key `sand-v1`, `:adapters:scene`); the
  GLSL maths of the web app is documented in the scene README ("Sand effect") for the Filament
  backend, which still has to implement it. The program counts per level are those of the web app
  (low 10, medium 12, high 20, see `WorldFingerprintTest`).
- **Allocation.** `World.update`, the pole stepping, the dust (also a footfall, `emitHoofDust`) and
  `setAid` allocate nothing per frame; with the grazing horses in view about 4 bytes per frame on
  average (the horses pick a new spot now and then); a pole transition costs about 0.5 KB. See
  `WorldAllocationTest` (JVM tests run without escape analysis, so boxing shows). The dust takes its
  numbers from a primitive `RandomSource` (`SeededRandom` has the numbers of `createRng`).
- **Take-off aid.** `World.setAid(elementId, dir, zone)` (or `setAid(RideAid?)` with the aid object of
  the ride session) instead of the web app's parameter object, so the ride screen allocates nothing.
