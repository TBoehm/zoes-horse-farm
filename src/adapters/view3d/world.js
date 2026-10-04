// The 3D world: lights, sky, arena, environment, obstacles and markings.
// Quality levels can be switched at runtime (the governor downgrades), stage by stage.
import * as THREE from 'three';
import { presetFor, QUALITY_PRESETS } from './quality.js';
import { MERGED_STAGE_ID, sameMaterialStage } from './quality-stages.js';
import { createDetailHold } from './detail-hold.js';
import { createSky, SKY_COLORS } from './sky.js';
import { createArena, createCourseLines } from './arena.js';
import { createEnvironment, SITE } from './environment.js';
import { createObstacles } from './obstacles.js';
import { createWind, setWindPatch } from './plant-shaders.js';
import { createAidMarker } from './aid-marker.js';
import { createDust } from './dust.js';
import { createGrazingHorses } from './horse/grazing.js';
import { planPaddockKeepOut } from './decor-plan.js';
import { PADDOCK, isOnArenaSand } from './world-layout.js';
import { createRng } from './textures.js';
import { collectGpuObjects, releaseNow } from './resilience.js';

const SHADOW_HALF = 24; // half extent of the shadow camera (m)
const SUN_DISTANCE = 90;
// Grazing horses in the paddock: different coats, a fixed seed (the same picture every time)
const GRAZING_COATS = Object.freeze(['grey', 'chestnut']);
const GRAZING_SEED = 31;
// The horses are only animated while the paddock (a sphere around it) is in view
const PADDOCK_SPHERE = Object.freeze({
  x: PADDOCK.x,
  z: PADDOCK.z,
  radius: Math.hypot(PADDOCK.width, PADDOCK.depth) / 2 + 3,
});
// Weakest footfall that raises hoof dust (a step at the walk does not)
const DUST_MIN_STRENGTH = 0.3;
const DUST_EDGE_MARGIN = 0.2; // m, no dust right at the fence

/** Material pair: standard (medium/high) and Lambert (low) with the same base values. */
function createMaterialPair(params) {
  const { roughness, metalness, normalMap, normalScale, ...common } = params;
  const standard = new THREE.MeshStandardMaterial({
    ...common,
    roughness: roughness ?? 0.8,
    metalness: metalness ?? 0,
    normalMap: normalMap ?? null,
    normalScale: normalScale ?? new THREE.Vector2(1, 1),
  });
  const lambert = new THREE.MeshLambertMaterial(common);
  return { standard, lambert, normalMap: normalMap ?? null };
}

/**
 * createWorld(renderer, { quality }) → world API (see architecture.md, section View).
 * `quality`: a level name or a preset object. `release`: frees GPU objects that the world replaces
 * while it runs (see createGpuEpoch in resilience.js).
 */
export function createWorld(renderer, { quality = 'medium', release = releaseNow } = {}) {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(SKY_COLORS.horizon);
  const pairs = [];
  const materialFactory = (_kind, params) => {
    const pair = createMaterialPair(params);
    pairs.push(pair);
    return pair;
  };

  // lights
  const sunDirection = new THREE.Vector3(-0.52, 0.74, -0.42).normalize();
  const hemi = new THREE.HemisphereLight(0xdde9f7, 0x7a6c50, 1.0);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xfff0dc, 2.7);
  sun.shadow.camera.left = -SHADOW_HALF;
  sun.shadow.camera.right = SHADOW_HALF;
  sun.shadow.camera.top = SHADOW_HALF;
  sun.shadow.camera.bottom = -SHADOW_HALF;
  sun.shadow.camera.near = 10;
  sun.shadow.camera.far = SUN_DISTANCE + 60;
  sun.shadow.bias = -0.0005;
  sun.shadow.normalBias = 0.03;
  scene.add(sun, sun.target);
  const shadowFocus = new THREE.Vector3(0, 0, 0);

  // sky
  const sky = createSky({ sunDirection });
  scene.add(sky.group);

  // arena, environment, obstacles, markings; one wind moves the trees, the grass, the flowers, the
  // bunting and the animals of all of them
  const wind = createWind();
  const arena = createArena({
    materialFactory,
    path: SITE.path,
    pathFence: SITE.pathFence,
    wind,
  });
  scene.add(arena.group);
  const environment = createEnvironment({ materialFactory, wind, release });
  scene.add(environment.group);
  const obstacles = createObstacles({ materialFactory, release, wind });
  scene.add(obstacles.group);
  const lines = createCourseLines({ materialFactory, release });
  scene.add(lines.group);
  const aid = createAidMarker();
  scene.add(aid.mesh);
  // hoof dust over the sand; the pool follows the level (none on low)
  const dust = createDust({ quality: 'low', release });
  scene.add(dust.object);
  // grazing horses of the paddock: built when a level has them (see syncGrazing)
  let grazing = null;
  let grazingCount = 0;

  // image-based light from the sky (PMREM; built lazily, freed on "low", rebuilt after a lost
  // WebGL context)
  let envTarget = null;
  let envTexture = null;
  function buildEnvironmentMap() {
    if (envTexture) return envTexture;
    const pmrem = new THREE.PMREMGenerator(renderer);
    const envScene = new THREE.Scene();
    const envSky = createSky({ sunDirection, cloudCount: 0 });
    envScene.add(envSky.group);
    // green ground for the lower hemisphere
    const floor = new THREE.Mesh(
      new THREE.CircleGeometry(300, 16).rotateX(-Math.PI / 2),
      new THREE.MeshBasicMaterial({ color: 0x5d6e3e }),
    );
    floor.position.y = -2;
    envScene.add(floor);
    envTarget = pmrem.fromScene(envScene, 0.04, 0.1, 1000);
    envTexture = envTarget.texture;
    envSky.dispose();
    floor.geometry.dispose();
    floor.material.dispose();
    pmrem.dispose();
    return envTexture;
  }

  function disposeEnvironmentMap() {
    release(envTarget);
    envTarget = null;
    envTexture = null;
  }

  function disposeShadowMap() {
    if (!sun.shadow.map) return;
    release(sun.shadow.map);
    sun.shadow.map = null;
  }

  /** All meshes with material pair and shadow role. */
  function managed() {
    return [...arena.meshes, ...environment.meshes, ...obstacles.meshes, ...lines.meshes].filter(
      (e) => e.mesh,
    );
  }

  // The preset each stage group has reached: a level change is applied stage by stage (see
  // quality-stages.js), so the groups can be at different levels for a short time. Meshes that
  // are added later (obstacles, lines) get the state of the groups, not of a target level.
  const stageState = {
    shadows: QUALITY_PRESETS.medium,
    materials: QUALITY_PRESETS.medium,
    density: QUALITY_PRESETS.medium,
  };

  // The optional details (flowers, tufts, birds, bunting, boxes, props) are not drawn while the
  // materials stage and the density stage are at different levels: the shaders of everything
  // visible change in between, and a detail that appears or disappears one stage later would be
  // compiled twice (see detail-hold.js). Code that decides the visibility itself restores them
  // first (density stage, new obstacles) and calls applyMeshes after it.
  const detailHold = createDetailHold();

  /**
   * Frees the GPU programs of materials (rule 4). three.js keeps every program a material has ever
   * been drawn with until the material is disposed, so a switch to other shaders (material type,
   * wind code, fog, shadows) that only sets `needsUpdate` would hold the old and the new programs
   * at the same time. A disposed material is no problem to use again: three.js builds its program
   * anew with the next compile.
   */
  function freePrograms(materials) {
    for (const material of new Set(materials)) release(material);
  }
  const pairMaterials = () => pairs.flatMap((pair) => [pair.standard, pair.lambert]);
  /** Every material of the scene, also those of the horses, the rider and the dust. */
  function sceneMaterials() {
    const found = new Set(pairMaterials());
    scene.traverse((object) => {
      if (!object.material) return;
      for (const m of Array.isArray(object.material) ? object.material : [object.material]) {
        found.add(m);
      }
    });
    return found;
  }

  // The meshes of the optional details whose GPU buffers were given back after they were hidden
  const freedDetails = new WeakSet();

  /**
   * Gives the GPU buffers of detail meshes that are hidden for good back (flowers, birds, tufts,
   * flower boxes ... of a level that does not have them): hiding a mesh does not free its
   * geometry, and an instanced mesh keeps its instance buffers. A mesh that is only held back
   * for a moment (see detail-hold.js) keeps them; one that is shown again is uploaded again by
   * three.js.
   */
  function freeHiddenDetails() {
    for (const e of managed()) {
      if (!e.detail) continue;
      const { mesh } = e;
      if (mesh.visible || detailHold.has(mesh)) {
        freedDetails.delete(mesh);
      } else if (!freedDetails.has(mesh)) {
        freedDetails.add(mesh);
        release(mesh.geometry);
        if (mesh.isInstancedMesh) release(mesh);
      }
    }
  }

  function applyMeshes() {
    const lambert = stageState.materials.material === 'lambert';
    const { shadows, shadowCasters } = stageState.shadows;
    for (const e of managed()) {
      e.mesh.material = lambert ? e.mats.lambert : e.mats.standard;
      const cast =
        shadows && (e.shadow === 'all' ? shadowCasters === 'all' : e.shadow === 'obstacles');
      e.mesh.castShadow = cast;
      e.mesh.receiveShadow = shadows && e.shadow !== 'none';
    }
    detailHold.sync(
      managed()
        .filter((e) => e.detail)
        .map((e) => e.mesh),
      sameMaterialStage(stageState.materials, stageState.density),
    );
    freeHiddenDetails();
  }

  // Each stage reads what is really there and changes only that, so a stage can be repeated and an
  // interrupted switch can continue from any state.

  /** Shadow pass: the map is freed when it is not used (2048² depth target on "high"). */
  function applyShadowStage(p) {
    stageState.shadows = p;
    // the shadow code is part of every program: the old ones go before the new ones are built
    if (renderer.shadowMap.enabled !== p.shadows) freePrograms(sceneMaterials());
    renderer.shadowMap.enabled = p.shadows;
    sun.castShadow = p.shadows;
    if (!p.shadows) {
      disposeShadowMap();
    } else if (sun.shadow.mapSize.x !== p.shadowMapSize) {
      // three.js makes a new map on the first shadow pass
      sun.shadow.mapSize.set(p.shadowMapSize, p.shadowMapSize);
      disposeShadowMap();
    }
    applyMeshes();
  }

  /**
   * Everything that changes shader programs: material type, normal maps, fog on/off and the
   * environment map. `gpu: false` (the context is lost) leaves the PMREM render to
   * restoreAfterContextLoss.
   */
  function applyMaterialStage(p, gpu) {
    // What changes, read from what is really there. Whatever is given back goes first (the
    // environment map's render target, the programs of the shaders that are left), then the new
    // state is set up; nothing is built before the old is gone (rule 4).
    const stale = new Set();
    const lambertNow = stageState.materials.material === 'lambert';
    if (lambertNow !== (p.material === 'lambert')) pairMaterials().forEach((m) => stale.add(m));
    // fog and the environment map are part of the program of every material in the scene
    const sceneWide = () => sceneMaterials().forEach((m) => stale.add(m));
    if (!p.envMap && scene.environment) {
      sceneWide();
      scene.environment = null;
      disposeEnvironmentMap(); // after it is detached; built again when going back up
    }
    if (!p.fog && scene.fog) {
      sceneWide();
      scene.fog = null;
    }
    if (p.fog && !scene.fog) sceneWide();
    if (p.envMap && !scene.environment) sceneWide();
    // the wind code of the scenery (high only): without it the plain program is used again
    for (const m of pairMaterials()) if (setWindPatch(m, p.wind)) stale.add(m);
    // the Lambert variant has no normal map, so only the standard one is rebuilt
    for (const pair of pairs) {
      const normalMap = p.normalMaps ? pair.normalMap : null;
      if (pair.standard.normalMap !== normalMap) {
        pair.standard.normalMap = normalMap;
        stale.add(pair.standard);
      }
    }
    stageState.materials = p;
    freePrograms(stale);
    applyMeshes();

    // lights: more sky light without an environment map
    if (p.envMap) {
      if (gpu) scene.environment = buildEnvironmentMap();
      scene.environmentIntensity = 0.8;
      hemi.intensity = 0.6;
    } else {
      hemi.intensity = 1.5;
    }

    // fog: a new Fog object makes three.js rebuild every material, so only add it here (the
    // distances are the density stage's business: they are uniforms)
    if (p.fog && !scene.fog) scene.fog = new THREE.Fog(SKY_COLORS.horizon, p.fog.near, p.fog.far);
  }

  /**
   * Scenery: instance counts, geometry detail, the details of a level (flowers, birds, butterflies,
   * bunting, paddock, flower boxes, wind) and the fog distances. Meshes that appear here bring
   * their own shader programs; the engine compiles after this stage.
   */
  function applyDensityStage(p) {
    detailHold.restore(); // the code below decides what is visible
    stageState.density = p;
    if (scene.fog && p.fog) {
      scene.fog.near = p.fog.near;
      scene.fog.far = p.fog.far;
    }
    environment.setDensity(p.envDensity, p.grassTufts, p.envDetail, {
      flowers: p.flowers,
      birds: p.birds,
      butterflies: p.butterflies,
      wind: p.wind,
    });
    const decor = p.decor ?? 0;
    arena.setDetail(decor);
    obstacles.setDecor(Boolean(p.planters));
    dust.setQuality(p.hoofDust ? (p.level === 'high' ? 'high' : 'medium') : 'low');
    syncGrazing(p);
    applyMeshes(); // holds the details back if the materials stage is not at this level yet
  }

  /**
   * The grazing horses of a level: made when the level has them, thrown away (and their GPU
   * objects released) when it has none, rebuilt when their number changes, otherwise only their
   * geometry detail follows the level.
   */
  function syncGrazing(p) {
    const wanted = p.grazingHorses ?? 0;
    if (grazing && wanted !== grazingCount) {
      grazing.dispose();
      grazing = null;
    }
    grazingCount = wanted;
    if (wanted <= 0) return;
    if (grazing) {
      grazing.setQuality(p);
      return;
    }
    grazing = createGrazingHorses({
      quality: p,
      area: { ...PADDOCK, avoid: planPaddockKeepOut() },
      count: wanted,
      coats: GRAZING_COATS,
      rng: createRng(GRAZING_SEED),
      release,
    });
    scene.add(grazing.group);
  }

  // scratch objects of the view test (see paddockInView)
  const viewMatrix = new THREE.Matrix4();
  const viewFrustum = new THREE.Frustum();
  const paddockSphere = new THREE.Sphere(
    new THREE.Vector3(PADDOCK_SPHERE.x, 0, PADDOCK_SPHERE.z),
    PADDOCK_SPHERE.radius,
  );
  function paddockInView(camera) {
    if (!camera) return true;
    viewMatrix.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse);
    return viewFrustum.setFromProjectionMatrix(viewMatrix).intersectsSphere(paddockSphere);
  }

  /**
   * Applies the world's anisotropic filtering of a level. Only call it while nothing is drawn (at
   * the start of a ride): three.js reads `texture.anisotropy` only when a texture is uploaded, so
   * a change needs `needsUpdate` and uploads the texture again, which is the most expensive part
   * of a level change (r186 WebGLTextures.js, uploadTexture → setTextureParameters). Textures
   * whose value already fits are not touched.
   */
  function syncAnisotropy(level) {
    const p = presetFor(level);
    if (!p) return;
    const anisotropy = Math.min(p.anisotropy, renderer.capabilities.getMaxAnisotropy());
    for (const t of [...arena.textures, ...environment.textures]) {
      if (t && t.anisotropy !== anisotropy) {
        t.anisotropy = anisotropy;
        t.needsUpdate = true;
      }
    }
  }

  /**
   * One stage of a level change ('shadows' | 'materials' | 'shadowsAndMaterials' | 'density', see
   * quality-stages.js).
   * The pixel ratio is the engine's business: changing it clears the drawing buffer, which must
   * not happen before the new shaders are ready (see engine.js). Horse and rider are the
   * engine's, too.
   */
  function applyQualityStage(id, level) {
    const p = presetFor(level);
    if (!p) return;
    if (id === 'shadows') applyShadowStage(p);
    else if (id === 'materials') applyMaterialStage(p, true);
    else if (id === MERGED_STAGE_ID) {
      applyShadowStage(p);
      applyMaterialStage(p, true);
    } else if (id === 'density') applyDensityStage(p);
  }

  /**
   * Switches to a level in one go (creation, a level change while no ride is drawing, a lost
   * context): every stage and the anisotropy. Only what really differs is touched.
   */
  function setQuality(next, { gpu = true } = {}) {
    const p = presetFor(next);
    if (!p) return;
    applyShadowStage(p);
    applyMaterialStage(p, gpu);
    applyDensityStage(p);
    syncAnisotropy(next);
  }

  /**
   * After a lost and restored WebGL context. three.js recreates its own GPU state (programs,
   * textures, buffers of geometries, instanced meshes and the shadow-map target) from the CPU
   * data on the next render. Not restorable is what only lived on the GPU: the PMREM environment
   * map is the result of a render pass, so it comes back empty and must be rendered again. The
   * old target is only forgotten, not disposed: disposing it would run dispose listeners of the
   * pre-restore textures and delete handles that belong to the lost context (`release` knows
   * that, see createGpuEpoch). The same goes for the shadow map, which three.js makes anew on
   * the next shadow pass: an old one that is kept would be uploaded again but could never be
   * freed without GL errors.
   * Source: onContextRestore in three r186 src/renderers/WebGLRenderer.js (calls initGLContext,
   * which resets properties, textures, geometries, programs and the shadow map object).
   */
  function restoreAfterContextLoss() {
    disposeEnvironmentMap();
    disposeShadowMap();
    if (stageState.materials.envMap) scene.environment = buildEnvironmentMap();
  }

  /**
   * What the engine compiles after a stage: the visible objects only. renderer.compileAsync(scene)
   * would also build the programs of every hidden mesh (it walks the whole graph, not just the
   * visible part, r186 WebGLRenderer.compile), i.e. the flowers, birds and flower boxes of a level
   * that does not show them. The root answers the walk with the visible objects, plus the hoof
   * dust of a level that has it (its points are hidden between two puffs, and the first puff
   * should not stall the frame with a compile). Pass it as the scene to compile, with the real
   * scene as the target: renderer.compileAsync(world.compileRoot, camera, world.scene).
   */
  const compileRoot = {
    traverse(fn) {
      scene.traverseVisible(fn);
      if (stageState.density.hoofDust) dust.object.children.forEach((o) => !o.visible && fn(o));
    },
    // WebGLRenderer.compile gathers the lights of `scene` a second time when it is not the target
    // scene: the lights are the target's (the real scene's), so there is nothing to add here
    // (they would count twice and the programs would be built for two sets of lights, which the
    // real render never uses)
    traverseVisible() {},
  };

  // The sun never moves: its light-space axes and the scratch vectors are made once
  const lightFwd = sunDirection.clone().negate();
  const lightRight = new THREE.Vector3()
    .crossVectors(lightFwd, THREE.Object3D.DEFAULT_UP)
    .normalize();
  const lightUp = new THREE.Vector3().crossVectors(lightRight, lightFwd).normalize();
  const snapped = new THREE.Vector3();

  function updateShadowCamera() {
    // snap to the shadow map texel grid (no shimmering while following)
    const size = sun.shadow.mapSize.x || 1024;
    const texel = (SHADOW_HALF * 2) / size;
    const r = shadowFocus.dot(lightRight);
    const u = shadowFocus.dot(lightUp);
    snapped
      .copy(shadowFocus)
      .addScaledVector(lightRight, Math.round(r / texel) * texel - r)
      .addScaledVector(lightUp, Math.round(u / texel) * texel - u);
    sun.target.position.copy(snapped);
    sun.position.copy(snapped).addScaledVector(sunDirection, SUN_DISTANCE);
    sun.target.updateMatrixWorld();
  }

  // approach direction per element (from setAid) so poles fall in jump direction
  const approachDirs = new Map();
  let aidShown = null; // aid parameters currently applied to the marker

  setQuality(quality);
  updateShadowCamera();

  return {
    scene,
    compileRoot,
    setObstacles(list, { flags = false } = {}) {
      detailHold.restore();
      obstacles.setObstacles(list, { flags });
      approachDirs.clear();
      aid.hide();
      aidShown = null;
      applyMeshes();
    },
    /** rails: Map elementId → boolean[]; fallDirs optional Map elementId → ±1. */
    syncRails(rails, dt = 0, fallDirs = null) {
      obstacles.syncRails(rails, dt, fallDirs, approachDirs);
    },
    highlight(elementIdOrNull, number) {
      obstacles.highlight(elementIdOrNull, number);
    },
    setAid(params) {
      if (!params) {
        if (aidShown) aid.hide();
        aidShown = null;
        return;
      }
      // called every frame with an equal value most of the time: skip the placement then
      const zone = params.zone;
      if (
        aidShown &&
        aidShown.elementId === params.elementId &&
        aidShown.dir === params.dir &&
        aidShown.near === zone?.near &&
        aidShown.far === zone?.far
      ) {
        return;
      }
      const element = obstacles.getElement(params.elementId);
      if (!element) {
        aid.hide();
        aidShown = null;
        return;
      }
      approachDirs.set(params.elementId, params.dir < 0 ? -1 : 1);
      aid.set(element, params.dir, zone);
      aidShown = {
        elementId: params.elementId,
        dir: params.dir,
        near: zone?.near,
        far: zone?.far,
      };
    },
    setLines(params) {
      lines.set(params);
      applyMeshes();
    },
    setFinishMarked(on) {
      lines.setFinishMarked(on);
    },
    setQuality,
    /** Size and kind of the textures the world uploads, for the GPU memory estimate. */
    textureSizes() {
      const sizes = new Map();
      for (const pair of pairs) {
        for (const [texture, normal] of [
          [pair.standard.map, false],
          [pair.normalMap, true],
        ]) {
          if (texture?.image) {
            sizes.set(texture, {
              width: texture.image.width,
              height: texture.image.height,
              normal,
            });
          }
        }
      }
      return [...sizes.values()];
    },
    applyQualityStage,
    syncAnisotropy,
    restoreAfterContextLoss,
    setShadowFocus(x, z) {
      shadowFocus.set(x, 0, z);
      updateShadowCamera();
    },
    /**
     * A footfall at the world position (x, y, z) of the sand: hoof dust, if the footfall is strong
     * enough (not at the walk), on the sand of the arena (not on grass or the path) and the level
     * has dust. `strength`: 0..1 (see horse.footfalls).
     */
    emitHoofDust(x, y, z, strength) {
      if (strength < DUST_MIN_STRENGTH || !isOnArenaSand(x, z, DUST_EDGE_MARGIN)) return;
      dust.emit(x, y, z, strength);
    },
    update(dt, camera) {
      dust.update(dt);
      if (grazing && paddockInView(camera)) grazing.update(dt);
      sky.update(dt, camera);
      environment.update(dt);
      obstacles.update(dt, camera);
      lines.update(dt);
      aid.update(dt);
    },
    /** Everything that has GPU resources now; handed to createGpuEpoch.contextLost. */
    gpuObjects() {
      return collectGpuObjects(scene, [envTarget]);
    },
    dispose() {
      grazing?.dispose();
      grazing = null;
      dust.dispose();
      obstacles.dispose();
      lines.dispose();
      aid.dispose();
      sky.dispose();
      const textures = new Set();
      scene.traverse((o) => {
        if (o.isInstancedMesh) o.dispose();
        if (o.geometry) o.geometry.dispose();
      });
      for (const pair of pairs) {
        for (const m of [pair.standard, pair.lambert]) {
          for (const key of ['map', 'normalMap', 'alphaMap']) if (m[key]) textures.add(m[key]);
          m.dispose();
        }
      }
      textures.forEach((t) => t.dispose());
      disposeEnvironmentMap();
      disposeShadowMap();
    },
  };
}
