// Zügel als dynamisches Band (zwei dünne Röhren, je Frame aktualisiert; ein Draw-Call).
import * as THREE from 'three';

const SEG = 10;
const SIDES = 4;
const RADIUS = 0.006;

export function createReins() {
  const verts = 2 * (SEG + 1) * SIDES;
  const pos = new Float32Array(verts * 3);
  const col = new Float32Array(verts * 3);
  const c = new THREE.Color().setRGB(0.16, 0.09, 0.05, THREE.SRGBColorSpace);
  for (let i = 0; i < verts; i++) col.set([c.r, c.g, c.b], i * 3);
  const index = [];
  for (let r = 0; r < 2; r++) {
    const base = r * (SEG + 1) * SIDES;
    for (let i = 0; i < SEG; i++) {
      for (let j = 0; j < SIDES; j++) {
        const a = base + i * SIDES + j;
        const b = base + i * SIDES + ((j + 1) % SIDES);
        const a1 = a + SIDES;
        const b1 = b + SIDES;
        index.push(a, a1, b, b, a1, b1);
      }
    }
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3).setUsage(THREE.DynamicDrawUsage));
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  geo.setIndex(index);
  const mesh = new THREE.Mesh(geo, new THREE.MeshBasicMaterial());
  mesh.name = 'horse-reins';
  mesh.frustumCulled = false;
  const p = new THREE.Vector3();
  const tan = new THREE.Vector3();
  const n1 = new THREE.Vector3();
  const n2 = new THREE.Vector3();
  const up = new THREE.Vector3(0, 1, 0);
  return {
    mesh,
    setMaterial(m) {
      mesh.material = m;
    },
    /** Zügel s (0 links, 1 rechts) von a nach b mit Durchhang sag (m). */
    setRein(s, a, b, sag) {
      const base = s * (SEG + 1) * SIDES;
      tan.subVectors(b, a).normalize();
      n1.crossVectors(tan, up);
      if (n1.lengthSq() < 1e-6) n1.set(1, 0, 0);
      n1.normalize();
      n2.crossVectors(n1, tan).normalize();
      for (let i = 0; i <= SEG; i++) {
        const t = i / SEG;
        p.lerpVectors(a, b, t);
        p.y -= sag * 4 * t * (1 - t);
        for (let j = 0; j < SIDES; j++) {
          const ang = (j / SIDES) * Math.PI * 2;
          const k = (base + i * SIDES + j) * 3;
          const w = RADIUS * 1.6;
          pos[k] = p.x + n1.x * Math.cos(ang) * w + n2.x * Math.sin(ang) * RADIUS;
          pos[k + 1] = p.y + n1.y * Math.cos(ang) * w + n2.y * Math.sin(ang) * RADIUS;
          pos[k + 2] = p.z + n1.z * Math.cos(ang) * w + n2.z * Math.sin(ang) * RADIUS;
        }
      }
    },
    commit() {
      geo.attributes.position.needsUpdate = true;
      geo.computeVertexNormals();
    },
    dispose() {
      geo.dispose();
    },
  };
}
