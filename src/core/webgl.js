// Prüft, ob 3D-Darstellung (WebGL) möglich ist (Regel 7).
export function hasWebGL(doc = globalThis.document) {
  try {
    const canvas = doc.createElement('canvas');
    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
    if (!gl) return false;
    gl.getExtension('WEBGL_lose_context')?.loseContext();
    return true;
  } catch {
    return false;
  }
}
