# `:adapters:scene` – the renderer-neutral scene model ("three-lite")

A small scene graph with **three.js r186 semantics** (same math, same vertex/index layout of the
geometry builders, same matrix and colour conventions), so that the view code of the web app
(`src/adapters/view3d/**`) can be ported line by line. Nothing in here touches a GPU: a
`RenderBackend` (Filament in `:adapters:render-filament`, `FakeRenderBackend` in tests) turns the
model into pictures. Package root: `app.zoeshorsefarm.scene`.

```
math/       Vec2 Vec3 Quat Euler Mat3 Mat4 Color MathUtils Box3 Sphere Plane Frustum CatmullRomCurve3
geometry/   Geometry, FloatAttribute/UShortAttribute, builders (BoxGeometry, ...), Shape/Path, mergeGeometries
graph/      Node (=Object3D) Group Bone Mesh InstancedMesh SkinnedMesh Skeleton Points Sprite Scene Fog
            cameras, lights, EnvironmentLight, frustum culling, collectGpuObjects
material/   Material and subclasses, effects (Wind, Coat), MaterialParams
texture/    Texture, Raster2D (software canvas), TextRasterizer, noise helpers
render/     RenderBackend (port), FakeRenderBackend, SceneStats, GpuTracker
```

## Conventions

- **Math is `Double`** (like JS numbers); **GPU data is `Float`** (like `Float32Array`). Builders compute in
  double and narrow when the attribute is filled, exactly as three.js does, so the numbers match.
- Math objects are **mutable and chainable** (`v.copy(a).sub(b).normalize()`), no operators, no hidden
  allocation. Keep scratch objects (`private val tmp = Vec3()`) in per-frame paths, as the JS does.
- `Mat4.e` is the column-major element array (`elements` in three.js); `set(...)` takes row-major arguments.
- Colours: `Color(0xRRGGBB)` is read as **sRGB and stored linear** (three.js `ColorManagement`).
  `Color().setRGB(r, g, b, ColorSpace.SRGB)` converts too; `setRGB(r, g, b)` is linear.
  Every hex-valued material parameter (`color = 0x336699`, `emissive`) goes through the same conversion.
- `rotation` (Euler, `EulerOrder.XYZ` default) and `quaternion` of a `Node` stay in sync automatically.
- `node.children` is a read-only list; copy it (`children.toList()`) before removing while iterating.
- **Dispose** frees the GPU copy, it does not destroy the object (three.js semantics): `Geometry`, `Material`,
  `Texture`, `InstancedMesh`, `Skeleton.boneTexture`, `EnvironmentLight` are `GpuObject`s with
  `dispose()`, `addDisposeListener`, `disposeCount`. A backend listens and frees; later use uploads again.
- `needsUpdate` on attributes (`instanceMatrix.needsUpdate = true`), textures and materials bumps a `version`
  that the backend compares. A material variant change (map added, effect toggled) needs `material.needsUpdate = true`.

## three.js -> scene model

| three.js | here |
| --- | --- |
| `Vector3`, `Vector2`, `Quaternion`, `Matrix4`, `Matrix3`, `Euler('XYZ')` | `Vec3`, `Vec2`, `Quat`, `Mat4`, `Mat3`, `Euler`, `EulerOrder.XYZ` |
| `new Vector3(...arr)`, `v.toArray()` | `Vec3().fromArray(arr)`, `v.toArray()` |
| `Color`, `color.setRGB(r,g,b,SRGBColorSpace)` | `Color`, `setRGB(r, g, b, ColorSpace.SRGB)` |
| `MathUtils.clamp/lerp/smoothstep/degToRad/ceilPowerOfTwo` | `MathUtils.*` |
| `Sphere`, `Box3`, `Frustum.setFromProjectionMatrix().intersectsSphere` | same names; `Frustum.intersectsObject(node)` in `graph/` |
| `CatmullRomCurve3(pts, false, 'centripetal')`, `getPointAt`, `getTangentAt`, `getLength` | `CatmullRomCurve3(pts, false, CatmullRomType.CENTRIPETAL)`, same methods |
| `Shape`, `Path`, `moveTo/lineTo/quadraticCurveTo`, `shape.holes.push(new Path(pts))` | `Shape`, `Path` (`holes.add(Path(pts))`) |
| `Object3D` | `Node` (alias `Object3D`), `Node.DEFAULT_UP` |
| `Group`, `Bone`, `Scene` | `Group`, `Bone`, `Scene` (`background: Color?`, `fog`, `environment`, `environmentIntensity`) |
| `Mesh(geometry, material)`, `Mesh(geometry, [m1, m2])` | `Mesh(geometry, material)`, `Mesh(geometry, listOf(m1, m2))` |
| `InstancedMesh(g, m, n)`, `setMatrixAt`, `setColorAt`, `count`, `instanceMatrix.setUsage(DynamicDrawUsage)` | same; `instanceMatrix.setUsage(Usage.DYNAMIC_DRAW)`, `capacity` |
| `SkinnedMesh`, `Skeleton(bones)`, `mesh.bind(skeleton, bindMatrix)` | same (`bind(skeleton, bindMatrix)`), `skeleton.boneTexture` is a `BoneTexture` |
| `Points(geometry, PointsMaterial)`, `Sprite(SpriteMaterial)` (`center`, `scale`) | same |
| `PerspectiveCamera(fov, aspect, near, far)`, `updateProjectionMatrix()` | same; `OrthographicCamera` too |
| `DirectionalLight`, `light.target`, `light.shadow.camera/mapSize/bias/normalBias/map` | same (`shadow.camera` is an `OrthographicCamera`, `shadow.mapSize` a `Vec2`) |
| `HemisphereLight(sky, ground, intensity)`, `Fog(color, near, far)` | same |
| `PMREMGenerator.fromScene(skyScene)` + `scene.environment` | `EnvironmentLight(zenith, horizon, ground, sunColor, sunDirection, floorColor)` as `scene.environment` |
| `BufferGeometry`, `setAttribute`, `setIndex`, `addGroup`, `setDrawRange`, `computeVertexNormals`, `toNonIndexed`, `applyMatrix4`, `translate`, `rotateX`, `clone` | `Geometry` (same methods; index is an `IntArray`) |
| `geometry.attributes.position` / `.uv` / `.normal` / `.color` | `geometry.position` / `.uv` / `.normal` / `.color` (`geometry.float("aSize")` for custom ones) |
| `Float32BufferAttribute(array, n)`, `Uint16BufferAttribute` | `FloatAttribute(FloatArray or List<Double>, n)`, `UShortAttribute(IntArray, 4)` |
| `attr.getX/setXYZ/count/needsUpdate/setUsage` | same |
| `BoxGeometry`, `PlaneGeometry`, `CylinderGeometry`, `ConeGeometry`, `SphereGeometry`, `IcosahedronGeometry`, `OctahedronGeometry`, `TetrahedronGeometry`, `TorusGeometry`, `CircleGeometry`, `ShapeGeometry`, `ExtrudeGeometry` | functions with the same names (named parameters, three.js defaults); `ExtrudeGeometry(shape, depth = 0.2)` has no bevel (the game only extrudes without bevel) |
| `mergeGeometries(list, useGroups)` | `mergeGeometries(list, useGroups)` (null if incompatible) |
| `MeshStandardMaterial({ color, roughness, ... })` | `StandardMaterial(color = 0x..., roughness = ..., side = Side.DOUBLE, ...)` |
| `MeshLambertMaterial`, `MeshBasicMaterial`, `SpriteMaterial`, `PointsMaterial` | `LambertMaterial`, `BasicMaterial`, `SpriteMaterial`, `PointsMaterial` |
| `ShaderMaterial` (sky dome, hoof dust) | `SkyMaterial(zenith, horizon, ground, sun, sunDirection)`, `ShaderMaterial("hoof-dust", uniforms)` |
| `THREE.FrontSide/BackSide/DoubleSide` | `Side.FRONT/BACK/DOUBLE` |
| `onBeforeCompile` wind patches, `customProgramCacheKey` | `material.effect = WindEffect.tree(wind)` etc., `effectEnabled` toggles it |
| `onBeforeCompile` horse coat + uniforms | `material.effect = CoatEffect(coatUniforms, low)` (`CoatUniforms` holds the values) |
| `material.userData`, `object.userData` | `userData: MutableMap<String, Any?>` |
| `object.onBeforeRender = (renderer, scene, camera) => ...` | `node.onBeforeRender = { backend, scene, camera -> ... }` |
| `CanvasTexture(canvas)`, `colorSpace`, `wrapS/T`, `anisotropy`, `needsUpdate` | `Texture(raster)` or `canvasTexture(raster, repeat, srgb, anisotropy)` |
| `document.createElement('canvas').getContext('2d')` | `Raster2D(width, height, textRasterizer)` (it is canvas and context in one) |
| `WebGLRenderer` (`render`, `compileAsync`, `info`, `setPixelRatio`, `setSize`, `getDrawingBufferSize`, `shadowMap`, `toneMapping`, context loss) | `RenderBackend` |
| `tests/support/scene-stats.js`, `gpu-tracker.js` | `SceneStats.of(root)`, `GpuTracker(scene) { shadowsEnabled }` |

## Examples

Geometry parts with vertex colours, merged into one draw call:

```kotlin
val parts = mutableListOf<Geometry>()
val post = BoxGeometry(0.1, 1.2, 0.1).translate(0.0, 0.6, 0.0).toNonIndexed()
// give every vertex a colour (linear working space, like Color)
val color = Color(0x6b3427)
val rgb = FloatArray(post.position.count * 3)
for (i in 0 until post.position.count) {
    rgb[i * 3] = color.r.toFloat(); rgb[i * 3 + 1] = color.g.toFloat(); rgb[i * 3 + 2] = color.b.toFloat()
}
post.setAttribute("color", FloatAttribute(rgb, 3))
parts += post
val fence = Mesh(mergeGeometries(parts)!!, StandardMaterial(vertexColors = true, roughness = 0.8))
fence.castShadow = true
```

Instances with colours, as the world code does:

```kotlin
val trees = InstancedMesh(geometry, StandardMaterial(vertexColors = true), capacity = 200)
val m = Mat4(); val q = Quat(); val s = Vec3(); val p = Vec3(); val c = Color()
for ((i, t) in items.withIndex()) {
    q.setFromAxisAngle(Node.DEFAULT_UP, t.yaw)
    trees.setMatrixAt(i, m.compose(p.set(t.x, 0.0, t.z), q, s.setScalar(t.scale)))
    trees.setColorAt(i, c.set(t.color))
}
trees.count = items.size
trees.instanceMatrix.needsUpdate = true
trees.instanceColor?.needsUpdate = true
trees.computeBoundingSphere()
```

Wind on a material (switched off on "low", the old program is freed by disposing):

```kotlin
val wind = Wind()                       // one per world: advance wind.time every frame
val crown = StandardMaterial(vertexColors = true, effect = WindEffect.tree(wind))
// later, on a quality change
crown.effectEnabled = false; crown.needsUpdate = true; crown.dispose()
```

A label texture:

```kotlin
val raster = Raster2D(256, 128, platformTextRasterizer)   // BlockTextRasterizer in tests
raster.fillStyle = "#ffffff"; raster.fillRect(0.0, 0.0, 256.0, 128.0)
raster.fillStyle = "#1b1b1b"; raster.textAlign = "center"; raster.textBaseline = "middle"
raster.font = "800 90px system-ui, sans-serif"
raster.fillText("12", 128.0, 69.0)
val label = canvasTexture(raster, repeat = false)
// draw again later: raster....; label.needsUpdate = true
```

Budgets without a GPU (the ported web tests `world-budget`, `world-stages`):

```kotlin
val stats = SceneStats.of(world.scene)           // calls, triangles, instances, shadowPass
val tracker = GpuTracker(world.scene) { backend.shadowsEnabled }
tracker.compile(world.compileRoot)               // any Traversable
check(tracker.snapshot().programs <= 20)
```

A view test with the fake backend:

```kotlin
val backend = FakeRenderBackend()
backend.render(scene, camera)
assertEquals(14, backend.info.drawCalls)
backend.simulateContextLoss(); backend.simulateContextRestore()
```

## Differences from three.js (on purpose)

- No `Layers`, raycasting, morph targets, `pivot`, `static`, events other than `dispose`.
- `Geometry.index` is an `IntArray` (the backend picks 16/32 bit); there is no interleaved or instanced
  attribute class (the instance buffers are plain `FloatAttribute`s on `InstancedMesh`).
- Materials carry no GLSL. Variants are described by fields plus the typed `effect`; `programKey(...)` in
  `render/GpuTracker.kt` is the key a backend should also use for its own program cache.
- `Raster2D` is not a browser canvas: gradients ignore the transform, joins are round, text goes through
  the `TextRasterizer` port, compositing supports `source-over`, `source-atop`, `copy`, `destination-out`.
- `ShapeGeometry`/`ExtrudeGeometry` accept `Shape` (curves: line, quadratic, cubic, ellipse arcs);
  `ExtrudeGeometry` has no bevel and no extrusion path.
- `Node.getWorldDirection` follows three.js: +Z for nodes, -Z (the look direction) for cameras.

## Tests

`./gradlew :adapters:scene:jvmTest`. The expected numbers of the math, geometry, camera and skeleton
tests were computed with three.js r186 (node); geometry tests compare vertex/index counts, groups and
weighted fingerprints of position, normal, uv and index. `SceneStatsTest` and `GpuTrackerTest` are ports
of `tests/support/scene-stats.test.js` and `gpu-tracker.test.js`.
