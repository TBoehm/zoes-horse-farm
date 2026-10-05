package app.zoeshorsefarm.view3d

import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.MaterialEffect
import app.zoeshorsefarm.scene.material.Wind
import app.zoeshorsefarm.scene.material.WindEffect

// Wind and small animations in the vertex shaders of the scenery (rule 3): trees, bushes, grass,
// flowers, bunting, birds and butterflies move without any work on the CPU. All patches share the
// same wind (time and strength), so the whole scene breathes with one wind.
// In the web app each patch extends a three.js material through onBeforeCompile; here a patch is
// the named effect `WindEffect` on the material, and the vertex maths lives in the Filament
// backend (`:adapters:render-filament`), which implements each kind of the effect.

/** The wind of a world: `time` (s, advanced once per frame) and `strength` (0 = calm, 1 = full). */
fun createWind(): Wind = Wind()

private fun <T : Material> patch(
    material: T,
    effect: MaterialEffect,
): T {
    material.effect = effect
    material.effectEnabled = true
    return material
}

/**
 * Switches the wind code of a patched material on or off (rule 4: wind only on "high"). Off, the
 * material builds the plain program (the one it had before the details existed, which other plain
 * materials share); on, the patched one. Returns true if the program changes: the caller must set
 * `needsUpdate` and dispose the material then, so that the old program is freed before the new one
 * is built (a material keeps every program it has ever had until it is disposed).
 */
fun setWindPatch(
    material: Material,
    on: Boolean,
): Boolean {
    if (material.effect !is WindEffect || material.effectEnabled == on) return false
    material.effectEnabled = on
    return true
}

/**
 * Tree crowns: the crown leans and swings with slow gusts (weight grows with the height above the
 * ground, the trunk stays), the leaves shimmer a little. Instanced meshes only (the phase comes
 * from the position of the instance, so neighbours do not move in step).
 */
fun <T : Material> patchTreeWind(
    material: T,
    wind: Wind,
): T = patch(material, WindEffect.tree(wind))

/** Bushes: the whole blob sways a little, more at the top. */
fun <T : Material> patchBushWind(
    material: T,
    wind: Wind,
): T = patch(material, WindEffect.bush(wind))

/** Grass tufts: the tips sway, depending on the position of the instance. */
fun <T : Material> patchTuftWind(
    material: T,
    wind: Wind,
): T = patch(material, WindEffect.tuft(wind))

/**
 * Flowers: the stem bends with the wind, and the geometry's `petal` attribute (1 on petals, 0 on
 * stem and heart) picks which vertices take the colour of the instance. Instanced meshes with an
 * instance colour only.
 * [base]: height (m) of a rigid part below the plants (the wooden box of a flower box): only what
 * is above it bends, so the box itself stays put. 0 for the meadow flowers.
 */
fun <T : Material> patchBlossoms(
    material: T,
    wind: Wind,
    base: Double = 0.0,
): T = patch(material, WindEffect.blossoms(wind, base))

/**
 * Bunting: the pennants flutter across the string. The `aFlutter` attribute is the direction of
 * the movement times the weight (0 at the string, 1 at the tip). The mesh sits at the origin, so
 * the position is the phase.
 */
fun <T : Material> patchBunting(
    material: T,
    wind: Wind,
): T = patch(material, WindEffect.bunting(wind))

/**
 * Wings flap in the vertex shader (the geometry lies along X, the body in the middle). [rate] is
 * the beat in rad/s, [amplitude] the lift of the wing tips per metre from the body, [glide] > 0
 * lets the beat pause now and then (birds glide between bursts). Instanced meshes only: every
 * instance beats with its own phase.
 */
fun <T : Material> patchWings(
    material: T,
    wind: Wind,
    rate: Double,
    amplitude: Double,
    glide: Double = 0.0,
): T = patch(material, WindEffect.wings(wind, rate, amplitude, glide))
