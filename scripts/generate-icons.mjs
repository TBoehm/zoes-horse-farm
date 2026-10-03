// Generates raster images at build time from the self-made vector icon (rule 2).
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Resvg } from '@resvg/resvg-js';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const svg = readFileSync(resolve(root, 'public/icon.svg'), 'utf8');
const outDir = resolve(root, 'public/generated');
mkdirSync(outDir, { recursive: true });

function render(source, size) {
  return new Resvg(source, { fitTo: { mode: 'width', value: size } }).render().asPng();
}

// Maskable: shrink the content to the safe area, full-bleed background
const inner = svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');
const maskable = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">
  <rect width="512" height="512" fill="#6cbf4f"/>
  <g transform="translate(64 64) scale(0.75)">${inner}</g>
</svg>`;

const outputs = [
  ['icon-192.png', svg, 192],
  ['icon-512.png', svg, 512],
  ['apple-touch-icon.png', maskable, 180],
  ['icon-maskable-512.png', maskable, 512],
];
for (const [name, source, size] of outputs) {
  writeFileSync(resolve(outDir, name), render(source, size));
}
console.log(`Icons generated: ${outputs.map(([n]) => n).join(', ')}`);
