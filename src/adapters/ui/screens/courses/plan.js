// Draufsicht-Plan eines Parcours auf einem Canvas (Vorstart-Karte, Regeln 26, 28).
import { ARENA, POLE_LENGTH } from '../../../../domain/sim/tuning.js';

export function drawCoursePlan(canvas, course, { startLabel, finishLabel } = {}) {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const cssW = canvas.clientWidth || 520;
  const cssH = canvas.clientHeight || Math.round(cssW * (ARENA.width / ARENA.length));
  canvas.width = Math.round(cssW * dpr);
  canvas.height = Math.round(cssH * dpr);
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

  // Plan quer: Platz-Länge (z) nach rechts, Breite (x) nach oben
  const pad = 14;
  const scale = Math.min((cssW - pad * 2) / ARENA.length, (cssH - pad * 2) / ARENA.width);
  const ox = cssW / 2;
  const oy = cssH / 2;
  const P = (x, z) => [ox + z * scale, oy - x * scale];

  ctx.clearRect(0, 0, cssW, cssH);
  ctx.fillStyle = '#e8d3a5';
  ctx.strokeStyle = '#8a6a43';
  ctx.lineWidth = 3;
  const [ax, ay] = P(ARENA.width / 2, -ARENA.length / 2);
  roundRect(ctx, ax, ay, ARENA.length * scale, ARENA.width * scale, 10);
  ctx.fill();
  ctx.stroke();

  const line = (l, color, label) => {
    const [x1, y1] = P(l.a[0], l.a[1]);
    const [x2, y2] = P(l.b[0], l.b[1]);
    ctx.strokeStyle = color;
    ctx.lineWidth = 3;
    ctx.setLineDash([6, 5]);
    ctx.beginPath();
    ctx.moveTo(x1, y1);
    ctx.lineTo(x2, y2);
    ctx.stroke();
    ctx.setLineDash([]);
    if (label) {
      ctx.fillStyle = color;
      ctx.font = `800 ${Math.max(11, scale * 1.6)}px system-ui, sans-serif`;
      ctx.textAlign = 'center';
      ctx.fillText(label, (x1 + x2) / 2, Math.min(y1, y2) - 6);
    }
  };
  if (course.start) line(course.start, '#2f7d32', startLabel);
  if (course.finish) line(course.finish, '#b3261e', finishLabel);

  for (const obstacle of course.obstacles) {
    obstacle.elements.forEach((el) => drawElement(ctx, P, scale, el));
    const first = obstacle.elements[0];
    const last = obstacle.elements[obstacle.elements.length - 1];
    const cx = (first.x + last.x) / 2;
    const cz = (first.z + last.z) / 2;
    // Pfeil in Sprungrichtung
    const nx = Math.sin(first.rot);
    const nz = Math.cos(first.rot);
    const [s0x, s0y] = P(cx - nx * 3.2, cz - nz * 3.2);
    const [s1x, s1y] = P(cx + nx * 3.2, cz + nz * 3.2);
    arrow(ctx, s0x, s0y, s1x, s1y, '#1f3b8a');
    if (obstacle.number) {
      // Nummer seitlich (links in Sprungrichtung)
      const tx = Math.cos(first.rot);
      const tz = -Math.sin(first.rot);
      const off = POLE_LENGTH / 2 + 2.4;
      const [bx, by] = P(cx + tx * off, cz + tz * off);
      const r = Math.max(10, scale * 1.5);
      ctx.fillStyle = '#ffffff';
      ctx.strokeStyle = '#1f3b8a';
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.arc(bx, by, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = '#1f3b8a';
      ctx.font = `900 ${r * 1.2}px system-ui, sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(String(obstacle.number), bx, by + 1);
      ctx.textBaseline = 'alphabetic';
    }
  }
}

function drawElement(ctx, P, scale, el) {
  const tx = -Math.cos(el.rot);
  const tz = Math.sin(el.rot);
  const half = POLE_LENGTH / 2;
  const spread = el.spread || 0;
  const nx = Math.sin(el.rot);
  const nz = Math.cos(el.rot);
  const offsets = spread > 0 ? [-spread / 2, spread / 2] : [0];
  ctx.lineCap = 'round';
  for (const o of offsets) {
    const [x1, y1] = P(el.x + nx * o - tx * half, el.z + nz * o - tz * half);
    const [x2, y2] = P(el.x + nx * o + tx * half, el.z + nz * o + tz * half);
    ctx.strokeStyle = '#d23b2f';
    ctx.lineWidth = Math.max(4, scale * 0.45);
    ctx.beginPath();
    ctx.moveTo(x1, y1);
    ctx.lineTo(x2, y2);
    ctx.stroke();
  }
  // Fahnen: rot rechts (+t), weiß links (−t)
  const flag = (sign, color) => {
    const [fx, fy] = P(el.x + tx * (half + 0.4) * sign, el.z + tz * (half + 0.4) * sign);
    ctx.fillStyle = color;
    ctx.strokeStyle = '#333';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.arc(fx, fy, Math.max(3, scale * 0.4), 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
  };
  flag(1, '#e53935');
  flag(-1, '#ffffff');
}

function arrow(ctx, x0, y0, x1, y1, color) {
  const angle = Math.atan2(y1 - y0, x1 - x0);
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(x0, y0);
  ctx.lineTo(x1, y1);
  ctx.stroke();
  const s = 8;
  ctx.beginPath();
  ctx.moveTo(x1, y1);
  ctx.lineTo(x1 - s * Math.cos(angle - 0.45), y1 - s * Math.sin(angle - 0.45));
  ctx.lineTo(x1 - s * Math.cos(angle + 0.45), y1 - s * Math.sin(angle + 0.45));
  ctx.closePath();
  ctx.fill();
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}
