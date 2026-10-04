import { describe, it, expect } from 'vitest';
import * as THREE from 'three';
import {
  createWind,
  patchTreeWind,
  patchBushWind,
  patchTuftWind,
  patchBlossoms,
  patchBunting,
  patchWings,
} from './plant-shaders.js';

const SHADERS = {
  standard: THREE.ShaderLib.standard.vertexShader,
  lambert: THREE.ShaderLib.lambert.vertexShader,
};

/** Runs the patch of a material over a three.js vertex shader, as the renderer would. */
function compile(material, kind) {
  const shader = { uniforms: {}, vertexShader: SHADERS[kind] };
  material.onBeforeCompile(shader, {});
  return shader;
}

// patch function and a name from the code it adds
const PATCHES = {
  tree: [patchTreeWind, 'treeK'],
  bush: [patchBushWind, 'bushSway'],
  tuft: [patchTuftWind, 'tuftSway'],
  blossom: [patchBlossoms, 'stemBend'],
  bunting: [patchBunting, 'pennantFlap'],
  wings: [(m, wind) => patchWings(m, wind, { rate: 9, amplitude: 0.5, glide: 1 }), 'wingBeat'],
};

describe('createWind', () => {
  it('has a running time and a full strength', () => {
    const wind = createWind();
    expect(wind.time.value).toBe(0);
    expect(wind.strength.value).toBe(1);
  });
});

describe('the vertex shader patches', () => {
  it('find their hooks in the shaders of the standard and the Lambert material', () => {
    for (const [name, [patch, added]] of Object.entries(PATCHES)) {
      for (const kind of ['standard', 'lambert']) {
        const wind = createWind();
        const material = patch(
          kind === 'standard' ? new THREE.MeshStandardMaterial() : new THREE.MeshLambertMaterial(),
          wind,
        );
        const shader = compile(material, kind);
        expect(shader.uniforms.windTime, `${name} ${kind}`).toBe(wind.time);
        expect(shader.uniforms.windStrength, `${name} ${kind}`).toBe(wind.strength);
        expect(shader.vertexShader, `${name} ${kind}`).toContain('uniform float windTime;');
        expect(shader.vertexShader.match(/uniform float windTime;/g)).toHaveLength(1);
        // the hooks are the original lines, extended
        expect(shader.vertexShader).toContain('#include <begin_vertex>');
        expect(shader.vertexShader).toContain('#include <common>');
        expect(shader.vertexShader, `${name} ${kind}`).toContain(added);
        expect(SHADERS[kind]).not.toContain(added);
      }
    }
  });

  it('weight the sway by the height above the ground, so the base stays put', () => {
    const tree = compile(patchTreeWind(new THREE.MeshStandardMaterial(), createWind()), 'standard');
    expect(tree.vertexShader).toContain('position.y');
    expect(tree.vertexShader).toContain('windStrength');
    const tuft = compile(patchTuftWind(new THREE.MeshStandardMaterial(), createWind()), 'lambert');
    expect(tuft.vertexShader).toMatch(/\* position\.y \*/);
  });

  it('let petals take the colour of the instance and stem and heart keep theirs', () => {
    const shader = compile(
      patchBlossoms(new THREE.MeshStandardMaterial(), createWind()),
      'standard',
    );
    expect(shader.vertexShader).toContain('attribute float petal;');
    expect(shader.vertexShader).toMatch(
      /mix\(color\.xyz, color\.xyz \* instanceColor\.xyz, petal\)/,
    );
    // the line must come after three.js has set vColor
    const text = shader.vertexShader;
    expect(text.indexOf('#include <color_vertex>')).toBeLessThan(text.indexOf('petal);'));
  });

  it('give every kind of patch its own program key', () => {
    const keys = new Set();
    for (const [patch] of Object.values(PATCHES)) {
      keys.add(patch(new THREE.MeshStandardMaterial(), createWind()).customProgramCacheKey());
    }
    expect(keys.size).toBe(Object.keys(PATCHES).length);
    const a = patchWings(new THREE.MeshStandardMaterial(), createWind(), {
      rate: 9,
      amplitude: 0.5,
    });
    const b = patchWings(new THREE.MeshStandardMaterial(), createWind(), {
      rate: 22,
      amplitude: 1,
    });
    expect(a.customProgramCacheKey()).not.toBe(b.customProgramCacheKey());
  });

  it('wings beat with a phase per instance and glide only when asked to', () => {
    const wind = createWind();
    const birds = compile(
      patchWings(new THREE.MeshStandardMaterial(), wind, { rate: 9, amplitude: 0.5, glide: 1 }),
      'standard',
    );
    expect(birds.vertexShader).toContain('gl_InstanceID');
    expect(birds.vertexShader).toContain('abs(position.x)');
    expect(birds.vertexShader).toContain('1.000 > 0.0');
    const butterflies = compile(
      patchWings(new THREE.MeshStandardMaterial(), wind, { rate: 24, amplitude: 1.4 }),
      'standard',
    );
    expect(butterflies.vertexShader).toContain('0.000 > 0.0');
  });

  it('share the one wind of the world', () => {
    const wind = createWind();
    const a = compile(patchTreeWind(new THREE.MeshStandardMaterial(), wind), 'standard');
    const b = compile(patchBunting(new THREE.MeshLambertMaterial(), wind), 'lambert');
    expect(a.uniforms.windTime).toBe(b.uniforms.windTime);
    wind.time.value = 12.5;
    expect(b.uniforms.windTime.value).toBe(12.5);
  });
});
