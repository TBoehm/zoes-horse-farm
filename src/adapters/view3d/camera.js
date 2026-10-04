// Camera (rules 13, 14): default is diagonally behind/above horse and rider, rider view between
// the ears.
import * as THREE from 'three';
import {
  FOLLOW_HEADING_MAX_RATE,
  FOLLOW_HEADING_STIFFNESS,
  followHeading,
  RIDER_HEADING_STIFFNESS,
} from './camera-math.js';

export const CAMERA_MODES = ['follow', 'rider'];

const RIDER_VIEW = { back: 0.5, up: 0.24, lookDown: 1.0 };
const FOLLOW = {
  back: 7.5,
  height: 3.6,
  lookAhead: 6,
  lookHeight: 1.1,
  stiffness: 5,
};
// The camera heading trails the horse's heading (constants and reasoning: camera-math.js), so the
// quick turns of the agile steering (SRT-009) sweep the view calmly. The rider view's look
// direction trails only slightly.

export function createCameraRig(camera) {
  let mode = 'follow';
  let initialized = false;
  let camHeading = 0;
  let headingReady = false;
  const pos = new THREE.Vector3();
  const look = new THREE.Vector3();
  const tmp = new THREE.Vector3();
  const desiredPos = new THREE.Vector3();
  const desiredLook = new THREE.Vector3();

  /** Eases the camera heading towards the horse's heading (calm view on fast turns). */
  function trailHeading(dt, heading, stiffness, maxRate) {
    camHeading = headingReady
      ? followHeading(camHeading, heading, stiffness, dt, maxRate)
      : heading;
    headingReady = true;
    return camHeading;
  }

  function followTargets(state) {
    const fx = Math.sin(camHeading);
    const fz = Math.cos(camHeading);
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
      headingReady = false;
    },
    toggle() {
      this.setMode(mode === 'follow' ? 'rider' : 'follow');
      return mode;
    },
    /** Jump straight to the target position (e.g. after a restart). */
    snap() {
      initialized = false;
      headingReady = false;
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
        // the look direction trails the heading slightly; the eye position stays on the horse
        const h = trailHeading(dt, state.heading, RIDER_HEADING_STIFFNESS, Infinity);
        look.set(tmp.x + Math.sin(h) * 10, tmp.y - RIDER_VIEW.lookDown, tmp.z + Math.cos(h) * 10);
        camera.lookAt(look);
        return;
      }
      trailHeading(dt, state.heading, FOLLOW_HEADING_STIFFNESS, FOLLOW_HEADING_MAX_RATE);
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
