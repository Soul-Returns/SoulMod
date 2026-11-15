/*
 * The game window: physical window px, Minecraft's GUI scale and Soul's UI scale, drawn as a resizable
 * frame with layers in z-order world → gui (GUI px, scale(g): the HUD) → dim → screen (GUI px: an open
 * screen such as an inventory, above the dim) → soul (Soul px, scale(s)) → top (window px).
 *
 * Exports:
 *   makeEnv(w, h, gui, soul) → { w, h, g, s, gw, gh, W, H, f }   (seed 1:1)
 *   maxGuiScale(w, h)        → largest GUI scale vanilla allows for that window
 *   PRESETS, SOUL_SCALE      window presets, Soul UI scale limits (SoulUi.kt)
 *   new GameWindow(host, { state, onChange })
 *     .env() .set(patch) .render() .update() .layers .controls() .setDim(on)
 *     .toGui(cx, cy) .toSoul(cx, cy) .describe(el) .destroy()
 * Usage: const gw = new GameWindow($('#app'), { state: S.win ??= {}, onChange: draw });
 * The soul layer takes the mouse (editors bind to it); set its pointer-events to none while a screen
 * below it needs hover and clicks (mocks/_example does per scene).
 */
import { clamp, fmt } from '../util.js';

/** Presets: [w, h, name?]. The owner's screen and window first (from the movegui seed). */
export const PRESETS = [[2560, 1440, 'your screen'], [2021, 658, 'your window'], [1920, 1080], [1366, 768], [1280, 720], [900, 520], [1568, 511, 'short-wide'], [700, 420]];
/** SoulUi.MIN_SCALE / MAX_SCALE, config slider step 0.1. `def` is the mock's start value only: the mod stores monitor height / 720 (nearest 0.25) once at a new config's first start (AutoUiScale), 2 on the 1440p "your screen". */
export const SOUL_SCALE = { min: 1, max: 6, step: 0.1, def: 2 };
/** Window.calculateScale: the next scale must leave ≥ 320×240 GUI px. The seed caps Auto at 6. */
const MIN_GUI_W = 320, MIN_GUI_H = 240, AUTO_CAP = 6;
const MIN_WIN = [320, 240], MAX_WIN = [3840, 2160];
const DEFAULT_STATE = { w: 1920, h: 1080, gui: 'auto', soul: SOUL_SCALE.def, zoom: 'fit', dim: false };

export function makeEnv(w, h, gui, soul) {
  let g = gui;
  if (g === 'auto') {
    g = 1;
    while (g < AUTO_CAP && Math.floor(w / (g + 1)) >= MIN_GUI_W && Math.floor(h / (g + 1)) >= MIN_GUI_H) g++;
  }
  const gw = Math.ceil(w / g), gh = Math.ceil(h / g);
  return { w, h, g, s: soul, gw, gh, W: (gw * g) / soul, H: (gh * g) / soul, f: soul / g };
}

/** Same rule as Auto: a fixed GUI scale above this is clamped down by vanilla. */
export const maxGuiScale = (w, h) => makeEnv(w, h, 'auto', 1).g;

export class GameWindow {
  constructor(host, { state = {}, onChange = () => {} } = {}) {
    for (const [k, v] of Object.entries(DEFAULT_STATE)) if (state[k] === undefined) state[k] = v;
    this.host = host;
    this.state = state;
    this.onChange = onChange;
    this.zoom = 1;
    this.resizing = null;
    host.innerHTML = `<div class="gw-root">
      <div class="gw-info"></div>
      <div class="gw-stage"><div class="gw-wrap">
        <div class="gw-frame"><div class="gw-game">
          <div class="gw-world"></div><div class="gw-gui"></div><div class="gw-dim"></div><div class="gw-screen"></div><div class="gw-soul s-layer"></div><div class="gw-top"></div>
        </div></div>
        <div class="gw-rh gw-rh-r" data-gw-rs="r"></div><div class="gw-rh gw-rh-b" data-gw-rs="b"></div><div class="gw-rh gw-rh-c" data-gw-rs="c"></div>
      </div></div></div>`;
    const q = (c) => host.querySelector('.gw-' + c);
    this.root = q('root');
    this.info = q('info');
    this.stage = q('stage');
    this.frame = q('frame');
    this.game = q('game');
    this.layers = { world: q('world'), gui: q('gui'), dim: q('dim'), screen: q('screen'), soul: q('soul'), top: q('top') };
    this._bindResize();
    this.ro = new ResizeObserver(() => this.update());
    this.ro.observe(this.stage);
    this.update();
  }

  /** Vanilla clamps a fixed GUI scale to what fits ([maxGuiScale]); Auto follows the seed rule. */
  env() {
    const { w, h, gui, soul } = this.state;
    return makeEnv(w, h, gui === 'auto' ? 'auto' : Math.min(gui, maxGuiScale(w, h)), soul);
  }

  set(patch) {
    Object.assign(this.state, patch);
    this.update();
    this.onChange(this.state, this.env());
  }

  setDim(on) {
    this.state.dim = !!on;
    this.layers.dim.classList.toggle('on', !!on);
  }

  /** Lays out frame and layers from state; cheap, call any time. */
  update() {
    const S = this.state, E = this.env();
    if (!this.resizing) {
      if (S.zoom === 'fit') {
        const availW = this.stage.clientWidth - 16;
        const availH = this.stage.clientHeight - 16;
        this.zoom = availW > 0 && availH > 0 ? Math.max(0.1, Math.min(1, availW / S.w, availH / S.h)) : 1;
      } else this.zoom = Number(S.zoom) || 1;
    }
    const z = this.zoom;
    this.frame.style.width = S.w * z + 'px';
    this.frame.style.height = S.h * z + 'px';
    Object.assign(this.game.style, { width: S.w + 'px', height: S.h + 'px', transform: `scale(${z})` });
    for (const l of [this.layers.gui, this.layers.screen]) Object.assign(l.style, { width: E.gw + 'px', height: E.gh + 'px', transform: `scale(${E.g})` });
    Object.assign(this.layers.soul.style, { width: E.W + 'px', height: E.H + 'px', transform: `scale(${E.s})` });
    this.layers.soul.style.setProperty('--soul-s', E.s); // soul/hud.js text shadows scale with it
    this.layers.dim.classList.toggle('on', !!S.dim);
    const clamped = S.gui !== 'auto' && S.gui !== E.g ? ` (set ${S.gui}, max ${E.g})` : S.gui === 'auto' ? ' (auto)' : '';
    this.info.innerHTML = `<b>${S.w} × ${S.h}</b><span>MC GUI ${E.g}${clamped} → ${E.gw}×${E.gh} GUI px</span>`
      + `<span>Soul ${fmt(E.s)} → ${fmt(E.W)}×${fmt(E.H)} Soul px</span><span>view ${Math.round(z * 100)}%</span>`;
    return E;
  }

  render() { return this.update(); }

  _bindResize() {
    const move = (e) => {
      const r = this.resizing;
      if (!r) return;
      const w = r.mode !== 'b' ? Math.round(clamp(r.w + (e.clientX - r.cx) / r.zoom, MIN_WIN[0], MAX_WIN[0])) : r.w;
      const h = r.mode !== 'r' ? Math.round(clamp(r.h + (e.clientY - r.cy) / r.zoom, MIN_WIN[1], MAX_WIN[1])) : r.h;
      if (w !== this.state.w || h !== this.state.h) this.set({ w, h });
    };
    const up = () => {
      if (!this.resizing) return;
      this.resizing = null;
      this.root.classList.remove('gw-resizing');
      this.set({});
    };
    for (const el of this.host.querySelectorAll('[data-gw-rs]')) {
      el.addEventListener('pointerdown', (e) => {
        e.preventDefault();
        el.setPointerCapture(e.pointerId);
        // Zoom stays frozen while dragging so the edge tracks the pointer; Fit refits on release.
        this.resizing = { mode: el.dataset.gwRs, cx: e.clientX, cy: e.clientY, w: this.state.w, h: this.state.h, zoom: this.zoom };
        this.root.classList.add('gw-resizing');
      });
      el.addEventListener('pointermove', move);
      el.addEventListener('pointerup', up);
      el.addEventListener('pointercancel', up);
    }
  }

  /** Client (viewport) px → window px. */
  toWindow(clientX, clientY) {
    const r = this.game.getBoundingClientRect(), z = r.width / this.state.w;
    return { x: (clientX - r.left) / z, y: (clientY - r.top) / z, inside: clientX >= r.left && clientX <= r.right && clientY >= r.top && clientY <= r.bottom };
  }

  toGui(clientX, clientY) {
    const p = this.toWindow(clientX, clientY), g = this.env().g;
    return { x: p.x / g, y: p.y / g, inside: p.inside };
  }

  toSoul(clientX, clientY) {
    const p = this.toWindow(clientX, clientY), s = this.env().s;
    return { x: p.x / s, y: p.y / s, inside: p.inside };
  }

  /** An element's rect as [x, y, w, h] in window, GUI and Soul px (rounded to 2 decimals). */
  describe(el) {
    const b = el.getBoundingClientRect(), p = this.toWindow(b.left, b.top), z = this.zoom, E = this.env();
    const win = [p.x, p.y, b.width / z, b.height / z];
    const by = (k) => win.map((v) => Number(fmt(v / k)));
    return { win: by(1), gui: by(E.g), soul: by(E.s), env: E };
  }

  controls() {
    const S = this.state, E = this.env(), max = maxGuiScale(S.w, S.h);
    const guiOpts = [['auto', `Auto (${makeEnv(S.w, S.h, 'auto', 1).g})`]];
    for (let g = 1; g <= AUTO_CAP; g++) guiOpts.push([g, g <= max ? String(g) : `${g}✕`]);
    return [
      { type: 'heading', text: 'Game window' },
      { type: 'buttons', label: 'Presets', buttons: PRESETS.map(([w, h, n]) => ({ label: `${w}×${h}${n ? ` · ${n}` : ''}`, on: S.w === w && S.h === h, onClick: () => this.set({ w, h }) })) },
      { type: 'number', label: 'Width', min: MIN_WIN[0], max: MAX_WIN[0], step: 1, get: () => S.w, set: (v) => this.set({ w: clamp(Math.round(v), MIN_WIN[0], MAX_WIN[0]) }) },
      { type: 'number', label: 'Height', min: MIN_WIN[1], max: MAX_WIN[1], step: 1, get: () => S.h, set: (v) => this.set({ h: clamp(Math.round(v), MIN_WIN[1], MAX_WIN[1]) }) },
      { type: 'seg', label: 'MC GUI scale', options: guiOpts, get: () => S.gui, set: (v) => this.set({ gui: v === 'auto' ? 'auto' : Number(v) }) },
      { type: 'note', html: `Now ${E.g}. ✕ = does not fit this window (vanilla needs ≥ 320×240 GUI px and clamps to ${max}).` },
      { type: 'seg', label: 'Soul UI scale', options: [[1, '1'], [1.5, '1.5'], [2, '2'], [3, '3'], [4, '4']], get: () => S.soul, set: (v) => this.set({ soul: Number(v) }) },
      { type: 'range', label: 'Soul UI scale', min: SOUL_SCALE.min, max: SOUL_SCALE.max, step: SOUL_SCALE.step, format: fmt, get: () => S.soul, set: (v) => this.set({ soul: Math.round(Number(v) * 10) / 10 }) },
      { type: 'seg', label: 'View', options: [['fit', 'Fit'], [1, '100%']], get: () => S.zoom, set: (v) => this.set({ zoom: v === 'fit' ? 'fit' : Number(v) }) },
      { type: 'note', html: 'Drag the frame\'s right / bottom edge or corner to resize the window live.' },
    ];
  }

  destroy() {
    this.ro.disconnect();
    this.host.innerHTML = '';
  }
}
