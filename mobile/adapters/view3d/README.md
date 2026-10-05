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
| `camera.js` | `CameraRig.kt` (`CameraRig`) | `CameraRigTest.kt` | uses `CameraMode` of `:core:application`; no JS test; expected poses were computed with the JS rig on three.js r186 |
| `camera-math.js` | `CameraMath.kt` | `CameraMathTest.kt` | |
| `detail-hold.js` | `DetailHold.kt` (`DetailHold`) | `DetailHoldTest.kt` | `createDetailHold()` is the class constructor |
| `flight-paths.js` | `FlightPaths.kt` | `FlightPathsTest.kt` | |
| `flower-geometry.js` | `FlowerGeometry.kt` | `FlowerGeometryTest.kt` | |
| `meadow-plan.js` | `MeadowPlan.kt` | `MeadowPlanTest.kt` | |
| `plant-shaders.js` | `PlantShaders.kt` | `PlantShadersTest.kt` | only the wind parameters; the shader maths is in the Filament backend |
| `resilience.js` | `Resilience.kt`, `Scheduler.kt` | `ResilienceTest.kt` | listens on the `RenderBackend` context listeners; `setTimeout` became the `Scheduler` port (`TickScheduler` is driven by the frame loop and by tests); `collectGpuObjects` is in the scene module |
| `textures.js` (surfaces) | `Textures.kt` | `TexturesTest.kt` | canvas = scene `Raster2D`, labels via `TextRasterizer`; no JS test |
| `textures.js` (geometry helpers) | `GeometryBuilder.kt` | `GeometryBuilderTest.kt` | `createGeometryBuilder` -> `GeometryBuilder` class |
| `world-layout.js` | `WorldLayout.kt`, `SiteLayout.kt`, `FallingPoles.kt` | `WorldLayoutTest.kt` | split by topic: obstacles/stand/lines, fence/site/paddock/scatter, falling poles |
| (helper) | `JsNumbers.kt` | – | `jsRound`: JS `Math.round` semantics (halves go up) |

## Conventions of this module

- Names stay close to the JS (`createWind`, `planMeadow`, `fallTarget` …); JS factory closures with
  state became classes (`CameraRig`, `DetailHold`, `GpuEpoch`, `RenderGate`, `RestoreWatchdog`,
  `ErrorReporter`).
- Randomness comes from the seeded generators of the JS (same numbers as the web app); time comes
  in as a parameter or through the `Scheduler` port.
- Per-frame paths (`CameraRig.update`, flight poses, …) reuse scratch objects and do not allocate.
