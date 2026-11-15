// Vanilla item tooltip in GUI px: GuiGraphicsExtractor.tooltip + DefaultTooltipPositioner +
// TooltipRenderUtil (26.2), with the tooltip/background and tooltip/frame nine-slice sprites redrawn in CSS.
//   layer.insertAdjacentHTML('beforeend', tooltip(hoveredItem, { mx, my, sw: E.gw, sh: E.gh }));
// Exports: TT, tooltipLines(itemOrLines), tooltipSize(lines), tooltipPos(mx, my, w, h, sw, sh), tooltip(itemOrLines, opts).

import { mcText } from '../util.js';
import { mcWidth, mcSpacing } from './font.js';

/** Constants from the 26.2 client (javap of the classes named). */
export const TT = {
  OFFSET_X: 12, OFFSET_Y: -12, // DefaultTooltipPositioner: mouse + (12, -12)
  FLIP: 24, MIN_X: 4,          // too wide for the right side → x - 24 - width, at least 4
  BOTTOM: 3,                   // y + height + 3 must fit the screen, else pushed up (no top clamp)
  LINE: 10,                    // ClientTextTooltip.getHeight
  TITLE_GAP: 2,                // extra space after the first line
  PAD: 3,                      // TooltipRenderUtil.PADDING_*; the background reaches 1 px further, without corners
};

/** [name, ...lore] of an item, or the lines as given. */
export const tooltipLines = (src) => (Array.isArray(src) ? src : src ? [src.name, ...(src.lore ?? [])] : []);

/** Text block size: widest line by Font.width; height 10 per line, 8 for a single line. */
export function tooltipSize(lines) {
  return { w: Math.max(0, ...lines.map(mcWidth)), h: lines.length === 1 ? 8 : lines.length * TT.LINE };
}

/** Top-left of the text block for the mouse at (mx, my) on an sw × sh GUI-px screen. */
export function tooltipPos(mx, my, w, h, sw, sh) {
  let x = mx + TT.OFFSET_X, y = my + TT.OFFSET_Y;
  if (x + w > sw) x = Math.max(x - TT.FLIP - w, TT.MIN_X);
  if (y + h + TT.BOTTOM > sh) y = sh - h - TT.BOTTOM;
  return { x, y };
}

/**
 * Tooltip HTML, absolutely positioned in a GUI-px layer. `src` is an item or § lines; opts
 * `{ mx, my, sw, sh }` = mouse position and screen size in GUI px. Lore without its own color
 * shows gray (SkyBlock always sets one; vanilla's purple-italic lore default is not modeled).
 */
export function tooltip(src, { mx, my, sw, sh }) {
  const lines = tooltipLines(src);
  if (!lines.length) return '';
  const { w, h } = tooltipSize(lines);
  const { x, y } = tooltipPos(mx, my, w, h, sw, sh);
  const P = TT.PAD;
  const body = lines.map((l, i) => `<div class="mc-text" style="top:${i * TT.LINE + (i ? TT.TITLE_GAP : 0)}px;letter-spacing:${mcSpacing(l)}px">${mcText(l, { base: i ? '7' : 'f', shadow: true })}</div>`).join('');
  return `<div class="mc-tt" style="left:${x - P - 1}px;top:${y - P - 1}px;width:${w + 2 * P + 2}px;height:${h + 2 * P + 2}px">`
    + `<i class="mc-tt-bg"></i><i class="mc-tt-frame"></i><div class="mc-tt-lines" style="left:${P + 1}px;top:${P + 1}px">${body}</div></div>`;
}
