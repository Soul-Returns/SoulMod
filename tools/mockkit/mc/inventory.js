// Vanilla container screens at exact 26.2 geometry, in GUI px: the player inventory (InventoryScreen,
// 176×166) and generic chests of 1–6 rows (ContainerScreen), plus a SkyBlock menu builder. Frames are
// redrawn pixel for pixel from inventory.png / generic_54.png as inline SVG (no images).
//   const s = chestScreen({ sw: E.gw, sh: E.gh, rows: 6, title: 'SkyBlock Menu', items: skyblockMenu({ rows: 6, items: { 13: myItem } }) });
//   guiLayer.innerHTML = s.html; bindInventory(guiLayer, { tooltip: (slot) => s.items[slot], onClick: (slot) => … });
// Exports: INV_W, INV_H, playerScreen(opts), chestScreen(opts), skyblockMenu(opts), slotIndex(row, col),
//   playerSlots(player, base), bindInventory(root, handlers), controls(state, rerender).
// Menu slot numbering is vanilla's (what dev-bridge captures report): InventoryMenu 0 result, 1–4 crafting,
// 5–8 armor head→feet, 9–35 main, 36–44 hotbar, 45 offhand; ChestMenu 0..rows*9-1 container, then
// 27 main + 9 hotbar.

import { mcText } from '../util.js';
import { item as toItem, itemIcon, filler, closeItem, backItem, isFiller } from './items.js';
import { tooltip as tooltipHtml } from './tooltip.js';
import { mcSpacing } from './font.js';

export const INV_W = 176, INV_H = 166;
const SLOT = 18;
// AbstractContainerScreen: labels at (8, 6) and (8, imageHeight - 94) in 0xFF404040, no shadow.
const LABEL = { x: 8, y: 6, invFromBottom: 94 };
// InventoryScreen: title "Crafting" at (97, 6); entity box (26, 8)-(75, 78) at scale 30; recipe book
// button 20×18 at (104, height / 2 - 22) = (104, 61) for an even screen height.
const CRAFT_LABEL_X = 97, RECIPE_BTN = [104, 61, 20, 18];
// ContainerScreen: imageHeight = 114 + rows * 18; the texture is drawn as rows * 18 + 17 rows of
// generic_54.png from v 0, then 96 rows from v 126.
const chestTop = (rows) => rows * SLOT + 17;

// Vanilla GUI grays and Soul's Dark UI swap for them (features/darkui/DarkUi.PALETTE, BACKGROUND, LABEL).
const THEMES = {
  vanilla: { panel: '#c6c6c6', hi: '#ffffff', shadow: '#555555', slot: '#8b8b8b', slotDark: '#373737', outline: '#000000', label: '#404040', box: '#000000' },
  dark: { panel: '#1c1c1c', hi: '#434343', shadow: '#060606', slot: '#282828', slotDark: '#000000', outline: '#000000', label: '#c8c8c8', box: '#000000' },
};

// ------------------------------------------------------------------ pixel geometry
/** Rect lists [x, y, w, h] per palette key; order of keys is paint order. */
function painter() {
  const layers = {};
  const put = (k, x, y, w, h) => ((layers[k] ??= []).push([x, y, w, h]));
  const svg = (T, W, H) => `<svg class="mc-frame" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" shape-rendering="crispEdges">`
    + Object.entries(layers).map(([k, rs]) => `<path fill="${T[k]}" d="${rs.map(([x, y, w, h]) => `M${x} ${y}h${w}v${h}h-${w}z`).join('')}"/>`).join('') + '</svg>';
  return { put, svg };
}
/** The container frame: 1 px black outline with 2–3 px cut corners, 2 px white bevel top/left, 2 px #555 bottom/right (measured). */
function frame(p, W, H) {
  p.put('panel', 3, 3, W - 6, H - 6); p.put('panel', W - 3, 2, 1, 1); p.put('panel', 2, H - 3, 1, 1);
  p.put('hi', 2, 1, W - 5, 1); p.put('hi', 1, 2, W - 4, 1); p.put('hi', 1, 3, 3, 1); p.put('hi', 1, 4, 2, H - 7);
  p.put('shadow', W - 3, 3, 2, H - 6); p.put('shadow', W - 4, H - 4, 1, 1); p.put('shadow', 3, H - 3, W - 4, 1); p.put('shadow', 3, H - 2, W - 5, 1);
  p.put('outline', 2, 0, W - 5, 1); p.put('outline', 1, 1, 1, 1); p.put('outline', W - 3, 1, 1, 1); p.put('outline', 0, 2, 1, H - 5);
  p.put('outline', W - 2, 2, 1, 1); p.put('outline', W - 1, 3, 1, H - 5); p.put('outline', 1, H - 3, 1, 1); p.put('outline', 2, H - 2, 1, 1);
  p.put('outline', W - 2, H - 2, 1, 1); p.put('outline', 3, H - 1, W - 5, 1);
}
/** An 18×18 slot frame whose item sits at (x + 1, y + 1): #373737 top/left, white bottom/right, #8b8b8b corners and fill. */
function slotFrame(p, x, y) {
  p.put('slot', x, y, 18, 18);
  p.put('slotDark', x, y, 17, 1); p.put('slotDark', x, y, 1, 17);
  p.put('hi', x + 1, y + 17, 17, 1); p.put('hi', x + 17, y + 1, 1, 17);
}

// ------------------------------------------------------------------ slots + items
const asItem = (v) => (!v ? null : v.icon && typeof v.icon === 'object' ? v : toItem(v));
/** Normalize items given as an array or { slot: item } into a sparse array of Items. */
function itemList(items) {
  const out = [];
  if (Array.isArray(items)) items.forEach((v, i) => { if (v) out[i] = asItem(v); });
  else for (const [k, v] of Object.entries(items ?? {})) if (v) out[+k] = asItem(v);
  return out;
}
/**
 * Player inventory contents `{ hotbar: [9], main: [27], armor: [head, chest, legs, feet], offhand }`
 * as menu slots starting at `base` (main row 1 first, hotbar last), the way chest menus number them.
 */
export function playerSlots(player = {}, base) {
  const out = {};
  (player.main ?? []).forEach((v, i) => { if (v) out[base + i] = v; });
  (player.hotbar ?? []).forEach((v, i) => { if (v) out[base + 27 + i] = v; });
  return out;
}
/** Container slot index of a (row, col) cell, both 0-based. */
export const slotIndex = (row, col) => row * 9 + col;

function slotHtml(slot, x, y, it, { hover, ghost, dark }) {
  const filler = dark && isFiller(it);
  return `<div class="mc-slot${hover === slot ? ' mc-hover' : ''}${filler ? ' mc-filler' : ''}" data-slot="${slot}" style="left:${x}px;top:${y}px">`
    + (filler ? '' : it ? itemIcon(it) : ghost ? itemIcon(toItem({ icon: ghost }), { ghost: true }) : '') + '</div>';
}
const labelHtml = (text, x, y) => `<span class="mc-label mc-text" style="left:${x}px;top:${y}px;letter-spacing:${mcSpacing(text)}px">${mcText(text)}</span>`;

function screenHtml({ W, H, left, top, svg, body, dark, extra = '' }) {
  return `<div class="mc-screen${dark ? ' mc-dark' : ''}" data-mc-screen style="left:${left}px;top:${top}px;width:${W}px;height:${H}px">${svg}${extra}${body}</div>`;
}
function result(W, imageH, drawnH, sw, sh, html, slots, items) {
  // AbstractContainerScreen.init: leftPos = (width - imageWidth) / 2, topPos = (height - imageHeight) / 2 (int division).
  const left = Math.floor((sw - W) / 2), top = Math.floor((sh - imageH) / 2);
  const abs = slots.map((s) => ({ ...s, x: s.x + left, y: s.y + top }));
  return {
    html: html(left, top), left, top, w: W, h: drawnH, slots: abs, items,
    /** Menu slot under a GUI-px point (the 16×16 item area, as AbstractContainerScreen.isHovering), or null. */
    slotAt: (gx, gy) => abs.find((s) => gx >= s.x - 1 && gx < s.x + 17 && gy >= s.y - 1 && gy < s.y + 17)?.slot ?? null,
  };
}

// The player in the entity box: a front view of the 32-model-px player model at scale 30 (×1.875),
// feet at the box center + (0.9 + 0.0625) × 30 ≈ y 72 (InventoryScreen.extractEntityInInventoryFollowsMouse).
const DOLL = `<div class="mc-doll" style="left:36px;top:12px">`
  + '<i class="mc-doll-head" style="left:7px;top:0;width:15px;height:15px"></i>'
  + '<i class="mc-doll-arm" style="left:0;top:15px;width:7px;height:23px"></i><i class="mc-doll-body" style="left:7px;top:15px;width:15px;height:23px"></i>'
  + '<i class="mc-doll-arm" style="left:22px;top:15px;width:7px;height:23px"></i>'
  + '<i class="mc-doll-leg" style="left:7px;top:38px;width:8px;height:22px"></i><i class="mc-doll-leg" style="left:15px;top:38px;width:7px;height:22px"></i></div>';

/**
 * The player inventory (InventoryScreen), centered on an sw × sh GUI-px screen.
 * opts: `{ sw, sh, items?, player?, hover?, dark?, hideCrafting?, hideRecipeBook?, hideOffhand?, doll? = true, title? = 'Crafting' }`;
 * `items` by InventoryMenu slot, `player` = `{ hotbar, main, armor, offhand }` (merged under `items`).
 * Hiding mirrors Soul's General → Inventory toggles: the slots and their frames (and the "Crafting"
 * label) disappear, leaving panel color (InventoryCleanup).
 */
export function playerScreen(opts) {
  const { sw, sh, hover = null, dark = false, hideCrafting = false, hideRecipeBook = false, hideOffhand = false, doll = true, title = 'Crafting' } = opts;
  const pl = opts.player ?? {};
  const merged = {};
  (pl.armor ?? []).forEach((v, i) => { if (v) merged[5 + i] = v; });
  (pl.main ?? []).forEach((v, i) => { if (v) merged[9 + i] = v; });
  (pl.hotbar ?? []).forEach((v, i) => { if (v) merged[36 + i] = v; });
  if (pl.offhand) merged[45] = pl.offhand;
  const items = itemList({ ...merged, ...(Array.isArray(opts.items) ? Object.fromEntries(opts.items.map((v, i) => [i, v])) : opts.items ?? {}) });

  const p = painter();
  frame(p, INV_W, INV_H);
  const slots = [];
  const add = (slot, x, y, ghost = null) => { slotFrame(p, x - 1, y - 1); slots.push({ slot, x, y, ghost }); };
  ['helmet', 'chestplate', 'leggings', 'boots'].forEach((g, i) => add(5 + i, 8, 8 + i * SLOT, `${g}:#373737`));
  // Entity box (25, 7)-(75, 78) with a slot-style bevel around black.
  p.put('slot', 25, 7, 51, 72); p.put('box', 26, 8, 49, 70);
  p.put('slotDark', 25, 7, 50, 1); p.put('slotDark', 25, 7, 1, 71); p.put('hi', 26, 78, 50, 1); p.put('hi', 75, 8, 1, 71);
  if (!hideOffhand) add(45, 77, 62);
  if (!hideCrafting) {
    for (let r = 0; r < 2; r++) for (let c = 0; c < 2; c++) add(1 + c + r * 2, 98 + c * SLOT, 18 + r * SLOT);
    add(0, 154, 28);
    // The arrow, #8b8b8b on the panel (measured: shaft x 135..149 y 34..36, head from x 144).
    p.put('slot', 135, 34, 15, 3); p.put('slot', 150, 35, 1, 1);
    for (let i = 0; i < 5; i++) { p.put('slot', 144, 29 + i, i + 1, 1); p.put('slot', 144, 41 - i, i + 1, 1); }
  }
  for (let r = 0; r < 3; r++) for (let c = 0; c < 9; c++) add(9 + c + r * 9, 8 + c * SLOT, 84 + r * SLOT);
  for (let c = 0; c < 9; c++) add(36 + c, 8 + c * SLOT, 142);

  const T = THEMES[dark ? 'dark' : 'vanilla'];
  const [bx, by, bw, bh] = RECIPE_BTN;
  const extra = (doll ? DOLL : '') + (hideRecipeBook ? '' : `<i class="mc-recipe" style="left:${bx}px;top:${by}px;width:${bw}px;height:${bh}px"></i>`)
    + (hideCrafting ? '' : labelHtml(title, CRAFT_LABEL_X, LABEL.y));
  const body = slots.map((s) => slotHtml(s.slot, s.x, s.y, items[s.slot], { hover, ghost: s.ghost, dark })).join('');
  return result(INV_W, INV_H, INV_H, sw, sh, (left, top) => screenHtml({ W: INV_W, H: INV_H, left, top, svg: p.svg(T, INV_W, INV_H), body, dark, extra }), slots, items);
}

/**
 * A generic chest (ContainerScreen, generic_9xN) of `rows` 1–6, centered on sw × sh GUI px.
 * opts: `{ sw, sh, rows, title, items?, player?, hover?, dark?, invLabel? = 'Inventory' }`; `items` by
 * ChestMenu slot (array or object; a capture's slots go in as-is), `player` fills the lower 36.
 * In `dark`, blank-named black panes draw as background, frame included (Soul's Dark UI).
 */
export function chestScreen(opts) {
  const { sw, sh, title = '', hover = null, dark = false, invLabel = 'Inventory' } = opts;
  const rows = Math.max(1, Math.min(6, opts.rows ?? 6));
  const n = rows * 9, top0 = chestTop(rows), H = top0 + 96, imageH = 114 + rows * SLOT;
  const given = Array.isArray(opts.items) ? Object.fromEntries(opts.items.map((v, i) => [i, v])) : opts.items ?? {};
  const items = itemList({ ...playerSlots(opts.player, n), ...given });

  const p = painter();
  frame(p, INV_W, H);
  const slots = [];
  const T = THEMES[dark ? 'dark' : 'vanilla'];
  const add = (slot, x, y) => {
    if (!(dark && isFiller(items[slot]))) slotFrame(p, x - 1, y - 1);
    slots.push({ slot, x, y });
  };
  for (let r = 0; r < rows; r++) for (let c = 0; c < 9; c++) add(r * 9 + c, 8 + c * SLOT, 18 + r * SLOT);
  for (let r = 0; r < 3; r++) for (let c = 0; c < 9; c++) add(n + r * 9 + c, 8 + c * SLOT, top0 + 14 + r * SLOT);
  for (let c = 0; c < 9; c++) add(n + 27 + c, 8 + c * SLOT, top0 + 72);

  const extra = labelHtml(title, LABEL.x, LABEL.y) + labelHtml(invLabel, LABEL.x, imageH - LABEL.invFromBottom);
  const body = slots.map((s) => slotHtml(s.slot, s.x, s.y, items[s.slot], { hover, dark })).join('');
  return result(INV_W, imageH, H, sw, sh, (left, top) => screenHtml({ W: INV_W, H, left, top, svg: p.svg(T, INV_W, H), body, dark, extra }), slots, items);
}

/**
 * Container items of a SkyBlock menu: `{ rows? = 6, fill? = 'all' | 'border' | 'none', pane? = 'black',
 * close? = true, back? = null | 'SkyBlock Menu', items? }` → `{ slot: Item }` for chestScreen. Hypixel puts
 * Close (barrier) at the bottom row's center and "Go Back" (arrow) left of it; `items` (by slot index,
 * see slotIndex) win over both and over the filler panes.
 */
export function skyblockMenu({ rows = 6, fill = 'all', pane = 'black', close = true, back = null, items = {} } = {}) {
  const out = {};
  const n = rows * 9, last = n - 9;
  for (let i = 0; i < n; i++) {
    const border = i < 9 || i >= last || i % 9 === 0 || i % 9 === 8;
    if (fill === 'all' || (fill === 'border' && border)) out[i] = filler(pane);
  }
  if (close) out[last + 4] = closeItem();
  if (back) out[last + 3] = backItem(back);
  for (const [k, v] of Object.entries(items)) if (v) out[+k] = asItem(v);
  return out;
}

// ------------------------------------------------------------------ interaction
/**
 * Delegated hover/click on the `[data-slot]` cells inside `root` (the GUI-px layer the screen HTML
 * sits in; its CSS scale is undone, so gx/gy are GUI px relative to root). Handlers:
 *   onHover(slot | null, { gx, gy, el, ev })  — on entering a slot or leaving all slots
 *   onClick(slot, { button, shift, gx, gy, ev }) — mouse down on a slot (right click too, menu suppressed)
 *   tooltip: (slot) => Item | null, or an items array/object — draws vanilla tooltips into a host
 *     element in root, sized to root's GUI-px box.
 * Returns an unbind function. Re-rendering the screen HTML keeps the binding (delegated).
 */
export function bindInventory(root, { onHover, onClick, tooltip } = {}) {
  const ac = new AbortController(), on = { signal: ac.signal };
  const toGui = (ev) => {
    const r = root.getBoundingClientRect(), k = r.width / root.offsetWidth || 1;
    return { gx: (ev.clientX - r.left) / k, gy: (ev.clientY - r.top) / k };
  };
  const slotOf = (ev) => {
    const el = ev.target instanceof Element ? ev.target.closest('[data-slot]') : null;
    return el && root.contains(el) ? { slot: +el.dataset.slot, el } : { slot: null, el: null };
  };
  const lookup = typeof tooltip === 'function' ? tooltip : tooltip ? (s) => tooltip[s] : null;
  const host = () => root.querySelector(':scope > .mc-tt-host') ?? root.appendChild(Object.assign(document.createElement('div'), { className: 'mc-tt-host' }));
  let cur = null;
  const clear = () => { if (lookup) host().innerHTML = ''; };
  root.addEventListener('pointermove', (ev) => {
    const { slot, el } = slotOf(ev), pos = toGui(ev);
    if (slot !== cur) { cur = slot; onHover?.(slot, { ...pos, el, ev }); }
    if (!lookup) return;
    const it = slot == null ? null : lookup(slot);
    host().innerHTML = it ? tooltipHtml(it, { mx: Math.floor(pos.gx), my: Math.floor(pos.gy), sw: root.offsetWidth, sh: root.offsetHeight }) : '';
  }, on);
  root.addEventListener('pointerleave', (ev) => { if (cur !== null) { cur = null; onHover?.(null, { ...toGui(ev), el: null, ev }); } clear(); }, on);
  root.addEventListener('mousedown', (ev) => {
    const { slot } = slotOf(ev);
    if (slot != null && onClick) onClick(slot, { button: ev.button, shift: ev.shiftKey, ...toGui(ev), ev });
  }, on);
  root.addEventListener('contextmenu', (ev) => { if (onClick && slotOf(ev).slot != null) ev.preventDefault(); }, on);
  return () => { ac.abort(); clear(); };
}

/** Drawer controls for the player-inventory hide toggles and Dark UI on a state object. */
export function controls(state, rerender) {
  const check = (key, label, hint) => ({ type: 'check', label, hint, get: () => !!state[key], set: (v) => { state[key] = v; rerender(); } });
  return [
    { type: 'heading', text: 'Inventory' },
    check('hideCrafting', 'Hide 2×2 crafting', 'General → Inventory → Hide vanilla elements'),
    check('hideRecipeBook', 'Hide recipe book button'),
    check('hideOffhand', 'Hide off-hand slot'),
    check('dark', 'Dark UI', 'Soul\'s recolor of container textures; blank black panes become background'),
  ];
}
