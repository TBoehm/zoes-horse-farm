// Reine Mathe-Helfer (ohne three.js).

export const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);
export const lerp = (a, b, t) => a + (b - a) * t;
export function smoothstep(a, b, x) {
  const t = clamp((x - a) / (b - a), 0, 1);
  return t * t * (3 - 2 * t);
}

/**
 * Glatte Interpolation einer Tabelle [[k, v1, v2, ...], ...] (kubisch, Catmull-Rom-artige
 * Tangenten auf ungleichmäßigen Stützstellen). Gibt eine Funktion k → [v1, v2, ...] zurück.
 */
export function table(rows) {
  const n = rows.length;
  const m = rows[0].length - 1;
  return function (k) {
    const out = new Array(m);
    if (k <= rows[0][0]) {
      for (let j = 0; j < m; j++) out[j] = rows[0][j + 1];
      return out;
    }
    if (k >= rows[n - 1][0]) {
      for (let j = 0; j < m; j++) out[j] = rows[n - 1][j + 1];
      return out;
    }
    let i = 0;
    while (k > rows[i + 1][0]) i++;
    const r0 = rows[Math.max(0, i - 1)];
    const r1 = rows[i];
    const r2 = rows[i + 1];
    const r3 = rows[Math.min(n - 1, i + 2)];
    const h = r2[0] - r1[0];
    const t = (k - r1[0]) / h;
    const t2 = t * t;
    const t3 = t2 * t;
    const h00 = 2 * t3 - 3 * t2 + 1;
    const h10 = t3 - 2 * t2 + t;
    const h01 = -2 * t3 + 3 * t2;
    const h11 = t3 - t2;
    for (let j = 1; j <= m; j++) {
      const m1 = ((r2[j] - r0[j]) / (r2[0] - r0[0])) * h;
      const m2 = ((r3[j] - r1[j]) / (r3[0] - r1[0])) * h;
      out[j - 1] = h00 * r1[j] + h10 * m1 + h01 * r2[j] + h11 * m2;
    }
    return out;
  };
}

