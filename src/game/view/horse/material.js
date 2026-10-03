// Fell-Material: MeshStandardMaterial (low: MeshLambertMaterial) mit eingeschobenem Shader-Code.
// Die Farbe entsteht pro Pixel aus der Ruhepose-Position (aRest, 3D-Noise für Apfelung und
// Schecken-Platten, dunkle Beine), Material-Gewichten (aMat: Langhaar, Huf, Auge, Ohr innen) und
// Kopf-Koordinaten (aFace: s entlang Kopf, u lateral, Vorderseite) für Abzeichen und Nüstern.
// setAppearance ändert nur Uniforms – kein Neuaufbau, kein Shader-Neukompilieren.
import * as THREE from 'three';
import { MARKING_REGIONS, coatParams, markingIndex, normalizeAppearance } from './coats.js';

const NOISE = /* glsl */ `
vec3 hz_mod289(vec3 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 hz_mod289(vec4 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 hz_perm(vec4 x){return hz_mod289(((x*34.0)+1.0)*x);}
vec4 hz_tis(vec4 r){return 1.79284291400159-0.85373472095314*r;}
float hzNoise(vec3 v){
  const vec2 C=vec2(1.0/6.0,1.0/3.0);
  const vec4 D=vec4(0.0,0.5,1.0,2.0);
  vec3 i=floor(v+dot(v,C.yyy));
  vec3 x0=v-i+dot(i,C.xxx);
  vec3 g=step(x0.yzx,x0.xyz);
  vec3 l=1.0-g;
  vec3 i1=min(g.xyz,l.zxy);
  vec3 i2=max(g.xyz,l.zxy);
  vec3 x1=x0-i1+C.xxx;
  vec3 x2=x0-i2+C.yyy;
  vec3 x3=x0-D.yyy;
  i=hz_mod289(i);
  vec4 p=hz_perm(hz_perm(hz_perm(i.z+vec4(0.0,i1.z,i2.z,1.0))+i.y+vec4(0.0,i1.y,i2.y,1.0))+i.x+vec4(0.0,i1.x,i2.x,1.0));
  float n_=0.142857142857;
  vec3 ns=n_*D.wyz-D.xzx;
  vec4 j=p-49.0*floor(p*ns.z*ns.z);
  vec4 x_=floor(j*ns.z);
  vec4 y_=floor(j-7.0*x_);
  vec4 x=x_*ns.x+ns.yyyy;
  vec4 y=y_*ns.x+ns.yyyy;
  vec4 h=1.0-abs(x)-abs(y);
  vec4 b0=vec4(x.xy,y.xy);
  vec4 b1=vec4(x.zw,y.zw);
  vec4 s0=floor(b0)*2.0+1.0;
  vec4 s1=floor(b1)*2.0+1.0;
  vec4 sh=-step(h,vec4(0.0));
  vec4 a0=b0.xzyw+s0.xzyw*sh.xxyy;
  vec4 a1=b1.xzyw+s1.xzyw*sh.zzww;
  vec3 p0=vec3(a0.xy,h.x);
  vec3 p1=vec3(a0.zw,h.y);
  vec3 p2=vec3(a1.xy,h.z);
  vec3 p3=vec3(a1.zw,h.w);
  vec4 norm=hz_tis(vec4(dot(p0,p0),dot(p1,p1),dot(p2,p2),dot(p3,p3)));
  p0*=norm.x;p1*=norm.y;p2*=norm.z;p3*=norm.w;
  vec4 m=max(0.6-vec4(dot(x0,x0),dot(x1,x1),dot(x2,x2),dot(x3,x3)),0.0);
  m=m*m;
  return 42.0*dot(m*m,vec4(dot(p0,x0),dot(p1,x1),dot(p2,x2),dot(p3,x3)));
}
`;

const R = MARKING_REGIONS;
const f = (x) => x.toFixed(4);

const COAT_FN = /* glsl */ `
uniform vec3 uBase, uDark, uBelly, uHair, uPointColor, uMuzzle, uHoof, uWhite;
uniform float uPoints, uDapple, uPinto, uMarking;
varying vec3 vRest;
varying vec4 vMat;
varying vec3 vFace;
${NOISE}
float hzPinto(vec3 p, float head){
  float n = hzNoise(p * vec3(1.5, 1.25, 1.5) + vec3(3.1, 0.4, 1.7)) * 0.8
          + hzNoise(p * 4.2 + 7.0) * 0.16;
  float bias = (1.0 - smoothstep(0.32, 0.8, p.y)) * 0.95   // weiße Beine
             + smoothstep(1.45, 1.7, p.y) * 0.25 * (1.0 - step(0.5, head)) // über den Rücken
             - head * 1.2;                                // Kopf dunkel
  return smoothstep(0.1, 0.17, n + bias);
}
vec3 horseCoat(){
  vec3 p = vRest;
  float head = step(0.0, vFace.x);
  float legs = 1.0 - smoothstep(0.4, 0.56, p.y + 0.03 * hzNoise(p * 14.0));
#ifdef HORSE_LOW
  float fine = 0.0;
#else
  float fine = hzNoise(p * 3.0) * 0.6 + hzNoise(p * 11.0) * 0.4;
#endif
  vec3 col = uBase * (1.0 + 0.07 * fine);
  // Oberlinie dunkler, Bauch/Flanke unten heller
  float body = step(0.75, p.y);
  col = mix(col, uDark, smoothstep(1.4, 1.75, p.y) * 0.55 * body * (1.0 - head));
  col = mix(col, uBelly, (1.0 - smoothstep(0.88, 1.12, p.y)) * body * 0.5);
  // Apfelschimmel: helle Flecken in dunklerem Netz, feine Sprenkel
  if (uDapple > 0.5) {
    float d = hzNoise(p * 6.5);
    float d2 = hzNoise(p * 13.0 + 3.0);
    float net = smoothstep(0.0, 0.55, abs(d + 0.35 * d2));
    vec3 g = mix(uBase, uDark, (1.0 - net) * 0.5);
    g = mix(g, uBase * 1.04, smoothstep(1.2, 0.95, p.y) * 0.5);
#ifndef HORSE_LOW
    float sp = smoothstep(0.72, 0.85, hzNoise(p * 55.0));
    g = mix(g, uDark * 0.7, sp * 0.5);
#endif
    col = mix(g, uBase, head * 0.35);
  }
  // dunkle Beine unten (Brauner: schwarz bis Karpus/Sprunggelenk)
  col = mix(col, uPointColor, legs * uPoints);
  // dunkle Ohrränder beim Braunen
  col = mix(col, uPointColor, smoothstep(2.18, 2.26, p.y) * uPoints * (1.0 - vMat.x));
  // Langhaar (Mähne, Schopf, Schweif) mit Strähnen
  float strand = hzNoise(vec3(p.x * 70.0, p.y * 5.0, p.z * 70.0));
  col = mix(col, uHair * (1.0 + 0.22 * strand), vMat.x);
  // Kopf: Maul dunkler, Abzeichen, Nüstern, Maulspalte
  if (head > 0.5) {
    float s = vFace.x, u = vFace.y, fr = vFace.z;
    float muz = smoothstep(0.49, 0.585, s);
    col = mix(col, uMuzzle, muz * 0.75);
    // Augenbogen dunkler
    col *= 1.0 - 0.18 * smoothstep(0.04, 0.0, abs(s - 0.15)) * smoothstep(0.1, 0.6, -fr + 0.5) * step(-0.2, fr);
    float wob = hzNoise(vec3(s * 28.0, u * 28.0, 3.0));
    float mark = 0.0;
    if (uMarking > 0.5 && uMarking < 1.5) {
      float d = length(vec2((s - ${f(R.star.s)}) / ${f(R.star.rs)}, u / ${f(R.star.ru)}));
      mark = (1.0 - smoothstep(0.8, 1.0, d + 0.18 * wob)) * smoothstep(0.35, 0.6, fr);
    } else if (uMarking > 1.5 && uMarking < 2.5) {
      float hw = mix(${f(R.blaze.w0)}, ${f(R.blaze.w1)}, smoothstep(0.3, 0.58, s)) + 0.008 * smoothstep(0.16, 0.08, s);
      float along = smoothstep(${f(R.blaze.s0)}, ${f(R.blaze.s0 + 0.02)}, s) * (1.0 - smoothstep(${f(R.blaze.s1 - 0.01)}, ${f(R.blaze.s1 + 0.01)}, s));
      mark = (1.0 - smoothstep(hw * 0.82, hw, abs(u) + 0.006 * wob)) * along * smoothstep(0.15, 0.35, fr);
    } else if (uMarking > 2.5) {
      float d = length(vec2((s - ${f(R.snip.s)}) / ${f(R.snip.rs)}, u / ${f(R.snip.ru)}));
      mark = (1.0 - smoothstep(0.75, 1.0, d + 0.15 * wob)) * smoothstep(0.2, 0.45, fr);
    }
    vec3 white = mix(uWhite, vec3(0.86, 0.66, 0.62), muz * 0.6);
    col = mix(col, white, mark);
    // Nüstern (Komma-Form) und Maulspalte
    float nz = length(vec2((s - 0.566 + (abs(u) - 0.048) * 0.5) / 0.022, (abs(u) - 0.047) / 0.012));
    float nost = (1.0 - smoothstep(0.7, 1.0, nz)) * smoothstep(0.0, 0.25, fr) * (1.0 - smoothstep(0.75, 0.95, fr));
    col = mix(col, vec3(0.03, 0.02, 0.02), nost * 0.92);
    float mouth = smoothstep(0.03, 0.0, abs(fr + 0.4)) * smoothstep(0.54, 0.57, s);
    col = mix(col, col * 0.25, mouth * 0.85);
  }
  // Schecke: große weiße Platten (auch im Langhaar)
  if (uPinto > 0.5) {
    float wp = hzPinto(p, head);
    col = mix(col, uWhite * (1.0 + 0.05 * fine), wp * (1.0 - vMat.y));
  }
  // Huf (bei weißem Bein helles Horn), Auge, Ohr innen
  vec3 hoof = uHoof * (1.0 + 0.25 * hzNoise(vec3(p.x * 90.0, p.y * 6.0, p.z * 90.0)));
  if (uPinto > 0.5) hoof = mix(hoof, vec3(0.55, 0.48, 0.38), hzPinto(p + vec3(0.0, 0.12, 0.0), 0.0));
  col = mix(col, hoof, vMat.y);
  col = mix(col, vec3(0.025, 0.017, 0.012), vMat.z);
  col = mix(col, col * 0.35 + vec3(0.02), vMat.w);
  return col;
}
`;

function linColor(rgb) {
  return new THREE.Color().setRGB(rgb[0], rgb[1], rgb[2], THREE.SRGBColorSpace);
}

export function createCoatUniforms() {
  return {
    uBase: { value: new THREE.Color() },
    uDark: { value: new THREE.Color() },
    uBelly: { value: new THREE.Color() },
    uHair: { value: new THREE.Color() },
    uPointColor: { value: new THREE.Color() },
    uMuzzle: { value: new THREE.Color() },
    uHoof: { value: new THREE.Color() },
    uWhite: { value: new THREE.Color() },
    uPoints: { value: 0 },
    uDapple: { value: 0 },
    uPinto: { value: 0 },
    uMarking: { value: 1 },
  };
}

export function applyAppearance(uniforms, appearance) {
  const a = normalizeAppearance(appearance);
  const p = coatParams(a.coat);
  uniforms.uBase.value.copy(linColor(p.base));
  uniforms.uDark.value.copy(linColor(p.dark));
  uniforms.uBelly.value.copy(linColor(p.belly));
  uniforms.uHair.value.copy(linColor(p.hair));
  uniforms.uPointColor.value.copy(linColor(p.pointColor));
  uniforms.uMuzzle.value.copy(linColor(p.muzzle));
  uniforms.uHoof.value.copy(linColor(p.hoof));
  uniforms.uWhite.value.copy(linColor(p.white));
  uniforms.uPoints.value = p.points;
  uniforms.uDapple.value = p.dapple;
  uniforms.uPinto.value = p.pinto;
  uniforms.uMarking.value = markingIndex(a.marking);
  return a;
}

/** Fell-Material für eine Qualitätsstufe; uniforms werden geteilt (Aussehen ändern = Werte setzen). */
export function createCoatMaterial(level, uniforms) {
  const low = level === 'low';
  const mat = low
    ? new THREE.MeshLambertMaterial({ color: 0xffffff })
    : new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.6, metalness: 0 });
  if (low) mat.defines = { HORSE_LOW: '' };
  mat.onBeforeCompile = (shader) => {
    Object.assign(shader.uniforms, uniforms);
    shader.vertexShader = shader.vertexShader
      .replace(
        '#include <common>',
        `#include <common>
attribute vec3 aRest;
attribute vec4 aMat;
attribute vec3 aFace;
varying vec3 vRest;
varying vec4 vMat;
varying vec3 vFace;`,
      )
      .replace(
        '#include <begin_vertex>',
        `#include <begin_vertex>
vRest = aRest; vMat = aMat; vFace = aFace;`,
      );
    shader.fragmentShader = shader.fragmentShader
      .replace('#include <common>', `#include <common>\n${COAT_FN}`)
      .replace(
        '#include <color_fragment>',
        '#include <color_fragment>\n  diffuseColor.rgb *= horseCoat();',
      )
      .replace(
        '#include <roughnessmap_fragment>',
        `#include <roughnessmap_fragment>
  roughnessFactor = mix(roughnessFactor, 0.72, vMat.x);
  roughnessFactor = mix(roughnessFactor, 0.45, vMat.y);
  roughnessFactor = mix(roughnessFactor, 0.12, vMat.z);`,
      );
  };
  mat.customProgramCacheKey = () => `zhf-horse-coat-${low ? 'low' : 'std'}`;
  return mat;
}

/** Material mit Vertex-Farben (Sattelzeug, Reiter). */
export function createVertexColorMaterial(level, roughness = 0.6) {
  return level === 'low'
    ? new THREE.MeshLambertMaterial({ vertexColors: true })
    : new THREE.MeshStandardMaterial({ vertexColors: true, roughness, metalness: 0 });
}
