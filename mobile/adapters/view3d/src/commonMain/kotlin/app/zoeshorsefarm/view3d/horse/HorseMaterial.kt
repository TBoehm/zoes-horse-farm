package app.zoeshorsefarm.view3d.horse

import app.zoeshorsefarm.application.GraphicsLevel
import app.zoeshorsefarm.domain.horse.Appearance
import app.zoeshorsefarm.scene.material.CoatEffect
import app.zoeshorsefarm.scene.material.CoatUniforms
import app.zoeshorsefarm.scene.material.LambertMaterial
import app.zoeshorsefarm.scene.material.Material
import app.zoeshorsefarm.scene.material.StandardMaterial
import app.zoeshorsefarm.scene.math.Color
import app.zoeshorsefarm.scene.math.ColorSpace

// Coat material: StandardMaterial (low: LambertMaterial) with the CoatEffect. The colour is computed
// per pixel by the render backend from the rest-pose position (aRest: 3D noise for dapples and
// pinto patches, dark lower legs), material weights (aMat: long hair, hoof, eye, inner ear) and
// head coordinates (aFace: s along the head, u lateral, front-ness) for markings and nostrils.
// setAppearance only changes uniforms - no rebuild, no shader recompilation.

private fun setLinear(
    target: Color,
    rgb: DoubleArray,
) {
    target.setRGB(rgb[0], rgb[1], rgb[2], ColorSpace.SRGB)
}

fun createCoatUniforms(): CoatUniforms = CoatUniforms()

/** Puts the colours and patterns of [appearance] into [uniforms]; returns the normalised appearance. */
fun applyAppearance(
    uniforms: CoatUniforms,
    appearance: Appearance,
): Appearance {
    val a = normalizeAppearance(appearance.coat, appearance.marking)
    val p = coatParams(a.coat)
    setLinear(uniforms.base, p.base)
    setLinear(uniforms.dark, p.dark)
    setLinear(uniforms.belly, p.belly)
    setLinear(uniforms.hair, p.hair)
    setLinear(uniforms.pointColor, p.pointColor)
    setLinear(uniforms.muzzle, p.muzzle)
    setLinear(uniforms.hoof, p.hoof)
    setLinear(uniforms.white, p.white)
    uniforms.points = p.points
    uniforms.dapple = p.dapple
    uniforms.pinto = p.pinto
    uniforms.marking = markingIndex(a.marking).toDouble()
    return a
}

/** Coat material for a quality level; uniforms are shared (changing appearance = setting values). */
fun createCoatMaterial(
    level: GraphicsLevel,
    uniforms: CoatUniforms,
): Material {
    val low = level == GraphicsLevel.LOW
    val effect = CoatEffect(uniforms, low)
    return if (low) {
        LambertMaterial(color = 0xffffff, effect = effect)
    } else {
        StandardMaterial(color = 0xffffff, roughness = 0.6, metalness = 0.0, effect = effect)
    }
}

/** Vertex-colour material (tack, rider). */
fun createVertexColorMaterial(
    level: GraphicsLevel,
    roughness: Double = 0.6,
): Material =
    if (level == GraphicsLevel.LOW) {
        LambertMaterial(vertexColors = true)
    } else {
        StandardMaterial(vertexColors = true, roughness = roughness, metalness = 0.0)
    }
