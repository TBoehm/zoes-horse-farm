// WebGL-Renderer mit einheitlicher Farb-/Tonwert-Konfiguration.
import * as THREE from 'three';

/**
 * Erzeugt den Renderer. Kantenglättung lässt sich später nicht mehr umschalten
 * (Kontext-Attribut), daher beim Erzeugen passend zur Startstufe wählen.
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

/** Obergrenze der Pixeldichte setzen (Grafikstufe) und sofort anwenden. */
export function setMaxPixelRatio(renderer, maxPixelRatio, devicePixelRatio = defaultDpr()) {
  renderer.userData = { ...renderer.userData, maxPixelRatio };
  const ratio = Math.min(devicePixelRatio, maxPixelRatio);
  if (renderer.getPixelRatio() !== ratio) renderer.setPixelRatio(ratio);
  return ratio;
}

/**
 * Passt Zeichenfläche und Kamera an die Größe an. Ohne width/height wird die CSS-Größe des
 * Canvas genommen. Liefert true, wenn sich etwas geändert hat.
 */
export function resizeRenderer(renderer, camera, width, height, maxPixelRatio) {
  const canvas = renderer.domElement;
  const w = Math.max(1, Math.floor(width ?? canvas.clientWidth ?? 1));
  const h = Math.max(1, Math.floor(height ?? canvas.clientHeight ?? 1));
  const max = maxPixelRatio ?? renderer.userData?.maxPixelRatio ?? 2;
  const ratio = Math.min(defaultDpr(), max);
  const size = renderer.getSize(new THREE.Vector2());
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
