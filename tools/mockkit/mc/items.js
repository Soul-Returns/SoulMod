// Minecraft/SkyBlock items for mocks: the item model, 16×16 GUI-px pixel icons drawn as inline SVG
// (no images), vanilla's stack count, enchantment glint, and SkyBlock rarity lines.
//   const hype = item({ id: 'HYPERION', mcId: 'minecraft:iron_sword', name: '§6Hyperion', lore: ['§7Damage: §c+260', '', rarityLine('LEGENDARY', 'SWORD')], glint: true });
//   html += itemIcon(hype);
// Exports: item(spec), itemIcon(item, opts?), iconFor(mcId, hints?), ICON_KINDS, rarityOf(lore),
//   rarityLine(rarity, type?, opts?), filler(color?), closeItem(), backItem(to?), isFiller(item), DYE.

import { RARITY, stripCodes, esc } from '../util.js';
import { mcWidth, mcSpacing } from './font.js';

/**
 * Item model. `name` and `lore` are § strings exactly as SkyBlock sends them; `icon` is
 * `{ kind, color }` (see ICON_KINDS). `id` is the SkyBlock id, `mcId` the vanilla item id.
 * @typedef {{ id: string|null, mcId: string|null, name: string, lore: string[], rarity: string|null,
 *   count: number, icon: { kind: string, color: string }, glint: boolean }} Item
 */

/** DyeColor.textureDiffuseColor, for stained glass, dyed leather and colored blocks. */
export const DYE = {
  white: '#f9fffe', orange: '#f9801d', magenta: '#c74ebd', light_blue: '#3ab3da', yellow: '#fed83d', lime: '#80c71f',
  pink: '#f38baa', gray: '#474f52', light_gray: '#9d9d97', cyan: '#169c9c', purple: '#8932b8', blue: '#3c44aa',
  brown: '#835432', green: '#5e7c16', red: '#b02e26', black: '#1d1d21',
};
// Representative texture colors of vanilla materials (eyeballed from the item textures).
const MATERIAL = {
  diamond: '#5decf5', iron: '#d8d8d8', golden: '#fad64a', gold: '#fad64a', stone: '#8f8f8f', wooden: '#9c7a48',
  netherite: '#4d494d', leather: '#a0653a', chainmail: '#9a9a9a', emerald: '#17dd62', redstone: '#d01010', lapis: '#2a5bcf',
  copper: '#c66e4e', amethyst: '#9a5cc6', prismarine: '#63a79b', quartz: '#ece6df', coal: '#2b2b2b', blaze: '#f3b52c',
};
// SkyBlock gemstone colors (matching their § color in item names).
const GEM = {
  RUBY: '#ff5555', AMBER: '#ffaa00', SAPPHIRE: '#55ffff', JADE: '#55ff55', AMETHYST: '#aa00aa', TOPAZ: '#ffff55',
  JASPER: '#ff55ff', OPAL: '#ffffff', ONYX: '#555555', CITRINE: '#ffaa00', PERIDOT: '#00aa00', AQUAMARINE: '#5555ff', JADERALD: '#55ff55',
};

// ------------------------------------------------------------------ sprites
// 16 rows × 16 chars. Tinted letters: o outline, d dark, m mid (the item color), l light. Fixed:
// w white, g/G gray, k black, h/H wood, s/S skin, r/R barrier red, y/Y gold, e grass green.
const FIXED = { w: '#ffffff', g: '#b4b4b4', G: '#646464', k: '#1e1e1e', h: '#8f6a3a', H: '#5a3d1c', s: '#d8a27a', S: '#b07850', r: '#e0302a', R: '#8a1515', y: '#f5d23c', Y: '#b8860b', e: '#4f9a34' };
const _ = '................';
const pad = (rows, top = 0) => [...Array(top).fill(_), ...rows, ...Array(Math.max(0, 16 - top - rows.length)).fill(_)];
/** A sprite drawn from a function of the pixel center's distance to (cx, cy). */
const round = (fn, cx = 8, cy = 8) => Array.from({ length: 16 }, (_, y) => Array.from({ length: 16 }, (_, x) => fn(x, y, Math.hypot(x + 0.5 - cx, y + 0.5 - cy)) || '.').join(''));
const HANDLE = ['...ohHo.........', '..ohHo..........', '.ohHo...........', 'ohHo............', 'oHo.............', '.o..............'];

const SPRITES = {
  sword: ['................', '.............ooo', '............olmo', '...........olmdo', '..........olmdo.', '.........olmdo..', '........olmdo...', '.......olmdo....',
    '..oo..olmdo.....', '..odoolmdo......', '...odlmdo.......', '...oHddo........', '..oHhoddo.......', '.oHho.oo........', 'oHho............', 'ooo.............'],
  pickaxe: pad(['...oooooo.......', '..ollllmmo......', '...oooommdo.....', '.......ohmdo....', '......ohHomdo...', '.....ohHo.omdo..', '....ohHo...omdo.',
    '...ohHo.....omo.', '..ohHo......omo.', '.ohHo........oo.', 'ohHo............', 'oHo.............', '.o..............'], 1),
  axe: pad(['........ooo.....', '.......olmmo....', '......olmmmdo...', '......ommmmddo..', '.....ohoommddo..', '....ohHo.oddo...', '...ohHo...oo....',
    '..ohHo..........', '.ohHo...........', 'ohHo............', 'oHo.............', '.o..............'], 2),
  shovel: pad(['...........ooo..', '..........olmmo.', '.........olmmmo.', '.........ommmdo.', '........ohoddo..', '.......ohHooo...', '......ohHo......',
    '.....ohHo.......', '....ohHo........', ...HANDLE.slice(0, 3), '.oHo............', '..o.............'], 1),
  hoe: pad(['.......oooooo...', '......olmmmmdo..', '.......oooohdo..', '.........ohHo...', '........ohHo....', '.......ohHo.....', '......ohHo......',
    '.....ohHo.......', '....ohHo........', ...HANDLE.slice(0, 3), '.oHo............', '..o.............'], 1),
  drill: pad(['.............oo.', '............olo.', '...........olmo.', '..........olmdo.', '.........olmdo..', '.....ooooomdo...', '....oggggoddo...',
    '...oggGggoo.....', '..oglggggGo.....', '..ogggGgggo.....', '..oGggggGo......', '..oGGgggo.......', '...oGGGo........', '....ooo.........'], 1),
  bow: pad(['...ooooo........', '..wohhhhoo......', '...w.oooHhoo....', '....w....oHho...', '.....w....oHho..', '......w....oHo..', '.......w...oHho.',
    '........w...oHo.', '.........w..oHo.', '..........w.oHo.', '...........woHo.', '............oHo.', '............oho.', '.............o..'], 1),
  rod: pad(['...........oo...', '..........ohoow.', '.........ohHo.w.', '........ohHo..w.', '.......ohHo...w.', '......ohHo....w.', '.....ohHo.....w.',
    '....ohHo......w.', '...ohHo.......w.', '..ohHo.......gwg', '.ohHo.........g.', '.oHo............', '..o.............'], 1),
  stick: pad(['............oo..', '...........omdo.', '..........omdo..', '.........omdo...', '........omdo....', '.......omdo.....', '......omdo......',
    '.....omdo.......', '....omdo........', '...omdo.........', '..omdo..........', '.omdo...........', '.odo............', '..o.............'], 1),
  helmet: pad(['....oooooooo....', '...olllllmmdo...', '..olmmmmmmmmdo..', '..olmmmmmmmmdo..', '..ommoooooommo..', '..omdo....odmo..', '..omdo....odmo..', '..oooo....oooo..'], 4),
  chestplate: pad(['.oooo......oooo.', '.olmdoooooodmdo.', '.olmmmmmmmmmmdo.', '.ooommmmmmmmooo.', '...olmmmmmmdo...', '...olmmmmmmdo...', '...olmmmmmmdo...',
    '...olmmmmmmdo...', '...olmmmmmmdo...', '...olmmmmmmdo...', '...oddddddddo...', '...oooooooooo...'], 2),
  leggings: pad(['..oooooooooooo..', '..olmmmmmmmmdo..', '..olmmmmmmmmdo..', '..olmmmoommmdo..', '..olmmo..olmdo..', '..olmdo..olmdo..', '..olmdo..olmdo..',
    '..olmdo..olmdo..', '..olmdo..olmdo..', '..olmdo..olmdo..', '..odddo..odddo..', '..ooooo..ooooo..'], 2),
  boots: pad(['..oooo....oooo..', '..olmo....olmo..', '..olmo....olmo..', '..olmo....olmo..', '.olmmo...olmmo..', 'olmmdo..olmmdo..', 'oooooo..oooooo..'], 5),
  head: pad(['..oooooooooooo..', '..ommmmmmmmmmo..', '..ommmmmmmmmmo..', '..omsmmmmmmsmo..', '..osssssssssso..', '..oswkssssskwo..', '..ossssSSsssso..',
    '..ossSSSSSSsso..', '..ossSssssSsso..', '..oddddddddddo..', '..oooooooooooo..'], 3),
  gem: pad(['.....oooooo.....', '....olllmmdo....', '...olwlmmmddo...', '..oooooooooooo..', '..olllmmmmmddo..', '...olmmmmmddo...', '....olmmmddo....',
    '.....olmddo.....', '......omdo......', '.......oo.......'], 3),
  ingot: pad(['.....oooooooo...', '....olwllllldo..', '...olllllllddo..', '..oooooooooddo..', '..ommmmmmmmodo..', '..ommmmmmmmdo...', '..oooooooooo....'], 5),
  dust: pad(['.....l....m.....', '........o.......', '......ollmo.....', '....ollllmmdo...', '...olllmmmmddo..', '..ollmmmmmmmddo.', '.oooooooooooooo.'], 7),
  book: pad(['..oooooooooooo..', '..odmmmmmmmmmo..', '..odmllllllmmo..', ...Array(7).fill('..odmmmmmmmmmo..'), '..odwwwwwwwwwo..', '..oooooooooooo..'], 2),
  potion: pad(['......oooo......', '......oHHo......', '......owwo......', '......owwo......', '....oowwwwoo....', '...owwwwwwwwo...', '..owmmmmmmmmwo..',
    '..omlmmmmmmmdo..', '..omlmmmmmmmdo..', '..ommmmmmmmmdo..', '..ommmmmmmmddo..', '...oddddddddo...', '....oooooooo....'], 1),
  egg: pad(['......oooo......', '.....olllmo.....', '....olwlmmmo....', '...olllmmdmmo...', '...ollmmmmmmo...', '..olmmmdmmmmdo..', '..ommmmmmmdmdo..',
    '..ommdmmmmmmdo..', '..ommmmmmmmddo..', '...ommmmmmddo...', '....oddddddo....', '.....oooooo.....'], 2),
  pane: ['llllllllllllllll', 'lmmmmmmmmmmmmmml', 'lmlmmmmmmmmmmmml', 'lmmlmmmmmmmmmmml', 'lmmmlmmmmmmmmmml', 'lmmmmmmmmmmmmmml', 'lmmmmmmmmmmmmmml', 'lmmmmmmmmmmmmmml',
    'lmmmmmmmmmmmmmml', 'lmmmmmmmmmmmmmml', 'lmmmmmmmmmmmmmml', 'lmmmmmmmmmmlmmml', 'lmmmmmmmmmmmlmml', 'lmmmmmmmmmmmmlml', 'lmmmmmmmmmmmmmml', 'llllllllllllllll'],
  barrier: round((x, y, d) => (d <= 7.2 && d >= 5.2 ? (d > 6.6 ? 'R' : 'r') : d < 5.2 && Math.abs(x - y) <= 1 ? 'r' : '')),
  arrow: pad(['...........ooo..', '..........oggo..', '.........ogGgo..', '........ohoGo...', '.......oho.o....', '......oho.......', '.....oho........',
    '....oho.........', '..woho..........', '.wwwo...........', '..ww............', '..w.............'], 1),
  chest: pad(['.oooooooooooooo.', '.olmmmmmmmmmmdo.', '.ommmmmmmmmmmdo.', '.ommmmmmmmmmmdo.', '.ooooooggoooooo.', '.ommmmmgkmmmmdo.', '.ommmmmmmmmmmdo.',
    '.ommmmmmmmmmmdo.', '.ommmmmmmmmmmdo.', '.oddddddddddddo.', '.oooooooooooooo.'], 2),
  coins: pad(['....oooooooo....', '...oyyyyyyyYo...', '...oyYyyyyyYo...', '...oyyyyyyyYo...', '...oYYYYYYYYo...', '...oyyyyyyyYo...', '...oYYYYYYYYo...',
    '...oyyyyyyyYo...', '...oYYYYYYYYo...', '....oooooooo....'], 3),
  star: pad(['.......ll.......', '.......lw.......', '......lwwl......', '.l...lwwwwl...l.', '..lllwwwwwwlll..', '...lwwwwwwwwl...', '....lwwwwwwl....',
    '...lwwwwwwwwl...', '..lllwwwwwwlll..', '.l...lwwwwl...l.', '......lwwl......', '.......wl.......', '.......ll.......'], 1),
  paper: pad(['...ooooooooo....', '...owwwwwwwlo...', '...owgggggwwlo..', '...owwwwwwwwwo..', '...owggggggwwo..', '...owwwwwwwwwo..', '...owgggggwwwo..',
    '...owwwwwwwwwo..', '...owggggggwwo..', '...owwwwwwwwwo..', '...owwwwwwwwwo..', '...ooooooooooo..'], 2),
  bone: pad(['............oo..', '...........owwo.', '..........owwwo.', '.........owwoo..', '........owwo....', '.......owwo.....', '......owwo......',
    '.....owwo.......', '....owwo........', '..oowwo.........', '.owwwo..........', '.owwo...........', '..oo............'], 1),
  feather: pad(['.............ol.', '...........ollo.', '..........olllo.', '.........olllo..', '........olllo...', '.......olllo....', '......olllo.....',
    '.....olllo......', '....olllo.......', '...ollo.........', '..oho...........', '.oh.............', 'o...............'], 1),
  torch: pad(['.......mm.......', '......mllm......', '......mllm......', ...Array(8).fill('.......hH.......'), '.......oo.......'], 2),
  tag: pad(['....oooooooooo..', '...ommmmmmmmmmo.', '..omkmmmmmmmmmo.', '..ommmmmmmmmmmo.', '...ommmmmmmmmmo.', '....oooooooooo..'], 5),
  block: pad(['.oooooooooooooo.', '.ollllllllllldo.', ...Array(10).fill('.olmmmmmmmmmmdo.'), '.oldddddddddddo.', '.oooooooooooooo.'], 1),
  painting: pad(['.oooooooooooooo.', '.ohhhhhhhhhhhho.', ...Array(5).fill('.ohllllllllllho.'), ...Array(4).fill('.oheeeeeeeeeeho.'), '.ohhhhhhhhhhhho.', '.oooooooooooooo.'], 1),
  key: pad(['...ooo..........', '..ommmo.........', '.omooomo........', '.omo.omoooooooo.', '.omooommmmmmmmo.', '..ommmooomomoo..', '...ooo...o.o....'], 5),
  fish: pad(['.....ooooo......', '...oommmmmo..oo.', '..ommlmmmmmooddo', '.omkmmmmmmmmddo.', '.ommmmmmmmmmddo.', '..ommmmmmmooddo.', '...oommmmmo..oo.', '.....ooooo......'], 4),
  pearl: round((x, y, d) => (d > 6.6 ? '' : d > 5.6 ? 'o' : x < 7 && y < 7 && d > 2.5 && d < 4.2 ? 'l' : x + y > 17 ? 'd' : 'm')),
  orb: round((x, y, d) => (d > 6.6 ? '' : d > 5.6 ? 'o' : x === 5 && y === 5 ? 'w' : x + y < 11 ? 'l' : x + y > 18 ? 'd' : 'm')),
  cookie: round((x, y, d) => (d > 6.6 ? '' : d > 5.6 ? 'H' : [[5, 5], [9, 4], [10, 9], [6, 10], [8, 7]].some(([a, b]) => a === x && b === y) ? 'k' : 'h')),
  clock: round((x, y, d) => (d > 6.6 ? '' : d > 5.4 ? (x + y > 16 ? 'Y' : 'y') : (x === 7 && y >= 4 && y <= 7) || (y === 7 && x >= 7 && x <= 10) ? 'k' : 'w')),
  compass: round((x, y, d) => (d > 6.6 ? '' : d > 5.4 ? 'G' : (x === 8 && y >= 3 && y <= 7) || (x === 9 && y >= 4 && y <= 6) ? 'r' : (x === 7 && y >= 8 && y <= 12) ? 'w' : 'g')),
};
for (const [k, rows] of Object.entries(SPRITES)) {
  if (rows.length !== 16 || rows.some((r) => r.length !== 16)) throw new Error(`mc/items.js: sprite ${k} is not 16×16`);
}
SPRITES.emerald = SPRITES.gem;

/** Kinds itemIcon draws, with their default color. */
const KIND_COLOR = {
  sword: MATERIAL.iron, pickaxe: MATERIAL.iron, axe: MATERIAL.iron, shovel: MATERIAL.iron, hoe: MATERIAL.iron, drill: '#e8b23a', bow: '#8f6a3a',
  rod: '#8f6a3a', stick: '#8f6a3a', helmet: MATERIAL.iron, chestplate: MATERIAL.iron, leggings: MATERIAL.iron, boots: MATERIAL.iron, head: '#4a3420',
  gem: MATERIAL.diamond, emerald: MATERIAL.emerald, ingot: MATERIAL.iron, dust: MATERIAL.redstone, book: '#7a3b22', potion: '#3b5bdb', egg: '#e8d9a8',
  pane: DYE.black, barrier: '#e0302a', arrow: '#8f6a3a', chest: '#a0743c', coins: MATERIAL.gold, star: '#d8d0f0', paper: '#f0f0f0', bone: '#eeeeee',
  feather: '#f2f2f2', torch: '#ffb02e', tag: '#c9a46b', block: MATERIAL.stone, painting: '#7ec0ee', key: MATERIAL.gold, fish: '#c47a5a',
  pearl: '#1d8f7e', orb: '#aaaaaa', cookie: '#b8834a', clock: MATERIAL.gold, compass: '#8b8b8b',
};
export const ICON_KINDS = Object.keys(KIND_COLOR);

// ------------------------------------------------------------------ color + svg
const rgb = (hex) => { const v = parseInt(hex.slice(1), 16); return [(v >> 16) & 255, (v >> 8) & 255, v & 255]; };
const hex = (c) => '#' + c.map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
const shade = (h, f) => hex(rgb(h).map((v) => v * f));
const tint = (h, f) => hex(rgb(h).map((v) => v + (255 - v) * f));
function palette(color) {
  return { ...FIXED, o: shade(color, 0.3), d: shade(color, 0.65), m: color, l: tint(color, 0.45) };
}
const normHex = (c) => (/^#[0-9a-f]{3}$/i.test(c) ? '#' + [...c.slice(1)].map((x) => x + x).join('') : c.toLowerCase());

const svgCache = new Map();
/** One path per palette letter, horizontal runs merged. */
function spriteSvg(kind, color, glint) {
  const key = `${kind}|${color}|${glint}`;
  let s = svgCache.get(key);
  if (s) return s;
  const rows = SPRITES[kind] ?? SPRITES.block, pal = palette(color), paths = {};
  let all = '';
  rows.forEach((row, y) => {
    for (let x = 0; x < 16;) {
      const c = row[x];
      let n = 1;
      while (x + n < 16 && row[x + n] === c) n++;
      if (c !== '.') {
        const r = `M${x} ${y}h${n}v1h-${n}z`;
        paths[c] = (paths[c] ?? '') + r;
        all += r;
      }
      x += n;
    }
  });
  s = `<svg class="mc-ico" viewBox="0 0 16 16" width="16" height="16" shape-rendering="crispEdges">`
    + Object.entries(paths).map(([c, d]) => `<path fill="${pal[c] ?? '#f0f'}" d="${d}"/>`).join('')
    + (glint ? `<defs><linearGradient id="mc-glint" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="16" y2="8" spreadMethod="repeat">`
      + `<stop offset="0" stop-color="#8040cc" stop-opacity="0"/><stop offset=".5" stop-color="#a060ff" stop-opacity=".75"/><stop offset="1" stop-color="#8040cc" stop-opacity="0"/>`
      + `<animateTransform attributeName="gradientTransform" type="translate" from="-16 0" to="16 0" dur="3s" repeatCount="indefinite"/></linearGradient></defs>`
      + `<path class="mc-glint" fill="url(#mc-glint)" d="${all}"/>` : '')
    + '</svg>';
  svgCache.set(key, s);
  return s;
}

// ------------------------------------------------------------------ model
const WORDS = [
  [/drill/, 'drill'], [/pickaxe|pickonimbus/, 'pickaxe'], [/_axe$|^axe$|treecapitator|jungle_axe/, 'axe'], [/shovel|spade/, 'shovel'], [/_hoe$/, 'hoe'],
  [/sword|blade|katana|cleaver|scythe|hyperion|valkyrie|astraea|scylla|midas|aspect_of|livid_dagger|giants_sword|wither_cloak/, 'sword'],
  [/bow$|terminator|juju|bow_/, 'bow'], [/fishing_rod|_rod$|rod_of|grappling_hook/, 'rod'], [/blaze_rod|stick|wand/, 'stick'],
  [/helmet|_hat$|_cap$|crown/, 'helmet'], [/chestplate|tunic/, 'chestplate'], [/leggings|pants/, 'leggings'], [/boots|slippers|galoshes/, 'boots'],
  [/player_head|skull|_head$/, 'head'], [/glass_pane/, 'pane'], [/barrier/, 'barrier'], [/^arrow$|_arrow$/, 'arrow'],
  [/ender_chest|chest|barrel|shulker|backpack|sack/, 'chest'], [/emerald/, 'emerald'], [/gemstone|diamond$|gem$|crystal|jaderald|^(rough|flawed|fine|flawless|perfect)_/, 'gem'],
  [/ingot|bar$|alloy/, 'ingot'], [/dust|powder|redstone$|gunpowder|sugar$|glowstone/, 'dust'], [/nugget|coin/, 'coins'], [/enchanted_book|book/, 'book'],
  [/potion|bottle/, 'potion'], [/egg$|_egg/, 'egg'], [/nether_star|star$/, 'star'], [/paper|map$|_map/, 'paper'], [/bone/, 'bone'], [/feather/, 'feather'],
  [/torch/, 'torch'], [/name_tag/, 'tag'], [/painting/, 'painting'], [/tripwire_hook|_key$|^key/, 'key'], [/cod|salmon|pufferfish|tropical_fish|fish/, 'fish'],
  [/ender_pearl|ender_eye|pearl/, 'pearl'], [/cookie/, 'cookie'], [/clock|watch/, 'clock'], [/compass/, 'compass'],
];
/** Specific color named by an id/name word (dye, gemstone, material), or null. */
function materialColor(word) {
  const tokens = word.split('_');
  for (const [dye, c] of Object.entries(DYE)) if (word.startsWith(dye + '_')) return c;
  for (const [g, c] of Object.entries(GEM)) if (tokens.includes(g.toLowerCase())) return c;
  for (const [m, c] of Object.entries(MATERIAL)) if (tokens.includes(m)) return c;
  return null;
}
/** A stable mid-tone color from a string, so unknown blocks and heads differ from each other. */
function hashColor(s) {
  let h = 0;
  for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return hex([64 + (h & 127), 64 + ((h >> 7) & 127), 64 + ((h >> 14) & 127)]);
}
// Generic shapes SkyBlock reuses for many items: tinted by rarity when nothing names a color.
const RARITY_TINTED = new Set(['orb', 'gem', 'egg', 'book', 'potion', 'dust', 'ingot']);

/**
 * Icon for a vanilla item id and/or SkyBlock id/name: `{ kind, color }`. The SkyBlock id is tried
 * first (drills are diamond pickaxes, item_model items are paper), then the vanilla id, then the name.
 */
export function iconFor(mcId, { sbId = null, name = '', rarity = null } = {}) {
  const vanilla = String(mcId ?? '').replace(/^minecraft:/, '');
  const named = stripCodes(name).trim().toLowerCase().replace(/[^a-z0-9]+/g, '_');
  const sb = sbId ? sbId.toLowerCase() : '';
  let kind = null;
  for (const w of [sb, vanilla !== 'paper' ? vanilla : '', named]) {
    const hit = w && WORDS.find(([re]) => re.test(w));
    if (hit) { kind = hit[1]; break; }
  }
  kind ??= vanilla === 'paper' && !sb ? 'paper' : sb || vanilla === 'paper' ? 'orb' : 'block';
  const color = [vanilla, sb, named].map((w) => w && materialColor(w)).find(Boolean)
    ?? (rarity && RARITY[rarity] && RARITY_TINTED.has(kind) ? RARITY[rarity].color : null)
    ?? (kind === 'block' && vanilla ? hashColor(vanilla) : kind === 'head' && named ? hashColor(named) : KIND_COLOR[kind]);
  return { kind, color };
}

/** The rarity SkyBlock prints on an item's last lore line ("§6§lLEGENDARY SWORD"), or null. */
export function rarityOf(lore = []) {
  for (let i = lore.length - 1; i >= 0; i--) {
    const t = stripCodes(lore[i]).trim();
    if (!t) continue;
    const m = t.match(/\b(VERY SPECIAL|UNCOMMON|COMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|ULTIMATE|ADMIN)\b/);
    return m ? m[1] : null;
  }
  return null;
}

/**
 * SkyBlock's rarity line: `rarityLine('LEGENDARY', 'SWORD')` → `§6§lLEGENDARY SWORD`; with
 * `{ recomb: true }` it gets the Recombobulator's obfuscated markers: `§6§l§ka§r §6§lLEGENDARY SWORD §6§l§ka`.
 */
export function rarityLine(rarity, type = '', { recomb = false } = {}) {
  const c = (RARITY[rarity] ?? RARITY.COMMON).code;
  const text = `§${c}§l${rarity}${type ? ' ' + type : ''}`;
  return recomb ? `§${c}§l§ka§r ${text} §${c}§l§ka` : text;
}

/** Normalize a spec into an Item. `icon` may be omitted (derived), a kind ('sword'), 'kind:#hex', or `{ kind, color }`. */
export function item(spec = {}) {
  const it = {
    id: spec.id ?? null, mcId: spec.mcId ?? null, name: spec.name ?? '', lore: spec.lore ?? [],
    rarity: null, count: spec.count ?? 1, icon: null, glint: !!spec.glint,
  };
  it.rarity = spec.rarity ?? rarityOf(it.lore);
  let icon = spec.icon;
  if (typeof icon === 'string') {
    const [kind, color] = icon.split(':');
    icon = { kind, color };
  }
  if (!icon) icon = iconFor(it.mcId, { sbId: it.id, name: it.name, rarity: it.rarity });
  it.icon = { kind: icon.kind, color: normHex(icon.color ?? KIND_COLOR[icon.kind] ?? KIND_COLOR.block) };
  return it;
}

/**
 * 16×16 GUI-px icon: `<span class="mc-item">` with the sprite, glint and the stack count (drawn
 * when count ≠ 1, like GuiGraphics.itemCount: right edge at x + 17, top at y + 9, white with shadow).
 * Opts: `{ count: false }` hides the count, `{ ghost: true }` draws the faint empty-slot outline.
 */
export function itemIcon(it, { count = true, ghost = false } = {}) {
  if (!it) return '';
  const svg = spriteSvg(it.icon.kind, it.icon.color, it.glint && !ghost);
  const n = count && it.count !== 1 ? String(it.count) : '';
  const countHtml = n ? `<span class="mc-count mc-text" style="left:${17 - mcWidth(n)}px;letter-spacing:${mcSpacing(n)}px">${esc(n)}</span>` : '';
  return `<span class="mc-item${ghost ? ' mc-ghost' : ''}">${svg}${countHtml}</span>`;
}

// ------------------------------------------------------------------ SkyBlock menu items
/** Hypixel's menu filler: a stained glass pane with a blank name. */
export const filler = (color = 'black') => item({ mcId: `minecraft:${color}_stained_glass_pane`, name: '§r ', icon: { kind: 'pane', color: DYE[color] ?? DYE.black } });
/** Blank-named black pane, the one Dark UI draws as background (DarkUi.isFiller). */
export const isFiller = (it) => !!it && it.icon.kind === 'pane' && !stripCodes(it.name).trim() && !it.lore.length;
/** The bottom-center barrier every SkyBlock menu has. */
export const closeItem = () => item({ mcId: 'minecraft:barrier', name: '§cClose' });
/** The "Go Back" arrow left of Close. */
export const backItem = (to = 'SkyBlock Menu') => item({ mcId: 'minecraft:arrow', name: '§aGo Back', lore: [`§7To ${to}`] });

