// Minecraft default-font metrics in GUI px, so boxes sized around vanilla text (tooltips, counts,
// sidebar) match the game even though the kit draws a monospace stand-in.
//   mcWidth('§l§6LEGENDARY') → 66        mcCharWidth('i') → 2        `letter-spacing:${mcSpacing(s)}px`
// Exports: MC_LINE, MC_ASCENT, STANDIN_ADVANCE, mcCharWidth(ch, bold?), mcWidth(text), mcSpacing(text).

/** Font.lineHeight: one text line is 9 GUI px. */
export const MC_LINE = 9;
/** Glyph ascent of the default bitmap font (font/include/default.json, ascii.png provider). */
export const MC_ASCENT = 7;

// Advances (glyph width + 1) of ASCII 32..126, measured from textures/font/ascii.png in the 26.2 client
// jar; space is 4 from font/include/space.json.
const ASCII = '42466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247';
// Non-ASCII glyphs SkyBlock text uses (unifont provider): approximate advances; others fall back to FALLBACK.
const EXTRA = { '«': 7, '»': 7, '°': 5, '²': 5, '▬': 9, '█': 9, '❤': 8, '✦': 8, '✪': 8, '✎': 8, '❈': 8, '❁': 8, '☘': 8, '⸕': 6, '➜': 8, '▸': 4, '▶': 6, '◆': 6, '⚔': 8 };
// Unicode-provider glyphs and Hypixel's resource-pack icons (private use area) have no fixed width here.
const FALLBACK = 6;

/** Advance of one character; bold glyphs are drawn twice 1 px apart, so they are 1 px wider. */
export function mcCharWidth(ch, bold = false) {
  const c = ch.codePointAt(0);
  const w = c >= 32 && c <= 126 ? +ASCII[c - 32] : EXTRA[ch] ?? FALLBACK;
  return w + (bold ? 1 : 0);
}

/** Width of a § formatted string as Font.width measures it (codes take no space; §l bold until a color or §r). */
export function mcWidth(s) {
  let w = 0, bold = false;
  const parts = String(s).split(/(§#[0-9a-fA-F]{6}|§.)/);
  for (const p of parts) {
    if (!p) continue;
    if (p[0] === '§' && p.length > 1) {
      const c = p.slice(1).toLowerCase();
      if (c === 'l') bold = true;
      else if (c === 'r' || c.startsWith('#') || /^[0-9a-f]$/.test(c)) bold = false;
      continue;
    }
    for (const ch of p) w += mcCharWidth(ch, bold);
  }
  return w;
}

/** Advance of the kit's monospace stand-in (--mc-font) at 8 px: 0.6 em, as DejaVu Sans Mono and most monospaces. */
export const STANDIN_ADVANCE = 4.8;
/**
 * CSS letter-spacing (px) that stretches or squeezes the stand-in so `s` spans exactly mcWidth(s),
 * keeping text inside boxes sized by vanilla metrics. Use as `style="letter-spacing:${mcSpacing(s)}px"`.
 */
export function mcSpacing(s) {
  const n = [...String(s).replace(/§(#[0-9a-fA-F]{6}|.)/g, '')].length;
  return n ? Math.round(((mcWidth(s) - n * STANDIN_ADVANCE) / n) * 1000) / 1000 : 0;
}
