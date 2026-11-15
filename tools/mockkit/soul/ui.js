// Soul UI controls in Soul px: the config menu's widgets (SoulScreenBase / ConfigScreenBase) and, inside
// any `.s-compact` ancestor, the Move GUI inspector's smaller ones (HudMoveScreen ROW_H 16, LABEL_W 46).
// Colors are config/gui/Theme.kt. Builders return HTML strings keyed by `data-key`; one delegated
// binder wires them and reports changes through `set(key, value, meta)`.
//
// Exports: THEME, LEGACY_EM, CAP, FONTS, soulPx, argb, ICON, PALETTE, CHROMA, chromaCycle, swatchStyle, panel, row,
//   button, toggleButton, toggle, slider, seg, dropdown, stepper, textField, colorPicker, section, confirmHtml,
//   showConfirm, openDropdown, openColorPicker, closePopup, bindSoulUi.
// Usage: root.innerHTML = panel(row('Scale', slider('scale', S.scale, { min: 0.25, max: 4, step: 0.05 })));
//        bindSoulUi(root, { get: (k) => S[k], set: (k, v) => { S[k] = v; render(); } });

import { clamp, esc, fmt } from '../util.js';

/** Theme.kt as CSS colors (ARGB alpha → rgba). */
export const THEME = {
  DIM: 'rgba(0,0,0,.4)', // 0x66000000
  PANEL: 'rgba(26,26,26,.847)', // 0xD81A1A1A
  TOP_NAV: '#111111', SIDEBAR: '#161616', SECTION: '#1e1e1e',
  PANEL_HOVER: 'rgba(36,36,36,.878)', // 0xE0242424
  CAT_ACTIVE: '#0e0e0e', SEPARATOR: '#2a2a2a', CONTROL: '#2a2a2a', CONTROL_HOVER: '#353535',
  ACCENT: '#3b82f6', ACCENT_DIM: '#2563eb', TEXT: '#eeeeee', TEXT_DIM: '#888888', TEXT_FAINT: '#555555',
  FIELD_BG: 'rgba(36,36,36,.878)', FIELD_BORDER: '#333333',
  MODAL_SCRIM: 'rgba(0,0,0,.69)', // SoulScreenBase.drawAlertModal 0xB0000000
  ERROR: '#e05252', // drawAlertModal title of a confirm / error
};

/** Kotlin ARGB int (e.g. 0xD81A1A1A) → CSS rgba(). */
export function argb(n) {
  const a = ((n >>> 24) & 255) / 255, r = (n >>> 16) & 255, g = (n >>> 8) & 255, b = n & 255;
  return `rgba(${r},${g},${b},${Math.round(a * 1000) / 1000})`;
}

/** MsdfFont.LEGACY_EM: Baloo 2's cap height / 0.875. Layouts tuned in legacy em sizes multiply by it. */
export const LEGACY_EM = 0.786;
/**
 * Cap height / em per family. SoulText normalizes every family to a cap height of 7/8 of the nominal
 * size, so the CSS font-size that matches nominal size s is s * 0.875 / CAP. Baloo 2 from LEGACY_EM;
 * Nunito (705/1000) and Inter (1490/2048) from their OS/2 tables; 'mc' is the monospace stand-in.
 */
export const CAP = { baloo: LEGACY_EM * 0.875, nunito: 0.705, inter: 0.7275, mc: 0.875 };
// single quotes: these go into style="…" attributes
export const FONTS = { baloo: "'Baloo 2', sans-serif", nunito: 'Nunito, sans-serif', inter: "Inter, 'Segoe UI', system-ui, sans-serif", mc: 'var(--mc-font)' };
/** CSS px for a SoulText nominal size (TITLE_SIZE 24 → 30.5 px Baloo 2). Legacy em sizes (11, 13) map to themselves. */
export const soulPx = (size, font = 'baloo') => Math.round((size * 0.875 / (CAP[font] || CAP.baloo)) * 100) / 100;

/** Small inline icons (currentColor). */
export const ICON = {
  undo: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M5.5 3.5 2.5 6.5l3 3"/><path d="M3 6.5h6.5a4 4 0 0 1 0 8H7"/></svg>',
  redo: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"><path d="m10.5 3.5 3 3-3 3"/><path d="M13 6.5H6.5a4 4 0 0 0 0 8H9"/></svg>',
  close: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m4 4 8 8M12 4l-8 8"/></svg>',
  reset: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M3 8a5 5 0 1 0 1.6-3.7"/><path d="M3 2.5v3h3"/></svg>',
  cog: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor"><circle cx="8" cy="8" r="5.4" stroke-width="2.4" stroke-dasharray="2.12 2.12"/><circle cx="8" cy="8" r="3.9" stroke-width="1.7"/></svg>',
};

const attr = (name, v) => (v === undefined || v === null || v === false ? '' : v === true ? ` ${name}` : ` ${name}="${esc(v)}"`);
const enc = (v) => esc(JSON.stringify(v));
const px = (v) => (typeof v === 'number' ? v + 'px' : v);
const decimalsOf = (step) => {
  const s = String(step);
  return s.includes('.') ? s.length - s.indexOf('.') - 1 : 0;
};
/** Options as [value, label] pairs or { value, label, note, disabled, header }. */
const normOpts = (options) => options.map((o) => (Array.isArray(o) ? { value: o[0], label: String(o[1]) } : o));
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

// ------------------------------------------------------------------ static pieces

/** Rounded Soul card (Theme.PANEL, 1 px FIELD_BORDER, radius 6 = ROUND_MEDIUM). */
export function panel(inner, { cls = '', style = '', pad = 8, width } = {}) {
  const st = `padding:${px(pad)};${width !== undefined ? `width:${px(width)};` : ''}${style}`;
  return `<div class="s-panel ${cls}" style="${st}">${inner}</div>`;
}

/** Option row: label left, control right (config ROW_H 32; compact: 16 high, 46 px label column). */
export function row(label, control, { hint, cls = '' } = {}) {
  return `<div class="s-row ${cls}"><span class="s-lb">${esc(label)}${hint ? `<small>${esc(hint)}</small>` : ''}</span><div class="s-ct">${control}</div></div>`;
}

/**
 * Button (drawButton: PANEL_HOVER, CONTROL_HOVER on hover; accent ACCENT_DIM → ACCENT). Variants
 * 'accent' | 'icon' | 'tab'; [on] fills it with the accent. Clicking calls handlers.action(key, el).
 */
export function button(label, { key, variant = '', on = false, disabled = false, icon = '', title, width } = {}) {
  const cls = ['s-btn', variant, on ? 'on' : ''].filter(Boolean).join(' ');
  return `<button type="button" class="${cls}" data-s="btn"${attr('data-key', key)}${attr('title', title)}${attr('disabled', disabled)}${width !== undefined ? ` style="width:${px(width)}"` : ''}>${icon}${label ? `<span>${esc(label)}</span>` : ''}</button>`;
}

/** Button that flips a boolean: set(key, !get(key)). */
export function toggleButton(label, key, on, { variant = '', disabled = false, icon = '', title } = {}) {
  return button(label, { key, variant, on, disabled, icon, title }).replace('data-s="btn"', 'data-s="tbtn"');
}

/** Pill switch (drawToggle: 36×18, knob 14 inset 2; off PANEL_HOVER, on ACCENT). */
export function toggle(key, on, { disabled = false } = {}) {
  return `<span class="s-sw${on ? ' on' : ''}${disabled ? ' dis' : ''}" role="switch" data-s="toggle"${attr('data-key', key)}></span>`;
}

/**
 * Slider (drawSlider: 4 px CONTROL track, ACCENT fill, 12 px knob, readout left in TEXT_DIM, track 120).
 * Values snap to [step] from [min]. Dragging calls set(key, v, { source: 'slider', final }).
 */
export function slider(key, value, { min = 0, max = 1, step = 0.01, width = 120, readout = true, suffix = '', disabled = false } = {}) {
  const f = clamp((value - min) / (max - min || 1), 0, 1);
  return `<span class="s-sld${disabled ? ' dis' : ''}" data-s="slider"${attr('data-key', key)} data-min="${min}" data-max="${max}" data-step="${step}"${attr('data-suffix', suffix)} style="--f:${f}">`
    + `${readout ? `<span class="s-sv">${esc(fmt(value) + suffix)}</span>` : ''}<span class="s-tr" style="--w:${px(width)}"><i class="s-fi"></i><i class="s-kn"></i></span></span>`;
}

/** Segmented control (the tracker tab strip: unselected tabs have no fill, selected ACCENT). */
export function seg(key, value, options, { disabled = false } = {}) {
  const bs = normOpts(options).map((o) => `<button type="button" class="${same(o.value, value) ? 'on' : ''}" data-v="${enc(o.value)}"${attr('disabled', disabled || o.disabled)}>${esc(o.label)}</button>`).join('');
  return `<span class="s-seg" data-s="seg"${attr('data-key', key)}>${bs}</span>`;
}

let pop = null; // the one open popup: { key, el, close }

/** Dropdown button (drawDropdownButton: bordered field, value, 5×3 caret; 100 wide). Opens a popup list. */
export function dropdown(key, value, options, { width = 100, placeholder = '', disabled = false } = {}) {
  const opts = normOpts(options);
  const cur = opts.find((o) => !o.header && same(o.value, value));
  const open = pop && pop.key === key ? ' open' : '';
  return `<button type="button" class="s-dd${open}" data-s="dd"${attr('data-key', key)} data-opts="${enc(opts)}"${attr('disabled', disabled)} style="width:${px(width)}"><span>${esc(cur ? cur.label : placeholder)}</span><i class="s-car"></i></button>`;
}

/**
 * Number field with up/down arrows. Arrows, ArrowUp/Down and the mouse wheel step by [step] (Shift ×10)
 * onto the step grid; typed values (Enter / blur) keep their own precision, rounded to [step]'s
 * decimals. Everything clamps to [min, max]; Escape reverts.
 */
export function stepper(key, value, { min = -Infinity, max = Infinity, step = 1, width = 48, unit = '', disabled = false } = {}) {
  const dec = decimalsOf(step);
  return `<span class="s-step${disabled ? ' dis' : ''}" data-s="step"${attr('data-key', key)} data-min="${min}" data-max="${max}" data-step="${step}" style="width:${px(width)}">`
    + `<input class="s-fld" value="${esc(String(+(+value).toFixed(dec)))}" inputmode="decimal" spellcheck="false"${attr('disabled', disabled)}>`
    + `<span class="s-arr"><button type="button" tabindex="-1" data-d="1"><i></i></button><button type="button" tabindex="-1" data-d="-1"><i></i></button></span></span>`
    + (unit ? `<span class="s-unit">${esc(unit)}</span>` : '');
}

/** Text field (drawTextField: FIELD_BG, 1 px FIELD_BORDER → ACCENT focused, text inset 6; 160 wide). Commits on Enter / blur. */
export function textField(key, value, { placeholder = '', width = 160, masked = false, disabled = false } = {}) {
  return `<input class="s-fld s-txt" data-s="text"${attr('data-key', key)} type="${masked ? 'password' : 'text'}" value="${esc(value)}"${attr('placeholder', placeholder)} spellcheck="false"${attr('disabled', disabled)} style="width:${px(width)}">`;
}

/**
 * Color control: the config row's swatch (drawSwatch 26×16, 1 px FIELD_BORDER, ROUND_SMALL) opening
 * the picker popup, plus a hex field (Enter / blur commits, Escape reverts). Value: '#rrggbb', or a
 * ChromaColor-like `{ color, chroma, speed }` whose popup has the Chroma switch and Speed slider (a
 * chroma swatch cycles through the hues; the hex field shows the base color).
 */
export function colorPicker(key, value, { disabled = false, width = 64 } = {}) {
  const chroma = typeof value === 'object' && value?.chroma;
  return `<span class="s-col${disabled ? ' dis' : ''}" data-s="color"${attr('data-key', key)}>`
    + `<button type="button" class="s-swatch${chroma ? ' s-chroma' : ''}" style="${swatchStyle(value)}"${attr('disabled', disabled)}></button>`
    + `<input class="s-fld s-hex" value="${esc(hexOut(value))}" spellcheck="false" maxlength="7"${attr('disabled', disabled)} style="width:${px(width)}"></span>`;
}

/**
 * Section: header (semibold) over [body]. With [key] it collapses: the header shows a caret and an
 * optional [summary], a click calls set(key, !open), and [body] renders only while [open].
 */
export function section(title, body = '', { key, open = true, summary = '' } = {}) {
  const coll = key !== undefined;
  const head = `<div class="s-sh"${coll ? ` data-s="sec"${attr('data-key', key)}` : ''}>${coll ? '<i class="s-car"></i>' : ''}<span class="s-st">${esc(title)}</span>${summary ? `<span class="s-ss">${esc(summary)}</span>` : ''}</div>`;
  return `<div class="s-sec${coll ? ' coll' : ''}${coll && open ? ' open' : ''}">${head}${!coll || open ? `<div class="s-sb">${body}</div>` : ''}</div>`;
}

// ------------------------------------------------------------------ modal

/** drawAlertModal in confirm mode: 300 wide, pad 16, red title, dim message, Cancel 72 + confirm max(text+28, 72). */
export function confirmHtml({ title, text, ok, cancel = 'Cancel' }) {
  return `<div class="s-scrim"><div class="s-modal" role="dialog"><div class="s-mt">${esc(title)}</div><div class="s-mx">${esc(text)}</div>`
    + `<div class="s-mb"><button type="button" class="s-btn" data-m="cancel">${esc(cancel)}</button><button type="button" class="s-btn accent" data-m="ok">${esc(ok)}</button></div></div></div>`;
}

/**
 * Show a confirm modal over [layer] (any positioned element in Soul px). Only the confirm button or
 * Enter runs [onOk]; Cancel / Escape run [onCancel]. Clicks outside are swallowed (the real modal's
 * full-screen catch region does nothing). Returns close().
 */
export function showConfirm(layer, { title, text, ok, cancel, onOk, onCancel }) {
  closePopup();
  const host = document.createElement('div');
  host.className = 's-modallayer';
  host.innerHTML = confirmHtml({ title, text, ok, cancel });
  layer.appendChild(host);
  const close = () => {
    host.remove();
    window.removeEventListener('keydown', onKey, true);
  };
  const finish = (yes) => {
    close();
    (yes ? onOk : onCancel)?.();
  };
  const onKey = (e) => {
    if (e.key !== 'Escape' && e.key !== 'Enter') return;
    e.preventDefault();
    e.stopPropagation();
    finish(e.key === 'Enter');
  };
  host.addEventListener('pointerdown', (e) => e.stopPropagation());
  host.addEventListener('click', (e) => {
    const b = e.target.closest('[data-m]');
    if (b) finish(b.dataset.m === 'ok');
  });
  window.addEventListener('keydown', onKey, true);
  host.querySelector('[data-m="ok"]').focus();
  return close;
}

// ------------------------------------------------------------------ popup list

/** Layer px per client px, so popups land right inside a CSS-scaled Soul layer. */
function layerScale(layer) {
  const r = layer.getBoundingClientRect();
  return { r, k: layer.offsetWidth ? r.width / layer.offsetWidth : 1 };
}

/**
 * Open the popup list under [anchor] (drawOpenDropdown: 16 px items, selected ACCENT + white text,
 * hover PANEL_HOVER; flips above when it would leave [layer]). One popup at a time; a click outside or
 * Escape closes it. [onPick] gets the option's value.
 */
export function openDropdown(anchor, { options, value, onPick, layer, key = null }) {
  closePopup();
  layer = layer || anchor.offsetParent || document.body;
  const { r: lr, k } = layerScale(layer);
  const ar = anchor.getBoundingClientRect();
  const a = [(ar.left - lr.left) / k, (ar.top - lr.top) / k, (ar.right - lr.left) / k, (ar.bottom - lr.top) / k];
  const opts = normOpts(options);
  const host = document.createElement('div');
  host.className = 's-poplayer' + (anchor.closest('.s-compact') ? ' s-compact' : '');
  host.innerHTML = '<div class="s-popscrim"></div><div class="s-pop">' + opts.map((o, i) => (o.header
    ? `<div class="s-oh">${esc(o.header)}</div>`
    : `<div class="s-o${same(o.value, value) ? ' cur' : ''}${o.disabled ? ' dis' : ''}" data-i="${i}"><span>${esc(o.label)}</span>${o.note ? `<small>${esc(o.note)}</small>` : ''}</div>`)).join('') + '</div>';
  layer.appendChild(host);
  const list = host.querySelector('.s-pop');
  const W = layer.offsetWidth || layer.clientWidth, H = layer.offsetHeight || layer.clientHeight;
  list.style.minWidth = a[2] - a[0] + 'px';
  list.style.maxHeight = Math.max(40, H - 4) + 'px';
  const w = list.offsetWidth, h = list.offsetHeight;
  const below = a[3] + 1;
  list.style.top = (below + h <= H ? below : Math.max(2, a[1] - 1 - h)) + 'px';
  list.style.left = clamp(a[0], 2, Math.max(2, W - w - 2)) + 'px';
  const onKey = (e) => {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    e.stopPropagation();
    closePopup();
  };
  const close = () => {
    host.remove();
    window.removeEventListener('keydown', onKey, true);
    if (key !== null) for (const el of document.querySelectorAll('.s-dd.open')) el.classList.remove('open');
  };
  host.addEventListener('pointerdown', (e) => {
    e.stopPropagation();
    if (e.target.classList.contains('s-popscrim')) {
      e.preventDefault();
      closePopup();
    }
  });
  host.addEventListener('click', (e) => {
    const o = e.target.closest('.s-o');
    if (!o || o.classList.contains('dis')) return;
    closePopup();
    onPick(opts[+o.dataset.i].value);
  });
  window.addEventListener('keydown', onKey, true);
  pop = { key, el: host, close };
  if (key !== null) anchor.classList.add('open');
  return closePopup;
}

export function closePopup() {
  if (!pop) return;
  const p = pop;
  pop = null;
  p.close();
}

// ------------------------------------------------------------------ color picker popup
// ConfigScreenBase.drawColorPicker: panel PAD 8, SV square 96, hue bar 12, GAP 8, swatch 22 + hex
// readout (16 high), then the Chroma toggle row (20) and, while on, the Speed slider row (+20;
// 0.5–20), anchored to the left of the swatch (else right), clamped in the layer. The mock adds a
// palette row of presets (Soul blue + the 16 chat colors) under the square.
const CP = { PAD: 8, SV: 96, BAR: 12, GAP: 8, HEX_H: 16, ROW_H: 20, ANCHOR_GAP: 6, CHIP: 10, CHIP_GAP: 2 };
/** ConfigScreenBase.chromaSpeedMin / Max; ChromaColor.speed default 6. */
export const CHROMA = { MIN: 0.5, MAX: 20, STEP: 0.5, DEFAULT: 6 };
/** ChromaColor.effective: seconds per hue loop = clamp(20 / max(speed, 0.5), 1, 40); hue = (now / cycle) % 1. */
export const chromaCycle = (speed) => clamp(20 / Math.max(speed ?? CHROMA.DEFAULT, CHROMA.MIN), 1, 40);
export const PALETTE = ['#3b82f6', '#ffffff', '#aaaaaa', '#555555', '#55ff55', '#00aa00', '#55ffff', '#00aaaa', '#5555ff', '#0000aa',
  '#ff55ff', '#aa00aa', '#ffff55', '#ffaa00', '#ff5555', '#aa0000'];
const parseHex = (s) => { const m = String(s).trim().match(/^#?([0-9a-f]{6})$/i); return m ? '#' + m[1].toLowerCase() : null; };
/** A color value is '#rrggbb' or a ChromaColor-like `{ color, chroma, speed }`. */
const colorOf = (v) => (typeof v === 'object' && v ? v.color : v) || '#000000';
const hexOut = (v) => String(colorOf(v)).toUpperCase();
const withColor = (old, hex) => (typeof old === 'object' && old ? { ...old, color: hex } : hex);
/** Inline style for a swatch: the color, or the animated rainbow (one hue at a time, like ChromaColor). */
export const swatchStyle = (v) => (typeof v === 'object' && v?.chroma ? `--cs:${chromaCycle(v.speed)}s` : `background:${esc(colorOf(v))}`);
function hexHsv(hex) {
  const n = parseInt(hex.slice(1), 16), r = ((n >> 16) & 255) / 255, g = ((n >> 8) & 255) / 255, b = (n & 255) / 255;
  const mx = Math.max(r, g, b), d = mx - Math.min(r, g, b);
  let h = 0;
  if (d) h = mx === r ? ((g - b) / d + 6) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h / 6, mx ? d / mx : 0, mx];
}
function hsvHex(h, s, v) {
  const f = (n) => { const k = (n + h * 6) % 6; return v - v * s * Math.max(0, Math.min(k, 4 - k, 1)); };
  return '#' + [f(5), f(3), f(1)].map((x) => Math.round(x * 255).toString(16).padStart(2, '0')).join('');
}

/**
 * Open the color popup beside [anchor] (a swatch). [value] is '#rrggbb' or `{ color, chroma, speed }`
 * (the object form shows the Chroma row). [onPick](value, final) gets the same shape, live while
 * dragging and on release.
 */
export function openColorPicker(anchor, { value, onPick, layer, key = null }) {
  closePopup();
  layer = layer || anchor.offsetParent || document.body;
  const { r: lr, k } = layerScale(layer);
  const ar = anchor.getBoundingClientRect();
  const a = [(ar.left - lr.left) / k, (ar.top - lr.top) / k, (ar.right - lr.left) / k, (ar.bottom - lr.top) / k];
  const hasChroma = typeof value === 'object' && value !== null;
  let chroma = hasChroma && !!value.chroma, speed = hasChroma ? value.speed ?? CHROMA.DEFAULT : CHROMA.DEFAULT;
  let [h, s, v] = hexHsv(parseHex(colorOf(value)) || '#3b82f6');
  const host = document.createElement('div');
  host.className = 's-poplayer';
  const chips = PALETTE.map((c) => `<i class="s-cp-chip" data-c="${c}" style="background:${c}"></i>`).join('');
  host.innerHTML = `<div class="s-popscrim"></div><div class="s-cpop" style="padding:${CP.PAD}px">`
    + `<div class="s-cp-top" style="gap:${CP.GAP}px"><div class="s-cp-sv" style="width:${CP.SV}px;height:${CP.SV}px"><i class="s-cp-cur"></i></div>`
    + `<div class="s-cp-hue" style="width:${CP.BAR}px;height:${CP.SV}px"><i class="s-cp-hk"></i></div></div>`
    + `<div class="s-cp-pal" style="margin-top:${CP.GAP}px;gap:${CP.CHIP_GAP}px;grid-template-columns:repeat(8,${CP.CHIP}px)">${chips}</div>`
    + `<div class="s-cp-hex" style="margin-top:${CP.GAP}px;height:${CP.HEX_H}px"><i class="s-cp-sw"></i><span></span></div>`
    + (hasChroma ? `<div class="s-cp-row" style="height:${CP.ROW_H}px"><span>Chroma</span><span class="s-sw s-cp-chroma" role="switch"></span></div>`
      + `<div class="s-cp-row s-cp-spd" style="height:${CP.ROW_H}px"><span>Speed</span><span class="s-cp-tr"><i class="s-cp-fi"></i><i class="s-cp-kn"></i></span><span class="s-cp-sv-n"></span></div>` : '')
    + '</div>';
  layer.appendChild(host);
  const panelEl = host.querySelector('.s-cpop'), sv = host.querySelector('.s-cp-sv'), hue = host.querySelector('.s-cp-hue');
  const place = () => {
    const W = layer.offsetWidth || layer.clientWidth, H = layer.offsetHeight || layer.clientHeight;
    const pw = panelEl.offsetWidth, ph = panelEl.offsetHeight;
    const left = a[0] - CP.ANCHOR_GAP - pw;
    panelEl.style.left = (left >= 2 ? left : Math.min(a[2] + CP.ANCHOR_GAP, Math.max(2, W - pw - 2))) + 'px';
    panelEl.style.top = clamp(a[1], 2, Math.max(2, H - ph - 2)) + 'px';
  };
  const out = () => (hasChroma ? { ...value, color: hsvHex(h, s, v), chroma, speed } : hsvHex(h, s, v));
  const paint = () => {
    const hex = hsvHex(h, s, v);
    sv.style.background = `linear-gradient(to bottom, transparent, #000), linear-gradient(to right, #fff, ${hsvHex(h, 1, 1)})`;
    sv.firstChild.style.left = s * 100 + '%';
    sv.firstChild.style.top = (1 - v) * 100 + '%';
    hue.firstChild.style.top = h * 100 + '%';
    const sw = host.querySelector('.s-cp-sw');
    sw.classList.toggle('s-chroma', chroma);
    sw.setAttribute('style', chroma ? `--cs:${chromaCycle(speed)}s` : `background:${hex}`);
    host.querySelector('.s-cp-hex span').textContent = hex.toUpperCase(); // the base color, also under chroma (as the config picker)
    for (const c of host.querySelectorAll('.s-cp-chip')) c.classList.toggle('cur', c.dataset.c === hex);
    if (hasChroma) {
      host.querySelector('.s-cp-chroma').classList.toggle('on', chroma);
      const spd = host.querySelector('.s-cp-spd');
      spd.style.display = chroma ? '' : 'none';
      spd.querySelector('.s-cp-tr').style.setProperty('--f', (speed - CHROMA.MIN) / (CHROMA.MAX - CHROMA.MIN));
      spd.querySelector('.s-cp-sv-n').textContent = fmt(speed);
    }
    return out();
  };
  paint();
  place();
  let drag = null;
  const at = (e, final) => {
    const r = drag.getBoundingClientRect(), fx = clamp((e.clientX - r.left) / r.width, 0, 1), fy = clamp((e.clientY - r.top) / r.height, 0, 1);
    if (drag === sv) { s = fx; v = 1 - fy; } else if (drag === hue) h = Math.min(fy, 0.9999);
    else speed = Math.round((CHROMA.MIN + fx * (CHROMA.MAX - CHROMA.MIN)) / CHROMA.STEP) * CHROMA.STEP;
    onPick(paint(), final);
  };
  const move = (e) => at(e, false);
  const up = (e) => { at(e, true); drag = null; window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); };
  const onKey = (e) => {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    e.stopPropagation();
    closePopup();
  };
  host.addEventListener('pointerdown', (e) => {
    e.stopPropagation();
    if (e.target.classList.contains('s-popscrim')) { e.preventDefault(); closePopup(); return; }
    const chip = e.target.closest('.s-cp-chip');
    if (chip) { [h, s, v] = hexHsv(chip.dataset.c); onPick(paint(), true); return; }
    if (e.target.closest('.s-cp-chroma')) { chroma = !chroma; onPick(paint(), true); place(); return; }
    const t = e.target.closest('.s-cp-sv, .s-cp-hue, .s-cp-tr');
    if (!t) return;
    e.preventDefault();
    drag = t;
    move(e);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  });
  const close = () => { host.remove(); window.removeEventListener('keydown', onKey, true); anchor.closest('.s-col')?.classList.remove('open'); };
  window.addEventListener('keydown', onKey, true);
  pop = { key, el: host, close };
  anchor.closest('.s-col')?.classList.add('open');
  return closePopup;
}

// ------------------------------------------------------------------ binder

/**
 * Wire every control under [root] by delegation. handlers: get(key) → value; set(key, value, meta)
 * applies it (meta.source is 'slider' with meta.final on release, 'wheel', 'step', 'type');
 * action(key, el) for plain buttons (default: set(key, true)); layer = element dropdown popups go in
 * (default root). After each set the matching controls are synced from get(), so re-rendering is
 * optional. Returns { sync(key), destroy() }.
 */
export function bindSoulUi(root, { get, set, action, layer } = {}) {
  const popLayer = layer || root;
  const sel = (k) => `[data-key="${CSS.escape(k)}"]`;

  function sync(key) {
    const v = get(key);
    for (const el of root.querySelectorAll(sel(key))) {
      switch (el.dataset.s) {
        case 'toggle':
        case 'tbtn': el.classList.toggle('on', !!v); break;
        case 'seg': for (const b of el.querySelectorAll('button[data-v]')) b.classList.toggle('on', same(JSON.parse(b.dataset.v), v)); break;
        case 'slider': {
          const min = +el.dataset.min, max = +el.dataset.max;
          el.style.setProperty('--f', clamp((v - min) / (max - min || 1), 0, 1));
          const sv = el.querySelector('.s-sv');
          if (sv) sv.textContent = fmt(v) + (el.dataset.suffix || '');
          break;
        }
        case 'dd': {
          const o = JSON.parse(el.dataset.opts).find((x) => !x.header && same(x.value, v));
          if (o) el.querySelector('span').textContent = o.label;
          break;
        }
        case 'step': {
          const inp = el.querySelector('input');
          if (document.activeElement !== inp) inp.value = String(+(+v).toFixed(decimalsOf(el.dataset.step)));
          break;
        }
        case 'text': if (document.activeElement !== el) el.value = v ?? ''; break;
        case 'color': {
          const sw = el.querySelector('.s-swatch');
          sw.classList.toggle('s-chroma', typeof v === 'object' && !!v?.chroma);
          sw.setAttribute('style', swatchStyle(v));
          const inp = el.querySelector('input');
          if (document.activeElement !== inp) inp.value = hexOut(v);
          break;
        }
        default: break;
      }
    }
  }
  const put = (key, v, meta = {}) => {
    set(key, v, meta);
    if (root.isConnected) sync(key);
  };

  /** The field shows the stored value even while focused (sync skips focused inputs). */
  const showStep = (el) => {
    el.querySelector('input').value = String(+(+get(el.dataset.key)).toFixed(decimalsOf(el.dataset.step)));
  };
  const stepNum = (el, n, src) => {
    const st = +el.dataset.step, min = +el.dataset.min, max = +el.dataset.max;
    const v = clamp(Math.round((+get(el.dataset.key) + n * st) / st) * st, min, max);
    put(el.dataset.key, +v.toFixed(decimalsOf(st)), { source: src });
    if (el.isConnected) showStep(el);
  };
  const commitStep = (el, inp) => {
    const st = +el.dataset.step, v = parseFloat(inp.value.trim().replace(',', '.'));
    if (Number.isFinite(v)) put(el.dataset.key, +clamp(v, +el.dataset.min, +el.dataset.max).toFixed(decimalsOf(st)), { source: 'type' });
    if (el.isConnected) showStep(el);
  };

  let drag = null;
  const sliderAt = (clientX) => {
    const d = drag, min = +d.el.dataset.min, max = +d.el.dataset.max, st = +d.el.dataset.step;
    const fr = clamp((clientX - d.x0) / Math.max(1, d.x1 - d.x0), 0, 1);
    return +clamp(min + Math.round((fr * (max - min)) / st) * st, min, max).toFixed(decimalsOf(st));
  };
  const onMove = (e) => {
    const v = sliderAt(e.clientX);
    if (v !== drag.last) put(drag.key, (drag.last = v), { source: 'slider', final: false });
  };
  const onUp = (e) => {
    window.removeEventListener('pointermove', onMove);
    window.removeEventListener('pointerup', onUp);
    const v = sliderAt(e.clientX), key = drag.key;
    drag = null;
    put(key, v, { source: 'slider', final: true });
  };

  const onDown = (e) => {
    const el = e.target.closest('[data-s="slider"]');
    if (!el || el.classList.contains('dis') || e.button !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    const tr = el.querySelector('.s-tr').getBoundingClientRect();
    drag = { el, key: el.dataset.key, x0: tr.left, x1: tr.right, last: get(el.dataset.key) };
    onMove(e);
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  const onClick = (e) => {
    const el = e.target.closest('[data-s]');
    if (!el || !root.contains(el) || el.matches('[disabled], .dis')) return;
    const key = el.dataset.key;
    switch (el.dataset.s) {
      case 'toggle':
      case 'tbtn':
      case 'sec': put(key, !get(key)); break;
      case 'btn': if (action) action(key, el); else put(key, true); break;
      case 'seg': {
        const b = e.target.closest('button[data-v]');
        if (b && !b.disabled) put(key, JSON.parse(b.dataset.v));
        break;
      }
      case 'dd':
        if (pop && pop.key === key) closePopup();
        else openDropdown(el, { options: JSON.parse(el.dataset.opts), value: get(key), layer: popLayer, key, onPick: (v) => put(key, v) });
        break;
      case 'step': {
        const b = e.target.closest('button[data-d]');
        if (b) stepNum(el, +b.dataset.d * (e.shiftKey ? 10 : 1), 'step');
        break;
      }
      case 'color':
        if (!e.target.closest('.s-swatch')) break;
        if (pop && pop.key === key) closePopup();
        else openColorPicker(el.querySelector('.s-swatch'), { value: get(key), layer: popLayer, key, onPick: (v, final) => put(key, v, { source: 'picker', final }) });
        break;
      default: break;
    }
  };

  const onWheel = (e) => {
    const el = e.target.closest('[data-s="step"]');
    if (!el || el.classList.contains('dis')) return;
    const raw = e.deltaY || e.deltaX;
    if (!raw) return;
    e.preventDefault();
    e.stopPropagation();
    stepNum(el, -Math.sign(raw) * (e.shiftKey ? 10 : 1), 'wheel');
  };

  const onChange = (e) => {
    const t = e.target;
    const st = t.closest('[data-s="step"]');
    if (st) return commitStep(st, t);
    const col = t.closest('[data-s="color"]');
    if (col) {
      const v = parseHex(t.value);
      if (v) put(col.dataset.key, withColor(get(col.dataset.key), v), { source: 'type' });
      else t.value = hexOut(get(col.dataset.key));
      return;
    }
    if (t.dataset.s === 'text') put(t.dataset.key, t.value, { source: 'type' });
  };

  const onKey = (e) => {
    const t = e.target;
    if (t.tagName !== 'INPUT') return;
    const st = t.closest('[data-s="step"]'), col = t.closest('[data-s="color"]');
    if (!st && !col && t.dataset.s !== 'text') return;
    e.stopPropagation();
    if (e.key === 'Enter') t.blur();
    else if (e.key === 'Escape') {
      e.preventDefault();
      if (st) showStep(st);
      else if (col) t.value = hexOut(get(col.dataset.key));
      else t.value = get(t.dataset.key) ?? '';
      t.blur();
    } else if (st && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      e.preventDefault();
      stepNum(st, (e.key === 'ArrowUp' ? 1 : -1) * (e.shiftKey ? 10 : 1), 'step');
    }
  };

  root.addEventListener('pointerdown', onDown);
  root.addEventListener('click', onClick);
  root.addEventListener('wheel', onWheel, { passive: false });
  root.addEventListener('change', onChange);
  root.addEventListener('keydown', onKey);
  return {
    sync,
    destroy() {
      root.removeEventListener('pointerdown', onDown);
      root.removeEventListener('click', onClick);
      root.removeEventListener('wheel', onWheel);
      root.removeEventListener('change', onChange);
      root.removeEventListener('keydown', onKey);
    },
  };
}
