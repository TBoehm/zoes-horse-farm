// Low-poly geometry of the meadow flowers and of the flower boxes at the jump standards. Both carry
// a `petal` attribute (1 on the blossoms that take the colour of the instance, 0 elsewhere) for the
// shader patch `patchBlossoms`. Procedural, nothing is loaded (rule 2).
import * as THREE from 'three';

const tmp = new THREE.Color();

/** Collects triangles with vertex colours and the `petal` weight; build() makes the geometry. */
function createBlossomBuilder() {
  const positions = [];
  const colors = [];
  const petals = [];
  const color = (hex, shade = 1) => {
    tmp.set(hex);
    return [tmp.r * shade, tmp.g * shade, tmp.b * shade];
  };
  return {
    color,
    /** One triangle: three points [x, y, z] and three colours [r, g, b]. */
    tri(a, b, c, ca, cb, cc, petal = 0) {
      positions.push(...a, ...b, ...c);
      colors.push(...ca, ...cb, ...cc);
      petals.push(petal, petal, petal);
    },
    /** A three.js geometry with a uniform colour, moved by `matrix`. */
    part(geometry, matrix, hex, petal = 0) {
      const g = geometry.index ? geometry.toNonIndexed() : geometry.clone();
      g.applyMatrix4(matrix);
      const p = g.attributes.position;
      const c = color(hex);
      for (let i = 0; i < p.count; i += 1) {
        positions.push(p.getX(i), p.getY(i), p.getZ(i));
        colors.push(...c);
        petals.push(petal);
      }
      g.dispose();
      geometry.dispose();
    },
    build({ upNormals = false } = {}) {
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
      g.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
      g.setAttribute('petal', new THREE.Float32BufferAttribute(petals, 1));
      if (upNormals) {
        // lit like the ground, so that a flower does not turn dark on its shaded side
        const normals = new Float32Array(positions.length);
        for (let i = 1; i < normals.length; i += 3) normals[i] = 1;
        g.setAttribute('normal', new THREE.BufferAttribute(normals, 3));
      } else {
        g.computeVertexNormals();
      }
      g.computeBoundingSphere();
      return g;
    },
  };
}

const FLOWER = Object.freeze({
  height: 0.6,
  petals: 5,
  petalRadius: 0.1,
  petalHalfWidth: 0.042,
  petalLift: 0.05,
  stem: 0x4f7a2f,
  heart: 0xe8b923,
});

/**
 * One meadow flower (11 triangles): a thin stem, one leaf, five petals bent up into a cup and a
 * small heart. Stands at the origin, about 0.6 m tall (taller than the grass); the petals are white-ish and take the
 * colour of the instance.
 */
export function buildFlowerGeometry() {
  const b = createBlossomBuilder();
  const { height: h } = FLOWER;
  const dark = b.color(FLOWER.stem, 0.7);
  const light = b.color(FLOWER.stem, 1.15);
  // stem: one quad (two triangles), double-sided in the material
  const w = 0.012;
  b.tri([-w, 0, 0], [w, 0, 0], [w * 0.6, h, 0], dark, dark, light);
  b.tri([-w, 0, 0], [w * 0.6, h, 0], [-w * 0.6, h, 0], dark, light, light);
  // leaf
  b.tri([0, 0.1, 0], [0.12, 0.25, 0.02], [0.02, 0.17, -0.02], dark, light, dark);
  // petals
  const base = [1, 1, 1].map((v) => v * 0.78);
  const tip = [1, 1, 1];
  for (let i = 0; i < FLOWER.petals; i += 1) {
    const a = (i / FLOWER.petals) * Math.PI * 2;
    const cx = Math.cos(a);
    const cz = Math.sin(a);
    const px = -cz * FLOWER.petalHalfWidth;
    const pz = cx * FLOWER.petalHalfWidth;
    b.tri(
      [cx * 0.015 - px, h, cz * 0.015 - pz],
      [cx * 0.015 + px, h, cz * 0.015 + pz],
      [cx * FLOWER.petalRadius, h + FLOWER.petalLift, cz * FLOWER.petalRadius],
      base,
      base,
      tip,
      1,
    );
  }
  // heart: three small triangles
  const heart = b.color(FLOWER.heart);
  const r = 0.028;
  for (let i = 0; i < 3; i += 1) {
    const a0 = (i / 3) * Math.PI * 2;
    const a1 = ((i + 1) / 3) * Math.PI * 2;
    b.tri(
      [0, h + 0.02, 0],
      [Math.cos(a0) * r, h + 0.01, Math.sin(a0) * r],
      [Math.cos(a1) * r, h + 0.01, Math.sin(a1) * r],
      heart,
      heart,
      heart,
    );
  }
  return b.build({ upNormals: true });
}

const PLANTER = Object.freeze({
  width: 0.27, // across the jump (x)
  height: 0.2,
  length: 1.0, // along the jump (z)
  wood: 0x6f4a2e,
  soil: 0x3b2c20,
  leaf: 0x3f7a35,
  accent: [0xf4f1e6, 0xf2cf2e],
});

/**
 * A flower box: wooden box with soil, leaves and a row of pompom blossoms (about 130 triangles).
 * The blossoms take the colour of the instance; a few white and yellow ones keep their own, so a
 * box looks like a mixed planting. Stands on the ground at the origin, long side along z.
 */
export function buildPlanterGeometry() {
  const b = createBlossomBuilder();
  const { width: w, height: h, length: l } = PLANTER;
  const at = (x, y, z, s = 1) =>
    new THREE.Matrix4().compose(
      new THREE.Vector3(x, y, z),
      new THREE.Quaternion(),
      new THREE.Vector3(s, s, s),
    );
  const box = new THREE.BoxGeometry(w, h, l);
  b.part(box, at(0, h / 2, 0), PLANTER.wood);
  const soil = new THREE.PlaneGeometry(w - 0.04, l - 0.04).rotateX(-Math.PI / 2);
  b.part(soil, at(0, h + 0.004, 0), PLANTER.soil);
  const slots = 10;
  for (let i = 0; i < slots; i += 1) {
    const z = -l / 2 + 0.08 + (i / (slots - 1)) * (l - 0.16);
    const x = (i % 2 === 0 ? -1 : 1) * 0.045;
    // leaves below, blossom on top
    b.part(new THREE.TetrahedronGeometry(0.07), at(x, h + 0.04, z), PLANTER.leaf);
    const accent = i % 4 === 2;
    b.part(
      new THREE.OctahedronGeometry(0.062),
      at(x, h + 0.115, z, 0.9 + (i % 3) * 0.12),
      accent ? PLANTER.accent[(i >> 2) % PLANTER.accent.length] : 0xffffff,
      accent ? 0 : 1,
    );
  }
  return b.build();
}
