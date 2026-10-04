// The 3D world: lights, sky, arena, environment, obstacles and markings.
// Quality levels can be switched at runtime (the governor downgrades).
import * as THREE from 'three';
import { QUALITY_PRESETS } from './quality.js';
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

  let preset = QUALITY_PRESETS.medium;

  function applyMeshes() {
    const lambert = preset.material === 'lambert';
    for (const e of managed()) {
      e.mesh.material = lambert ? e.mats.lambert : e.mats.standard;
      const cast =
        preset.shadows &&
        (e.shadow === 'all' ? preset.shadowCasters === 'all' : e.shadow === 'obstacles');
      e.mesh.castShadow = cast;
      e.mesh.receiveShadow = preset.shadows && e.shadow !== 'none';
    }
  }

  /**
   * Switches the quality level. Only what really differs between the levels is touched: a
   * material needs a new shader only when its normal map is switched on/off (three.js rebuilds
   * the programs for fog, environment map and shadow changes by itself), and a texture is only
   * uploaded again when its anisotropy changes.
   */
  function setQuality(next) {
    const p = QUALITY_PRESETS[next];
    if (!p) return;
    const before = preset;
    preset = p;
    // The pixel ratio is the engine's business: changing it clears the drawing buffer, which must
    // not happen before the new shaders are ready (see engine.js)

    // shadows: the map is freed when it is not used (2048² depth target on "high") and made again
    // by three.js on the first shadow pass, or when its size changes
    renderer.shadowMap.enabled = p.shadows;
    sun.castShadow = p.shadows;
    if (!p.shadows) {
      disposeShadowMap();
    } else if (sun.shadow.mapSize.x !== p.shadowMapSize) {
      sun.shadow.mapSize.set(p.shadowMapSize, p.shadowMapSize);
      disposeShadowMap();
    }

    // materials: the Lambert variant has no normal map, so only the standard one is rebuilt
    if (before.normalMaps !== p.normalMaps) {
      for (const pair of pairs) {
        pair.standard.normalMap = p.normalMaps ? pair.normalMap : null;
        pair.standard.needsUpdate = true;
      }
    }
    const anisotropy = Math.min(p.anisotropy, renderer.capabilities.getMaxAnisotropy());
    for (const t of [...arena.textures, ...environment.textures]) {
      if (t && t.anisotropy !== anisotropy) {
        t.anisotropy = anisotropy;
        t.needsUpdate = true;
      }
    }
    applyMeshes();

    // lights: more sky light without an environment map
    if (p.envMap) {
      scene.environment = buildEnvironmentMap();
      scene.environmentIntensity = 0.8;
      hemi.intensity = 0.6;
    } else {
      scene.environment = null;
      disposeEnvironmentMap(); // after it is detached; built again when going back up
      hemi.intensity = 1.5;
    }

    // fog: a new Fog object makes three.js rebuild every material, so keep the old one if the
    // values are the same
    if (before.fog !== p.fog || (p.fog && !scene.fog)) {
      scene.fog = p.fog ? new THREE.Fog(SKY_COLORS.horizon, p.fog.near, p.fog.far) : null;
    }

    environment.setDensity(p.envDensity, p.grassTufts, p.envDetail);
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
    if (preset.envMap) scene.environment = buildEnvironmentMap();
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
