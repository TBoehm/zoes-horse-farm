// Soft limit of the reach of a two-bone limb (pure, no three.js), shared by the horse legs and
// the rider's arms.

/**
 * Soft limit of the reach: a target distance up to `soft` short of the full stretch is kept, further
 * out it is compressed so that it approaches the full stretch without ever touching it. With
 * soft = 0 it is the hard clamp. A hard clamp locks the leg straight for as long as the hoof
 * target is out of reach (the body is in the air during a jump, a long stride ends at the full
 * stretch) and then, when the target comes back, the knee bends by 40° or more in one frame (acos
 * has an infinite slope at the full stretch). The compression keeps the slope bounded, so that a
 * landing, a take-off or a lift-off bends the joints smoothly. The price is a hoof that stays up
 * to a few millimetres short of a target near the full stretch (the rest pose and the stance of
 * the gaits are near it).
 */
export function softReach(d, dMax, soft) {
  if (soft <= 0) return Math.min(d, dMax);
  const dSoft = dMax - soft;
  if (d <= dSoft) return d;
  return dSoft + soft * (1 - Math.exp(-(d - dSoft) / soft));
}
