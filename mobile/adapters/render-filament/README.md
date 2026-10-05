# `:adapters:render-filament`

The Filament backend of the native app. Two layers:

* the **low level** (`math`, `mesh`, `material`, `light`, `context`, `resources`) turns plain arrays and
  settings into pixels and does not know the scene model;
* the **integration** (`backend`) implements the scene model's `RenderBackend` port on it:
  `FilamentRenderBackend` (see "Scene integration" below), so the view code renders without knowing Filament.

The module depends on filament-kmp 0.7.1 (Filament 1.77.x), `filamat` and `:adapters:scene`. Package
`app.zoeshorsefarm.render.filament`.

Everything that can be decided without a GPU is **pure Kotlin with unit tests** (about 510 tests on the
JVM): shader code generation, variant keys, mesh packing, tangent frames, spherical harmonics, shadow
snapping, fog and pixel ratio maths, memory accounting, the mapping of the scene model onto all of that
and the whole synchronisation of the node graph (it runs against a fake device that also fails on a use
after free). The classes that call Filament are thin and are only compiled (jvm and both iOS targets) –
the native library cannot be loaded in JVM tests. The test source set also depends on `:adapters:view3d` and
`:core:domain` (tests only): the real world, horse and rider are drawn through `RenderCore` on the fake device
(`WorldOnFilamentTest`, `HorseOnFilamentTest`), which checks that every material of the game has a valid shader
and that quality changes and disposals never leave a renderable on a destroyed object.

## Packages

| Package | Pure (tested) | Native (compiled only) |
| --- | --- | --- |
| `math` | `Mat4Ops` (column-major 4x4 on `FloatArray`, no allocation), `LinearRgb` | |
| `mesh` | `MeshData`, `MeshPacker`, `VertexLayout`, `TangentFrames`, `Aabb`, `InstanceData`, `SkinPalette`, `SpriteQuads`, `TextureData`, `UploadRing` | `GpuMesh`, `GpuTexture`, `InstanceTexture`, `SpriteBatch`, `FilamentRenderable` |
| `material` | `MaterialSpec`, `MaterialSources` (+ `WindGlsl`, `CoatGlsl`, `GlslPreprocessor`, `GlslNumber`), `MaterialLibrary`, `MaterialPackageCache` | `FilamentMaterialCompiler`, `filamentMaterialLibrary` |
| `light` | `AmbientSh`, `ShadowFocus` | `SunLight`, `AmbientLight` |
| `context` | `RenderSettings`, `PixelPlan`, `FogParams`, `BackendStats` | `FilamentContext` |
| `resources` | `GpuResourceTracker`, `GpuMemoryModel`, `DisposalStack` | |
| `backend.mapping` | `MaterialMapping`, `MaterialValues`, `GeometryMapping`, `PrimitiveRanges`, `TextureMapping`, `EnvironmentSh`, `SpriteBillboard`, `InstanceMatrices`, `RenderOrder` | |
| `backend.sync` | `SceneSync` (+ registries, `MeshEntry`, `PointsEntry`, `SpriteEntry`, `InstanceFeed`, `SkinFeed`, `ProgramBook`), `StageSync` | |
| `backend.device` | the ports `GpuDevice` and `StagePort` | `FilamentGpuDevice`, `FilamentStage` |
| `backend` | `RenderCore` (the `RenderBackend` without native calls) | `FilamentRenderBackend` |

## Public API in short

```kotlin
// 1. context: engine, renderer, view, scene, camera, sun, ambient light
val context = FilamentContext.create(RenderSettings(...)) ?: showNoGraphics()
context.attachSurface(surface /* platform NativeSurface */, widthPx, heightPx, devicePixelRatio)
context.setLens(fovDegrees = 58f, near = 0.1f, far = 900f)
context.applySettings(settings)            // graphics level: only what changed is touched
context.sun.configure(directionToSun, color, intensity)
context.ambient.set(AmbientSh.sum(AmbientSh.hemisphere(sky, ground, 0.6f), AmbientSh.environment(...)))

// 2. materials: one per MaterialSpec, built lazily, cached by spec.key
val materials = filamentMaterialLibrary(context.engine, context.tracker, cache = packageCache)
val lit = materials.get(MaterialSpec(Shading.LIT, vertexColors = true))      // FilamentMaterial
val instance = lit.material.createInstance("fence")
instance.setParameter(MaterialSources.BASE_COLOR, 1f, 1f, 1f, 1f)

// 3. geometry and renderables
val mesh = GpuMesh.upload(context.engine, context.tracker, "fence", MeshData(positions, normals, colors, indices = idx))
val fence = FilamentRenderable.create(context, mesh, instance, RenderableOptions(castShadows = true))
fence.setTransform(matrix16)

// 4. every frame
context.setCameraPose(cameraMatrixWorld)
context.sun.setFocus(horseX, 0f, horseZ, cameraX, cameraY, cameraZ)
context.setWind(timeSeconds, strength)
context.renderFrame()

// 5. teardown: destroy your renderables/meshes/textures/materials first, then
context.detachSurface()    // on Android surfaceDestroyed, before it returns
context.dispose()
```

`MaterialSpec` decides the shader code, material *instances* decide the values. The names a material
instance sets are listed in `MaterialSources` (`baseColor` float4 linear, `roughness`, `metallic`,
`normalScale`, samplers `baseColorMap` / `normalMap` / `alphaMap`, `instanceData`; sky: `zenith`,
`horizon`, `groundColor`, `sunColor`, `sunDir`; coat: the `uBase`, `uDark`, ... of the web material).

### Materials (`MaterialSpec` -> Filament material)

| Web material | Spec |
| --- | --- |
| `MeshStandardMaterial` | `Shading.LIT` (roughness, metalness, optional vertex colours, base colour map, normal map, alpha map) |
| `MeshLambertMaterial` | `Shading.LAMBERT`: lit shading with `customSurfaceShading` = `albedo / PI * light * NdotL * visibility`, like three.js r155+ (no specular) |
| `MeshBasicMaterial` | `Shading.UNLIT` (`blend = TRANSPARENT`, `alphaMap`, `toneMapped = false`) |
| sky dome `ShaderMaterial` | `Shading.SKY` (culls front faces, no depth write, high precision) |
| `THREE.Points` (hoof dust) | `Shading.SPRITE` + `SpriteBatch` (camera facing quads: Filament has no point size) |
| `InstancedMesh` (+ instance colour) | `instancing = TRANSFORMS` / `TRANSFORMS_AND_COLOR` + `InstanceTexture` |
| `SkinnedMesh` | `skinning = true` + `SkinPalette` + `RenderableOptions(boneCount)` |
| `plant-shaders.js` | `wind = WindEffect.Tree / Bush / Tuft / Blossom(base) / Bunting / Wings(rate, amplitude, glide)`: the same maths, in `materialVertex` |
| `horse/material.js` | `coat = CoatKind.STANDARD / LOW`: the same GLSL; `MarkingRegions` carries `MARKING_REGIONS` of `coats.js` |

Notes:

* **Generation is pure strings** (`MaterialSources.generate(spec)` returns a `MaterialSource`). `GlslSanity`
  (test helper) checks every valid combination of the options for undeclared or unused
  parameters, variables and attributes, balanced braces, and so on.
* **`MaterialLibrary`** builds lazily, caches by `spec.key`, reports `materialCount`, `totalBuilds`,
  `estimatedPrograms` (the web `renderer.info.programs`), destroys newest first (`clear`, `release`) and
  remembers a failed compile (`MaterialBuildException`) instead of retrying every frame.
* **Variant filtering**: dynamic lights, VSM, SSR and stereo are always filtered out, skinning unless the
  spec is skinned. Directional lighting, shadow receiving and fog are never filtered (they are runtime
  switches of the graphics level), because filtering a needed variant crashes.
* **Compile cost**: filamat compiles at run time (glslang + translators), tens to hundreds of milliseconds
  per material on a phone. Give `filamentMaterialLibrary` a `MaterialPackageCache` (a file in the app's
  cache directory; `InMemoryPackageCache` is the reference) and build the materials of a level before the
  first frame. The key contains a content hash and the Filament version.
* Call `FilamentMaterialCompiler.shutdown()` once at the end of the app.

### Conventions

* Matrices are column-major `FloatArray`s (three.js `Matrix4.elements` = Filament `mat4f`).
* Colours given to Filament are **linear** (`LinearRgb.fromSrgbHex` does what three.js `ColorManagement`
  does); vertex colours are stored linear; colour maps are uploaded as `SRGB8_A8` (`TextureData.srgb`).
* Texture row 0 is the top row; the material's default uv flip makes three.js uvs (`flipY`) look the same.
* Lights use the three.js numbers unchanged: `RenderSettings.exposure = 1` sets Filament's linear camera
  exposure to 1, so intensity 2.7 is "2.7 like in three.js". The hemisphere light is
  `AmbientSh.hemisphere(sky, ground, intensity)`; it is divided by PI because three.js (r155 and later)
  multiplies hemisphere light by `albedo / PI`. The PMREM environment map is approximated by
  `AmbientSh.environment` (diffuse only; there is no specular reflection without a cubemap).
* Tone mapping is Filament's `ACESLegacy`, which is the curve of three.js `ACESFilmicToneMapping`.
* Fog: all materials are compiled with `linearFog` (Filament's linear fog equation), so
  `FogParams.fromLinear(near, far, color)` gives a fog that starts at `near` and is opaque at `far`
  (`density = 1 / (far - near)`, height falloff 0). three.js eases with a smoothstep; the straight line is at
  most 0.1 away from it and equal at both ends and in the middle. The fog colour is multiplied by the intensity
  of the scene's indirect light, so the scene always has one (`AmbientLight.clear()` makes it black, it does
  not remove it). The sky and the clouds are drawn with `RenderableOptions(fog = false)`.
* Shadows: PCF, one cascade, `stable`. Filament fits the shadow map to the camera frustum, so the web
  `setShadowFocus` becomes `SunLight.setFocus`: `ShadowFocus` snaps the focus to the light-space texel grid
  (the web maths) and sets `shadowFar` to the camera distance to the focus plus the 24 m half extent, in
  steps of 4 m so that Filament's shadow options change rarely.
* Pixel ratio: `PixelPlan` turns the quality level's `pixelRatio` cap into a fixed render scale below 1
  (dynamic resolution with `minScale == maxScale`); the surface keeps its physical size.

### Memory

`GpuResourceTracker` is passed to every wrapper (`context.tracker`): buffers, textures, instance
textures, materials, frame buffers and the shadow map register their bytes and release them on destroy.
`tracker.totalMegabytes` / `bytesOf(kind)` feed the budget logic. `GpuMemoryModel` holds the assumptions
(HDR colour `R11G11B10F`, 32 bit depth, MSAA multiples, swap chain, 32 bit shadow depth) and, like the
web model, is meant to be tuned on a real device. `DisposalStack` destroys in reverse order of creation
and never stops at a failing destroy.

## Scene integration (`backend`)

```kotlin
val backend = FilamentRenderBackend.create(cache = packageCache) ?: showNoGraphics()   // RenderBackend
backend.attachSurface(surface, widthPx, heightPx, devicePixelRatio)    // Android Surface / iOS CAMetalLayer
backend.msaaSamples = 4                                                // 0, 2 or 4 (not in the port)
backend.toneMapping = ToneMapping.ACES_FILMIC; backend.toneMappingExposure = 1.0
backend.setPixelRatio(1.5); backend.shadowsEnabled = true
backend.compile(world.compileRoot, camera, scene) { /* the first screen is built */ }
// every frame
backend.render(scene, camera)
backend.info.drawCalls / .triangles / .programs / .textures / .geometries
// surface lifecycle (all on the render thread)
backend.onSurfaceResized(widthPx, heightPx, devicePixelRatio)
backend.detachSurface()      // Android surfaceDestroyed: listeners get onContextLost, drawing stops
backend.attachSurface(...)   // listeners get onContextRestored, everything is sent to Filament again
backend.dispose()            // renderables, material instances, materials, textures, meshes, then the engine
```

`render(scene, camera)` is `RenderCore.render`: update the matrices (scene, and the camera if it is not in the
scene), `SceneSync.sync` (entities), `StageSync.sync` (lights, camera, fog, settings), draw.

### How the scene model is mapped

| Scene model | Filament |
| --- | --- |
| `Mesh` | one renderable with one primitive; `Geometry.drawRange` cuts the index range |
| `Mesh` with a material list | one renderable, one primitive per `Geometry.groups` entry (clipped to the draw range) with `materials[group.materialIndex]`; no groups: everything with the first material |
| `InstancedMesh` | renderable with `instances(count)` and an identity transform; matrices (`instanceMatrix`) and colours (`instanceColor`) in the instance data texture, uploaded when their `version` changed or `count` grew; a mesh that is not at the origin has its matrices multiplied by its world matrix; one box over all instances for culling. A changed `count` builds the renderable again (cheap) |
| `SkinnedMesh` | `Skeleton.update()` every frame, then `SkinPalette.fromBoneMatrices` (`bindInverse * boneMatrix * bind`) -> `setBones`; the horse is drawn with `frustumCulled = false` in the web code, a `boundingSphere` is used when it is set |
| `Points` (hoof dust) | `SpriteBatch`: a camera facing quad per vertex, `position` = centre, `aSize` = diameter in metres, `aAlpha` = opacity (missing: the material's `size` / 1); rewritten when one of the three attributes was marked `needsUpdate` |
| `Sprite` | the shared unit quad; the model matrix is composed every frame from the node and the camera (`SpriteBillboard`: scale from the node, `center`, `SpriteMaterial.rotation`) |
| `visible` | the entity leaves the Filament scene (nothing is freed); an invisible parent hides the subtree |
| `castShadow`, `receiveShadow`, `frustumCulled`, `renderOrder` | in place on the renderable; `renderOrder` -> priority 0..7 (`4 + renderOrder`, clamped) and, for transparent materials, the global blend order (`RenderOrder`) |
| `onBeforeRender` | called for every visible node before its values are read |
| node removed from the graph | its renderable (and the instance data and private material instances of an instanced mesh) is freed; geometries, textures and materials stay until disposed, like three.js |
| `Geometry` / `Texture` / `Material` / `InstancedMesh` `dispose()` | the device copy is freed at once (renderables that use it are dropped first, textures in use are replaced by a 1x1 placeholder); the next frame uploads it again |
| `Geometry` attributes | `position`, `normal` (-> tangent frame), `color` (3 or 4 floats), `uv`, `skinIndex`/`skinWeight`, custom: `aRest` 0, `aMat` 1, `aFace` 2, `petal` 0, `aFlutter` 0; 16 bit indices up to 65535 vertices, else 32 bit. `needsUpdate` on `position`, `normal`, `uv` and custom attributes rewrites that buffer in place (the reins, every frame, without allocating); anything else that changes (attribute added or removed, new index, colours, skin data, vertex count) uploads the geometry again. A lit mesh without normals gets them (`computeVertexNormals`) |
| `Texture` | RGBA8 copy of the pixels (Filament reads them later), sRGB from `colorSpace`, wrap, mipmaps if `generateMipmaps` and the filter uses them, anisotropy capped by `capabilities`; re-uploaded when `version` changed |

Materials (`MaterialMapping` -> `MaterialSpec`, `MaterialValues` -> uniforms):

| Scene material | Spec |
| --- | --- |
| `StandardMaterial` / `LambertMaterial` / `BasicMaterial` | `LIT` / `LAMBERT` / `UNLIT` with vertex colours (only if the geometry has `color`), `map`, `normalMap` (plain lit meshes only), `alphaMap` (all need `uv`), `transparent`, `side`, `depthWrite`, `toneMapped`, instancing (+ colour when `instanceColor` exists), skinning |
| `WindEffect` `TREE` `BUSH` `TUFT` `WINGS` | the matching wind, on instanced meshes only |
| `WindEffect` `BLOSSOMS` | needs instance colours, vertex colours and the `petal` attribute |
| `WindEffect` `BUNTING` | needs `aFlutter`, not instanced |
| `CoatEffect` (`low`) | `CoatKind.STANDARD` / `LOW`, needs `aRest`, `aMat`, `aFace`; the coat paints the whole surface, so maps, vertex colours and wind are dropped. `CoatUniforms` is written every frame (flare, blink) but only changed values reach Filament |
| `SandEffect(arenaHalfWidth, arenaHalfLength)` | `sand = true` (lit, plain meshes): `sandTint` of the web `patchSandMaterial` multiplies the colour, the ground position comes from the vertex stage, `arenaHalf` is a material parameter |
| `SkyMaterial` / `ShaderMaterial("sky")` | `SKY` |
| `ShaderMaterial("hoof-dust")`, `PointsMaterial` | `SPRITE` (transparent) |
| `SpriteMaterial` on a `Sprite` | `UNLIT` with its map, transparent, double sided |
| anything else | no spec: reported once through `SyncLog`, the node is not drawn |

A spec that the variant cannot do is not an error: the unsupported part is dropped (wind on a plain mesh,
vertex colours without a colour attribute ...). `polygonOffset`, `depthTest` and `fog` follow the material;
`polygonOffset` is the material instance's `setPolygonOffset(factor, units)`.

Programs (`info.programs`): `ProgramBook` counts them by the scene model's `programKey` exactly like `GpuTracker`
(one per distinct key and material, kept until the material is disposed, shared between materials with the same
key), so the view's program budget rules hold on the device; sprites are not counted, like in `GpuTracker`.
The Filament programs of the materials it built are a separate number (`estimatedFilamentPrograms`).

### Lights, camera, environment (`StageSync`)

* The first visible `DirectionalLight` is the sun: colour, intensity, direction from its position to its `target`
  (both in world space). With `shadowsEnabled` and `castShadow`: `shadow.mapSize.x` (power of two, at most 4096) and
  half the width of `shadow.camera` become `ShadowSettings`; the target is the shadow focus (`SunLight.setFocus`
  with the camera position), like the web `setShadowFocus`. While the shadows are on the light has a
  `shadow.map` object; disposing it releases the map object and the next frame makes a new one. `shadow.bias` and
  `normalBias` are three.js values and are not used (Filament has its own).
* `HemisphereLight`s (up to 4) and `scene.environment` (`EnvironmentLight`) become one ambient SH light
  (`AmbientSh.hemisphere` and `EnvironmentSh`, which integrates the web sky dome, sun glow, sun disc and floor over the
  sphere; `scene.environmentIntensity` scales it). It is recomputed only when an input changed.
* `scene.fog` -> `FogParams.fromLinear`, `scene.background` -> clear colour, `toneMapping` (`NONE` -> linear, `ACES_FILMIC`
  -> `ACESLegacy`), `toneMappingExposure`, `setPixelRatio`, `msaaSamples` -> `RenderSettings`; only changes are sent.
* The camera: lens from `PerspectiveCamera` (effective fov, near, far; the aspect follows the surface), pose from
  `matrixWorld`, both sent only when they changed. The wind (`time`, `strength`) of the first wind material found
  goes to the material global once per change.

### Deviations from the web renderer

* `shadowType` and `shadowAutoUpdate` are accepted and ignored: Filament renders PCF shadows every frame.
* `setPixelRatio(r)` is a cap on the physical surface: the backing store is `size * min(r, devicePixelRatio)`, drawn
  at a fixed render scale below the surface size (`PixelPlan`). `setSize` takes logical pixels; the platform reports
  the physical surface with `attachSurface` / `onSurfaceResized`.
* No specular environment reflections (no prefiltered cubemap); `Lambert` materials also get the ambient light.
* Not supported (the web view does not use them): `alphaTest`, `flatShading`, `emissive`, `envMapIntensity`; `Side.BACK`
  outside the sky is drawn double sided; texture filtering is always linear; `shadow.bias`.
* `info.drawCalls` / `triangles` count what `SceneStats` counts (every visible object, no culling), plus the shadow pass
  while the sun casts shadows.
* Several `Wind` objects: the shaders read one, the first found.
* The context is never really lost on Filament: `detachSurface` reports a lost context and `attachSurface` a restored
  one, and meshes, textures and materials survive (they belong to the engine). After a destroyed engine call
  `resetResources()` and rebuild the backend.

### The steady path

`SceneSync.sync` walks the graph with indexed loops, compares versions and cached values, and calls Filament only for
what changed: a frame in which nothing changed makes no call (the tests assert that), except the bone matrices of
skinned meshes (Filament has no "unchanged" for them), the camera when it moves, a sprite when the camera moves and
the shadow focus. On JVM/Android every `setTransform` / `setBones` / `setParameter` copies into native memory (see
the limitations below), so the number of calls is what is kept small.

## What the platform code must do

1. Create the `NativeSurface` (Android `Surface`, iOS `CAMetalLayer` pointer) and call `attachSurface`; call
   `onSurfaceResized` on size or density changes and `detachSurface` before the platform frees the surface.
2. Give `FilamentRenderBackend.create` a `MaterialPackageCache` (a file in the app's cache directory) so that
   only the first run pays for the shader compile, and `compile(...)` the first screen before showing it.
3. Translate a graphics level into `msaaSamples`, `setPixelRatio`, `shadowsEnabled`, the light's `shadow.mapSize`
   and the scene's `fog` / `environment` (the view code does most of that on the scene objects).
4. Call `FilamentMaterialCompiler.shutdown()` once when the app ends.
5. Debug box: `backend.info`, `backend.backendStats()` (Filament object counts, tracked MiB, upload fall backs),
   `estimatedFilamentPrograms`, `builtMaterials`.

## Assumptions to verify on a device

These follow the Filament documentation and sources but could not run here (no GPU, no Android SDK):

* `RenderableManager.setGeometryAt` (draw range changes), `setCulling`, `setFogEnabled` and the global blend order
  behave as documented; a renderable keeps working when the material instance of a primitive is replaced
  (`setMaterialInstanceAt`).
* An instanced renderable with an identity entity transform draws `inst * position` in world space; the instance
  matrices are baked with the node's world matrix when it is not the identity.
* Destroying a material instance, vertex buffer or texture right after the renderable that used it was destroyed
  (same frame, before `renderFrame`) is safe; the backend never destroys one that a live renderable still uses.
* `Material.compile(HIGH)` followed by `Engine.flush()` starts the program compiles without blocking.
* The SH light from `EnvironmentSh` looks like the web PMREM diffuse light closely enough (compare screenshots; the
  `AmbientSh` conventions are the ones of the assumptions below).

* `getWorldFromModelMatrix()` of an instanced renderable is the renderable's own (rebasing) matrix, so
  `mulMat4x4Float3(getWorldFromModelMatrix(), inst * position)` is right with an identity entity transform.
* `inverseTonemap()` (used by `toneMapped = false`, the number signs) exists in 1.77 and inverts the ACES curve
  well enough; colours near white may clip.
* An `IndirectLight` with irradiance only (no reflections cubemap) is accepted by `build()`.
* `IndirectLight.irradiance(2, sh)` takes pre-scaled coefficients in the order constant, y, z, x (as the
  shader sums them), so `AmbientSh` values are used as they are.
* RGBA32F data textures can be read with `texelFetch` in the vertex shader on every target GPU
  (GLES 3.0 and Metal guarantee it); a 2048 row limit is assumed (`InstanceData.MAX_ROWS`).
* Fixed dynamic resolution (`min == max`) renders at that scale from the first frame.
* `ShaderQuality.HIGH` (coat, sky) is needed for the noise and the sun disc; other materials use the default.

## filament-kmp 0.7.1: limitations found

* **Uploads are not copied** on iOS: `setBufferAt` / `setImage` pin the `ByteArray` and Filament reads it later;
  the array is freed by the release callback. Never write to an array that was passed in until its callback
  fired. `UploadRing` handles this for the data that changes (particles, instance texture, the normals of deformed
  meshes such as the reins); `overflowCount` of a ring and `BackendStats.uploadOverflows` show when the driver
  held every slot (should stay 0); static uploads
  (`MeshPacker`, `TextureData`) use arrays that are never written again.
* **Per-call allocation on JVM/Android**: every `FloatArray` argument (`setTransform`, `setBones`,
  `setMaterialGlobal`, `setParameter`, light and shadow options) is copied into freshly allocated native
  memory (`InteropScope`), and options objects (`FogOptions`, `ShadowOptions`, `DynamicResolutionOptions`,
  `View.*Options`) are created and destroyed per assignment. On iOS arrays are pinned instead. The backend
  keeps the number of such calls per frame small (shadow options only change with the stepped `shadowFar`),
  but a frame still allocates a few small objects on Android; there is no way around it in common code.
* **`InstanceBuffer` is limited to `Engine.maxAutomaticInstances` (64)**: the web scene draws 11 000 tufts
  in one call, hence the instance data texture.
* **No point size**: `PrimitiveType.POINTS` cannot set `gl_PointSize` from a material; sprites are quads.
* **No per-material tone mapping**: tone mapping is a View post process, hence the inverse in the shader.
* **`NativeSurface` is an `expect class`** without a common constructor: the platform code (Android `Surface`,
  iOS `CAMetalLayer` pointer) creates it. Nothing is assumed about it here.
* **`filamat` run time compile** needs `MaterialBuilder.init()` / `shutdown()` around its use and is not
  thread safe; there is no async variant.
* Specular image based light needs a prefiltered reflections cubemap (`IBLPrefilterContext` in filament-utils
  could make one from the sky); the ambient light here is SH irradiance only, so there are no environment
  reflections (rough surfaces in the web scene barely show them).
