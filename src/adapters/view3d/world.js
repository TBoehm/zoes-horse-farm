// The 3D world: lights, sky, arena, environment, obstacles and markings.
// Quality levels can be switched at runtime (the governor downgrades).
import * as THREE from 'three';
import { QUALITY_PRESETS } from './quality.js';
import { setMaxPixelRatio } from './renderer.js';
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

  // image-based light from the sky (PMREM, once)
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
    envTexture = pmrem.fromScene(envScene, 0.04, 0.1, 1000).texture;
    envSky.dispose();
    floor.geometry.dispose();
    floor.material.dispose();
    pmrem.dispose();
    return envTexture;
  }

  /** All meshes with material pair and shadow role. */
  function managed() {
    return [...arena.meshes, ...environment.meshes, ...obstacles.meshes, ...lines.meshes].filter(
      (e) => e.mesh,
    );
  }

  let level = null;
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

  function setQuality(next) {
    const p = QUALITY_PRESETS[next];
    if (!p) return;
    level = next;
    preset = p;
    setMaxPixelRatio(renderer, p.pixelRatio);

    // shadows
    renderer.shadowMap.enabled = p.shadows;
    sun.castShadow = p.shadows;
    if (p.shadows && sun.shadow.mapSize.x !== p.shadowMapSize) {
      sun.shadow.mapSize.set(p.shadowMapSize, p.shadowMapSize);
      if (sun.shadow.map) {
        sun.shadow.map.dispose();
        sun.shadow.map = null;
      }
    }

    // materials
    for (const pair of pairs) {
      pair.standard.normalMap = p.normalMaps ? pair.normalMap : null;
      pair.standard.needsUpdate = true;
      pair.lambert.needsUpdate = true;
    }
    for (const t of [...arena.textures, ...environment.textures]) {
      if (t && t.anisotropy !== p.anisotropy) {
        t.anisotropy = Math.min(p.anisotropy, renderer.capabilities.getMaxAnisotropy());
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
      hemi.intensity = 1.5;
    }

    // fog
    scene.fog = p.fog ? new THREE.Fog(SKY_COLORS.horizon, p.fog.near, p.fog.far) : null;

    environment.setDensity(p.envDensity, p.grassTufts, p.envDetail);
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
    sun,
    get level() {
      return level;
    },
    /** Active preset (e.g. so horse/rider can set their shadows accordingly). */
    get preset() {
      return preset;
    },
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
      if (envTexture) envTexture.dispose();
      if (sun.shadow.map) sun.shadow.map.dispose();
    },
  };
}
