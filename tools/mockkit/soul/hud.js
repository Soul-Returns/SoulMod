// Soul HUD content: what a MovableHud's drawContent puts on screen, at HUD scale 1 in Soul px (wrap it
// in a positioned box and scale that with transform). Metrics come from ui/hud/tracker/TrackerHud.kt
// (+ TrackerModel.kt), ui/hud/alert/AlertHud.kt, features/notifications/NotificationHud.kt and
// features/mobhealth/MobHealthHud.kt.
//
// Exports: TRACKER, ALERT, NOTIF, MOB, trackerPanel(o), trackerSize(o) → [w, h], alertHud(o), notificationHud(o),
//   mobHealthHud(o), card(inner, o), textHud(lines, o), tint(hex).
// Usage: el.innerHTML = trackerPanel({ title: 'Sacks', timer: '00:12:41', tab: 'Session',
//          columns: [{ width: 38, color: '#eee' }], rows: [{ label: 'Mithril', color: '#55ff55', cells: ['1,204'] }] });

import { esc, mcText } from '../util.js';
import { FONTS, LEGACY_EM, soulPx } from './ui.js';

/** TrackerHud companion constants (legacy SoulTheme metrics). Sizes are legacy em (CSS px in Baloo 2). */
export const TRACKER = {
  PAD: 12, GAP: 6, HEADER_GAP: 4, ROW_GAP: 2, CHIP_GAP: 8,
  LINE_BODY: 11, LINE_HEADING: 13,
  CONTROL_H: 27, CONTROL_PAD_H: 10, TAB_GAP: 4, CARET_W: 7, CARET_H: 4,
  LIST_HEIGHT: 140, COL_DIVIDER_H: 9, COL_DIVIDER_TOP: 2, ICON_SIZE: 10, ICON_GAP: 3,
  WIDTH: 280, // TrackerSpec.width default (Sacks 270, Nucleus 300)
  CELL_W: 32, // TrackerColumn.cellWidth default
  EM_HEADING: 13, EM_BODY: 11, EM_CAPTION: 10,
  BG: 'rgba(26,26,26,.847)', // hudBackgroundColor default 0xD81A1A1A, ROUND_MEDIUM (r 6)
  PAUSED: '#ff5555',
};
/** AlertHud: nominal SoulText sizes, line = (size * 1.3).toInt(), semibold. */
export const ALERT = { TITLE_SIZE: 24, SUBTITLE_SIZE: 14, MORE_SIZE: 12, GAP: 4, MORE_GAP: 6, MORE_COLOR: '#c0c0c0', RED: '#ff5555', FADE_MS: 500 };
/** NotificationHud: SIZE 10, PAD 5, wrap at 220, 3 px between toasts, card 0x90000000 ROUND_SMALL. */
export const NOTIF = { SIZE: 10, PAD: 5, MAX_WIDTH: 220, GAP: 3, BG: 'rgba(0,0,0,.565)' };
/** MobHealthHud: name 10 / small 8 (line = size * 9 / 8), PAD 5, GAP 3, 4 px pill bar on a 0x60FFFFFF track, min width 110, card 0x90000000 ROUND_SMALL. */
export const MOB = { NAME: 10, SMALL: 8, PAD: 5, GAP: 3, BAR_H: 4, MIN_W: 110, TRACK: 'rgba(255,255,255,.376)', DIM: '#aaaaaa',
  GREEN: '#55ff55', YELLOW: '#ffff55', RED: '#ff5555', BG: 'rgba(0,0,0,.565)' };

const T = TRACKER;
const line = (size) => Math.floor(size * 1.3);
const em = (e, font) => soulPx(e * LEGACY_EM, font);
const fam = (font) => FONTS[font] || FONTS.baloo;

/** AlertHud.tint: each channel halfway to white (FF5555 → FFAAAA). */
export function tint(hex) {
  const v = parseInt(hex.slice(1), 16);
  const ch = (s) => (((v >> s) & 255) + 255) >> 1;
  return '#' + [16, 8, 0].map((s) => ch(s).toString(16).padStart(2, '0')).join('');
}

const listHeight = (o) => {
  const n = (o.rows || []).length;
  if (o.scrollable ?? true) return o.listHeight ?? T.LIST_HEIGHT;
  return n ? n * T.LINE_BODY + (n - 1) * T.ROW_GAP : T.LINE_BODY;
};
const footerHeight = (o) => {
  const f = o.footer || {};
  return (f.selects || []).length * (T.CONTROL_H + T.GAP) + (f.filter ? T.CONTROL_H + T.GAP : 0) + T.CONTROL_H + (f.reset ? T.GAP + T.CONTROL_H : 0);
};

/** TrackerHud.contentSize for the same options as trackerPanel: [width, height]. */
export function trackerSize(o) {
  let h = T.PAD + T.LINE_HEADING + T.HEADER_GAP + (o.headerLines || []).length * (T.LINE_BODY + T.HEADER_GAP);
  h += o.interactive ? T.CONTROL_H : T.LINE_BODY;
  if (!o.hideList) h += T.GAP * 2 + 1 + listHeight(o) + ((o.chips || []).length ? T.GAP + T.LINE_BODY : 0);
  if (o.interactive) h += T.GAP * 2 + 1 + footerHeight(o);
  return [o.width ?? T.WIDTH, h + T.PAD];
}

const caret = () => '<i class="s-tp-car"></i>';
const trigger = (label, w) => `<div class="s-tp-trg" style="width:${w}px"><span>${esc(label)}</span>${caret()}</div>`;

/**
 * The shared tracker panel. o: { title, width = 280, headerLines: [html], timer: text | { text, paused },
 * tab: 'Session', tabs: [names] (strip only when interactive), columns: [{ width = 32, caption, color }],
 * rows: [{ label, color, icon: css color | html, cells: [text] }], emptyText, scrollable = true,
 * listHeight = 140, scroll = 0, chips: [text], hideList, interactive (inventory open + hovered: tab strip,
 * row ×, footer), footer: { selects: [label], filter: label, sort: label, reset: label },
 * background = true, bgColor, shadow = true, font = 'baloo' }.
 */
export function trackerPanel(o) {
  const [w, h] = trackerSize(o);
  const font = o.font || 'baloo';
  const body = em(T.EM_BODY, font), cap = em(T.EM_CAPTION, font);
  const cols = o.columns || [];
  const contentW = w - T.PAD * 2;
  const bg = o.background ?? true ? `background:${o.bgColor || T.BG};` : '';
  let s = `<div class="s-hud s-tp${o.shadow ?? true ? ' sh' : ''}" style="width:${w}px;height:${h}px;${bg}font-family:${fam(font)};font-size:${body}px">`;

  s += `<div class="s-tp-t" style="font-size:${em(T.EM_HEADING, font)}px">${esc(o.title || '')}</div>`;
  for (const l of o.headerLines || []) s += `<div class="s-tp-hx">${l}</div>`;

  const timer = typeof o.timer === 'string' ? { text: o.timer } : o.timer;
  const tm = timer ? `<span class="s-tp-tm">${esc(timer.text)}</span>${timer.paused ? '<span class="s-tp-pz">(Paused)</span>' : ''}` : '';
  let right = '';
  if (o.interactive) {
    right = `<span class="s-tp-tabs">${(o.tabs || []).map((t) => `<span class="s-tp-tab${t === o.tab ? ' on' : ''}">${esc(t)}</span>`).join('')}</span>`;
  } else if (o.tab) right = `<span class="s-tp-tabn">${esc(o.tab)}</span>`;
  s += `<div class="s-tp-h" style="height:${o.interactive ? T.CONTROL_H : T.LINE_BODY}px"><span class="s-tp-tml">${tm}</span>${right}</div>`;

  if (!o.hideList) {
    s += '<div class="s-tp-sep"></div>';
    const rows = o.rows || [];
    let list = '';
    if (!rows.length) list = `<div class="s-tp-empty">${esc(o.emptyText || 'Nothing tracked yet')}</div>`;
    for (const r of rows) {
      let cells = '';
      cols.forEach((c, i) => {
        if (i > 0) cells += '<i class="s-tp-d"></i>';
        const color = c.color || (c.caption ? '#888888' : '#3b82f6'); // TrackerColumn.resolvedCellColor
        cells += `<span class="s-tp-c" style="width:${c.width ?? T.CELL_W}px;color:${color}${c.caption ? `;font-size:${cap}px` : ''}">${esc((r.cells || [])[i] ?? '')}</span>`;
      });
      const icon = r.icon === undefined ? '' : /^(#|rgb|hsl)/.test(r.icon) ? `<i class="s-tp-ic" style="background:${r.icon}"></i>` : `<i class="s-tp-ic">${r.icon}</i>`;
      list += `<div class="s-tp-r">${o.interactive && o.removable ? '<i class="s-tp-rm"></i>' : ''}${icon}<span class="s-tp-l" style="color:${r.color || '#eeeeee'}">${esc(r.label)}</span>${cells}</div>`;
    }
    s += `<div class="s-tp-list" style="height:${listHeight(o)}px"><div class="s-tp-rows" style="transform:translateY(${-(o.scroll || 0)}px)">${list}</div></div>`;
    if ((o.chips || []).length) {
      s += `<div class="s-tp-chips${o.chips.length === 1 ? ' one' : ''}">${o.chips.map((c) => `<span>${esc(c)}</span>`).join('')}</div>`;
    }
  }

  if (o.interactive) {
    const f = o.footer || {};
    s += '<div class="s-tp-sep"></div><div class="s-tp-foot">';
    for (const l of f.selects || []) s += `<div class="s-tp-fr">${trigger(l, contentW)}</div>`;
    if (f.filter) {
      const unit = Math.floor((contentW - T.GAP) / 3); // weights 2 : 1
      s += `<div class="s-tp-fr">${trigger(f.filter, contentW - T.GAP - unit)}<div class="s-tp-btn" style="width:${unit}px">Show All</div></div>`;
    }
    const half = Math.floor((contentW - T.GAP) / 2);
    s += `<div class="s-tp-fr">${f.sort ? trigger(`Sort: ${f.sort}`, half) + trigger('Columns', contentW - half - T.GAP) : trigger('Columns', contentW)}</div>`;
    if (f.reset) s += `<div class="s-tp-fr rs"><div class="s-tp-btn" style="width:${contentW}px">${esc(f.reset)}</div></div>`;
    s += '</div>';
  }
  return s + '</div>';
}

/**
 * AlertHud: centered semibold title in the alert color, a grey `+N` after it when more wait, and the
 * subtitle tinted halfway to white. o: { title, subtitle, color = '#ff5555', more = 0, alpha = 1,
 * shadow = true, font = 'baloo' }. Height: 31 (+ 4 + 18 with a subtitle).
 */
export function alertHud({ title = 'Alert!', subtitle = 'Feature warnings show here', color = ALERT.RED, more = 0, alpha = 1, shadow = true, font = 'baloo' } = {}) {
  const tl = line(ALERT.TITLE_SIZE), sl = line(ALERT.SUBTITLE_SIZE);
  // `+N` shares the title's cap top: lift it by the cap-height difference (caps are 7/8 of the size).
  const lift = (ALERT.TITLE_SIZE - ALERT.MORE_SIZE) * 0.875;
  const plus = more > 0 ? `<span class="s-al-m" style="font-size:${soulPx(ALERT.MORE_SIZE, font)}px;margin-left:${ALERT.MORE_GAP}px;top:-${lift}px">+${more}</span>` : '';
  const sub = subtitle ? `<div class="s-al-s" style="margin-top:${ALERT.GAP}px;height:${sl}px;line-height:${sl}px;font-size:${soulPx(ALERT.SUBTITLE_SIZE, font)}px;color:${tint(color)}">${esc(subtitle)}</div>` : '';
  return `<div class="s-hud s-alert${shadow ? ' sh' : ''}" style="font-family:${fam(font)};opacity:${alpha}"><div class="s-al-t" style="height:${tl}px;line-height:${tl}px;font-size:${soulPx(ALERT.TITLE_SIZE, font)}px;color:${color}">${esc(title)}${plus}</div>${sub}</div>`;
}

/**
 * NotificationHud toast stack, newest last. Every card is as wide as the widest (the real one draws
 * all backgrounds at the stack's width). o: { cards: [{ text, color }], background = true,
 * bgColor = '#00000090', bold = false, shadow = true, font = 'baloo' }.
 */
export function notificationHud({ cards, background = true, bgColor = NOTIF.BG, bold = false, shadow = true, font = 'baloo' } = {}) {
  cards = cards || [
    { text: 'Scheduled maintenance in 10 minutes.', color: '#ffd744' },
    { text: 'Your profile sync is up to date.', color: '#ffffff' },
  ];
  const lh = line(NOTIF.SIZE);
  const cs = cards.map((c) => `<div class="s-nc" style="padding:${NOTIF.PAD}px;max-width:${NOTIF.MAX_WIDTH + NOTIF.PAD * 2}px;color:${c.color || '#fff'};${background ? `background:${bgColor};` : ''}">${esc(c.text)}</div>`).join('');
  return `<div class="s-hud s-notif${shadow ? ' sh' : ''}${bold ? ' b' : ''}" style="gap:${NOTIF.GAP}px;font-family:${fam(font)};font-size:${soulPx(NOTIF.SIZE, font)}px;line-height:${lh}px">${cs}</div>`;
}

/**
 * MobHealthHud card: name (§ text) with `Lv<level>` dim on the right, a health bar colored like Hypixel's
 * numbers (green > ½, yellow > ¼, red), then `current / max` and the percentage. o: { name, level,
 * current, max, fraction, background = true, shadow = true, font = 'baloo' }.
 */
export function mobHealthHud({ name = 'Voidgloom Seraph IV', level = 655, current = '68.6M', max = '210M', fraction = 68.6 / 210, background = true, shadow = true, font = 'baloo' } = {}) {
  const M = MOB, nl = Math.round((M.NAME * 9) / 8), sl = Math.round((M.SMALL * 9) / 8);
  const color = fraction == null || fraction > 0.5 ? M.GREEN : fraction > 0.25 ? M.YELLOW : M.RED;
  const small = `font-size:${soulPx(M.SMALL, font)}px;height:${sl}px;line-height:${sl}px`;
  const bar = fraction == null ? '' : `<div style="height:${M.BAR_H}px;margin-bottom:${M.GAP}px;border-radius:${M.BAR_H / 2}px;background:${M.TRACK};position:relative">`
    + `<i style="position:absolute;left:0;top:0;bottom:0;width:max(${M.BAR_H}px,${(fraction * 100).toFixed(2)}%);border-radius:${M.BAR_H / 2}px;background:${color}"></i></div>`;
  return `<div class="s-hud${shadow ? ' sh' : ''}" style="display:inline-block;min-width:${M.MIN_W + M.PAD * 2}px;padding:${M.PAD}px;border-radius:4px;${background ? `background:${M.BG};` : ''}font-family:${fam(font)}">`
    + `<div style="display:flex;justify-content:space-between;gap:8px;align-items:flex-end;height:${nl}px;margin-bottom:${M.GAP}px"><span style="font-size:${soulPx(M.NAME, font)}px;line-height:${nl}px">${mcText(name)}</span>`
    + `<span style="color:${M.DIM};${small}">${level != null ? `Lv${level}` : ''}</span></div>${bar}`
    + `<div style="display:flex;justify-content:space-between;gap:8px;${small}"><span style="color:${color}">${esc(max != null ? `${current} / ${max}` : current)}</span>`
    + `<span style="color:${M.DIM}">${fraction != null ? `${(fraction * 100).toFixed(1)}%` : ''}</span></div></div>`;
}

/** Generic HUD card (0x90000000, ROUND_SMALL r 4) around [inner] HTML. o: { background, bgColor, pad = 4, radius = 4, cls, style }. */
export function card(inner, { background = true, bgColor = NOTIF.BG, pad = 4, radius = 4, cls = '', style = '' } = {}) {
  return `<div class="s-hud s-card ${cls}" style="padding:${pad}px;border-radius:${radius}px;${background ? `background:${bgColor};` : ''}${style}">${inner}</div>`;
}

/**
 * Lines of § formatted text (SoulRichText) on an optional card. o: { size = 10 (SoulText nominal),
 * lineH = size * 1.3, color = '#fff', align = 'left', bold, shadow = true, font, background = true,
 * bgColor, pad = 4 }.
 */
export function textHud(lines, { size = 10, lineH, color = '#ffffff', align = 'left', bold = false, shadow = true, font = 'baloo', ...cardOpts } = {}) {
  const lh = lineH ?? line(size);
  const inner = lines.map((l) => `<div>${mcText(l)}</div>`).join('');
  const st = `color:${color};text-align:${align};font-family:${fam(font)};font-size:${soulPx(size, font)}px;line-height:${lh}px;`;
  return card(inner, { ...cardOpts, cls: `s-text${shadow ? ' sh' : ''}${bold ? ' b' : ''}`, style: st + (cardOpts.style || '') });
}
