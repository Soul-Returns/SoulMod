// Move GUI drag + snap math (HudMoveScreen / HudEdits), pure, in the caller's px space (Soul px). Boxes
// are [x0, y0, x1, y1]. Port 1:1: magnet within SNAP_PX of a target line on each axis (the box's start,
// center or end, nearest wins), else the axis lands on a whole px.
//
// Exports: SNAP_PX, snapTargets(bounds, rects, horizontal, inset?), snapAxis(start, len, targets, snapPx?, guides?),
//   dragTo(startBox, dx, dy, opts) → { box, guidesX, guidesY }.
// Usage: const r = dragTo(start, p.x - down.x, p.y - down.y, { bounds, targetsX, targetsY, snap: !e.altKey });

import { clamp } from '../util.js';

/** HudMoveScreen.SNAP_PX / HudEdits.SNAP_PX: magnet distance for guides, in Soul px. */
export const SNAP_PX = 5;

/** Snap lines on one axis: bounds start (+inset) / center / end (-inset), then every rect's start, center, end. */
export function snapTargets(bounds, rects, horizontal, inset = 0) {
  const [a0, a1] = horizontal ? [bounds[0], bounds[2]] : [bounds[1], bounds[3]];
  const e = clamp(inset, 0, (a1 - a0) / 2);
  const out = [a0 + e, (a0 + a1) / 2, a1 - e];
  for (const r of rects) {
    const s = horizontal ? r[0] : r[1], t = horizontal ? r[2] : r[3];
    out.push(s, (s + t) / 2, t);
  }
  return out;
}

/**
 * Shift that puts the span [start, start + len] on the nearest target within [snapPx] (measured from
 * its start, center or end), or null. Every target the shifted span then touches is pushed to [guides].
 */
export function snapAxis(start, len, targets, snapPx = SNAP_PX, guides = []) {
  const edges = [start, start + len / 2, start + len];
  let best = null;
  for (const e of edges) {
    for (const t of targets) {
      const d = t - e;
      if (Math.abs(d) <= snapPx && (best === null || Math.abs(d) < Math.abs(best))) best = d;
    }
  }
  if (best === null) return null;
  for (const e of edges) for (const t of targets) if (Math.abs(t - (e + best)) < 0.5 && !guides.includes(t)) guides.push(t);
  return best;
}

/**
 * The box a drag puts [startBox] at after moving the pointer by (dx, dy): kept inside [bounds] when
 * [clampTo], then snapped per axis (unless [snap] is false, e.g. Alt held); unsnapped axes round to
 * whole px. opts: { bounds, targetsX, targetsY, snap = true, snapPx = SNAP_PX, clampTo = true }.
 */
export function dragTo(startBox, dx, dy, { bounds, targetsX = [], targetsY = [], snap = true, snapPx = SNAP_PX, clampTo = true }) {
  const w = startBox[2] - startBox[0], h = startBox[3] - startBox[1];
  let x0 = startBox[0] + dx, y0 = startBox[1] + dy;
  if (clampTo && bounds) {
    x0 = clamp(x0, bounds[0], Math.max(bounds[0], bounds[2] - w));
    y0 = clamp(y0, bounds[1], Math.max(bounds[1], bounds[3] - h));
  }
  const guidesX = [], guidesY = [];
  const sx = snap ? snapAxis(x0, w, targetsX, snapPx, guidesX) : null;
  const sy = snap ? snapAxis(y0, h, targetsY, snapPx, guidesY) : null;
  x0 += sx !== null ? sx : Math.round(x0) - x0;
  y0 += sy !== null ? sy : Math.round(y0) - y0;
  return { box: [x0, y0, x0 + w, y0 + h], guidesX, guidesY };
}
