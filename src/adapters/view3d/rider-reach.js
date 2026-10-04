// Reach of a limb of the rider (pure, no three.js). A hard clamp at full extension makes the
// elbow/knee angle change very fast for a small move of the target (the angle follows acos of the
// distance), which shows as a pop when a hand is carried forward over the horse's neck. The soft
// limit compresses the last part of the reach smoothly, so the limb straightens gently and never
// fully.
import { softReach } from '../../shared/reach.js';

const KNEE = 0.86; // share of the full reach where the compression starts
const FULL = 0.998; // the limb never gets longer than this share of l1 + l2

/** Distance the limb is posed for when the target is `dist` away. */
export function limbReach(dist, l1, l2) {
  const max = l1 + l2;
  const min = Math.abs(l1 - l2) + 1e-3;
  return softReach(Math.max(dist, min), FULL * max, (FULL - KNEE) * max);
}
