// Shared helpers for every kit module and mock. No DOM state here.

export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];
export const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
/** Up to two decimals, no trailing zeros: 1.5, 2, 0.33. */
export const fmt = (v) => (Math.round(v * 100) / 100).toString();
export const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
export const deep = (o) => JSON.parse(JSON.stringify(o));
/** [x0, y0, x1, y1] boxes. */
export const overlaps = (a, b) => a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];
/** 1,234,567 */
export const num = (n) => Math.round(n).toLocaleString('en-US');
/** 1.2k / 3.4M / 5.6B, like SkyBlock's short numbers. */
export function short(n) {
  const a = Math.abs(n);
  for (const [d, s] of [[1e12, 'T'], [1e9, 'B'], [1e6, 'M'], [1e3, 'k']]) {
    if (a >= d) return (Math.round((n / d) * 10) / 10).toString() + s;
  }
  return String(Math.round(n));
}
/** Seeded PRNG (mulberry32) so fake data is the same on every reload. */
export function rng(seed = 1) {
  let t = seed >>> 0;
  const next = () => {
    t += 0x6d2b79f5;
    let r = Math.imul(t ^ (t >>> 15), 1 | t);
    r ^= r + Math.imul(r ^ (r >>> 7), 61 | r);
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
  };
  next.int = (a, b) => a + Math.floor(next() * (b - a + 1));
  next.pick = (arr) => arr[Math.floor(next() * arr.length)];
  return next;
}

// ------------------------------------------------------------------ Minecraft text
/** Minecraft's 16 chat colors by § code. */
export const MC_COLORS = {
  0: '#000000', 1: '#0000aa', 2: '#00aa00', 3: '#00aaaa', 4: '#aa0000', 5: '#aa00aa', 6: '#ffaa00', 7: '#aaaaaa',
  8: '#555555', 9: '#5555ff', a: '#55ff55', b: '#55ffff', c: '#ff5555', d: '#ff55ff', e: '#ffff55', f: '#ffffff',
};
/** Shadow color of a text color: each channel / 4, as vanilla draws it. */
export function mcShadow(hex) {
  const v = parseInt(hex.slice(1), 16);
  const c = (s) => ((v >> s) & 255) >> 2;
  return `rgb(${c(16)},${c(8)},${c(0)})`;
}
/** SkyBlock rarities: name, § code and color. */
export const RARITY = Object.fromEntries([
  ['COMMON', 'f'], ['UNCOMMON', 'a'], ['RARE', '9'], ['EPIC', '5'], ['LEGENDARY', '6'], ['MYTHIC', 'd'],
  ['DIVINE', 'b'], ['SPECIAL', 'c'], ['VERY SPECIAL', 'c'], ['ULTIMATE', '4'], ['ADMIN', '4'],
].map(([name, code]) => [name, { name, code, color: MC_COLORS[code] }]));

/**
 * § formatted text to HTML spans: colors 0-f, §#rrggbb, §l bold, §o italic, §n underline, §m strike,
 * §k obfuscated (class mc-obf), §r reset. [base] is the § code (or #hex) the text starts in; [shadow]
 * adds vanilla's per-color drop shadow inline.
 */
export function mcText(s, { base = null, shadow = false } = {}) {
  let color = base ? (base.startsWith('#') ? base : MC_COLORS[base]) : null;
  let fmtSet = new Set();
  let out = '';
  const parts = String(s).split(/(§#[0-9a-fA-F]{6}|§.)/);
  for (const p of parts) {
    if (!p) continue;
    if (p[0] === '§' && p.length > 1) {
      const c = p.slice(1).toLowerCase();
      if (c.startsWith('#')) { color = c; fmtSet = new Set(); }
      else if (MC_COLORS[c]) { color = MC_COLORS[c]; fmtSet = new Set(); }
      else if (c === 'r') { color = base ? (base.startsWith('#') ? base : MC_COLORS[base]) : null; fmtSet = new Set(); }
      else if ('klmno'.includes(c)) fmtSet.add(c);
      continue;
    }
    const st = [];
    if (color) st.push(`color:${color}`);
    if (shadow && color) st.push(`text-shadow:1px 1px 0 ${mcShadow(color)}`);
    if (fmtSet.has('l')) st.push('font-weight:bold');
    if (fmtSet.has('o')) st.push('font-style:italic');
    const deco = [fmtSet.has('n') && 'underline', fmtSet.has('m') && 'line-through'].filter(Boolean).join(' ');
    if (deco) st.push(`text-decoration:${deco}`);
    const cls = fmtSet.has('k') ? ' class="mc-obf"' : '';
    out += st.length || cls ? `<span${cls}${st.length ? ` style="${st.join(';')}"` : ''}>${esc(p)}</span>` : esc(p);
  }
  return out;
}
export const stripCodes = (s) => String(s).replace(/§(#[0-9a-fA-F]{6}|.)/g, '');
