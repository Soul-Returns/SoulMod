/*
 * The mock runtime: what a mock's script talks to. Runs inside the mock's frame; the drawer shell
 * (shell/shell.js, the parent page) reads it through window.__mock, so both stay in sync without a
 * message protocol. Works without the shell too (mock opened at /m/ directly), minus the drawer.
 *
 *   import { Mock } from '/kit/mock.js';
 *   const S = Mock.state({ rows: 6, win: {} }, { skip: ['drag'] }); // survives hot reloads
 *   Mock.render(draw);                         // called on variant / control / ghost changes
 *   Mock.variants([['current', 'Current'], ['proposed', 'Proposed']]);  // A/B switch in the drawer
 *   Mock.send('text');                                                  // a message from the mock's own button
 *   Mock.controls(() => [{ type: 'range', label: 'Rows', min: 1, max: 9, get: () => S.rows, set: (v) => (S.rows = v) }]);
 *   Mock.pick(el, { id: 'tray', title: 'Tray style', options: [{ id: 'a', label: 'Ring', text: '…', render: (box) => … }] });
 *   Mock.describe((el) => win.describe(el)); // extra facts on an element comment (game px rects)
 */

const KEY = `mock:${location.pathname}`;
const params = new URLSearchParams(location.search);
const GHOST = params.get('ghost'); // a read-only copy over the real mock showing another variant
const shell = (() => {
  try { return window.parent !== window ? window.parent.__mockShell ?? null : null; } catch { return null; }
})();
const tell = (what) => shell?.notify?.(what, window);
const listeners = {};
const emit = (ev, ...a) => (listeners[ev] ?? []).forEach((f) => { try { f(...a); } catch (e) { console.error(e); } });
let stateObj = null, stateSkip = new Set(), lastSaved = '';
let renderFn = null, controlsFn = null, variantList = null, describers = [], leanApi = null;
let variant = null;

const ss = {
  get: (k) => { try { return JSON.parse(sessionStorage.getItem(`${KEY}:${k}`)); } catch { return null; } },
  set: (k, v) => { try { sessionStorage.setItem(`${KEY}:${k}`, JSON.stringify(v)); } catch {} },
  del: (k) => { try { sessionStorage.removeItem(`${KEY}:${k}`); } catch {} },
};
const plain = (o) => JSON.parse(JSON.stringify(o, (k, v) => (stateSkip.has(k) ? undefined : v)) ?? 'null');
// saved values win, but keys added to the defaults since the save still appear
function merge(into, saved) {
  if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return saved;
  for (const [k, v] of Object.entries(saved)) {
    into[k] = into[k] && typeof into[k] === 'object' && !Array.isArray(into[k]) && v && typeof v === 'object' && !Array.isArray(v) ? merge(into[k], v) : v;
  }
  return into;
}
function snapshotJson() {
  return stateObj ? JSON.stringify(plain(stateObj)) : '';
}
function save() {
  if (!stateObj || GHOST) return;
  const js = snapshotJson();
  if (js !== lastSaved) {
    lastSaved = js;
    ss.set('state', JSON.parse(js));
    emit('save');
    tell('state');
  }
}
function rerender() {
  if (renderFn) {
    try { renderFn(); } catch (e) { console.error(e); }
  }
  tell('controls');
}

export const Mock = {
  /** True when this frame is the ghost overlay (read-only copy showing another variant). */
  ghost: !!GHOST,
  /** True when the drawer shell frames this mock. */
  framed: !!shell,

  /** The mock's state: [defaults] merged with what was saved before the last reload. Keys in [skip] are never saved. */
  state(defaults = {}, { skip = [] } = {}) {
    stateSkip = new Set(skip);
    stateObj = defaults;
    const saved = ss.get('state');
    if (saved) merge(stateObj, saved);
    lastSaved = snapshotJson();
    if (!GHOST) setInterval(save, 250);
    return stateObj;
  },
  /** Saves now (the runtime also saves every 250 ms and before a reload). */
  save,
  /** Forget the saved state and reload with the defaults. */
  reset() {
    ss.del('state');
    lastSaved = '';
    stateObj = null;
    location.reload();
  },

  render(fn) {
    renderFn = fn;
  },
  /** Ask the drawer to redraw the sandbox controls (they also refresh on every state change). */
  refresh: () => tell('controls'),

  variants(list, { value } = {}) {
    variantList = list.map((v) => (Array.isArray(v) ? { id: String(v[0]), label: String(v[1] ?? v[0]) } : typeof v === 'string' ? { id: v, label: v } : v));
    const ids = variantList.map((v) => v.id);
    const stored = ss.get('variant');
    if (GHOST) variant = ids.includes(GHOST) ? GHOST : ids.find((i) => i !== stored) ?? ids[0];
    else variant = ids.includes(stored) ? stored : ids.includes(value) ? value : ids[0];
    tell('variants');
    return variant;
  },
  get variant() { return variant; },
  setVariant(id) {
    if (!variantList?.some((v) => v.id === id) || id === variant) return;
    variant = id;
    if (!GHOST) ss.set('variant', id);
    emit('variant', id);
    rerender();
    tell('variants');
  },

  controls(fn) {
    controlsFn = typeof fn === 'function' ? fn : () => fn;
    tell('controls');
  },
  describe(fn) {
    describers.push(fn);
  },
  /** Lean mode (lean.js): { designs, scenes, design(), scene(), questions } for the shell, which then hides the sandbox, variants and ghost. */
  lean(api) {
    leanApi = api;
    tell('lean');
  },

  /** A message from the mock's own UI (e.g. a "Send this layout" button), with screenshot and state like the drawer's. */
  send(text, { element = null } = {}) {
    if (shell) return shell.sendFromMock({ text, element });
    return fetch('/api/message', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind: 'message', text, variant, design: leanApi?.design(), scene: leanApi?.scene(), state: stateObj ? plain(stateObj) : undefined }) });
  },

  /**
   * Pick-style choice cards inside the mock. [opts]: { id, title, intro?, options: [{ id, label, text?, html?,
   * render?(box) }], multi?, recommended? }. Picking stores at once; Send delivers it to the agent.
   */
  pick(host, opts) {
    return mountPick(host, opts);
  },

  on(ev, fn) {
    (listeners[ev] ??= []).push(fn);
  },
};

// ------------------------------------------------------------------ the shell's view of this frame
window.__mock = {
  get ghost() { return GHOST; },
  save,
  state: () => (stateObj ? plain(stateObj) : undefined),
  hasState: () => !!stateObj,
  // the ghost follows the real frame's state live
  applyState(s) {
    if (!stateObj || !s) return;
    for (const k of Object.keys(stateObj)) if (!stateSkip.has(k) && !(k in s)) delete stateObj[k];
    merge(stateObj, s);
    rerender();
  },
  variants: () => variantList,
  variant: () => variant,
  setVariant: (id) => Mock.setVariant(id),
  lean: () => leanApi,
  controls: () => {
    try { return controlsFn ? controlsFn() ?? [] : []; } catch (e) { console.error(e); return []; }
  },
  changed: () => { save(); rerender(); },
  describe(el) {
    const out = {};
    for (const f of describers) {
      try { Object.assign(out, f(el) ?? {}); } catch {}
    }
    return Object.keys(out).length ? out : null;
  },
  reset: () => Mock.reset(),
};
addEventListener('pagehide', save);
if (shell) queueMicrotask(() => shell.attach(window));

// ------------------------------------------------------------------ inline picks
let pickCss = false;
function ensurePickCss() {
  if (pickCss) return;
  pickCss = true;
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = '/kit/shell/pick.css';
  document.head.appendChild(link);
}
async function loadPicks() {
  try { return (await (await fetch('/api/state')).json()).picks ?? {}; } catch { return {}; }
}
let picksLoaded = null;
function mountPick(host, opts) {
  ensurePickCss();
  picksLoaded ??= loadPicks();
  const options = opts.options.map((o) => ({ ...o, id: String(o.id ?? o.label) }));
  const st = { choices: [], note: '', sent: false };
  const root = document.createElement('section');
  root.className = 'mkp';
  root.dataset.mock = `pick-${opts.id}`;
  root.dataset.mockLabel = opts.title ?? opts.id;
  host.appendChild(root);
  const labels = () => st.choices.map((c) => options.find((o) => o.id === c)?.label ?? c);
  const persist = () => {
    if (GHOST) return;
    fetch('/api/picks', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id: opts.id, title: opts.title, choices: st.choices, labels: labels(), note: st.note, sent: st.sent }) }).catch(() => {});
  };
  const paint = () => {
    for (const card of root.querySelectorAll('.mkp-card')) card.classList.toggle('on', st.choices.includes(card.dataset.id));
    root.querySelector('.mkp-send').disabled = !st.choices.length || GHOST;
    root.querySelector('.mkp-state').textContent = st.sent ? 'Sent' : st.choices.length ? 'Not sent yet' : '';
  };
  root.innerHTML = `<header class="mkp-head"><h3>${escHtml(opts.title ?? opts.id)}</h3>${opts.intro ? `<p>${escHtml(opts.intro)}</p>` : ''}</header>
    <div class="mkp-grid">${options.map((o) => `<div class="mkp-card" tabindex="0" role="button" data-id="${escHtml(o.id)}" data-mock="pick-${escHtml(opts.id)}-${escHtml(o.id)}" data-mock-label="${escHtml(o.label)}">
      <h4>${escHtml(o.label)}${String(opts.recommended ?? '') === o.id || o.recommended ? '<span class="mkp-rec">recommended</span>' : ''}<span class="mkp-on">PICKED</span></h4>
      ${o.text ? `<p>${escHtml(o.text)}</p>` : ''}<div class="mkp-body">${o.html ?? ''}</div></div>`).join('')}</div>
    <footer class="mkp-foot"><input class="mkp-note" placeholder="Note (optional)"><span class="mkp-state"></span><button class="mkp-send" type="button">Send pick</button></footer>`;
  root.querySelectorAll('.mkp-card').forEach((card) => {
    const o = options.find((x) => x.id === card.dataset.id);
    if (o.render) o.render(card.querySelector('.mkp-body'));
  });
  const choose = (id) => {
    if (GHOST) return;
    if (opts.multi) st.choices = st.choices.includes(id) ? st.choices.filter((c) => c !== id) : [...st.choices, id];
    else st.choices = [id];
    st.sent = false;
    paint();
    persist();
  };
  root.addEventListener('click', (e) => {
    const card = e.target.closest('.mkp-card');
    if (card && root.contains(card)) choose(card.dataset.id);
  });
  root.addEventListener('keydown', (e) => {
    const card = e.target.closest('.mkp-card');
    if (card && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); choose(card.dataset.id); }
  });
  const note = root.querySelector('.mkp-note');
  note.addEventListener('input', () => { st.note = note.value; st.sent = false; paint(); persist(); });
  root.querySelector('.mkp-send').addEventListener('click', async () => {
    const pick = { id: opts.id, title: opts.title ?? opts.id, choices: st.choices, labels: labels() };
    try {
      if (shell) await shell.sendFromMock({ kind: 'pick', text: st.note, pick, element: root });
      else await fetch('/api/message', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind: 'pick', text: st.note, pick }) });
      st.sent = true;
      paint();
      persist();
    } catch (e) {
      root.querySelector('.mkp-state').textContent = `Could not send: ${e.message}`;
    }
  });
  picksLoaded.then((all) => {
    const saved = all[opts.id];
    if (saved) {
      st.choices = (saved.choices ?? []).filter((c) => options.some((o) => o.id === c));
      st.note = saved.note ?? '';
      st.sent = !!saved.sent;
      note.value = st.note;
    }
    paint();
  });
  paint();
  return { get choices() { return st.choices; }, el: root };
}
const escHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
