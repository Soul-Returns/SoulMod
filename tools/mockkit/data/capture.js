// Dev-bridge captures (NDJSON written by `tools/devbridge/listen listen`, one {t, kind, data} event per
// line) turned into kit data: the last sidebar / tab list / action bar, the chat feed, and every opened
// container screen in its final state with items in the kit's item model, ready for chestScreen.
//   const cap = await loadCapture('./capture.ndjson');
//   const s = cap.screens[0]; guiLayer.innerHTML = chestScreen({ sw, sh, rows: s.rows, title: s.title, items: s.slots }).html;
// Exports: parseCapture(text), loadCapture(url), captureItem(raw).

import { item } from '../mc/items.js';

/**
 * A captured item (`{ id, count, name, lore, sb_id?, custom_data? }`) as a kit Item. Enchanted
 * SkyBlock items carry `enchantments:{…}` in custom_data and get the glint.
 */
export function captureItem(raw) {
  if (!raw || typeof raw !== 'object') return null;
  return item({
    id: raw.sb_id ?? null, mcId: raw.id, name: raw.name ?? '', lore: raw.lore ?? [], count: raw.count ?? 1,
    glint: /enchantments:\{[^}]/.test(raw.custom_data ?? ''),
  });
}

/**
 * Parse capture text → `{ sidebar: { title, lines } | null, tab: { header, footer, lines } | null,
 * actionbar: string | null, chat: [string], screens: [{ title, rows, menuType, size, slots, clicks, closedBy }] }`.
 * `slots` is indexed by menu slot (container first, then 27 main + 9 hotbar), null where empty, as
 * the last screen_contents + slot_update events left it. `rows` is N for generic_9xN, else ceil(size / 9).
 * Lines and titles keep their § codes.
 */
export function parseCapture(text) {
  const out = { sidebar: null, tab: null, actionbar: null, chat: [], screens: [] };
  let cur = null;
  for (const line of String(text).split('\n')) {
    if (!line.trim()) continue;
    let ev;
    try { ev = JSON.parse(line); } catch { continue; } // a capture cut mid-line still loads
    const d = ev.data ?? {};
    switch (ev.kind) {
      case 'sidebar': out.sidebar = { title: d.title ?? '', lines: d.lines ?? [] }; break;
      case 'tab': out.tab = { header: d.header ?? '', footer: d.footer ?? '', lines: d.lines ?? [] }; break;
      case 'actionbar': out.actionbar = d.text ?? ''; break;
      case 'chat': out.chat.push(d.text ?? ''); break;
      case 'screen_open': {
        const size = d.container_slots ?? 0, m = /generic_9x(\d)/.exec(d.menu_type ?? '');
        cur = { id: d.container_id, title: d.title ?? '', menuType: d.menu_type ?? null, rows: m ? +m[1] : Math.ceil(size / 9), size,
          slots: Array(d.slot_count ?? size + 36).fill(null), clicks: [], closedBy: null };
        out.screens.push(cur);
        break;
      }
      case 'screen_contents':
        if (cur && d.container_id === cur.id) {
          cur.slots = Array(d.slot_count ?? cur.slots.length).fill(null);
          for (const s of d.slots ?? []) cur.slots[s.slot] = captureItem(s.item);
        }
        break;
      case 'slot_update':
        if (cur && d.container_id === cur.id && d.slot >= 0) cur.slots[d.slot] = d.item === 'empty' ? null : captureItem(d.item);
        break;
      case 'page':
        if (cur && d.same_container && d.container_id === cur.id) cur.title = d.title ?? cur.title;
        break;
      case 'click':
        if (cur) cur.clicks.push({ slot: d.slot, button: d.button, input: d.input });
        break;
      case 'screen_close':
        if (cur && d.container_id === cur.id) { cur.closedBy = d.by ?? null; cur = null; }
        break;
    }
  }
  for (const s of out.screens) delete s.id;
  return out;
}

/** Fetch a capture next to the mock (relative URLs resolve against the page) and parse it. */
export async function loadCapture(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`loadCapture ${url}: HTTP ${res.status}`);
  return parseCapture(await res.text());
}
