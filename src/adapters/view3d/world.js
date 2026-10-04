// The 3D world: lights, sky, arena, environment, obstacles and markings.
// Quality levels can be switched at runtime (the governor downgrades), stage by stage.
import * as THREE from 'three';
import { presetFor, QUALITY_PRESETS } from './quality.js';
import { createSky, SKY_COLORS } from './sky.js';
import { createArena, createCourseLines } from './arena.js';
import { createEnvironment, SITE } from './environment.js';
import { createObstacles } from './obstacles.js';
import { createAidMarker } from './aid-marker.js';

const SHADOW_HALF = 24; // half extent of the shadow camera (m)
const SUN_DISTANCE = 90;

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
 * `quality`: a level name or a preset object.
 */
export function createWorld(renderer, { quality = 'medium' } = {}) {
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

  // arena, environment, obstacles, markings
  const arena = createArena({ materialFactory, path: SITE.path, pathFence: SITE.pathFence });
  scene.add(arena.group);
  const environment = createEnvironment({ materialFactory });
  scene.add(environment.group);
  const obstacles = createObstacles({ materialFactory });
  scene.add(obstacles.group);
  const lines = createCourseLines({ materialFactory });
  scene.add(lines.group);
  const aid = createAidMarker();
  scene.add(aid.mesh);

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
    envTarget?.dispose();
    envTarget = null;
    envTexture = null;
  }

  function disposeShadowMap() {
    if (!sun.shadow.map) return;
    sun.shadow.map.dispose();
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
  }

  // Each stage reads what is really there and changes only that, so a stage can be repeated and an
  // interrupted switch can continue from any state.

  /** Shadow pass: the map is freed when it is not used (2048² depth target on "high"). */
  function applyShadowStage(p) {
    stageState.shadows = p;
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
    stageState.materials = p;
    // the Lambert variant has no normal map, so only the standard one is rebuilt
    for (const pair of pairs) {
      const normalMap = p.normalMaps ? pair.normalMap : null;
      if (pair.standard.normalMap !== normalMap) {
        pair.standard.normalMap = normalMap;
        pair.standard.needsUpdate = true;
      }
    }
    applyMeshes();

    // lights: more sky light without an environment map
    if (p.envMap) {
      if (gpu) scene.environment = buildEnvironmentMap();
      scene.environmentIntensity = 0.8;
      hemi.intensity = 0.6;
    } else {
      scene.environment = null;
      disposeEnvironmentMap(); // after it is detached; built again when going back up
      hemi.intensity = 1.5;
    }

    // fog: a new Fog object makes three.js rebuild every material, so only add or remove it here
    // (the distances are the density stage's business: they are uniforms)
    if (p.fog && !scene.fog) scene.fog = new THREE.Fog(SKY_COLORS.horizon, p.fog.near, p.fog.far);
    else if (!p.fog) scene.fog = null;
  }

  /** Scenery: instance counts, geometry detail and the fog distances (no shader change). */
  function applyDensityStage(p) {
    stageState.density = p;
    if (scene.fog && p.fog) {
      scene.fog.near = p.fog.near;
      scene.fog.far = p.fog.far;
    }
    environment.setDensity(p.envDensity, p.grassTufts, p.envDetail);
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
   * One stage of a level change ('shadows' | 'materials' | 'density', see quality-stages.js).
   * The pixel ratio is the engine's business: changing it clears the drawing buffer, which must
   * not happen before the new shaders are ready (see engine.js). Horse and rider are the
   * engine's, too.
   */
  function applyQualityStage(id, level) {
    const p = presetFor(level);
    if (!p) return;
    if (id === 'shadows') applyShadowStage(p);
    else if (id === 'materials') applyMaterialStage(p, true);
    else if (id === 'density') applyDensityStage(p);
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
   * pre-restore textures and delete handles that belong to the lost context. The shadow map stays
   * as it is (three.js rebuilds its framebuffer lazily).
   * Source: onContextRestore in three r186 src/renderers/WebGLRenderer.js (calls initGLContext,
   * which resets properties, textures, geometries, programs and the shadow map object).
   */
  function restoreAfterContextLoss() {
    envTarget = null;
    envTexture = null;
    if (stageState.materials.envMap) scene.environment = buildEnvironmentMap();
  }

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
    setObstacles(list, { flags = false } = {}) {
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
    update(dt, camera) {
      sky.update(dt, camera);
      environment.update(dt);
      obstacles.update(dt, camera);
      lines.update(dt);
      aid.update(dt);
    },
    dispose() {
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
