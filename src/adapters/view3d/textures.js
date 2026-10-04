// Procedural surfaces (rule 2: nothing is loaded) and small geometry helpers.
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';

/** Deterministic random numbers (mulberry32). */
export function createRng(seed = 1) {
  let a = seed >>> 0;
  return function rng() {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Tileable value noise: grid with `period` cells, returns f(u, v) for u, v ∈ [0, 1). */
function createTileNoise(period, seed = 1) {
  const rng = createRng(seed);
  const grid = new Float32Array(period * period);
  for (let i = 0; i < grid.length; i += 1) grid[i] = rng();
  const at = (x, y) =>
    grid[(((y % period) + period) % period) * period + (((x % period) + period) % period)];
  return function noise(u, v) {
    const x = u * period;
    const y = v * period;
    const x0 = Math.floor(x);
    const y0 = Math.floor(y);
    const fx = x - x0;
    const fy = y - y0;
    const sx = fx * fx * (3 - 2 * fx);
    const sy = fy * fy * (3 - 2 * fy);
    const a = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * sx;
    const b = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * sx;
    return a + (b - a) * sy;
  };
}

/** Tileable fbm from several octaves, result roughly 0..1. */
function createTileFbm(basePeriod, octaves, seed = 1) {
  const layers = [];
  for (let o = 0; o < octaves; o += 1)
    layers.push(createTileNoise(basePeriod << o, seed + o * 101));
  return function fbm(u, v) {
    let sum = 0;
    let amp = 0.5;
    let norm = 0;
    for (const layer of layers) {
      sum += layer(u, v) * amp;
      norm += amp;
      amp *= 0.5;
    }
    return sum / norm;
  };
}

export function createCanvas(width, height) {
  if (typeof document !== 'undefined') {
    const c = document.createElement('canvas');
    c.width = width;
    c.height = height;
    return c;
  }
  return new OffscreenCanvas(width, height);
}

function canvasTexture(canvas, { repeat = true, srgb = true, anisotropy = 1 } = {}) {
  const tex = new THREE.CanvasTexture(canvas);
  if (repeat) tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
  tex.anisotropy = anisotropy;
  tex.generateMipmaps = true;
  tex.minFilter = THREE.LinearMipmapLinearFilter;
  tex.needsUpdate = true;
  return tex;
}

/** Normal map from a tileable height field. */
function normalCanvasFromHeight(height, size, strength) {
  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(size, size);
  const idx = (x, y) => ((y + size) % size) * size + ((x + size) % size);
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const dx = (height[idx(x + 1, y)] - height[idx(x - 1, y)]) * strength;
      const dy = (height[idx(x, y + 1)] - height[idx(x, y - 1)]) * strength;
      const len = Math.hypot(dx, dy, 1);
      const o = (y * size + x) * 4;
      img.data[o] = ((-dx / len) * 0.5 + 0.5) * 255;
      img.data[o + 1] = ((dy / len) * 0.5 + 0.5) * 255;
      img.data[o + 2] = ((1 / len) * 0.5 + 0.5) * 255;
      img.data[o + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return canvas;
}

/**
 * Arena sand: grainy, slightly wavy, with hoof prints and hints of harrow lines.
 * Returns { map, normalMap } (tileable, one tile ≈ 4 m).
 */
export function createSandTextures({ size = 512, seed = 7, normal = true } = {}) {
  const rng = createRng(seed);
  const fbmLow = createTileFbm(4, 4, seed);
  const fbmMid = createTileFbm(16, 3, seed + 9);
  const height = new Float32Array(size * size);
  const moist = new Float32Array(size * size);

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const u = x / size;
      const v = y / size;
      const i = y * size + x;
      // harrow lines: fine parallel grooves, slightly wavy
      const drag = Math.sin((v + fbmLow(u, v) * 0.04) * Math.PI * 2 * 48) * 0.5 + 0.5;
      height[i] = fbmLow(u, v) * 0.6 + fbmMid(u, v) * 0.35 + drag * 0.08 + rng() * 0.12;
      moist[i] = fbmLow(u + 0.37, v + 0.11);
    }
  }

  // hoof prints: oval dents with a rim, often in short trails
  const px = size / 4; // pixels per meter
  const prints = Math.round(size * 0.035);
  for (let p = 0; p < prints; p += 1) {
    const cx = rng() * size;
    const cy = rng() * size;
    const ang = rng() * Math.PI * 2;
    const steps = 1 + Math.floor(rng() * 3);
    for (let s = 0; s < steps; s += 1) {
      const sx = cx + Math.cos(ang) * s * 0.9 * px;
      const sy = cy + Math.sin(ang) * s * 0.9 * px;
      stampHoof(
        height,
        moist,
        size,
        sx,
        sy,
        ang,
        (0.045 + rng() * 0.015) * px,
        0.12 + rng() * 0.18,
      );
    }
  }

  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(size, size);
  const light = [208, 186, 148];
  const dark = [150, 122, 88];
  for (let i = 0; i < size * size; i += 1) {
    const h = height[i];
    const m = moist[i];
    let t = THREE.MathUtils.clamp(0.3 + (0.6 - h) * 0.35 + (m - 0.5) * 0.45, 0, 1);
    const speck = rng();
    if (speck > 0.985)
      t = Math.min(1, t + 0.35); // dark grains
    else if (speck < 0.02) t = Math.max(0, t - 0.3); // light grains
    const o = i * 4;
    img.data[o] = light[0] + (dark[0] - light[0]) * t;
    img.data[o + 1] = light[1] + (dark[1] - light[1]) * t;
    img.data[o + 2] = light[2] + (dark[2] - light[2]) * t;
    img.data[o + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);

  const map = canvasTexture(canvas);
  const normalMap = normal
    ? canvasTexture(normalCanvasFromHeight(height, size, 2.2), { srgb: false })
    : null;
  return { map, normalMap };
}

function stampHoof(height, moist, size, cx, cy, ang, r, depth) {
  const c = Math.cos(ang);
  const s = Math.sin(ang);
  const ext = Math.ceil(r * 1.8);
  for (let dy = -ext; dy <= ext; dy += 1) {
    for (let dx = -ext; dx <= ext; dx += 1) {
      // local coordinates: a along the stride, b across
      const a = (dx * c + dy * s) / (r * 1.1);
      const b = (-dx * s + dy * c) / r;
      const d = Math.hypot(a, b);
      if (d > 1.6) continue;
      const x = (((Math.round(cx + dx) % size) + size) % size) | 0;
      const y = (((Math.round(cy + dy) % size) + size) % size) | 0;
      const i = y * size + x;
      // dent with raised rim, deeper at the toe
      const bowl = d < 1 ? -(1 - d * d) * (0.8 + 0.4 * Math.max(0, a)) : 0;
      const rim = d >= 0.9 && d < 1.6 ? Math.sin(((d - 0.9) / 0.7) * Math.PI) * 0.35 : 0;
      height[i] += (bowl + rim) * depth;
      if (d < 1) moist[i] += (1 - d) * 0.25 * depth;
    }
  }
}

/** Meadow: green with blades and dry patches (tileable, one tile ≈ 6 m). */
export function createGrassTexture({ size = 512, seed = 21 } = {}) {
  const rng = createRng(seed);
  const fbm = createTileFbm(4, 4, seed);
  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(size, size);
  const a = [74, 112, 46];
  const b = [112, 138, 62];
  const dry = [150, 146, 88];
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const u = x / size;
      const v = y / size;
      const n = fbm(u, v);
      const d = THREE.MathUtils.smoothstep(fbm(u + 0.5, v + 0.25), 0.62, 0.78) * 0.6;
      const g = rng() * 0.25;
      const t = THREE.MathUtils.clamp(n + g - 0.15, 0, 1);
      const o = (y * size + x) * 4;
      for (let k = 0; k < 3; k += 1) {
        const base = a[k] + (b[k] - a[k]) * t;
        img.data[o + k] = base + (dry[k] - base) * d;
      }
      img.data[o + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  // blades as short strokes, wrapped at the edges (tileable)
  ctx.lineCap = 'round';
  const blades = size * 14;
  for (let i = 0; i < blades; i += 1) {
    const x = rng() * size;
    const y = rng() * size;
    const len = (3 + rng() * 7) * (size / 512);
    const ang = -Math.PI / 2 + (rng() - 0.5) * 1.6;
    const l = 30 + rng() * 30;
    ctx.strokeStyle = `hsla(${80 + rng() * 30}, ${35 + rng() * 25}%, ${l}%, ${0.35 + rng() * 0.4})`;
    ctx.lineWidth = (0.6 + rng() * 1.1) * (size / 512);
    for (const ox of [-size, 0, size]) {
      for (const oy of [-size, 0, size]) {
        if (x + ox < -12 || x + ox > size + 12 || y + oy < -12 || y + oy > size + 12) continue;
        ctx.beginPath();
        ctx.moveTo(x + ox, y + oy);
        ctx.lineTo(x + ox + Math.cos(ang) * len, y + oy + Math.sin(ang) * len);
        ctx.stroke();
      }
    }
  }
  return canvasTexture(canvas);
}

/** Soft clouds: atlas with 4 variants (2×2), alpha in the image. */
export function createCloudAtlas({ size = 512, seed = 5 } = {}) {
  const rng = createRng(seed);
  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext('2d');
  const cell = size / 2;
  for (let k = 0; k < 4; k += 1) {
    const ox = (k % 2) * cell;
    const oy = Math.floor(k / 2) * cell;
    const puffs = 14 + Math.floor(rng() * 10);
    for (let p = 0; p < puffs; p += 1) {
      const t = rng();
      const x = ox + cell * (0.18 + t * 0.64);
      const hump = Math.sin(t * Math.PI);
      const y = oy + cell * (0.62 - hump * 0.18 * rng() - rng() * 0.08);
      const r = cell * (0.08 + hump * 0.12 + rng() * 0.06);
      const g = ctx.createRadialGradient(x, y - r * 0.3, r * 0.1, x, y, r);
      const shade = 236 + Math.floor(rng() * 19);
      g.addColorStop(0, `rgba(${shade},${shade},${shade + 2 > 255 ? 255 : shade + 2},0.55)`);
      g.addColorStop(0.6, `rgba(${shade - 12},${shade - 10},${shade - 6},0.25)`);
      g.addColorStop(1, 'rgba(220,226,235,0)');
      ctx.fillStyle = g;
      ctx.fillRect(x - r, y - r, r * 2, r * 2);
    }
    // slightly grey underside
    const shadow = ctx.createLinearGradient(0, oy + cell * 0.5, 0, oy + cell * 0.75);
    shadow.addColorStop(0, 'rgba(150,160,175,0)');
    shadow.addColorStop(1, 'rgba(150,160,175,0.18)');
    ctx.globalCompositeOperation = 'source-atop';
    ctx.fillStyle = shadow;
    ctx.fillRect(ox, oy, cell, cell);
    ctx.globalCompositeOperation = 'source-over';
  }
  return canvasTexture(canvas, { repeat: false });
}

/** Soft rectangle mask (alpha) for ground markings. */
export function createSoftRectTexture({ size = 128, edge = 0.18 } = {}) {
  const canvas = createCanvas(size, size);
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(size, size);
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const u = Math.min(x, size - 1 - x) / size;
      const v = Math.min(y, size - 1 - y) / size;
      const a = THREE.MathUtils.smoothstep(Math.min(u, v), 0, edge);
      // faint cross stripes so the zone reads as an area
      const stripe = 0.85 + 0.15 * Math.sin((y / size) * Math.PI * 10);
      const o = (y * size + x) * 4;
      img.data[o] = img.data[o + 1] = img.data[o + 2] = 255;
      img.data[o + 3] = a * stripe * 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return canvasTexture(canvas, { repeat: false, srgb: false });
}

/**
 * Text atlas (system font) for number boards and signs.
 * items: [{ key, draw(ctx, w, h) }] → { texture, rects: Map key → {u0, v0, u1, v1} }
 */
export function createLabelAtlas(items, { cellW = 128, cellH = 128 } = {}) {
  const cols = Math.max(1, Math.min(items.length, Math.floor(2048 / cellW)));
  const rows = Math.max(1, Math.ceil(items.length / cols));
  const width = THREE.MathUtils.ceilPowerOfTwo(cols * cellW);
  const height = THREE.MathUtils.ceilPowerOfTwo(rows * cellH);
  const canvas = createCanvas(width, height);
  const ctx = canvas.getContext('2d');
  const rects = new Map();
  items.forEach((item, i) => {
    const x = (i % cols) * cellW;
    const y = Math.floor(i / cols) * cellH;
    ctx.save();
    ctx.translate(x, y);
    ctx.beginPath();
    ctx.rect(0, 0, cellW, cellH);
    ctx.clip();
    item.draw(ctx, cellW, cellH);
    ctx.restore();
    rects.set(item.key, {
      u0: (x + 1) / width,
      u1: (x + cellW - 1) / width,
      v0: 1 - (y + cellH - 1) / height,
      v1: 1 - (y + 1) / height,
    });
  });
  const texture = canvasTexture(canvas, { repeat: false });
  return { texture, rects, canvas };
}

export const SYSTEM_FONT =
  'system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif';

/** Shrinks the font until the text fits maxWidth. */
export function fitText(ctx, text, maxWidth, weight, sizePx) {
  let size = sizePx;
  ctx.font = `${weight} ${size}px ${SYSTEM_FONT}`;
  while (size > 8 && ctx.measureText(text).width > maxWidth) {
    size -= 2;
    ctx.font = `${weight} ${size}px ${SYSTEM_FONT}`;
  }
  return size;
}

// ---------------------------------------------------------------------------------------------
// Geometry helpers: color parts with vertex colors, transform and merge them.

const tmpColor = new THREE.Color();

/** Colors a geometry uniformly (hex/Color, sRGB) and makes it non-indexed. */
function paint(geometry, color, { jitter = 0, rng = Math.random } = {}) {
  const g = geometry.index ? geometry.toNonIndexed() : geometry;
  if (g !== geometry) geometry.dispose();
  if (!g.attributes.uv) {
    g.setAttribute(
      'uv',
      new THREE.Float32BufferAttribute(new Float32Array(g.attributes.position.count * 2), 2),
    );
  }
  const n = g.attributes.position.count;
  const colors = new Float32Array(n * 3);
  tmpColor.set(color);
  for (let i = 0; i < n; i += 1) {
    const f = jitter ? 1 + (rng() - 0.5) * jitter : 1;
    colors[i * 3] = tmpColor.r * f;
    colors[i * 3 + 1] = tmpColor.g * f;
    colors[i * 3 + 2] = tmpColor.b * f;
  }
  g.setAttribute('color', new THREE.BufferAttribute(colors, 3));
  return g;
}

/** Collects colored parts and merges them into one geometry. */
export function createGeometryBuilder() {
  const parts = [];
  const m = new THREE.Matrix4();
  const q = new THREE.Quaternion();
  const e = new THREE.Euler();
  const s = new THREE.Vector3();
  const p = new THREE.Vector3();
  return {
    /**
     * Adds a part: geometry (taken over), color, transform {x,y,z, rx,ry,rz, sx,sy,sz} or Matrix4.
     */
    add(geometry, color, transform = {}, opts) {
      const g = paint(geometry, color, opts);
      if (transform.isMatrix4) {
        g.applyMatrix4(transform);
      } else {
        const t = transform;
        p.set(t.x || 0, t.y || 0, t.z || 0);
        e.set(t.rx || 0, t.ry || 0, t.rz || 0);
        q.setFromEuler(e);
        s.set(t.sx ?? 1, t.sy ?? 1, t.sz ?? 1);
        m.compose(p, q, s);
        g.applyMatrix4(m);
      }
      parts.push(g);
      return g;
    },
    /** Adds an already colored geometry with a matrix. */
    addPainted(geometry, matrix) {
      const g = geometry.clone();
      if (matrix) g.applyMatrix4(matrix);
      parts.push(g);
      return g;
    },
    get count() {
      return parts.length;
    },
    build() {
      if (!parts.length) return new THREE.BufferGeometry();
      const merged = mergeGeometries(parts, false);
      for (const g of parts) g.dispose();
      parts.length = 0;
      merged.computeBoundingSphere();
      return merged;
    },
  };
}

/** Box with its bottom at y = 0 (handy for posts, walls). */
export function boxOnGround(w, h, d) {
  const g = new THREE.BoxGeometry(w, h, d);
  g.translate(0, h / 2, 0);
  return g;
}

/** Randomly displaces vertices (organic shapes for tree crowns, bushes). */
export function jitterVertices(geometry, amount, rng = Math.random) {
  const pos = geometry.attributes.position;
  // move equal positions equally so no holes appear
  const cache = new Map();
  for (let i = 0; i < pos.count; i += 1) {
    const key = `${pos.getX(i).toFixed(3)},${pos.getY(i).toFixed(3)},${pos.getZ(i).toFixed(3)}`;
    let d = cache.get(key);
    if (!d) {
      d = [(rng() - 0.5) * amount, (rng() - 0.5) * amount, (rng() - 0.5) * amount];
      cache.set(key, d);
    }
    pos.setXYZ(i, pos.getX(i) + d[0], pos.getY(i) + d[1], pos.getZ(i) + d[2]);
  }
  geometry.computeVertexNormals();
  return geometry;
}

/** Scales vertex colors by height (darker at the bottom, like ambient occlusion). */
export function shadeByHeight(geometry, minY, maxY, bottom = 0.55, top = 1.1) {
  const pos = geometry.attributes.position;
  const col = geometry.attributes.color;
  for (let i = 0; i < pos.count; i += 1) {
    const t = THREE.MathUtils.clamp((pos.getY(i) - minY) / (maxY - minY), 0, 1);
    const f = bottom + (top - bottom) * t;
    col.setXYZ(i, col.getX(i) * f, col.getY(i) * f, col.getZ(i) * f);
  }
  return geometry;
}
