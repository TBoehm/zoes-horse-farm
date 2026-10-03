// Kamera (Regeln 13, 14): Standard schräg hinter/über Pferd und Reiter, Reiter-Sicht zwischen den Ohren.
import * as THREE from 'three';

export const CAMERA_MODES = ['follow', 'rider'];

const FOLLOW = { back: 7.5, height: 3.6, lookAhead: 6, lookHeight: 1.1, stiffness: 5 };

export function createCameraRig(camera) {
  let mode = 'follow';
  let initialized = false;
  const pos = new THREE.Vector3();
  const look = new THREE.Vector3();
  const tmp = new THREE.Vector3();
  const desiredPos = new THREE.Vector3();
  const desiredLook = new THREE.Vector3();

  function followTargets(state) {
    const fx = Math.sin(state.heading);
    const fz = Math.cos(state.heading);
    // Beim Springen die Kamera nur halb mit anheben, damit der Sprung sichtbar bleibt
    const lift = (state.y ?? 0) * 0.5;
    desiredPos.set(state.x - fx * FOLLOW.back, FOLLOW.height + lift, state.z - fz * FOLLOW.back);
    desiredLook.set(
      state.x + fx * FOLLOW.lookAhead,
      FOLLOW.lookHeight + lift,
      state.z + fz * FOLLOW.lookAhead,
    );
  }

  return {
    get mode() {
      return mode;
    },
    setMode(next) {
      if (!CAMERA_MODES.includes(next)) return;
      mode = next;
      initialized = false;
    },
    toggle() {
      this.setMode(mode === 'follow' ? 'rider' : 'follow');
      return mode;
    },
    /** Sofort auf die Zielposition springen (z. B. nach Neustart). */
    snap() {
      initialized = false;
    },
    update(dt, state, earAnchor) {
      if (mode === 'rider' && earAnchor) {
        earAnchor.updateWorldMatrix(true, false);
        earAnchor.getWorldPosition(tmp);
        camera.position.copy(tmp);
        camera.position.y += 0.12;
        const fx = Math.sin(state.heading);
        const fz = Math.cos(state.heading);
        look.set(tmp.x + fx * 10, tmp.y - 0.9, tmp.z + fz * 10);
        camera.lookAt(look);
        return;
      }
      followTargets(state);
      if (!initialized) {
        pos.copy(desiredPos);
        look.copy(desiredLook);
        initialized = true;
      } else {
        const k = 1 - Math.exp(-FOLLOW.stiffness * dt);
        pos.lerp(desiredPos, k);
        look.lerp(desiredLook, Math.min(1, k * 1.6));
      }
      camera.position.copy(pos);
      camera.lookAt(look);
    },
  };
}
