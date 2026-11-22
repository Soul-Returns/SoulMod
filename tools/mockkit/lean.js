/*
 * Lean mock: one small file per mock. A fixed 1920x1080 game frame (GUI 3, Soul 1.5, scaled to fit
 * the page), designs and scenes as two rows of tabs above it, a few persisted state keys, and the
 * Open questions page. No sandbox, no window or scale controls, no ghost.
 *
 *   import { lean } from '/kit/lean.js';
 *   lean({
 *     title: 'Contest HUD',
 *     frame: { backdrop: 'world' | 'dim' | 'plain', hud: true | { scoreboard, chat, bossBar, actionBar, title… }, gui: 3, soul: 1.5 },   // all optional; hud: true = hotbar + status only
 *     designs: [['a', 'Compact'], ['b', 'Wide']],          // keys 1..9
 *     scenes: [['running', 'Running'], ['done', 'Finished']], // keys ← →
 *     state: { tab: 'history' },                           // defaults of the persisted state
 *     questions: [{ id: 'q1', title, text, options: [{ id, label, proposed: true, design?, scene? }] }], // key Q
 *     css: ['mock.css'],                                   // extra stylesheets (hot reload swaps them)
 *     draw({ design, scene, layers, state, ui, env, first }) {},  // after every design / scene / state change; first = once-only setup (bindInventory)
 *   });
 *
 * layers: { world, gui, dim, screen, soul, top } as in game/window.js (soul = Soul px, screen and gui = GUI px).
 * They are emptied before each draw (scroll positions of scrollable elements are kept); hud keeps the vanilla HUD in gui. Fill them with HTML strings.
 * ui (each returns an attribute string or markup to put inside your HTML):
 *   ui.set(key, value)  click sets state[key]        ui.toggle(key)   click flips state[key]
 *   ui.tip(lines)       hover shows a vanilla tooltip (§ lines)   ui.at(x, y, html, label?)  positioned box (Soul px in soul)
 *   ui.put(key, value)  set a key from your own handler
 * Soul controls from soul/ui.js (toggle, seg, dropdown, ...) bind to state by their key automatically.
 */
import { Mock } from './mock.js';
import { makeEnv } from './game/window.js';
import { renderVanilla, VANILLA_DEFAULTS } from './game/vanilla.js';
import { tooltip } from './mc/tooltip.js';
import { bindSoulUi } from './soul/ui.js';
import { esc } from './util.js';

const W = 1920, H = 1080;
const attr = (v) => esc(JSON.stringify(v));

function loadCss(href) {
  if ([...document.querySelectorAll('link[rel="stylesheet"]')].some((l) => new URL(l.href).pathname === new URL(href, location.href).pathname)) return null;
  return new Promise((done) => {
    const l = Object.assign(document.createElement('link'), { rel: 'stylesheet', href, onload: done, onerror: done });
    document.head.append(l);
  });
}

export async function lean(o) {
  await Promise.all(['/kit/kit.css', '/kit/lean.css', ...(o.css ?? [])].map(loadCss));
  const designs = o.designs ?? [], scenes = o.scenes ?? [], questions = o.questions ?? [], fr = o.frame ?? {};
  const S = Mock.state({ design: designs[0]?.[0] ?? null, scene: scenes[0]?.[0] ?? null, page: 'mock', answers: {}, state: { ...o.state } });
  if (!designs.some((d) => d[0] === S.design)) S.design = designs[0]?.[0] ?? null;
  if (!scenes.some((d) => d[0] === S.scene)) S.scene = scenes[0]?.[0] ?? null;
  if (S.page === 'questions' && !questions.length) S.page = 'mock';
  const E = makeEnv(W, H, fr.gui ?? 3, fr.soul ?? 1.5);

  document.title = o.title ?? 'Mock';
  document.body.innerHTML = `<header id="lean-bar"></header><main id="lean-stage"><div id="lean-wrap"><div class="gw-frame"><div class="gw-game">
    <div class="gw-world"></div><div class="gw-gui"></div><div class="gw-dim"></div><div class="gw-screen"></div><div class="gw-soul s-layer"></div><div class="gw-top"></div><div class="gw-gui lean-tt"></div>
  </div></div></div><pre id="lean-err" hidden></pre><section id="lean-q" data-mock="open-questions" data-mock-label="Open questions (mock only)" hidden></section></main>`;
  const $ = (s) => document.querySelector(s);
  const frame = $('.gw-frame'), game = $('.gw-game'), wrap = $('#lean-wrap'), stage = $('#lean-stage'), errBox = $('#lean-err');
  const q = (c) => $('.gw-' + c);
  const layers = { world: q('world'), gui: q('gui'), dim: q('dim'), screen: q('screen'), soul: q('soul'), top: q('top') };
  const ttLayer = $('.lean-tt');
  Object.assign(game.style, { width: W + 'px', height: H + 'px' });
  for (const l of [layers.gui, layers.screen, ttLayer]) Object.assign(l.style, { width: E.gw + 'px', height: E.gh + 'px', transform: `scale(${E.g})` });
  Object.assign(layers.soul.style, { width: E.W + 'px', height: E.H + 'px', transform: `scale(${E.s})` });
  layers.soul.style.setProperty('--soul-s', E.s);
  const backdrop = fr.backdrop ?? 'world';
  layers.dim.classList.toggle('on', backdrop === 'dim');
  if (backdrop === 'plain') layers.world.style.background = '#16181d';
  // hud: true is hotbar + status only; opt in per piece: { scoreboard: true, chat: true, bossBar: true, actionBar: '§a…', title: '§c…' } (true = the kit's sample content)
  const hudOpts = { ...VANILLA_DEFAULTS, scoreboard: false, chat: false, bossBar: null, actionBar: null, title: null, subtitle: null };
  for (const [k, v] of Object.entries(typeof fr.hud === 'object' ? fr.hud : {})) hudOpts[k] = v === true && VANILLA_DEFAULTS[k] != null ? VANILLA_DEFAULTS[k] : v;

  // ---- state
  const ui = {
    set: (k, v) => `data-lean-set="${esc(k)}" data-lean-val="${attr(v)}"`,
    toggle: (k) => `data-lean-toggle="${esc(k)}"`,
    tip: (lines) => `data-tip="${attr(lines)}"`,
    at: (x, y, html, label) => `<div class="lean-at"${label ? ` data-mock="${esc(label.toLowerCase().replace(/\W+/g, '-'))}" data-mock-label="${esc(label)}"` : ''} style="left:${x}px;top:${y}px">${html}</div>`,
    put: (k, v) => { S.state[k] = v; changed(); },
  };
  function changed() { redraw(); Mock.save(); }
  function go(patch) { Object.assign(S, patch); paintBar(); redraw(); Mock.save(); }

  let first = true;
  // Scroll positions survive a redraw (same design and scene): found by their child-index path.
  const SCROLL = ['screen', 'soul', 'top'];
  let drawn = '';
  function scrolled() {
    const out = [];
    for (const k of SCROLL) for (const el of layers[k].querySelectorAll('*')) {
      if (!el.scrollTop && !el.scrollLeft) continue;
      const path = [];
      for (let n = el; n !== layers[k]; n = n.parentElement) path.push([...n.parentElement.children].indexOf(n));
      out.push([k, path.reverse(), el.scrollTop, el.scrollLeft]);
    }
    return out;
  }
  function redraw() {
    const id = `${S.design}|${S.scene}`;
    const keep = id === drawn ? scrolled() : [];
    for (const k of SCROLL) layers[k].innerHTML = '';
    layers.gui.querySelectorAll(':scope > :not(.v-hud)').forEach((n) => n.remove());
    ttLayer.innerHTML = '';
    if (fr.hud) renderVanilla(layers.gui, E, hudOpts);
    else layers.gui.querySelector(':scope > .v-hud')?.remove();
    try {
      o.draw?.({ design: S.design, scene: S.scene, layers, state: S.state, ui, env: E, first });
      first = false;
      errBox.hidden = true;
    } catch (e) {
      console.error(e);
      errBox.hidden = false;
      errBox.textContent = `draw() failed: ${e.message}`;
    }
    for (const [k, path, top, left] of keep) {
      let el = layers[k];
      for (const i of path) el = el?.children[i];
      if (el) { el.scrollTop = top; el.scrollLeft = left; }
    }
    drawn = id;
  }

  // ---- clicks, tooltips, Soul controls
  game.addEventListener('click', (e) => {
    const t = e.target instanceof Element ? e.target.closest('[data-lean-set],[data-lean-toggle]') : null;
    if (!t) return;
    if (t.dataset.leanSet) S.state[t.dataset.leanSet] = JSON.parse(t.dataset.leanVal);
    else S.state[t.dataset.leanToggle] = !S.state[t.dataset.leanToggle];
    changed();
  });
  const zoom = () => game.getBoundingClientRect().width / W;
  game.addEventListener('pointermove', (e) => {
    const t = e.target instanceof Element ? e.target.closest('[data-tip]') : null;
    if (!t) { ttLayer.innerHTML = ''; return; }
    const r = game.getBoundingClientRect(), k = zoom() * E.g;
    ttLayer.innerHTML = tooltip(JSON.parse(t.dataset.tip), { mx: Math.floor((e.clientX - r.left) / k), my: Math.floor((e.clientY - r.top) / k), sw: E.gw, sh: E.gh });
  });
  game.addEventListener('pointerleave', () => { ttLayer.innerHTML = ''; });
  bindSoulUi(layers.soul, {
    get: (k) => S.state[k],
    set: (k, v, meta = {}) => { S.state[k] = v; if (meta.source === 'slider' && !meta.final) return; changed(); },
  });

  // ---- the bar: designs, scenes, questions
  const bar = $('#lean-bar');
  const openCount = () => questions.filter((x) => !S.answers[x.id]).length;
  const row = (label, hint, list, cur, key, extra = '') => `<div class="lean-row" data-mock="${key}s" data-mock-label="${label} tabs"><span class="lean-k">${label}<small>${hint}</small></span>`
    + `<span class="lean-seg">${list.map(([id, t], i) => `<button type="button" data-${key}="${esc(id)}" aria-pressed="${id === cur}">${key === 'design' ? `<kbd>${i + 1}</kbd>` : ''}${esc(t)}</button>`).join('')}</span>${extra}</div>`;
  function paintBar() {
    const qBtn = questions.length ? `<button type="button" class="lean-qb" data-page="questions" aria-pressed="${S.page === 'questions'}" data-mock="questions-link" data-mock-label="Open questions (mock only)">Open questions<i>${openCount()}</i><kbd>Q</kbd></button>` : '';
    bar.innerHTML = (designs.length ? row('Design', '1–9', designs, S.design, 'design', scenes.length ? '' : qBtn) : '') + (scenes.length ? row('Scene', '← →', scenes, S.scene, 'scene', qBtn) : '')
      + (!designs.length && !scenes.length ? qBtn : '');
    bar.hidden = !bar.innerHTML;
    $('#lean-q').hidden = S.page !== 'questions';
    // the questions page covers the stage instead of removing it, so scroll positions stay
    if (S.page === 'questions') paintQuestions();
  }
  bar.addEventListener('click', (e) => {
    const b = e.target instanceof Element ? e.target.closest('button') : null;
    if (!b) return;
    if (b.dataset.design) go({ design: b.dataset.design, page: 'mock' });
    else if (b.dataset.scene) go({ scene: b.dataset.scene, page: 'mock' });
    else if (b.dataset.page) go({ page: S.page === 'questions' ? 'mock' : 'questions' });
  });
  /** Keys 1-9, ←/→, Q, Esc. The shell calls this for every keydown, whether focus is in the frame or outside it. */
  function key(e) {
    if (e.altKey || e.ctrlKey || e.metaKey || e.target?.closest?.('input,textarea,select,[contenteditable]')) return false;
    const n = Number(e.key);
    if (n >= 1 && n <= designs.length) go({ design: designs[n - 1][0], page: 'mock' });
    else if (e.key.toLowerCase() === 'q' && questions.length) go({ page: S.page === 'questions' ? 'mock' : 'questions' });
    else if (e.key === 'Escape' && S.page === 'questions') go({ page: 'mock' });
    else if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && scenes.length) {
      const i = scenes.findIndex((s) => s[0] === S.scene) + (e.key === 'ArrowRight' ? 1 : -1);
      go({ scene: scenes[(i + scenes.length) % scenes.length][0], page: 'mock' });
    } else return false;
    return true;
  }
  /** For the CLI (`snap --design b`, `set`): { design?, scene?, page? }; throws on an unknown id. */
  function set({ design, scene, page }) {
    if (design && !designs.some((d) => d[0] === design)) throw new Error(`no design "${design}" (have ${designs.map((d) => d[0]).join(', ') || 'none'})`);
    if (scene && !scenes.some((d) => d[0] === scene)) throw new Error(`no scene "${scene}" (have ${scenes.map((d) => d[0]).join(', ') || 'none'})`);
    if (page && page !== 'mock' && !(page === 'questions' && questions.length)) throw new Error(`no page "${page}" (mock${questions.length ? ', questions' : ''})`);
    go({ ...(design ? { design } : {}), ...(scene ? { scene } : {}), page: page ?? 'mock' });
  }

  // ---- the Open questions page (mock only)
  function paintQuestions() {
    const open = openCount();
    $('#lean-q').innerHTML = `<div class="lq-in"><h2>Open questions <span class="lq-chip">MOCK ONLY</span></h2>
      <p class="lq-lead">${open} open, ${questions.length - open} answered. An answer goes to the chat with its number; comment on anything else with Alt+C.</p>`
      + questions.map((x, i) => `<div class="lq-card" data-mock="question-${i + 1}" data-mock-label="Question ${i + 1}: ${esc(x.title)}"><div class="lq-h"><span class="lq-n">${i + 1}</span><b>${esc(x.title)}</b></div>
        <p>${esc(x.text ?? '')}</p><div class="lq-o">${x.options.map((op) => `<button type="button" class="${S.answers[x.id] === op.id ? 'on' : ''}" data-q="${esc(x.id)}" data-o="${esc(op.id)}">${esc(op.label)}${op.proposed ? ' <small>(proposed)</small>' : ''}</button>`).join('')}</div></div>`).join('') + '</div>';
  }
  $('#lean-q').addEventListener('click', (e) => {
    const b = e.target instanceof Element ? e.target.closest('button[data-q]') : null;
    if (!b) return;
    const i = questions.findIndex((x) => x.id === b.dataset.q), op = questions[i].options.find((x) => x.id === b.dataset.o);
    S.answers[questions[i].id] = op.id;
    go({ ...(op.design ? { design: op.design } : {}), ...(op.scene ? { scene: op.scene } : {}) });
    Mock.send(`Q${i + 1} (${questions[i].title}): ${op.label}`);
  });

  // ---- fit, describe, register
  const fit = () => {
    const r = stage.getBoundingClientRect(), z = Math.max(0.1, Math.min(1, (r.width - 24) / W, (r.height - 24) / H));
    Object.assign(wrap.style, { width: W * z + 'px', height: H * z + 'px' });
    Object.assign(frame.style, { width: W * z + 'px', height: H * z + 'px' });
    game.style.transform = `scale(${z})`;
  };
  new ResizeObserver(fit).observe(stage);
  Mock.describe((el) => {
    if (!game.contains(el)) return null;
    const b = el.getBoundingClientRect(), g = game.getBoundingClientRect(), z = zoom();
    const win = [(b.left - g.left) / z, (b.top - g.top) / z, b.width / z, b.height / z];
    const by = (k) => win.map((v) => Math.round((v / k) * 10) / 10);
    return { win: by(1), gui: by(E.g), soul: by(E.s) }; // [x, y, w, h] in 1920x1080 frame px, GUI px, Soul px
  });
  Mock.render(redraw);
  Mock.lean({ designs, scenes, questions, design: () => S.design, scene: () => S.scene, key, get: () => ({ design: S.design, scene: S.scene, page: S.page }), set });
  fit();
  paintBar();
  redraw();
}
