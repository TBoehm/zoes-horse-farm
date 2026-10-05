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
