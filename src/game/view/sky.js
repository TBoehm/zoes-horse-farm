// Himmel: Verlaufskuppel mit Sonne (Shader) und ein paar weiche Wolken (ein Draw-Call).
import * as THREE from 'three';
import { createCloudAtlas, createRng } from './textures.js';

export const SKY_COLORS = Object.freeze({
  zenith: 0x3f7fcf,
  horizon: 0xcfe2ee,
  ground: 0xb8c7bf,
  sun: 0xfff1d6,
});

const vertexShader = /* glsl */ `
  varying vec3 vDir;
  void main() {
    vDir = normalize(position);
    vec4 p = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    gl_Position = p.xyww; // immer ganz hinten
  }
`;

const fragmentShader = /* glsl */ `
  uniform vec3 zenith;
  uniform vec3 horizon;
  uniform vec3 groundColor;
  uniform vec3 sunColor;
  uniform vec3 sunDir;
  varying vec3 vDir;
  void main() {
    vec3 d = normalize(vDir);
    float h = d.y;
    vec3 col = h > 0.0
      ? mix(horizon, zenith, pow(clamp(h, 0.0, 1.0), 0.55))
      : mix(horizon, groundColor, clamp(-h * 6.0, 0.0, 1.0));
    float s = max(dot(d, sunDir), 0.0);
    col += sunColor * (pow(s, 6.0) * 0.18 + pow(s, 64.0) * 0.35);
    col += sunColor * smoothstep(0.9993, 0.9997, s) * 6.0;
    gl_FragColor = vec4(col, 1.0);
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
  }
`;

/**
 * sunDirection: Richtung zur Sonne (normiert). Liefert { group, dome, clouds, sunDirection,
 * update(dt, camera) }. Die Gruppe folgt der Kamera, damit der Himmel unendlich weit wirkt.
 */
export function createSky({ sunDirection, radius = 420, cloudCount = 9, seed = 3 } = {}) {
  const group = new THREE.Group();
  group.name = 'sky';
  const sunDir = (sunDirection || new THREE.Vector3(-0.45, 0.62, -0.64)).clone().normalize();

  const domeMaterial = new THREE.ShaderMaterial({
    uniforms: {
      zenith: { value: new THREE.Color(SKY_COLORS.zenith) },
      horizon: { value: new THREE.Color(SKY_COLORS.horizon) },
      groundColor: { value: new THREE.Color(SKY_COLORS.ground) },
      sunColor: { value: new THREE.Color(SKY_COLORS.sun) },
      sunDir: { value: sunDir },
    },
    vertexShader,
    fragmentShader,
    side: THREE.BackSide,
    depthWrite: false,
    fog: false,
  });
  const dome = new THREE.Mesh(new THREE.SphereGeometry(radius, 32, 16), domeMaterial);
  dome.frustumCulled = false;
  dome.renderOrder = -10;
  group.add(dome);

  // Wolken: Quads auf einem Ring, zur Mitte gedreht, Atlas mit 4 Varianten
  const rng = createRng(seed);
  const positions = [];
  const uvs = [];
  const indices = [];
  const r = radius * 0.86;
  for (let i = 0; i < cloudCount; i += 1) {
    const az = (i / cloudCount) * Math.PI * 2 + rng() * 0.5;
    const el = THREE.MathUtils.degToRad(7 + rng() * 16);
    const w = 70 + rng() * 80;
    const h = w * 0.5;
    const center = new THREE.Vector3(
      Math.cos(el) * Math.sin(az) * r,
      Math.sin(el) * r,
      Math.cos(el) * Math.cos(az) * r,
    );
    const toCenter = center.clone().negate().normalize();
    const right = new THREE.Vector3().crossVectors(new THREE.Vector3(0, 1, 0), toCenter).normalize();
    const up = new THREE.Vector3().crossVectors(toCenter, right).normalize();
    const v = (k) => positions.push(k.x, k.y, k.z);
    const base = positions.length / 3;
    v(center.clone().addScaledVector(right, -w / 2).addScaledVector(up, -h / 2));
    v(center.clone().addScaledVector(right, w / 2).addScaledVector(up, -h / 2));
    v(center.clone().addScaledVector(right, w / 2).addScaledVector(up, h / 2));
    v(center.clone().addScaledVector(right, -w / 2).addScaledVector(up, h / 2));
    const k = Math.floor(rng() * 4);
    const u0 = (k % 2) * 0.5;
    const v0 = 0.5 - Math.floor(k / 2) * 0.5;
    uvs.push(u0, v0, u0 + 0.5, v0, u0 + 0.5, v0 + 0.5, u0, v0 + 0.5);
    indices.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  const cloudGeometry = new THREE.BufferGeometry();
  cloudGeometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  cloudGeometry.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
  cloudGeometry.setIndex(indices);
  const cloudMaterial = new THREE.MeshBasicMaterial({
    map: createCloudAtlas(),
    transparent: true,
    depthWrite: false,
    fog: false,
    side: THREE.DoubleSide,
  });
  const clouds = new THREE.Mesh(cloudGeometry, cloudMaterial);
  clouds.frustumCulled = false;
  clouds.renderOrder = -9;
  group.add(clouds);

  return {
    group,
    dome,
    clouds,
    sunDirection: sunDir,
    horizonColor: new THREE.Color(SKY_COLORS.horizon),
    update(dt, camera) {
      if (camera) group.position.copy(camera.position);
      clouds.rotation.y += dt * 0.002;
    },
    dispose() {
      dome.geometry.dispose();
      domeMaterial.dispose();
      cloudGeometry.dispose();
      cloudMaterial.map.dispose();
      cloudMaterial.dispose();
    },
  };
}
