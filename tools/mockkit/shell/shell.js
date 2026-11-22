/*
 * The mock shell: the page the server serves at /. The mock runs in #mock (an iframe of /m/), the
 * drawer holds the conversation with the agent and the sandbox controls. Same origin, so the shell
 * reads the mock's runtime (window.__mock, see ../mock.js) and its DOM directly.
 */
import { capture } from './snap.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const SKEY = 'mockshell:v2';
// seen: the newest agent message id read (unread badge); draft: the composer text
const S = Object.assign({ open: true, tab: 'chat', draft: '', pins: true, ghost: false, width: 380, seen: 0, outbox: [] },
  JSON.parse(sessionStorage.getItem(SKEY) || '{}'));
const saveShell = () => sessionStorage.setItem(SKEY, JSON.stringify(S));
const data = { name: '', messages: [], picks: {}, status: null, online: false, pid: null };
const frame = $('mock'), ghost = $('ghost');
let mockWin = null, ghostWin = null;
let pendingEl = null; // element picked for the next message: { el, desc }

// ------------------------------------------------------------------ the mock frame
window.__mockShell = {
  attach(win) {
    if (win === ghost.contentWindow) {
      ghostWin = win;
      syncGhost();
      return;
    }
    mockWin = win;
    watchFrameKeys(win);
    paintVariants();
    paintLean();
    paintUnread();
    paintControls(true);
    pushState();
  },
  notify(what, win) {
    if (win && win !== mockWin) return;
    if (what === 'state') { syncGhost(); paintControls(); pushStateSoon(); }
    else if (what === 'controls') paintControls();
    else if (what === 'variants') { paintVariants(); syncGhost(); pushStateSoon(); }
    else if (what === 'lean') paintLean();
  },
  sendFromMock({ kind = 'message', text = '', element = null, pick }) {
    const el = element?.nodeType === 1 ? element : null;
    return send({ kind, text, el, pick });
  },
};
const rt = () => mockWin?.__mock ?? null;
/** Lean mocks (tools/mockkit/lean.js): chat only, no sandbox tab. Design and scene tag every message. */
function paintLean() {
  const lean = !!rt()?.lean();
  document.body.classList.toggle('lean', lean);
  if (lean && S.tab !== 'chat') setTab('chat');
}
function leanTags() {
  const l = rt()?.lean();
  if (!l) return {};
  const tag = (list, id) => { const x = list.find((e) => e[0] === id); return x ? `${x[1]} (${id})` : id; };
  return { design: l.design() ? tag(l.designs, l.design()) : undefined, scene: l.scene() ? tag(l.scenes, l.scene()) : undefined };
}
frame.src = '/m/';
frame.addEventListener('load', () => {
  // a mock without the runtime still gets chat, comments and screenshot captures
  if (!frame.contentWindow.__mock) { mockWin = frame.contentWindow; watchFrameKeys(mockWin); paintVariants(); paintControls(true); }
  document.title = `${frame.contentDocument?.title || data.name} · mock`;
});

function reloadMock() {
  try { rt()?.save(); } catch {}
  mockWin = null;
  ghostWin = null;
  frame.contentWindow.location.reload();
  if (!ghost.hidden) ghost.contentWindow.location.reload();
}
function swapCss(win, paths) {
  const doc = win?.document;
  if (!doc) return false;
  let all = true;
  for (const p of paths) {
    const links = [...doc.querySelectorAll('link[rel="stylesheet"]')].filter((l) => new URL(l.href).pathname === p);
    if (!links.length) all = false;
    for (const l of links) l.href = `${p}?v=${Date.now()}`;
  }
  return all;
}
function onChange(paths) {
  if (paths.some((p) => p.startsWith('/kit/shell/'))) {
    saveDraft();
    try { rt()?.save(); } catch {}
    location.reload();
    return;
  }
  const names = paths.map((p) => p.split('/').pop()).join(', ');
  if (paths.every((p) => p.endsWith('.css')) && swapCss(mockWin, paths)) {
    if (!ghost.hidden) swapCss(ghostWin, paths);
    toast(`Styles updated: ${esc(names)}`);
    return;
  }
  reloadMock();
  toast(`Reloaded: ${esc(names)}`);
}

// ------------------------------------------------------------------ server link
let es = null;
function connect() {
  es = new EventSource('/api/events');
  es.addEventListener('hello', async (e) => {
    const hello = JSON.parse(e.data);
    data.online = true;
    if (data.pid && data.pid !== hello.pid) toast('Server restarted');
    data.pid = hello.pid;
    await loadState();
    flushOutbox();
  });
  es.addEventListener('status', (e) => { data.status = JSON.parse(e.data); paintStatus(); });
  es.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    const i = data.messages.findIndex((x) => x.id === m.id);
    if (i >= 0) data.messages[i] = m; else data.messages.push(m);
    if (m.from === 'agent') announce(m);
    paintUnread();
    paintLog();
  });
  es.addEventListener('delivered', (e) => {
    const ids = new Set(JSON.parse(e.data).ids);
    for (const m of data.messages) if (ids.has(m.id)) m.delivered = m.delivered ?? new Date().toISOString();
    paintLog();
  });
  es.addEventListener('change', (e) => onChange(JSON.parse(e.data).paths));
  es.addEventListener('snap', (e) => answerSnap(JSON.parse(e.data)));
  es.addEventListener('server', () => { data.online = false; paintStatus(); });
  es.onerror = () => { data.online = false; paintStatus(); };
}
async function loadState() {
  const st = await (await fetch('/api/state')).json();
  Object.assign(data, { name: st.name, messages: st.messages ?? [], picks: st.picks ?? {}, status: st.status });
  $('title').textContent = st.name;
  paintStatus();
  paintUnread();
  paintLog();
}
async function post(path, body) {
  const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error ?? `HTTP ${r.status}`);
  return r.json();
}
let stateTimer = null;
const pushStateSoon = () => { clearTimeout(stateTimer); stateTimer = setTimeout(pushState, 1200); };
function pushState() {
  const r = rt();
  if (!r?.hasState() || !data.online) return;
  post('/api/mockstate', { state: r.state(), variant: r.variant() }).catch(() => {});
}
async function flushOutbox() {
  while (S.outbox.length && data.online) {
    try {
      await post('/api/message', S.outbox[0]);
      S.outbox.shift();
      saveShell();
    } catch { break; }
  }
  paintStatus();
}

// ------------------------------------------------------------------ status
function paintStatus() {
  const st = data.status ?? {};
  let dot = 'idle', text = 'Connecting';
  if (!data.online) { dot = 'err'; text = S.outbox.length ? `Server offline · ${S.outbox.length} waiting here` : 'Server offline · messages wait here'; }
  else if (st.listening) { dot = 'ok'; text = 'Agent listening'; }
  else if (st.queued) { dot = 'warn'; text = `${st.queued} queued · the agent gets ${st.queued > 1 ? 'them' : 'it'} next`; }
  else if (st.working) { dot = 'live'; text = 'Agent is working on it'; }
  else { dot = 'idle'; text = 'Agent not listening · messages queue'; }
  $('agent').innerHTML = `<i class="dot ${dot === 'live' ? 'ok live' : dot}"></i><span>${esc(text)}</span>`;
}

// ------------------------------------------------------------------ unread
const num = (m) => Number(m.id.slice(1));
const unreadCount = () => data.messages.filter((m) => m.from === 'agent' && num(m) > S.seen).length;
function paintUnread() {
  if (S.open && S.tab === 'chat') {
    S.seen = Math.max(S.seen, ...data.messages.filter((m) => m.from === 'agent').map(num));
    saveShell();
  }
  const n = unreadCount();
  $('unreadAll').textContent = n || '';
  const b = $('reopen').querySelector('.badge');
  b.hidden = !n;
  b.textContent = n;
}
const ICON_EL = '<svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="12" r="7"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4"/></svg>';
const ICON_PICK = '<svg class="ic" viewBox="0 0 24 24"><rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><path d="M14 17l2 2 4-4"/></svg>';

// ------------------------------------------------------------------ log
// Raw text keeps omp-style image tokens `[🖼 #n]`; they render as chips (hover: preview, click: large).
const TOKEN = /\[🖼 #(\d+)\]/g;
const ICON_IMG = '<svg class="ic" viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="9" cy="10" r="2"/><path d="M21 16l-5-5-9 9"/></svg>';
function fmtText(t, m) {
  return esc(t).replace(/`([^`\n]+)`/g, '<code>$1</code>').replace(TOKEN, (tok, n) => {
    const a = m?.attachments?.find((x) => x.n === Number(n));
    return a ? `<span class="att" data-src="/attachments/${esc(a.file.split('/').pop())}" title="Open image #${n}">${ICON_IMG}#${n}</span>` : tok;
  });
}
// One conversation: every message, with a tag for what it refers to (element, pick, the message an
// agent reply answers).
function refTag(m) {
  if (m.element && m.kind !== 'pick') return `<button type="button" class="ref el" data-sel="${esc(m.element.selector)}" title="${esc(m.element.selector)}">${ICON_EL}${esc(m.element.label || m.element.selector)}</button>`;
  if (m.pick) return `<span class="ref pick">${ICON_PICK}${esc(m.pick.title ?? m.pick.id)}</span>`;
  if (m.to) {
    const q = data.messages.find((x) => x.id === m.to);
    const what = q?.element?.label ?? q?.pick?.title ?? (q?.text ?? '').replace(TOKEN, '🖼').split('\n')[0];
    return `<button type="button" class="ref to" data-to="${esc(m.to)}">↩ ${esc(what.length > 40 ? what.slice(0, 39) + '…' : what || m.to)}</button>`;
  }
  return '';
}
function paintLog() {
  const msgs = data.messages;
  const log = $('log');
  const atEnd = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
  if (!msgs.length) {
    log.innerHTML = '<div class="empty-t">Nothing here yet. Write to the agent below, or press <b>Comment</b> and click any element in the mock.</div>';
  } else {
    log.innerHTML = msgs.map((m) => {
      const who = m.from === 'agent' ? 'agent' : 'you';
      const meta = [`<span class="who">${who}</span>`, `<span>${new Date(m.at).toTimeString().slice(0, 5)}</span>`];
      if (m.variant) meta.push(`<span>variant ${esc(m.variant)}</span>`);
      if (m.design) meta.push(`<span>${esc(m.design)}</span>`);
      if (m.scene) meta.push(`<span>${esc(m.scene)}</span>`);
      if (m.from === 'user') meta.push(m.delivered ? '<span class="ok">✓ delivered</span>' : '<span class="q">queued</span>');
      const pick = m.pick ? `<div class="pickl">Picked: ${esc((m.pick.labels ?? []).join(', ') || 'nothing')}</div>` : '';
      return `<div class="msg ${m.from}" data-id="${esc(m.id)}"><div class="meta">${meta.join('')}</div>${refTag(m)}${pick}${m.text ? fmtText(m.text, m) : ''}</div>`;
    }).join('');
  }
  if (atEnd || msgs.at(-1)?.from === 'user') log.scrollTop = log.scrollHeight;
  paintUnread();
}
/** Scroll to a message and flash it (pins and reply tags lead here). */
function showMessage(id) {
  const n = $('log').querySelector(`.msg[data-id="${CSS.escape(id)}"]`);
  if (!n) return;
  n.scrollIntoView({ block: 'center', behavior: 'smooth' });
  n.classList.remove('flash');
  void n.offsetWidth;
  n.classList.add('flash');
}
$('log').addEventListener('click', (e) => {
  const img = e.target.closest('.att[data-src]');
  if (img) openLightbox(img.dataset.src ?? img.src);
  const el = e.target.closest('.ref.el[data-sel]');
  if (el) flashElement(el.dataset.sel);
  const to = e.target.closest('.ref.to[data-to]');
  if (to) showMessage(to.dataset.to);
});

// image preview on hover (composer and log chips) and a lightbox on click
const peek = Object.assign(document.createElement('img'), { id: 'peek', hidden: true, alt: '' });
const lightbox = Object.assign(document.createElement('div'), { id: 'lightbox', hidden: true });
lightbox.innerHTML = '<img alt="">';
document.body.append(peek, lightbox);
document.addEventListener('mouseover', (e) => {
  const a = e.target.closest?.('.att[data-src]');
  if (!a?.dataset.src) { peek.hidden = true; return; }
  const r = a.getBoundingClientRect();
  peek.src = a.dataset.src;
  peek.hidden = false;
  peek.style.left = `${Math.max(8, Math.min(r.left, innerWidth - 248))}px`;
  peek.style.bottom = `${innerHeight - r.top + 8}px`;
});
function openLightbox(src) {
  peek.hidden = true;
  lightbox.firstChild.src = src;
  lightbox.hidden = false;
}
lightbox.addEventListener('click', () => { lightbox.hidden = true; });

// ------------------------------------------------------------------ composer
// A contenteditable: pasted / dropped images become chips at the caret, numbered per message;
// deleting a chip (Backspace, ×) drops its image.
const box = $('text');
const composerImages = new Map();
function chipEl(n) {
  const c = document.createElement('span');
  c.className = 'att';
  c.contentEditable = 'false';
  c.dataset.n = n;
  c.dataset.src = composerImages.get(n)?.data ?? '';
  c.innerHTML = `${ICON_IMG}#${n}<button type="button" class="att-x" tabindex="-1" aria-label="Remove image">×</button>`;
  return c;
}
function boxText() {
  let out = '';
  const walk = (node) => {
    for (const n of node.childNodes) {
      if (n.nodeType === 3) out += n.data;
      else if (n.classList?.contains('att')) out += `[🖼 #${n.dataset.n}]`;
      else if (n.nodeName === 'BR') out += '\n';
      else {
        if ((n.nodeName === 'DIV' || n.nodeName === 'P') && out && !out.endsWith('\n')) out += '\n';
        walk(n);
      }
    }
  };
  walk(box);
  return out;
}
function setBoxText(text) {
  box.textContent = '';
  let last = 0;
  for (const m of text.matchAll(TOKEN)) {
    box.append(text.slice(last, m.index));
    box.append(composerImages.has(Number(m[1])) ? chipEl(Number(m[1])) : m[0]);
    last = m.index + m[0].length;
  }
  box.append(text.slice(last));
}
function pruneImages() {
  const live = new Set([...box.querySelectorAll('.att')].map((c) => Number(c.dataset.n)));
  for (const n of composerImages.keys()) if (!live.has(n)) composerImages.delete(n);
}
function insertAtCaret(nodes) {
  const sel = getSelection();
  let range = sel.rangeCount && box.contains(sel.getRangeAt(0).commonAncestorContainer) ? sel.getRangeAt(0) : null;
  if (!range) {
    range = document.createRange();
    range.selectNodeContents(box);
    range.collapse(false);
  }
  range.deleteContents();
  for (const n of nodes) {
    range.insertNode(n);
    range.setStartAfter(n);
    range.collapse(true);
  }
  sel.removeAllRanges();
  sel.addRange(range);
}
const readAsDataUrl = (file) => new Promise((ok, fail) => {
  const fr = new FileReader();
  fr.onload = () => ok(fr.result);
  fr.onerror = fail;
  fr.readAsDataURL(file);
});
async function addImages(files) {
  const list = [];
  for (const f of [...files].filter((x) => x.type.startsWith('image/'))) list.push({ type: f.type, data: await readAsDataUrl(f) });
  addImageData(list);
}
/** Image chips at the caret, one per { type, data } (pasted, dropped or captured). */
function addImageData(list) {
  const nodes = [];
  for (const im of list) {
    const n = Math.max(0, ...composerImages.keys()) + 1;
    composerImages.set(n, im);
    nodes.push(chipEl(n), document.createTextNode(' '));
  }
  if (!nodes.length) return;
  box.focus();
  insertAtCaret(nodes);
  saveDraft();
}
box.addEventListener('paste', (e) => {
  e.preventDefault();
  const files = [...(e.clipboardData?.files ?? [])];
  if (files.some((f) => f.type.startsWith('image/'))) { addImages(files); return; }
  const t = e.clipboardData?.getData('text/plain') ?? '';
  if (t) document.execCommand('insertText', false, t); // plain text only, keeps the undo stack
});
const field = box.closest('.field');
field.addEventListener('dragover', (e) => {
  if (![...e.dataTransfer.types].includes('Files')) return;
  e.preventDefault();
  field.classList.add('is-drop');
});
field.addEventListener('dragleave', () => field.classList.remove('is-drop'));
field.addEventListener('drop', (e) => {
  field.classList.remove('is-drop');
  if (!e.dataTransfer?.files?.length) return;
  e.preventDefault();
  const r = document.caretRangeFromPoint?.(e.clientX, e.clientY);
  if (r && box.contains(r.startContainer)) {
    const s = getSelection();
    s.removeAllRanges();
    s.addRange(r);
  }
  addImages(e.dataTransfer.files);
});
box.addEventListener('click', (e) => {
  const x = e.target.closest('.att-x');
  if (!x) {
    const a = e.target.closest('.att[data-src]');
    if (a) openLightbox(a.dataset.src);
    return;
  }
  x.closest('.att').remove();
  pruneImages();
  saveDraft();
});
// The draft's images live in their own sessionStorage key (they can be large) so a shell reload
// (hot reload of the kit) keeps the chips with their pictures.
const IMG_KEY = `${SKEY}:images`;
function saveDraft() {
  S.draft = boxText();
  saveShell();
  try { sessionStorage.setItem(IMG_KEY, JSON.stringify([...composerImages])); } catch {}
}
function loadDraft() {
  composerImages.clear();
  try { for (const [n, v] of JSON.parse(sessionStorage.getItem(IMG_KEY) || '[]')) composerImages.set(n, v); } catch {}
  setBoxText(S.draft ?? '');
}
// the element the next message comments on, with × to drop it
function paintTarget() {
  $('target').hidden = !pendingEl;
  $('target').innerHTML = pendingEl ? `<span>On</span><span class="chip el">${ICON_EL}${esc(pendingEl.desc.label)}<svg class="ic x" data-x viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></span>` : '';
}
$('target').addEventListener('click', (e) => {
  if (e.target.closest('[data-x]')) {
    pendingEl = null;
    paintTarget();
  }
});
box.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' || e.isComposing) return;
  e.preventDefault();
  if (e.shiftKey) document.execCommand('insertLineBreak');
  else sendComposer();
});
box.addEventListener('input', () => {
  pruneImages();
  saveDraft();
});
$('send').addEventListener('click', sendComposer);
async function sendComposer() {
  pruneImages();
  const text = boxText().trim();
  const images = [...composerImages].map(([n, v]) => ({ n, ...v }));
  if (!text && !images.length) return;
  const keep = new Map(composerImages);
  box.textContent = '';
  composerImages.clear();
  saveDraft();
  const sent = await send({ kind: pendingEl ? 'comment' : 'message', text, images, el: pendingEl?.el, desc: pendingEl?.desc });
  if (!sent) {
    for (const [k, v] of keep) composerImages.set(k, v);
    setBoxText(text);
    saveDraft();
  }
}

/** Everything goes out here: drawer messages, comments, the mock's picks and Mock.send. */
async function send({ kind, text, el = null, desc = null, pick, images = [] }) {
  const r = rt();
  const live = el && el.isConnected ? el : desc ? findEl(desc.selector) : null;
  const element = live ? describe(live) : desc;
  const body = { kind, text, element: element ? strip(element) : undefined, pick, images: images.length ? images : undefined, variant: r?.variant() ?? undefined, ...leanTags(), state: r?.hasState() ? r.state() : undefined };
  $('send').disabled = true;
  try {
    try {
      await post('/api/message', body);
    } catch (e) {
      if (data.online) { toast(`Could not send: ${esc(e.message)}`); return false; }
      S.outbox.push(body);
      saveShell();
      toast('Server offline: the message waits here and goes out when it is back');
    }
    if (kind === 'comment') pendingEl = null;
    paintTarget();
    return true;
  } finally {
    $('send').disabled = false;
  }
}
const strip = ({ el, ...rest }) => rest;

// ------------------------------------------------------------------ elements: describe, find, comment mode
function selectorFor(el) {
  const doc = el.ownerDocument;
  const parts = [];
  for (let n = el; n && n.nodeType === 1 && n !== doc.documentElement; n = n.parentElement) {
    if (n.dataset?.mock) { parts.unshift(`[data-mock="${CSS.escape(n.dataset.mock)}"]`); break; }
    if (n.id && doc.querySelectorAll(`#${CSS.escape(n.id)}`).length === 1) { parts.unshift(`#${CSS.escape(n.id)}`); break; }
    if (n === doc.body) { parts.unshift('body'); break; }
    let part = n.tagName.toLowerCase();
    const cls = [...n.classList].filter((c) => !/^(on|sel|hover|active|open|is-|dragging|hidden)/.test(c)).slice(0, 2);
    if (cls.length) part += '.' + cls.map((c) => CSS.escape(c)).join('.');
    const sibs = n.parentElement ? [...n.parentElement.children].filter((s) => s.tagName === n.tagName) : [];
    if (sibs.length > 1) part += `:nth-of-type(${sibs.indexOf(n) + 1})`;
    parts.unshift(part);
  }
  return parts.join(' > ');
}
function labelFor(el) {
  const own = el.dataset?.mockLabel || el.getAttribute('aria-label') || el.getAttribute('title');
  if (own) return own.slice(0, 60);
  if (el.dataset?.mock) return el.dataset.mock.replace(/[-_]+/g, ' ');
  const text = (el.innerText ?? el.textContent ?? '').replace(/\s+/g, ' ').trim();
  // long text means a container: its first words would name the wrong thing
  if (text && text.length <= 60) return text.length > 40 ? text.slice(0, 39) + '…' : text;
  return el.id || el.tagName.toLowerCase() + (el.classList[0] ? '.' + el.classList[0] : '');
}
const round = (v) => Math.round(v * 2) / 2;
function describe(el) {
  const r = el.getBoundingClientRect();
  const out = { selector: selectorFor(el), label: labelFor(el), tag: el.tagName.toLowerCase() + [...el.classList].slice(0, 3).map((c) => '.' + c).join(''), rect: { x: round(r.left), y: round(r.top), w: round(r.width), h: round(r.height) } };
  const game = rt()?.describe(el);
  if (game) out.game = game;
  return out;
}
function findEl(selector) {
  try { return mockWin?.document.querySelector(selector) ?? null; } catch { return null; }
}

let picking = null; // { el, chain: [], style }
function startComment() {
  if (!mockWin || picking) return;
  const doc = mockWin.document;
  const style = doc.createElement('style');
  // HUD layers often ignore the mouse; while commenting everything is clickable
  style.textContent = '* { pointer-events: auto !important; }';
  doc.head.appendChild(style);
  picking = { el: null, chain: [], style };
  $('catcher').hidden = false;
  $('commentBtn').classList.add('is-on');
  toast('Click an element to comment on it · scroll to pick its parent · Esc cancels');
}
function stopComment() {
  if (!picking) return;
  picking.style.remove();
  picking = null;
  $('catcher').hidden = true;
  $('hl').hidden = true;
  $('commentBtn').classList.remove('is-on');
}
function frameOffset() {
  const r = frame.getBoundingClientRect(), s = $('stage').getBoundingClientRect();
  return { x: r.left - s.left, y: r.top - s.top };
}
function showHl(el, label) {
  if (!el) { $('hl').hidden = true; return; }
  const r = el.getBoundingClientRect(), o = frameOffset();
  const hl = $('hl');
  hl.hidden = false;
  Object.assign(hl.style, { left: `${o.x + r.left}px`, top: `${o.y + r.top}px`, width: `${r.width}px`, height: `${r.height}px` });
  hl.classList.toggle('below', r.top < 28);
  hl.firstChild.textContent = label ?? labelFor(el);
}
// Everything is hit-testable while commenting (HUDs ignore the mouse otherwise), so transparent
// full-window layers and editor decoration would win: skip elements covering most of the frame and
// empty ones that ignore the mouse normally (outlines, guides). Start at the nearest [data-mock]
// (what the author named); the wheel walks up and back down the chain.
function pickAt(x, y) {
  const doc = mockWin.document, vw = doc.documentElement.clientWidth, vh = doc.documentElement.clientHeight;
  const stack = doc.elementsFromPoint(x, y).filter((n) => n !== doc.documentElement && n !== doc.body);
  picking.style.disabled = true;
  const decoration = new Set(stack.filter((n) => mockWin.getComputedStyle(n).pointerEvents === 'none' && !n.textContent.trim() && !n.querySelector('svg, img, canvas')));
  picking.style.disabled = false;
  const small = (n) => { const r = n.getBoundingClientRect(); return r.width * r.height < vw * vh * 0.6; };
  const deepest = stack.find((n) => small(n) && !decoration.has(n)) ?? stack.find((n) => !decoration.has(n)) ?? stack[0];
  if (!deepest) return null;
  const chain = [deepest];
  const named = deepest.closest('[data-mock]');
  if (named && small(named)) for (let n = deepest; n !== named; n = n.parentElement) chain.push(n.parentElement);
  return chain;
}
$('catcher').addEventListener('mousemove', (e) => {
  const o = frameOffset(), s = $('stage').getBoundingClientRect();
  const chain = pickAt(e.clientX - s.left - o.x, e.clientY - s.top - o.y);
  if (chain && chain[0] !== picking.chain[0]) picking.chain = chain;
  picking.el = picking.chain.at(-1) ?? null;
  showHl(picking.el);
});
$('catcher').addEventListener('wheel', (e) => {
  e.preventDefault();
  const cur = picking.chain.at(-1);
  if (!cur) return;
  if (e.deltaY < 0 && cur.parentElement && cur.parentElement !== mockWin.document.documentElement) picking.chain.push(cur.parentElement);
  else if (e.deltaY > 0 && picking.chain.length > 1) picking.chain.pop();
  picking.el = picking.chain.at(-1);
  showHl(picking.el);
}, { passive: false });
$('catcher').addEventListener('click', (e) => {
  e.preventDefault();
  const el = picking?.el;
  stopComment();
  if (!el) return;
  const desc = describe(el);
  pendingEl = { el, desc };
  setOpen(true);
  setTab('chat');
  paintTarget();
  $('text').focus();
});
$('commentBtn').addEventListener('click', () => (picking ? stopComment() : startComment()));

// ------------------------------------------------------------------ screenshot capture (Alt+S)
// Drag a rectangle over the mock (a click takes all of it); the area is rendered by snap.js at the
// device pixel ratio and lands in the composer as an image chip.
let shooting = null; // { x0, y0, x1, y1 } in stage px while dragging
function startShot() {
  if (!mockWin || shooting || picking) return;
  shooting = {};
  $('shooter').hidden = false;
  $('shotBtn').classList.add('is-on');
  toast('Drag over the mock to capture an area · click for all of it · Esc cancels');
}
function stopShot() {
  shooting = null;
  $('shooter').hidden = true;
  $('shooter').classList.remove('dragging');
  $('shotBtn').classList.remove('is-on');
}
const stagePt = (e) => { const s = $('stage').getBoundingClientRect(); return { x: e.clientX - s.left, y: e.clientY - s.top }; };
function shotRect() {
  const { x0, y0, x1, y1 } = shooting;
  return { x: Math.min(x0, x1), y: Math.min(y0, y1), w: Math.abs(x1 - x0), h: Math.abs(y1 - y0) };
}
function paintShot() {
  const r = shotRect(), box = $('shotRect');
  Object.assign(box.style, { left: `${r.x}px`, top: `${r.y}px`, width: `${r.w}px`, height: `${r.h}px` });
  box.firstChild.textContent = `${Math.round(r.w)} × ${Math.round(r.h)}`;
}
$('shooter').addEventListener('pointerdown', (e) => {
  e.preventDefault();
  $('shooter').setPointerCapture(e.pointerId);
  const p = stagePt(e);
  Object.assign(shooting, { x0: p.x, y0: p.y, x1: p.x, y1: p.y, down: true });
  $('shooter').classList.add('dragging');
  paintShot();
});
$('shooter').addEventListener('pointermove', (e) => {
  if (!shooting?.down) return;
  const p = stagePt(e);
  Object.assign(shooting, { x1: p.x, y1: p.y });
  paintShot();
});
$('shooter').addEventListener('pointerup', async () => {
  if (!shooting?.down) return;
  const r = shotRect(), o = frameOffset();
  const W = mockWin.document.documentElement.clientWidth, H = mockWin.document.documentElement.clientHeight;
  // a click (no real drag) takes the whole mock; otherwise the dragged area, clamped to the mock
  let crop = { x: 0, y: 0, w: W, h: H };
  if (r.w >= 4 && r.h >= 4) {
    const x = Math.max(0, r.x - o.x), y = Math.max(0, r.y - o.y);
    crop = { x, y, w: Math.min(W, r.x - o.x + r.w) - x, h: Math.min(H, r.y - o.y + r.h) - y };
  }
  stopShot();
  if (crop.w < 1 || crop.h < 1) return;
  try {
    const data = await capture(mockWin, { crop, scale: window.devicePixelRatio || 1 });
    setOpen(true);
    setTab('chat');
    addImageData([{ type: 'image/png', data }]);
  } catch (e) {
    toast(`Capture failed: ${esc(e?.message ?? e)}`);
  }
});
$('shotBtn').addEventListener('click', () => (shooting ? stopShot() : startShot()));

function flashElement(selector) {
  const el = findEl(selector);
  if (!el) { toast('That element is not in the mock right now'); return; }
  showHl(el);
  setTimeout(() => { if (!picking) $('hl').hidden = true; }, 1400);
}

// ------------------------------------------------------------------ pins
function paintPins() {
  const host = $('pins');
  if (!S.pins || !mockWin) { host.innerHTML = ''; return; }
  const o = frameOffset();
  // one pin per commented element: its comment count, pulsing while an agent answer to it is unread
  const groups = new Map();
  for (const m of data.messages) {
    if (m.kind !== 'comment' || !m.element) continue;
    const g = groups.get(m.element.selector) ?? { n: 0, ids: new Set() };
    g.n += 1;
    g.desc = m.element;
    g.last = m.id;
    g.ids.add(m.id);
    groups.set(m.element.selector, g);
  }
  let html = '';
  for (const [sel, g] of groups) {
    const el = findEl(sel);
    const r = el?.getBoundingClientRect();
    const box = r && (r.width || r.height) ? r : null;
    const rect = box ? { x: box.left, y: box.top, w: box.width } : g.desc.rect;
    // hidden right now (another scene): no pin; gone from the mock: a faded pin where it was
    if (!rect || (el && !box)) continue;
    const unread = data.messages.some((m) => m.from === 'agent' && g.ids.has(m.to) && num(m) > S.seen);
    const cls = ['pin', pendingEl?.desc.selector === sel ? 'cur' : '', unread ? 'unread' : '', box ? '' : 'gone'].join(' ');
    html += `<button type="button" class="${cls}" data-last="${esc(g.last)}" title="${esc(g.desc.label)}" style="left:${o.x + rect.x + rect.w}px;top:${o.y + rect.y}px">${g.n}</button>`;
  }
  if (host.innerHTML !== html) host.innerHTML = html;
}
$('pins').addEventListener('click', (e) => {
  const p = e.target.closest('[data-last]');
  if (!p) return;
  setOpen(true);
  setTab('chat');
  showMessage(p.dataset.last);
});
setInterval(paintPins, 400);
$('pinsOn').checked = S.pins;
$('pinsOn').addEventListener('change', () => { S.pins = $('pinsOn').checked; saveShell(); paintPins(); });

// ------------------------------------------------------------------ variants + ghost
function paintVariants() {
  const r = rt();
  const list = r?.variants();
  const bar = $('variantbar');
  if (!list?.length) {
    bar.hidden = true;
    ghost.hidden = true;
    return;
  }
  bar.hidden = false;
  const cur = r.variant();
  const others = list.filter((v) => v.id !== cur);
  if (!others.some((v) => v.id === S.ghostOf)) S.ghostOf = others[0]?.id ?? null;
  bar.innerHTML = `<div class="seg" role="group" aria-label="Variant">${list.map((v) => `<button type="button" data-variant="${esc(v.id)}" aria-pressed="${v.id === cur}">${esc(v.label)}</button>`).join('')}<i class="thumb"></i></div>
    ${others.length ? `<label class="toggle" title="Overlay the other variant, faint, over this one"><input type="checkbox" id="ghostOn" ${S.ghost ? 'checked' : ''}><span class="track"></span>Ghost${others.length > 1 ? '' : ` of ${esc(others[0].label)}`}</label>` : ''}
    ${others.length > 1 && S.ghost ? `<div class="seg" role="group" aria-label="Ghost variant">${others.map((v) => `<button type="button" data-ghostof="${esc(v.id)}" aria-pressed="${v.id === S.ghostOf}">${esc(v.label)}</button>`).join('')}<i class="thumb"></i></div>` : ''}`;
  bar.querySelectorAll('.seg').forEach(placeThumb);
  const want = S.ghost && S.ghostOf ? `/m/?ghost=${encodeURIComponent(S.ghostOf)}` : null;
  if (!want) { ghost.hidden = true; ghostWin = null; }
  else {
    ghost.hidden = false;
    if (ghost.getAttribute('src') !== want) { ghostWin = null; ghost.setAttribute('src', want); }
  }
}
function placeThumb(seg) {
  const on = seg.querySelector('button[aria-pressed="true"]');
  const t = seg.querySelector('.thumb');
  if (!t) return;
  t.style.opacity = on ? 1 : 0;
  if (on) Object.assign(t.style, { left: `${on.offsetLeft}px`, width: `${on.offsetWidth}px`, top: `${on.offsetTop}px`, height: `${on.offsetHeight}px` });
}
$('variantbar').addEventListener('click', (e) => {
  const v = e.target.closest('[data-variant]');
  if (v) { rt()?.setVariant(v.dataset.variant); return; }
  const g = e.target.closest('[data-ghostof]');
  if (g) { S.ghostOf = g.dataset.ghostof; saveShell(); paintVariants(); }
});
$('variantbar').addEventListener('change', (e) => {
  if (e.target.id === 'ghostOn') { S.ghost = e.target.checked; saveShell(); paintVariants(); }
});
function syncGhost() {
  const g = ghostWin?.__mock, r = rt();
  if (!g || !r || ghost.hidden) return;
  if (S.ghostOf && g.variant() !== S.ghostOf) g.setVariant(S.ghostOf);
  if (r.hasState()) g.applyState(r.state());
}

// ------------------------------------------------------------------ sandbox controls
let controlsSig = '';
let controlItems = [];
function paintControls(force = false) {
  const items = rt()?.controls() ?? [];
  const val = (it) => { try { return it.get ? it.get() : undefined; } catch { return undefined; } };
  const sig = JSON.stringify(items.map((it) => [it.type, it.label, it.text, it.html, it.hint, it.min, it.max, it.step, it.options, val(it), it.buttons?.map((b) => [b.label, b.on, b.hint])]));
  const host = $('controls');
  const active = document.activeElement;
  if (!force && sig === controlsSig) return;
  if (!force && host.contains(active) && (active.type === 'range' || active.type === 'number' || active.tagName === 'SELECT')) return;
  controlsSig = sig;
  controlItems = items;
  if (!items.length) {
    host.innerHTML = '<div class="empty-t">This mock has no sandbox controls. A mock adds them with <code>Mock.controls(() => [...])</code>.</div>';
    return;
  }
  host.innerHTML = items.map((it, i) => controlHtml(it, i, val(it))).join('');
  host.querySelectorAll('.seg').forEach(placeThumb);
}
const optVal = (o) => (Array.isArray(o) ? o[0] : o?.value ?? o);
const optLabel = (o) => (Array.isArray(o) ? o[1] ?? o[0] : o?.label ?? o);
function controlHtml(it, i, v) {
  const k = it.label ? `<span class="k">${esc(it.label)}</span>` : '';
  const hint = it.hint ? `<span class="hint">${esc(it.hint)}</span>` : '';
  switch (it.type) {
    case 'heading': return `<div class="lbl">${esc(it.text ?? it.label)}</div>`;
    case 'note': return `<div class="ctl"><div class="inline-msg"><span>${it.html ?? esc(it.text ?? '')}</span></div></div>`;
    case 'seg': return `<div class="ctl">${k}<div class="seg">${it.options.map((o, j) => {
      const disabled = Array.isArray(o) ? o[2]?.disabled : o?.disabled;
      return `<button type="button" data-i="${i}" data-j="${j}" aria-pressed="${String(optVal(o)) === String(v)}" ${disabled ? 'title="does not fit"' : ''}>${esc(optLabel(o))}</button>`;
    }).join('')}<i class="thumb"></i></div>${hint}</div>`;
    case 'range': {
      const min = it.min ?? 0, max = it.max ?? 100, pct = ((Number(v) - min) / (max - min || 1)) * 100;
      return `<div class="ctl">${k}<div class="slider-row"><label class="slider" style="--v:${pct}%"><input type="range" data-i="${i}" min="${min}" max="${max}" step="${it.step ?? 1}" value="${esc(v)}"><span class="rail"><span class="fill"></span></span><span class="knob"></span></label><output>${esc(it.format ? it.format(v) : v)}</output></div>${hint}</div>`;
    }
    case 'number': return `<div class="ctl row">${k}<div class="field" style="width:110px"><input type="number" data-i="${i}" value="${esc(v)}" ${it.min !== undefined ? `min="${it.min}"` : ''} ${it.max !== undefined ? `max="${it.max}"` : ''} step="${it.step ?? 1}"></div>${hint}</div>`;
    case 'check': return `<div class="ctl"><label class="toggle"><input type="checkbox" data-i="${i}" ${v ? 'checked' : ''}><span class="track"></span>${esc(it.label)}</label>${hint}</div>`;
    case 'select': return `<div class="ctl">${k}<div class="field"><select data-i="${i}">${it.options.map((o, j) => `<option value="${j}" ${String(optVal(o)) === String(v) ? 'selected' : ''}>${esc(optLabel(o))}</option>`).join('')}</select></div>${hint}</div>`;
    case 'buttons': return `<div class="ctl">${k}<div class="chips">${it.buttons.map((b, j) => `<button type="button" class="chip ${b.on ? 'on' : ''}" data-i="${i}" data-b="${j}" ${b.hint ? `title="${esc(b.hint)}"` : ''}>${esc(b.label)}${b.small ? ` <small>${esc(b.small)}</small>` : ''}</button>`).join('')}</div>${hint}</div>`;
    default: return '';
  }
}
function applyControl(i, value) {
  const it = controlItems[i];
  if (!it?.set) return;
  try { it.set(value); } catch (e) { console.error(e); }
  rt()?.changed();
}
$('controls').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-i]');
  if (!b) return;
  const it = controlItems[b.dataset.i];
  if (b.dataset.b !== undefined) {
    try { it.buttons[b.dataset.b].onClick?.(); } catch (err) { console.error(err); }
    rt()?.changed();
  } else if (b.dataset.j !== undefined) applyControl(b.dataset.i, optVal(it.options[b.dataset.j]));
  paintControls(true);
});
$('controls').addEventListener('input', (e) => {
  const t = e.target;
  if (t.dataset.i === undefined) return;
  const it = controlItems[t.dataset.i];
  if (t.type === 'range') {
    const v = Number(t.value);
    const label = t.closest('.slider');
    label.style.setProperty('--v', `${((v - Number(t.min)) / (Number(t.max) - Number(t.min) || 1)) * 100}%`);
    label.parentElement.querySelector('output').textContent = it.format ? it.format(v) : v;
    applyControl(t.dataset.i, v);
  } else if (t.type === 'number') {
    if (t.value !== '' && !Number.isNaN(Number(t.value))) applyControl(t.dataset.i, Number(t.value));
  } else if (t.type === 'checkbox') applyControl(t.dataset.i, t.checked);
  else if (t.tagName === 'SELECT') applyControl(t.dataset.i, optVal(it.options[t.value]));
});
$('controls').addEventListener('change', () => paintControls(true));
$('reloadMock').addEventListener('click', reloadMock);
$('resetState').addEventListener('click', () => {
  const r = rt();
  if (r) r.reset(); else reloadMock();
});

// ------------------------------------------------------------------ agent requests a screenshot
async function answerSnap({ req, element }) {
  try {
    if (!mockWin) throw new Error('the mock is not loaded');
    const el = element ? findEl(element) : null;
    const d = el ? describe(el) : null;
    const shot = await capture(mockWin, { highlight: d?.rect, label: d?.label });
    await post('/api/snap', { req, shot });
  } catch (e) {
    post('/api/snap', { req, error: String(e?.message ?? e) }).catch(() => {});
  }
}

// ------------------------------------------------------------------ drawer: open, tabs, width, keys
function setOpen(open) {
  S.open = open;
  saveShell();
  document.body.classList.toggle('closed', !open);
  $('reopen').hidden = open;
  paintUnread();
}
function setTab(tab) {
  S.tab = tab;
  saveShell();
  document.querySelectorAll('#drawer .tabs button').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === tab)));
  $('chat').hidden = tab !== 'chat';
  $('sandbox').hidden = tab !== 'sandbox';
  placeTabBar();
  if (tab === 'sandbox') paintControls(true);
  paintUnread();
}
function placeTabBar() {
  const on = document.querySelector('#drawer .tabs button[aria-selected="true"]');
  const bar = document.querySelector('#drawer .tabs .bar');
  if (on) Object.assign(bar.style, { left: `${on.offsetLeft}px`, width: `${on.offsetWidth}px` });
}
document.querySelector('#drawer .tabs').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-tab]');
  if (b) setTab(b.dataset.tab);
});
$('collapse').addEventListener('click', () => setOpen(false));
$('reopen').addEventListener('click', () => setOpen(true));
const setWidth = (w) => {
  S.width = Math.round(Math.max(300, Math.min(window.innerWidth * 0.6, w)));
  document.documentElement.style.setProperty('--drawer-w', `${S.width}px`);
};
$('resizer').addEventListener('pointerdown', (e) => {
  const rz = $('resizer');
  rz.setPointerCapture(e.pointerId);
  rz.classList.add('on');
  frame.style.pointerEvents = 'none';
  const move = (ev) => setWidth(window.innerWidth - ev.clientX - 8);
  const up = () => { rz.classList.remove('on'); frame.style.pointerEvents = ''; rz.removeEventListener('pointermove', move); saveShell(); };
  rz.addEventListener('pointermove', move);
  rz.addEventListener('pointerup', up, { once: true });
});
function onKey(e) {
  if (e.key === 'Escape' && !lightbox.hidden) { lightbox.hidden = true; e.preventDefault(); }
  else if (e.key === 'Escape' && picking) { stopComment(); e.preventDefault(); }
  else if (e.key === 'Escape' && shooting) { stopShot(); e.preventDefault(); }
  else if (e.altKey && !e.ctrlKey && (e.code === 'KeyC')) { e.preventDefault(); picking ? stopComment() : startComment(); }
  else if (e.altKey && !e.ctrlKey && (e.code === 'KeyS')) { e.preventDefault(); shooting ? stopShot() : startShot(); }
  else if (e.altKey && !e.ctrlKey && (e.code === 'KeyD')) { e.preventDefault(); setOpen(!S.open); }
}
window.addEventListener('keydown', onKey);
function watchFrameKeys(win) {
  try { win.addEventListener('keydown', onKey, true); } catch {}
}

// ------------------------------------------------------------------ toasts
function toast(html, onClick) {
  const t = document.createElement('div');
  t.className = 'toast-m';
  t.innerHTML = html;
  if (onClick) t.addEventListener('click', onClick);
  $('toasts').appendChild(t);
  setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 300); }, onClick ? 6000 : 2600);
  while ($('toasts').children.length > 3) $('toasts').firstChild.remove();
}
function announce(m) {
  if (S.open && S.tab === 'chat') return;
  const first = m.text.split('\n')[0];
  toast(`<b>Agent</b>: ${esc(first.length > 120 ? first.slice(0, 119) + '…' : first)}`, () => {
    setOpen(true);
    setTab('chat');
    showMessage(m.id);
  });
}

// ------------------------------------------------------------------ start
setWidth(S.width);
setOpen(S.open);
setTab(S.tab);
paintTarget();
loadDraft();
paintStatus();
connect();
window.addEventListener('resize', () => { placeTabBar(); document.querySelectorAll('#drawer .seg').forEach(placeThumb); });
