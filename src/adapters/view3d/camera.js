// Camera (rules 13, 14): default is diagonally behind/above horse and rider, rider view between the ears.
import * as THREE from 'three';

export const CAMERA_MODES = ['follow', 'rider'];

const RIDER_VIEW = { back: 0.5, up: 0.24, lookDown: 1.0 };
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
    // When jumping, lift the camera only halfway so the jump stays visible
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
    /** Jump straight to the target position (e.g. after a restart). */
    snap() {
      initialized = false;
    },
    update(dt, state, earAnchor) {
      if (mode === 'rider' && earAnchor) {
        earAnchor.updateWorldMatrix(true, false);
        earAnchor.getWorldPosition(tmp);
        const fx = Math.sin(state.heading);
        const fz = Math.cos(state.heading);
        // Eyes slightly behind and above the poll so that the ears and the mane stay in view
        camera.position.set(
          tmp.x - fx * RIDER_VIEW.back,
          tmp.y + RIDER_VIEW.up,
          tmp.z - fz * RIDER_VIEW.back,
        );
        look.set(tmp.x + fx * 10, tmp.y - RIDER_VIEW.lookDown, tmp.z + fz * 10);
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
