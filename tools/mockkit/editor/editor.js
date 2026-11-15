// Move GUI editing over any set of boxes (HudMoveScreen behavior, without HUD-specific link/anchor logic):
// click selects the top-most item (the selected one wins where they overlap; clicking it again there
// cycles to the one below on release unless dragged), drag with snap guides (Alt: free), arrows nudge
// 1 px (Shift 10), Tab / Shift+Tab walk the items, Ctrl+Z undo, Ctrl+Shift+Z / Ctrl+Y redo, Escape
// deselects, optional wheel handler. Draws outlines, labels and guides into [overlay] and glues an
// optional inspector to the selection. All coordinates are the caller's layer px (Soul px usually).
//
// Exports: createEditor(opts) → editor, plus re-exports of snap / history / inspector helpers.
// Usage: const ed = createEditor({ surface: soulLayer, overlay, toLocal, items: () => S.items.map(…),
//          move: (id, box) => { S.pos[id] = box; }, snapshot: () => JSON.stringify(S.pos),
//          restore: (j) => { S.pos = JSON.parse(j); }, bounds: () => [0, 0, E.W, E.H], onChange: render });

import { esc, overlaps } from '../util.js';
import { closePopup } from '../soul/ui.js';
import { SNAP_PX, snapTargets, dragTo } from './snap.js';
import { COALESCE_MS, createHistory } from './history.js';
import { placeInspector } from './inspector.js';

export { SNAP_PX, snapTargets, snapAxis, dragTo } from './snap.js';
export { COALESCE_MS, createHistory } from './history.js';
export { PAD, GAP, placeInspector, inspectorHtml } from './inspector.js';

/** Pointer travel (layer px) before a press becomes a drag. */
const DRAG_START_PX = 2;
const NUDGE = 1, NUDGE_SHIFT = 10;

/**
 * opts: { surface (gets pointer/wheel input), overlay (gets the outlines; same px space), toLocal(clientX, clientY) → {x, y},
 *   items() → [{ id, box: [x0,y0,x1,y1], label }] in draw order (last = top-most), move(id, box), snapshot(), restore(json),
 *   bounds() → [x0,y0,x1,y1], snapRects() → extra snap rects (vanilla HUD …), snapPx = 5, edgeInset = 0, clampToBounds = true,
 *   inspector (element placed beside the selection), avoid() → rects the inspector keeps clear, onSelect(id), onChange(),
 *   onWheel(id, dir ±1, event) (runs inside an edit coalesced per item), keyboard = true, coalesceMs = 800,
 *   ignore (selector whose pointerdowns are not editor input) }.
 * Returns { selected, dragging, select(id), edit(fn, key?), undo(), redo(), canUndo(), canRedo(), render(), controls(), destroy() }.
 */
export function createEditor(o) {
  const {
    surface, overlay, toLocal, items, move, snapshot, restore, bounds,
    snapRects = () => [], edgeInset = 0, clampToBounds = true, inspector = null, avoid = () => [],
    onSelect = () => {}, onChange = () => {}, onWheel = null, keyboard = true, coalesceMs = COALESCE_MS,
    ignore = '.s-panel, .s-poplayer, .s-modallayer, [data-ed-ignore]',
  } = o;
  const st = { snap: true, snapPx: o.snapPx ?? SNAP_PX };
  const hist = createHistory({ snapshot, restore, coalesceMs });
  let sel = null, press = null, drag = null, mouse = null, inspSide = 'right', inspOpen = true;
  overlay.classList.add('ed-overlay');

  const list = () => items();
  const byId = (id) => list().find((it) => it.id === id) || null;
  /** Items under a point, top-most first. */
  const itemsAt = (x, y) => list().filter((it) => x >= it.box[0] && x <= it.box[2] && y >= it.box[1] && y <= it.box[3]).map((it) => it.id).reverse();
  const changed = () => {
    onChange();
    render();
  };

  function select(id) {
    if (id === sel) return;
    sel = id;
    inspSide = 'right';
    inspOpen = true;
    onSelect(id);
    render();
  }
  function edit(fn, key = null) {
    if (hist.edit(fn, key, sel)) changed();
  }
  const refocus = (e) => {
    if (e && e.focus && byId(e.focus)) select(e.focus);
    changed();
  };
  const undo = () => { const e = hist.undo(); if (e) refocus(e); };
  const redo = () => { const e = hist.redo(); if (e) refocus(e); };

  // ---------------------------------------------------------------- overlay
  const rectDiv = (cls, x0, y0, x1, y1) => `<div class="${cls}" style="left:${x0}px;top:${y0}px;width:${Math.max(0, x1 - x0)}px;height:${Math.max(0, y1 - y0)}px"></div>`;
  const outline = (b, cls) => rectDiv('ed-box' + cls, Math.floor(b[0]) - 1, Math.floor(b[1]) - 1, Math.round(b[2]) + 1, Math.round(b[3]) + 1);
  /** Name label 10 px above the outline, below it when there is no room. */
  const label = (b, text, cls) => {
    const y0 = Math.floor(b[1]) - 1, y1 = Math.round(b[3]) + 1;
    return `<div class="ed-lbl ${cls}" style="left:${Math.floor(b[0]) - 1}px;top:${y0 - 10 >= bounds()[1] ? y0 - 10 : y1 + 2}px">${esc(text)}</div>`;
  };

  function render() {
    const its = list(), B = bounds();
    if (sel && !its.some((it) => it.id === sel)) sel = null;
    let hovered = null;
    if (mouse && !press && !drag) {
      const under = itemsAt(mouse.x, mouse.y);
      hovered = under.find((id) => id === sel) || under[0] || null;
    }
    let html = '';
    for (const it of its) {
      if (it.id === sel) continue;
      const hov = it.id === hovered;
      html += outline(it.box, hov ? ' hover' : '');
      if (hov && it.label) html += label(it.box, it.label, 'hover');
    }
    const s = sel ? its.find((it) => it.id === sel) : null;
    if (s) {
      html += outline(s.box, ' sel');
      if (s.label) html += label(s.box, s.label, 'sel');
    }
    if (drag) {
      for (const gx of drag.guidesX) html += rectDiv('ed-guide', gx - 0.5, B[1], gx + 0.5, B[3]);
      for (const gy of drag.guidesY) html += rectDiv('ed-guide', B[0], gy - 0.5, B[2], gy + 0.5);
    }
    overlay.innerHTML = html;
    surface.classList.toggle('ed-hovering', !!hovered);
    surface.classList.toggle('ed-dragging', !!drag);
    if (!inspector) return;
    inspector.classList.toggle('ed-hidden', !s || !inspOpen);
    if (!s || !inspOpen) return;
    if (drag) {
      // Frozen while dragging; it hides if the dragged box goes under it, and is re-placed on the drop.
      const r = inspector.getBoundingClientRect(), a = toLocal(r.left, r.top), z = toLocal(r.right, r.bottom);
      inspector.classList.toggle('ed-under', overlaps([a.x, a.y, z.x, z.y], s.box));
      return;
    }
    inspector.classList.remove('ed-under');
    inspSide = placeInspector(inspector, s.box, { bounds: B, side: inspSide, avoid: avoid() }).side;
  }

  // ---------------------------------------------------------------- pointer
  const isEditorTarget = (t) => !(t.closest && (t.closest(ignore) || (inspector && inspector.contains(t))));

  function onDown(e) {
    if ((e.button !== 0 && e.button !== 2) || !isEditorTarget(e.target)) return;
    const p = toLocal(e.clientX, e.clientY);
    const a = document.activeElement;
    if (a && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) a.blur();
    closePopup();
    const under = itemsAt(p.x, p.y);
    if (!under.length) {
      select(null);
      return;
    }
    e.preventDefault();
    const target = sel && under.includes(sel) ? sel : under[0];
    const cycle = target === sel && under.length > 1;
    select(target);
    if (e.button !== 0) return;
    press = { id: target, x: p.x, y: p.y, cycle, under };
    window.addEventListener('pointerup', onUp);
  }

  function onMove(e) {
    const p = toLocal(e.clientX, e.clientY);
    const r = surface.getBoundingClientRect();
    mouse = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom ? p : null;
    if (press) {
      if (!drag) {
        if (Math.hypot(p.x - press.x, p.y - press.y) < DRAG_START_PX) return;
        const it = byId(press.id);
        if (!it) return;
        const others = list().filter((x) => x.id !== press.id).map((x) => x.box).concat(snapRects());
        const B = bounds();
        drag = {
          id: press.id, start: it.box.slice(), before: snapshot(), guidesX: [], guidesY: [],
          targetsX: snapTargets(B, others, true, edgeInset), targetsY: snapTargets(B, others, false, edgeInset),
        };
      }
      const res = dragTo(drag.start, p.x - press.x, p.y - press.y, {
        bounds: bounds(), targetsX: drag.targetsX, targetsY: drag.targetsY, snap: st.snap && !e.altKey, snapPx: st.snapPx, clampTo: clampToBounds,
      });
      drag.guidesX = res.guidesX;
      drag.guidesY = res.guidesY;
      move(drag.id, res.box);
      changed();
      return;
    }
    render();
  }

  function onUp() {
    window.removeEventListener('pointerup', onUp);
    const pr = press, d = drag;
    press = null;
    drag = null;
    if (d) {
      hist.push(d.before, snapshot(), null, d.id);
      changed();
    } else if (pr && pr.cycle) {
      const i = pr.under.indexOf(sel);
      select(pr.under[(i + 1) % pr.under.length]);
    } else render();
  }

  function onLeave() {
    mouse = null;
    if (!press) render();
  }

  function onWheelEv(e) {
    if (!onWheel || !isEditorTarget(e.target)) return;
    const raw = e.deltaY !== 0 ? -e.deltaY : e.shiftKey ? -e.deltaX : 0; // Shift+wheel may arrive as deltaX
    if (!raw) return;
    const p = toLocal(e.clientX, e.clientY);
    const under = itemsAt(p.x, p.y);
    const id = under.find((u) => u === sel) || under[0];
    if (!id) return;
    e.preventDefault();
    select(id);
    edit(() => onWheel(id, Math.sign(raw), e), 'wheel:' + id);
  }

  function onInspClick(e) {
    if (!e.target.closest('[data-ed="close"]')) return;
    inspOpen = false;
    render();
  }

  // ---------------------------------------------------------------- keys
  function onKey(e) {
    const t = e.target;
    if (t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) || t.isContentEditable)) return;
    if (document.querySelector('.s-modallayer')) return;
    if (e.ctrlKey || e.metaKey) {
      const k = e.key.toLowerCase();
      if (k === 'z') {
        e.preventDefault();
        if (e.shiftKey) redo();
        else undo();
      } else if (k === 'y') {
        e.preventDefault();
        redo();
      }
      return;
    }
    if (press) return;
    if (e.key === 'Tab') {
      const ids = list().map((it) => it.id);
      if (!ids.length) return;
      e.preventDefault();
      const i = ids.indexOf(sel);
      select(ids[i < 0 ? 0 : (i + (e.shiftKey ? ids.length - 1 : 1)) % ids.length]);
      return;
    }
    if (e.key === 'Escape' && sel) {
      select(null);
      return;
    }
    const step = e.shiftKey ? NUDGE_SHIFT : NUDGE;
    const d = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[e.key];
    const it = d && sel ? byId(sel) : null;
    if (!it) return;
    e.preventDefault();
    edit(() => move(it.id, [it.box[0] + d[0], it.box[1] + d[1], it.box[2] + d[0], it.box[3] + d[1]]), 'nudge:' + it.id);
  }

  surface.addEventListener('pointerdown', onDown);
  surface.addEventListener('pointerleave', onLeave);
  surface.addEventListener('wheel', onWheelEv, { passive: false });
  const onMenu = (e) => { if (isEditorTarget(e.target)) e.preventDefault(); };
  surface.addEventListener('contextmenu', onMenu);
  window.addEventListener('pointermove', onMove);
  if (inspector) inspector.addEventListener('click', onInspClick);
  if (keyboard) window.addEventListener('keydown', onKey);
  render();

  return {
    get selected() { return sel; },
    get dragging() { return drag ? drag.id : null; },
    select,
    edit,
    undo,
    redo,
    canUndo: hist.canUndo,
    canRedo: hist.canRedo,
    render,
    /** Drawer items for the editor's tunables. */
    controls: () => [
      { type: 'heading', text: 'Editor' },
      { type: 'check', label: 'Snapping', hint: 'Alt ignores it while dragging', get: () => st.snap, set: (v) => { st.snap = v; } },
      { type: 'range', label: 'Snap distance', min: 0, max: 12, step: 1, get: () => st.snapPx, set: (v) => { st.snapPx = v; }, format: (v) => v + ' px' },
      { type: 'buttons', buttons: [{ label: 'Undo', onClick: undo }, { label: 'Redo', onClick: redo }] },
    ],
    destroy() {
      surface.removeEventListener('pointerdown', onDown);
      surface.removeEventListener('pointerleave', onLeave);
      surface.removeEventListener('wheel', onWheelEv);
      surface.removeEventListener('contextmenu', onMenu);
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('keydown', onKey);
      if (inspector) inspector.removeEventListener('click', onInspClick);
      overlay.innerHTML = '';
    },
  };
}
