/*
 * Vanilla HUD at real GUI-px positions (Gui / ChatComponent / BossHealthOverlay geometry) and the rects
 * Soul attaches to (VanillaHudLayout.build, converted to Soul px).
 *
 * Exports:
 *   VANILLA_IDS, AB_W, SB_W, CHAT_W, CHAT_H          ids + geometry constants
 *   VANILLA_DEFAULTS                                 default content/toggles; deep-copy and edit
 *   vanillaRects(E, opts) → { gui: {id: [x0,y0,x1,y1] GUI px}, <id>: { id, name, hidden, rect, collapsed } (Soul px) }
 *   renderVanilla(guiLayer, E, opts)                 draws into a .v-hud child of the GUI layer
 *   chatOpacity(ageTicks)                            vanilla's chat fade curve
 * Usage: renderVanilla(win.layers.gui, win.env(), { ...VANILLA_DEFAULTS, bossBar: null });
 */
import { mcText } from '../util.js';

export const VANILLA_IDS = [
  { id: 'hotbar', name: 'Hotbar' },
  { id: 'status', name: 'Health / food rows' },
  { id: 'actionbar', name: 'Action bar' },
  { id: 'chat', name: 'Chat' },
  { id: 'scoreboard', name: 'Scoreboard' },
  { id: 'bossbar', name: 'Boss bar' },
];
/** VanillaHudLayout.ACTION_BAR_W: fixed slot so links stay stable while the text varies. */
export const AB_W = 220;
/** Sidebar text width stand-in (the real one is the widest line in MC's font); seed value. */
export const SB_W = 120;
/** ChatComponent.getWidth(1.0) = 320, + 12 * chatScale as VanillaHudLayout adds; getHeight(0.44 unfocused) = 90. */
export const CHAT_W = 332, CHAT_H = 90;
const LINE = 9; // Font.lineHeight
const HOTBAR_W = 182, HALF = 91;
const CHAT_BOTTOM = 40; // chat sits 40 GUI px above the bottom
const CHAT_FADE_TICKS = 200; // messages fade after 10 s unless chat is open
const CHAT_TEXT_INSET = 4;
const CHAT_BG = 0.5; // accessibility "text background opacity" default
const SB_LINE_BG = 0.3, SB_TITLE_BG = 0.4; // Options.getBackgroundColor(0.3f / 0.4f) in displayScoreboardSidebar
const BOSS_STEP = 19; // 10 + font.lineHeight between stacked boss bars
/** BossEvent.BossBarColor → approximate sprite fill colors. */
export const BOSS_COLORS = { pink: '#ec4fc8', blue: '#2bb5e3', red: '#e8403a', green: '#45d636', yellow: '#e5d933', purple: '#8a35d8', white: '#e8e8e8' };
/** Gui.renderTitle: title at scale 4 drawn at y -10, subtitle at scale 2 drawn at y 5, around the screen center. */
const TITLE_SCALE = 4, SUB_SCALE = 2, TITLE_Y = -10, SUB_Y = 5;

export const VANILLA_DEFAULTS = {
  hotbar: true,
  selected: 0,
  // Per slot: null (empty), a CSS color (placeholder item) or { html } (e.g. an item from the mc slice).
  items: ['#5fa8ff', '#d04040', null, '#888888', '#b08040', null, '#9b5bd6', '#aaaaaa', '#ddffff'],
  status: true,
  health: 20, maxHealth: 20, absorption: 8, armor: 14, food: 20, xpLevel: 412, xpProgress: 0.63,
  actionBar: '§c6,267/5,892❤     §a582❈ Defense     §b1,498/1,498✎ Mana',
  chat: true,
  chatOpen: false,
  chatWidth: 320, // ChatComponent.getWidth: GUI px of text, before the +12 inset
  // { text, age } with age in ticks since received; age ≥ 200 is faded out unless the chat is open.
  chatLines: [
    { text: '§2[NPC] §fEmissary Braum§f: Welcome back!', age: 40 },
    { text: '§aYour profile sync is up to date.', age: 20 },
    { text: '§6§lEVENT! §eMithril Powder and Gemstone Powder drops are boosted!', age: 0 },
  ],
  scoreboard: true,
  scoreTitle: '§e§lSKYBLOCK',
  scoreLines: ['§711/08/26 §8m23AR', '', 'Late Winter 10th', '§72:10pm §e☀', ' §b⏣ Dwarven Village', '', 'Purse: §61,193,004,241', 'Bits: §b13,845', '', 'Slayer Quest', '§cSven Packmaster IV', ' §7(§e1,864§7/§c5k§7) Combat XP', '', '§ewww.hypixel.net'],
  scoreCount: null, // null = all lines (max 15, as vanilla); a number shows that many
  scoreNumbers: false, // vanilla's red score numbers (Hypixel hides them)
  scoreWidth: SB_W,
  bossBar: [{ name: '§dEnder Dragon', progress: 0.72, color: 'pink' }],
  title: null, // e.g. '§cRARE DROP!'
  subtitle: null,
};

const shownScoreLines = (o) => o.scoreLines.slice(0, Math.min(15, o.scoreCount ?? o.scoreLines.length));
const bossList = (o) => (o.bossBar ? (Array.isArray(o.bossBar) ? o.bossBar : [o.bossBar]) : []);

/** The seed's vanillaRects with opts instead of mock state. rect/collapsed in Soul px (× g / s). */
export function vanillaRects(E, opts = {}) {
  const o = { ...VANILLA_DEFAULTS, ...opts };
  const { gw, gh } = E, k = E.g / E.s, cx = Math.floor(gw / 2);
  const toS = (r) => [r[0] * k, r[1] * k, r[2] * k, r[3] * k];
  const n = o.scoreboard ? shownScoreLines(o).length : 0;
  let sb = null;
  if (n > 0) {
    const listH = n * LINE, bottom = Math.floor(gh / 2) + Math.floor(listH / 3), left = gw - o.scoreWidth - 3;
    sb = [left - 2, bottom - listH - LINE - 1, gw - 1, bottom];
  }
  const gui = {
    hotbar: [cx - HALF, gh - 22, cx + HALF, gh],
    status: [cx - HALF, gh - 39, cx + HALF, gh - 22],
    actionbar: [cx - AB_W / 2, gh - 72, cx + AB_W / 2, gh - 63],
    chat: [0, gh - CHAT_BOTTOM - CHAT_H, o.chatWidth + 12, gh - CHAT_BOTTOM],
    scoreboard: sb,
    bossbar: [cx - HALF, 3, cx + HALF, 17],
  };
  const collapsed = { scoreboard: [gw - 1, Math.floor(gh / 2), gw - 1, Math.floor(gh / 2)], bossbar: [cx - HALF, 0, cx + HALF, 0] };
  const V = { gui };
  for (const v of VANILLA_IDS) {
    const hidden = (v.id === 'scoreboard' && !sb) || (v.id === 'bossbar' && !bossList(o).length);
    const raw = gui[v.id] || collapsed[v.id];
    V[v.id] = { id: v.id, name: v.name, hidden, rect: toS(raw), collapsed: toS(collapsed[v.id] || raw) };
  }
  return V;
}

/** ChatComponent: alpha = clamp((1 - age / 200) * 10, 0, 1)². */
export function chatOpacity(age) {
  const d = Math.min(1, Math.max(0, (1 - age / CHAT_FADE_TICKS) * 10));
  return d * d;
}

const at = (x, y, w, h, extra = '') => `left:${x}px;top:${y}px;${w != null ? `width:${w}px;` : ''}${h != null ? `height:${h}px;` : ''}${extra}`;
const icon = (cls, x, y, extra = '') => `<i class="${cls}" style="${at(x, y, null, null, extra)}"></i>`;

function hotbarHtml(o, cx, gh) {
  let h = `<div class="v-hotbar" style="${at(cx - HALF, gh - 22, HOTBAR_W, 22)}">`;
  o.items.forEach((it, i) => {
    const x = 3 + i * 20; // item at hotbar x + 3 + i*20, y + 3, 16×16
    if (it && typeof it === 'object') h += `<div class="v-item" style="${at(x, 3, 16, 16)}">${it.html}</div>`;
    else if (it) h += `<b class="v-item" style="${at(x + 2, 5, 12, 12, `background:${it}`)}"></b>`;
  });
  h += `<i class="v-sel" style="${at(-1 + o.selected * 20, -1, 24, 23)}"></i></div>`;
  return h;
}

/** Gui.renderPlayerHealth geometry: hearts/armor left of center, food right, rows stack upwards. */
function statusHtml(o, cx, gh) {
  const left = cx - HALF, right = cx + HALF, top = gh - 39;
  const hearts = Math.ceil(o.maxHealth / 2), absorb = Math.ceil(o.absorption / 2);
  const rows = Math.ceil((o.maxHealth + o.absorption) / 2 / 10);
  const rowH = Math.max(10 - (rows - 2), 3);
  let h = '';
  for (let i = hearts + absorb - 1; i >= 0; i--) {
    const x = left + (i % 10) * 8, y = top - Math.floor(i / 10) * rowH;
    let cls = 'v-heart';
    if (i >= hearts) cls += (i - hearts) * 2 + 1 < o.absorption ? ' gold' : ' gold half';
    else if (i * 2 + 1 < o.health) cls += ' full';
    else if (i * 2 + 1 === o.health) cls += ' half';
    h += icon(cls, x, y);
  }
  if (o.armor > 0) {
    const y = top - (rows - 1) * rowH - 10;
    for (let i = 0; i < 10; i++) h += icon(`v-armor ${i * 2 + 1 < o.armor ? 'full' : i * 2 + 1 === o.armor ? 'half' : ''}`, left + i * 8, y);
  }
  for (let i = 0; i < 10; i++) h += icon(`v-food ${i * 2 + 1 < o.food ? 'full' : i * 2 + 1 === o.food ? 'half' : ''}`, right - i * 8 - 9, top);
  h += `<div class="v-xp" style="${at(left, gh - 29, HOTBAR_W, 5)}"><b style="width:${Math.round(o.xpProgress * HOTBAR_W)}px"></b></div>`;
  if (o.xpLevel > 0) h += `<div class="v-text v-xpnum" style="${at(cx, gh - 35, null, null)}">${o.xpLevel}</div>`;
  return h;
}

function chatHtml(o, gh) {
  const max = Math.floor((o.chatOpen ? 180 : CHAT_H) / LINE); // chatHeightFocused 1.0 → 180
  const lines = o.chatLines.slice(-max).reverse();
  let h = '';
  lines.forEach((l, i) => {
    const a = o.chatOpen ? 1 : chatOpacity(l.age ?? 0);
    if (a <= 0.01) return;
    const y = gh - CHAT_BOTTOM - (i + 1) * LINE;
    h += `<div class="v-chatline v-text" style="${at(0, y, o.chatWidth + 12, LINE, `background:rgba(0,0,0,${CHAT_BG * a});opacity:${0.1 + 0.9 * a}`)}">`
      + `<span style="left:${CHAT_TEXT_INSET}px">${mcText(l.text, { base: 'f' })}</span></div>`;
  });
  return h;
}

function scoreHtml(o, r) {
  const lines = shownScoreLines(o), n = lines.length;
  let h = `<div class="v-score v-text" style="${at(r[0], r[1], r[2] - r[0] + 1, r[3] - r[1])}">`
    + `<div class="v-score-t" style="background:rgba(0,0,0,${SB_TITLE_BG})">${mcText(o.scoreTitle, { base: 'f' })}</div>`
    + `<div class="v-score-gap" style="background:rgba(0,0,0,${SB_LINE_BG})"></div>`;
  lines.forEach((l, i) => {
    h += `<div class="v-score-l" style="background:rgba(0,0,0,${SB_LINE_BG})"><span>${mcText(l, { base: 'f' }) || '&nbsp;'}</span>`
      + (o.scoreNumbers ? `<span class="v-score-n">${n - i}</span>` : '') + '</div>';
  });
  return h + '</div>';
}

function bossHtml(o, cx) {
  return bossList(o).map((b, i) => {
    const y = 12 + i * BOSS_STEP, c = BOSS_COLORS[b.color] || b.color || BOSS_COLORS.pink;
    return `<div class="v-boss-name v-text" style="${at(cx, y - LINE, null, null)}">${mcText(b.name, { base: 'f' })}</div>`
      + `<div class="v-boss-bar" style="${at(cx - HALF, y, HOTBAR_W, 5, `--c:${c}`)}"><b style="width:${Math.round((b.progress ?? 1) * HOTBAR_W)}px"></b></div>`;
  }).join('');
}

function titleHtml(o, gw, gh) {
  let h = `<div class="v-titles" style="${at(gw / 2, gh / 2, 0, 0)}">`;
  if (o.title) h += `<div class="v-title v-text" style="top:${TITLE_Y * TITLE_SCALE}px;--k:${TITLE_SCALE}">${mcText(o.title, { base: 'f' })}</div>`;
  if (o.subtitle) h += `<div class="v-title v-text" style="top:${SUB_Y * SUB_SCALE}px;--k:${SUB_SCALE}">${mcText(o.subtitle, { base: 'f' })}</div>`;
  return h + '</div>';
}

/** Draws the vanilla HUD into a `.v-hud` child of [guiLayer] (other GUI-layer children stay). Idempotent. */
export function renderVanilla(guiLayer, E, opts = {}) {
  const o = { ...VANILLA_DEFAULTS, ...opts };
  let root = guiLayer.querySelector(':scope > .v-hud');
  if (!root) {
    root = document.createElement('div');
    root.className = 'v-hud';
    guiLayer.prepend(root);
  }
  const { gw, gh } = E, cx = Math.floor(gw / 2), V = vanillaRects(E, o);
  let h = '';
  if (o.chat) h += chatHtml(o, gh);
  if (o.scoreboard && V.gui.scoreboard) h += scoreHtml(o, V.gui.scoreboard);
  if (bossList(o).length) h += bossHtml(o, cx);
  if (o.title || o.subtitle) h += titleHtml(o, gw, gh);
  if (o.actionBar) h += `<div class="v-action v-text" style="${at(cx, gh - 72, null, null)}">${mcText(o.actionBar, { base: 'f' })}</div>`;
  if (o.status) h += statusHtml(o, cx, gh);
  if (o.hotbar) h += hotbarHtml(o, cx, gh);
  if (root._sig !== h) {
    root._sig = h;
    root.innerHTML = h;
  }
  return V;
}
