# `:adapters:render-filament`

The Filament backend of the native app: everything between "plain arrays and settings" and "pixels on
the screen", without knowing the scene model. The integration step (next wave) connects it to
`:adapters:scene`; until then this module only depends on filament-kmp 0.7.1 (Filament 1.77.x) and
`filamat`. Package `app.zoeshorsefarm.render.filament`.

Everything that can be decided without a GPU is **pure Kotlin with unit tests** (272 tests on the
JVM): shader code generation, variant keys, mesh packing, tangent frames, spherical harmonics, shadow
snapping, fog and pixel ratio maths, memory accounting. The classes that call Filament are thin and are
only compiled (jvm and both iOS targets) – the native library cannot be loaded in JVM tests.

## Packages

| Package | Pure (tested) | Native (compiled only) |
| --- | --- | --- |
| `math` | `Mat4Ops` (column-major 4x4 on `FloatArray`, no allocation), `LinearRgb` | |
| `mesh` | `MeshData`, `MeshPacker`, `VertexLayout`, `TangentFrames`, `Aabb`, `InstanceData`, `SkinPalette`, `SpriteQuads`, `TextureData`, `UploadRing` | `GpuMesh`, `GpuTexture`, `InstanceTexture`, `SpriteBatch`, `FilamentRenderable` |
| `material` | `MaterialSpec`, `MaterialSources` (+ `WindGlsl`, `CoatGlsl`, `GlslPreprocessor`, `GlslNumber`), `MaterialLibrary`, `MaterialPackageCache` | `FilamentMaterialCompiler`, `filamentMaterialLibrary` |
| `light` | `AmbientSh`, `ShadowFocus` | `SunLight`, `AmbientLight` |
| `context` | `RenderSettings`, `PixelPlan`, `FogParams`, `BackendStats` | `FilamentContext` |
| `resources` | `GpuResourceTracker`, `GpuMemoryModel`, `DisposalStack` | |

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

## What the integration step must do

1. **Scene model -> `MeshData` / `TextureData`.** `Geometry` attributes map to `MeshData` fields
   (`position`, `normal`, `color`, `uv`, `skinIndex` as `ShortArray`, `skinWeight`, extra attributes as
   `CustomAttribute` slots: coat `aRest` -> 0, `aMat` -> 1, `aFace` -> 2; flower `petal` and bunting
   `aFlutter` -> 0). Upload again when a geometry's `version` changes. `TextureData.pixels` must not be
   modified after the upload (Filament reads the array later).
2. **Choose the `MaterialSpec`** from the scene material (`StandardMaterial` / `LambertMaterial` /
   `BasicMaterial` / `SkyMaterial` / `PointsMaterial` and their flags), check `missingAttributes(spec,
   mesh.semantics)`, set the instance parameters, bind textures with
   `instance.setParameter(name, gpuTexture.texture, gpuTexture.sampler)`.
3. **Instanced meshes**: create an `InstanceTexture(capacity)`, `update(...)` the instances that changed,
   `bind(materialInstance)`, build the renderable with `RenderableOptions(instanceCount = n, bounds =
   Aabb.ofInstances(...))` and an **identity transform** (the instance matrices are world matrices).
   Changing the count means building a new renderable (cheap); the texture stays.
4. **Skinned meshes**: `SkinPalette(boneCount).compute(...)` then `renderable.setBones(palette)` each frame.
5. **Frame**: copy the scene camera into `setCameraPose` / `setLens`, the wind into `setWind`, the horse
   position into `sun.setFocus`, the sky group follows the camera by its transform, then `renderFrame()`.
6. **Graphics levels**: translate a quality preset into `RenderSettings` (pixel ratio, MSAA 0/2/4, shadow map
   size, fog, tone mapping) and `AmbientSh` terms; rebuild materials whose spec changed (material type,
   wind, normal maps) through `MaterialLibrary.release` and `get`. A level change never needs a new engine.
7. **Lost context**: Filament has no "context lost" event on Metal; on Android a lost EGL context destroys
   the surface, which arrives as `detachSurface()` + `attachSurface()`. Meshes and textures survive a new
   swap chain; after a destroyed *engine* everything is rebuilt from the CPU data (`MaterialLibrary.clear()`,
   `GpuResourceTracker.clear()`).
8. **Debug box**: `context.stats()` (`BackendStats`), `materials.materialCount`, `estimatedPrograms`,
   `tracker.totalMegabytes`.

## Assumptions to verify on a device

These follow the Filament documentation and sources but could not run here (no GPU, no Android SDK):

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
  fired. `UploadRing` handles this for the data that changes (particles, instance texture); static uploads
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
