// Rasterizes the config menu icons from the mock (mocks/config-menu/mock.js, the spec) into
// src/main/resources/assets/soul/textures/gui/icons/<name>.png: white strokes on transparent, tinted
// at draw time; the filled `flame` logo keeps its colors. 64 px with linear filtering (.mcmeta) so
// they stay smooth from UI scale 1 to 4. Needs rsvg-convert. Run: node scripts/config_icons.mjs
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const SIZE = 64;
const src = readFileSync('mocks/config-menu/mock.js', 'utf8');
const start = src.indexOf('const P = ');
const end = src.indexOf('/** The one "right now" marker');
if (start < 0 || end < 0) throw new Error('icon block not found in mock.js');
const block = src.slice(start, end).replace(/^const herePin[\s\S]*$/m, '');
const I = new Function(`${block}; return { I, PUSHPIN_D };`)().I;
// Text glyphs the mock uses that the MSDF atlases lack (↻ next tip, ↑ ↓ ↵ palette key hints), as icons.
const L = (d) => `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`;
Object.assign(I, {
  refresh: L('<path d="M12.6 6.2A5 5 0 1 0 13 9.5"/><path d="M13 3v3.4H9.6"/>'),
  arrowUp: L('<path d="M8 13V3M4 7l4-4 4 4"/>'),
  arrowDown: L('<path d="M8 3v10M4 9l4 4 4-4"/>'),
  enter: L('<path d="M13 3v5.5H3.5M6.5 5.5l-3 3 3 3"/>'),
});
// Inventory buttons editor (mocks/inventory-buttons-editor/ IC + TRASH): duplicate, delete, add, and the
// trash drop zone's can with its lid shut and lifted (drawn as two icons so the lid can move).
const E = (d) => `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`;
const LID = '<path d="M3 4.5h10M6.5 4.5V3h3v1.5"/>';
Object.assign(I, {
  copy: E('<rect x="5.5" y="5.5" width="8" height="8" rx="1.2"/><path d="M10.5 3.5V3a.5.5 0 0 0-.5-.5H3a.5.5 0 0 0-.5.5v7a.5.5 0 0 0 .5.5h.5"/>'),
  trash: E('<path d="M3 4.5h10M6.5 4.5V3h3v1.5M4.5 4.5l.6 8.5a1 1 0 0 0 1 1h3.8a1 1 0 0 0 1-1l.6-8.5"/>'),
  plus: E('<path d="M8 3v10M3 8h10"/>'),
  trashBody: E('<path d="M4.5 4.5l.6 8.5a1 1 0 0 0 1 1h3.8a1 1 0 0 0 1-1l.6-8.5M6.8 7v4.5M9.2 7v4.5"/>'),
  trashLid: E(LID),
  trashLidOpen: E(`<g transform="translate(0 -1.5) rotate(12 13 4.5)">${LID}</g>`),
});
// Auto updater (mocks/updater/ I): warning, download, done, failed, restart.
Object.assign(I, {
  warn: E('<path d="M8 2 14.5 13.5h-13z"/><path d="M8 6.5v3.5M8 12v.2"/>'),
  download: E('<path d="M8 2.5v8M4.5 7 8 10.5 11.5 7M3 13.5h10"/>'),
  okCircle: E('<circle cx="8" cy="8" r="6"/><path d="m5.3 8.2 1.9 1.9 3.6-4"/>'),
  xCircle: E('<circle cx="8" cy="8" r="6"/><path d="m6 6 4 4M10 6l-4 4"/>'),
  restart: E('<path d="M3 8a5 5 0 1 0 1.6-3.7"/><path d="M3 2.5v3h3"/>'),
});
// Leap menu location line (mocks/leap-menu/ ICON): seen now, last seen, party message, map room. Tinted at draw time.
const S16 = (d) => `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round">${d}</svg>`;
Object.assign(I, {
  locSeen: S16('<circle cx="8" cy="8" r="3.2" fill="currentColor" stroke="none"/><circle cx="8" cy="8" r="6" stroke-opacity=".45"/>'),
  locLast: S16('<circle cx="8" cy="8" r="3.2"/><circle cx="8" cy="8" r="6" stroke-opacity=".45"/>'),
  locChat: S16('<path d="M2.5 3.5h11v7.2H7.2L4.4 13v-2.3H2.5z"/>'),
  locMap: S16('<path d="M8 14.5s4.6-4.4 4.6-7.6A4.6 4.6 0 0 0 3.4 6.9c0 3.2 4.6 7.6 4.6 7.6z"/><circle cx="8" cy="6.9" r="1.6" fill="currentColor" stroke="none"/>'),
});

const out = 'src/main/resources/assets/soul/textures/gui/icons';
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
const tmp = join(tmpdir(), 'soul-icons');
mkdirSync(tmp, { recursive: true });
const names = [];
for (const [name, svg0] of Object.entries(I)) {
  if (!svg0.startsWith('<svg')) continue;
  let svg = svg0.replace('<svg ', `<svg xmlns="http://www.w3.org/2000/svg" width="${SIZE}" height="${SIZE}" `);
  if (name !== 'flame') svg = svg.replaceAll('currentColor', '#ffffff').replaceAll('#242424', '#000000');
  const file = name.replace(/[A-Z]/g, (c) => '_' + c.toLowerCase());
  writeFileSync(join(tmp, file + '.svg'), svg);
  execFileSync('rsvg-convert', ['-w', String(SIZE), '-h', String(SIZE), '-o', join(out, file + '.png'), join(tmp, file + '.svg')]);
  writeFileSync(join(out, file + '.png.mcmeta'), JSON.stringify({ texture: { blur: true, clamp: true } }) + '\n');
  names.push(file);
}
console.log(`${names.length} icons: ${names.join(' ')}`);
