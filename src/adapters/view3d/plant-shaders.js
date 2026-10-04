// Wind and small animations in the vertex shaders of the scenery (rule 3): trees, bushes, grass,
// flowers, bunting, birds and butterflies move without any work on the CPU. All patches share the
// same two uniforms (time and strength), so the whole scene breathes with one wind.
// Each patch extends a three.js material (standard or Lambert) through onBeforeCompile.

/** The wind of a world: `time` (s, advanced once per frame) and `strength` (0 = calm, 1 = full). */
export function createWind() {
  return { time: { value: 0 }, strength: { value: 1 } };
}

const COMMON = /* glsl */ `
  uniform float windTime;
  uniform float windStrength;
`;

/**
 * Hooks GLSL into a material. `key` names the program (three.js caches by it: patches with other
 * code must not share a key). `declarations` come after `#include <common>`, `afterBegin` after
 * the vertex position is set up (it may change `transformed`), `afterColor` after the vertex
 * colour is set (it may change `vColor`).
 */
function patchMaterial(
  material,
  wind,
  key,
  { declarations = '', afterBegin = '', afterColor = '' },
) {
  material.onBeforeCompile = (shader) => {
    shader.uniforms.windTime = wind.time;
    shader.uniforms.windStrength = wind.strength;
    let vertex = shader.vertexShader
      .replace('#include <common>', `#include <common>\n${COMMON}\n${declarations}`)
      .replace('#include <begin_vertex>', `#include <begin_vertex>\n${afterBegin}`);
    if (afterColor)
      vertex = vertex.replace('#include <color_vertex>', `#include <color_vertex>\n${afterColor}`);
    shader.vertexShader = vertex;
  };
  material.customProgramCacheKey = () => key;
  return material;
}

/**
 * Tree crowns: the crown leans and swings with slow gusts (weight grows with the height above the
 * ground, the trunk stays), the leaves shimmer a little. Instanced meshes only (the phase comes
 * from the position of the instance, so neighbours do not move in step).
 */
export function patchTreeWind(material, wind) {
  return patchMaterial(material, wind, 'wind-tree-v1', {
    afterBegin: /* glsl */ `
      #ifdef USE_INSTANCING
        vec2 wp = vec2(instanceMatrix[3].x, instanceMatrix[3].z);
        float crown = clamp((position.y - 1.6) / 5.6, 0.0, 1.0);
        crown *= crown;
        float gust = 0.65 + 0.35 * sin(windTime * 0.31 + wp.x * 0.021 + wp.y * 0.017);
        float swayA = sin(windTime * 1.05 + wp.x * 0.085 + wp.y * 0.06);
        float swayB = sin(windTime * 2.1 + wp.y * 0.13 + position.x * 0.9);
        float leaf = sin(windTime * 5.3 + position.x * 3.1 + position.z * 2.7 + position.y * 2.3);
        float treeK = crown * gust * windStrength;
        transformed.x += (swayA * 0.26 + swayB * 0.07) * treeK + leaf * 0.025 * crown * windStrength;
        transformed.z += (swayA * 0.12 + swayB * 0.05) * treeK - leaf * 0.02 * crown * windStrength;
      #endif`,
  });
}

/** Bushes: the whole blob sways a little, more at the top. */
export function patchBushWind(material, wind) {
  return patchMaterial(material, wind, 'wind-bush-v1', {
    afterBegin: /* glsl */ `
      #ifdef USE_INSTANCING
        vec2 wp = vec2(instanceMatrix[3].x, instanceMatrix[3].z);
        float bushTop = clamp(position.y / 1.2, 0.0, 1.0);
        bushTop *= bushTop;
        float bushSway = sin(windTime * 1.6 + wp.x * 0.3 + wp.y * 0.23);
        float bushLeaf = sin(windTime * 3.7 + position.x * 2.0 + position.z * 1.7);
        transformed.x += (bushSway * 0.055 + bushLeaf * 0.018) * bushTop * windStrength;
        transformed.z += (bushSway * 0.025 - bushLeaf * 0.012) * bushTop * windStrength;
      #endif`,
  });
}

/** Grass tufts: the tips sway, depending on the position of the instance. */
export function patchTuftWind(material, wind) {
  return patchMaterial(material, wind, 'wind-tuft-v2', {
    afterBegin: /* glsl */ `
      #ifdef USE_INSTANCING
        vec2 ip = vec2(instanceMatrix[3].x, instanceMatrix[3].z);
        float tuftSway = sin(windTime * 1.7 + ip.x * 0.35 + ip.y * 0.21) * 0.6
                   + sin(windTime * 3.1 + ip.y * 0.5) * 0.25;
        transformed.x += tuftSway * position.y * 0.18 * windStrength;
        transformed.z += tuftSway * position.y * 0.08 * windStrength;
      #endif`,
  });
}

/**
 * Flowers: the stem bends with the wind, and the geometry's `petal` attribute (1 on petals, 0 on
 * stem and heart) picks which vertices take the colour of the instance. Instanced meshes with an
 * instance colour only.
 */
export function patchBlossoms(material, wind) {
  return patchMaterial(material, wind, 'wind-blossom-v1', {
    declarations: 'attribute float petal;',
    afterBegin: /* glsl */ `
      #ifdef USE_INSTANCING
        vec2 fp = vec2(instanceMatrix[3].x, instanceMatrix[3].z);
        float stemBend = sin(windTime * 1.9 + fp.x * 0.5 + fp.y * 0.37) * 0.6
                   + sin(windTime * 3.3 + fp.y * 0.9) * 0.25;
        transformed.x += stemBend * position.y * 0.35 * windStrength;
        transformed.z += stemBend * position.y * 0.15 * windStrength;
      #endif`,
    afterColor: /* glsl */ `
      #ifdef USE_INSTANCING_COLOR
        vColor.xyz = mix(color.xyz, color.xyz * instanceColor.xyz, petal);
      #endif`,
  });
}

/**
 * Bunting: the pennants flutter across the string. The `aFlutter` attribute is the direction of
 * the movement times the weight (0 at the string, 1 at the tip). The mesh sits at the origin, so
 * the position is the phase.
 */
export function patchBunting(material, wind) {
  return patchMaterial(material, wind, 'wind-bunting-v1', {
    declarations: 'attribute vec3 aFlutter;',
    afterBegin: /* glsl */ `
      float pennantFlap = sin(windTime * 3.1 + position.x * 1.9 + position.z * 1.5) * 0.6
                 + sin(windTime * 5.7 + position.x * 3.3 - position.z * 2.1) * 0.25 + 0.3;
      transformed += aFlutter * pennantFlap * windStrength * 0.12;`,
  });
}

/**
 * Wings flap in the vertex shader (the geometry lies along X, the body in the middle). `rate` is
 * the beat in rad/s, `amplitude` the lift of the wing tips per metre from the body, `glide` > 0
 * lets the beat pause now and then (birds glide between bursts). Instanced meshes only: every
 * instance beats with its own phase.
 */
export function patchWings(material, wind, { rate, amplitude, glide = 0 }) {
  const f = (n) => n.toFixed(3);
  return patchMaterial(material, wind, `wind-wings-${f(rate)}-${f(amplitude)}-${f(glide)}`, {
    afterBegin: /* glsl */ `
      float wingPhase = float(gl_InstanceID) * 1.37;
      float wingBeat = sin(windTime * ${f(rate)} + wingPhase);
      float wingPause = ${f(glide)} > 0.0
        ? mix(1.0, smoothstep(-0.3, 0.5, sin(windTime * 0.6 + wingPhase * 2.0)), ${f(glide)})
        : 1.0;
      transformed.y += wingBeat * abs(position.x) * ${f(amplitude)} * wingPause;`,
  });
}
