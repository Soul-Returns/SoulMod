// The Move GUI inspector glued to the selection: right of it (or left, whichever fits; the side sticks),
// clamped into the bounds with a scroll cap, stepping below / above when it would cover a rect to keep
// clear. Same px space as the selection box. Plus the inspector's frame markup (header + scroll body).
//
// Exports: PAD, GAP, placeInspector(el, box, opts) → { side, rect }, inspectorHtml(title, body, opts).
// Usage: insp.innerHTML = inspectorHtml('Sack Tracker', rows); side = placeInspector(insp, box, { bounds, side }).side;

import { clamp, esc, overlaps } from '../util.js';
import { ICON } from '../soul/ui.js';

/** HudMoveScreen.PAD: panels keep this far from the screen edge. GAP: inspector to selection. */
export const PAD = 6;
export const GAP = 6;

/**
 * Position [el] (absolute, in the box's px space) next to [box]. opts: { bounds: [x0,y0,x1,y1],
 * side = 'right' | 'left' (the sticky side), avoid: [rects] it must not cover (e.g. what the selection
 * is attached to), pad = PAD, gap = GAP }. Sets left / top / maxHeight; returns the side used and its rect.
 */
export function placeInspector(el, box, { bounds, side = 'right', avoid = [], pad = PAD, gap = GAP }) {
  const [L, T, R, B] = bounds;
  el.style.maxHeight = 'none';
  const iw = el.offsetWidth, nat = el.offsetHeight;
  const maxX = Math.max(L + pad, R - pad - iw);
  const at = (s) => {
    if (s === 'right' || s === 'left') {
      const x = clamp(s === 'right' ? box[2] + gap : box[0] - gap - iw, L + pad, maxX);
      const maxH = Math.max(40, B - T - pad * 2), ih = Math.min(nat, maxH);
      const y = clamp(box[1], T + pad, Math.max(T + pad, B - pad - ih));
      return { side: s, x, y, ih, maxH, fits: s === 'right' ? box[2] + gap + iw <= R - pad : box[0] - gap - iw >= L + pad };
    }
    const x = clamp(box[0], L + pad, maxX), need = Math.min(nat, 120);
    if (s === 'below') {
      const y = Math.max(T + pad, box[3] + gap), maxH = Math.max(40, B - pad - y);
      return { side: s, x, y, ih: Math.min(nat, maxH), maxH, fits: y + need <= B - pad };
    }
    const maxH = Math.max(40, box[1] - gap - T - pad), ih = Math.min(nat, maxH);
    return { side: s, x, y: box[1] - gap - ih, ih, maxH, fits: box[1] - gap - need >= T + pad };
  };
  const other = (s) => (s === 'right' ? 'left' : 'right');
  const rect = (c) => [c.x, c.y, c.x + iw, c.y + c.ih];
  const covers = (c) => avoid.some((a) => overlaps(rect(c), a));
  let c = at(side === 'left' ? 'left' : 'right');
  if (!c.fits) {
    const o = at(other(c.side));
    c = o.fits ? o : at(R - box[2] >= box[0] - L ? 'right' : 'left');
  }
  if (covers(c)) {
    // The clear spot that shows the most of the inspector (below / above may have to scroll).
    const alts = [at(other(c.side)), at('below'), at('above')].filter((o) => o.fits && !covers(o) && !overlaps(rect(o), box));
    const best = alts.reduce((p, o) => (!p || o.ih > p.ih + 0.5 ? o : p), null);
    if (best) c = best;
  }
  el.style.maxHeight = c.maxH + 'px';
  el.style.left = c.x + 'px';
  el.style.top = c.y + 'px';
  return { side: c.side === 'left' || c.side === 'right' ? c.side : side, rect: rect(c) };
}

/**
 * Inspector frame (HudMoveScreen HEADER_H 26): title, optional chip, close button (data-ed="close"),
 * scrolling body. Put it in an element with classes `s-panel s-compact ed-insp`.
 */
export function inspectorHtml(title, body, { chip = '', close = true } = {}) {
  return `<div class="ed-ih"><span class="ed-it">${esc(title)}</span>${chip ? `<span class="ed-chip">${esc(chip)}</span>` : ''}${close ? `<button type="button" class="ed-x" data-ed="close" title="Close">${ICON.close}</button>` : ''}</div><div class="ed-ib">${body}</div>`;
}
