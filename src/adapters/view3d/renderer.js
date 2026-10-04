// WebGL renderer with a consistent color / tone-mapping setup.
import * as THREE from 'three';

const SIZE = new THREE.Vector2(); // scratch: resize is checked every frame

/**
 * Creates the renderer. Antialiasing cannot be toggled later (context attribute), so pick it
 * to match the initial quality level.
 */
export function createRenderer(canvas, { antialias = true, alpha = false } = {}) {
  const renderer = new THREE.WebGLRenderer({
    canvas,
    antialias,
    alpha,
    powerPreference: 'high-performance',
    stencil: false,
  });
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;
  renderer.shadowMap.enabled = false;
  renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.shadowMap.autoUpdate = true;
  renderer.userData = { maxPixelRatio: 2 };
  return renderer;
}

/** Sets the pixel-ratio cap (quality level) and applies it right away. */
export function setMaxPixelRatio(renderer, maxPixelRatio, devicePixelRatio = defaultDpr()) {
  renderer.userData = { ...renderer.userData, maxPixelRatio };
  const ratio = Math.min(devicePixelRatio, maxPixelRatio);
  if (renderer.getPixelRatio() !== ratio) renderer.setPixelRatio(ratio);
  return ratio;
}

/**
 * Fits drawing buffer and camera to the size. Without width/height the canvas CSS size is used.
 * Returns true if anything changed.
 */
export function resizeRenderer(renderer, camera, width, height, maxPixelRatio) {
  const canvas = renderer.domElement;
  const w = Math.max(1, Math.floor(width ?? canvas.clientWidth ?? 1));
  const h = Math.max(1, Math.floor(height ?? canvas.clientHeight ?? 1));
  const max = maxPixelRatio ?? renderer.userData?.maxPixelRatio ?? 2;
  const ratio = Math.min(defaultDpr(), max);
  const size = renderer.getSize(SIZE);
  const changed = size.x !== w || size.y !== h || renderer.getPixelRatio() !== ratio;
  if (!changed) return false;
  renderer.setPixelRatio(ratio);
  renderer.setSize(w, h, false);
  if (camera && camera.isPerspectiveCamera) {
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  return true;
}

function defaultDpr() {
  return (typeof window !== 'undefined' && window.devicePixelRatio) || 1;
}
