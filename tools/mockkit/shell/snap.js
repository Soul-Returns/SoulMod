/*
 * Screenshot of a same-origin frame without dependencies: the frame's DOM is cloned with its live
 * values (inputs, canvases, scroll), every stylesheet is inlined (fonts and url() assets as data
 * URLs), and the result is drawn as an SVG <foreignObject> image onto a canvas. Chromium and Firefox
 * keep that canvas untainted because nothing in the SVG is external.
 *
 * Not captured: cross-origin images/frames, inner iframes, :hover styles, running animations
 * (drawn at their start), scroll positions of inner scroll boxes (their top/left is shown).
 *
 *   const png = await capture(frame.contentWindow, { highlight: { x, y, w, h } });  // data:image/png
 */

const dataCache = new Map();
async function toDataUrl(url) {
  if (url.startsWith('data:')) return url;
  if (!dataCache.has(url)) {
    dataCache.set(url, (async () => {
      const r = await fetch(url);
      if (!r.ok) throw new Error(`${r.status} ${url}`);
      const blob = await r.blob();
      return await new Promise((ok, fail) => {
        const fr = new FileReader();
        fr.onload = () => ok(fr.result);
        fr.onerror = fail;
        fr.readAsDataURL(blob);
      });
    })().catch(() => 'data:,'));
  }
  return dataCache.get(url);
}

const XML_NAME = /^[A-Za-z_][-A-Za-z0-9_.:]*$/;
const URL_RE = /url\(\s*(['"]?)([^'")]+)\1\s*\)/g;
async function inlineUrls(css, base) {
  const jobs = [];
  css.replace(URL_RE, (m, q, u) => {
    if (!u.startsWith('data:') && !u.startsWith('#')) jobs.push(u);
    return m;
  });
  const map = new Map();
  await Promise.all(jobs.map(async (u) => map.set(u, await toDataUrl(new URL(u, base).href))));
  return css.replace(URL_RE, (m, q, u) => (map.has(u) ? `url("${map.get(u)}")` : m));
}

async function sheetText(sheet) {
  let rules;
  try { rules = sheet.cssRules; } catch { return ''; }
  const base = sheet.href ?? sheet.ownerNode?.ownerDocument?.baseURI ?? location.href;
  const parts = [];
  for (const r of rules) {
    if (r.type === CSSRule.IMPORT_RULE) {
      if (r.styleSheet) parts.push(await sheetText(r.styleSheet));
    } else parts.push(r.cssText);
  }
  return inlineUrls(parts.join('\n'), base);
}

const cssCache = new WeakMap();
async function documentCss(doc) {
  const out = [];
  for (const sheet of doc.styleSheets) {
    if (sheet.disabled) continue;
    // a sheet object is replaced when it reloads, so caching per object stays correct
    if (!cssCache.has(sheet)) cssCache.set(sheet, sheetText(sheet));
    out.push(await cssCache.get(sheet));
  }
  return out.join('\n');
}

/** Copy what cloneNode leaves behind: form values, canvas pixels, scroll offsets. */
function syncLive(src, dst) {
  const a = src.querySelectorAll('input, textarea, select, canvas');
  const b = dst.querySelectorAll('input, textarea, select, canvas');
  for (let i = 0; i < a.length; i++) {
    const s = a[i], d = b[i];
    if (!d) break;
    if (s.tagName === 'INPUT') {
      if (s.type === 'checkbox' || s.type === 'radio') s.checked ? d.setAttribute('checked', '') : d.removeAttribute('checked');
      else d.setAttribute('value', s.value);
    } else if (s.tagName === 'TEXTAREA') d.textContent = s.value;
    else if (s.tagName === 'SELECT') {
      [...d.options].forEach((o, j) => (s.options[j]?.selected ? o.setAttribute('selected', '') : o.removeAttribute('selected')));
    } else if (s.tagName === 'CANVAS') {
      const img = dst.ownerDocument.createElement('img');
      for (const at of s.attributes) img.setAttribute(at.name, at.value);
      try { img.src = s.toDataURL(); } catch {}
      const cs = s.ownerDocument.defaultView.getComputedStyle(s);
      img.style.width = cs.width;
      img.style.height = cs.height;
      d.replaceWith(img);
    }
  }
}

/**
 * PNG data URL of the frame. [crop] {x, y, w, h} in CSS px keeps only that area; [scale] renders at
 * that many device px per CSS px (the SVG is vector, so it stays sharp); [highlight] outlines a rect.
 */
export async function capture(win, { highlight = null, label = '', crop = null, scale = 1 } = {}) {
  const doc = win.document;
  await doc.fonts?.ready;
  const W = doc.documentElement.clientWidth, H = doc.documentElement.clientHeight;
  const css = await documentCss(doc);
  const root = doc.documentElement.cloneNode(true);
  syncLive(doc.documentElement, root);
  root.querySelectorAll('script, link, style, noscript, iframe').forEach((n) => n.remove());
  // HTML parsing keeps attribute names XML rejects (e.g. from an unescaped quote in a style), and one
  // such name makes the whole SVG undecodable
  for (const el of root.querySelectorAll('*')) {
    for (const at of [...el.attributes]) if (!XML_NAME.test(at.name)) el.removeAttribute(at.name);
  }
  await Promise.all([...root.querySelectorAll('img')].map(async (img) => {
    const src = img.getAttribute('src');
    if (src && !src.startsWith('data:')) img.setAttribute('src', await toDataUrl(new URL(src, doc.baseURI).href));
    img.removeAttribute('srcset');
  }));
  await Promise.all([...root.querySelectorAll('[style*="url("]')].map(async (el) => {
    el.setAttribute('style', await inlineUrls(el.getAttribute('style'), doc.baseURI));
  }));
  let head = root.querySelector('head');
  if (!head) {
    head = doc.createElement('head');
    root.prepend(head);
  }
  const style = doc.createElement('style');
  style.textContent = css;
  head.appendChild(style);
  root.style.width = `${W}px`;
  root.style.height = `${H}px`;
  root.style.overflow = 'hidden';
  if (win.scrollX || win.scrollY) {
    const body = root.querySelector('body');
    if (body) body.style.transform = `translate(${-win.scrollX}px, ${-win.scrollY}px)`;
  }
  const xhtml = new XMLSerializer().serializeToString(root);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}"><foreignObject x="0" y="0" width="100%" height="100%">${xhtml}</foreignObject></svg>`;
  const img = new Image();
  img.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  await img.decode();
  // the first paint of an SVG image can still miss its embedded fonts; give it a frame
  await new Promise((r) => setTimeout(r, 60));
  const c = crop ?? { x: 0, y: 0, w: W, h: H };
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(c.w * scale));
  canvas.height = Math.max(1, Math.round(c.h * scale));
  const g = canvas.getContext('2d');
  g.scale(scale, scale);
  g.translate(-c.x, -c.y);
  g.drawImage(img, 0, 0, W, H);
  if (highlight) drawHighlight(g, highlight, label);
  return canvas.toDataURL('image/png');
}

function drawHighlight(g, r, label) {
  g.save();
  g.lineWidth = 2;
  g.strokeStyle = '#ff7eb6';
  g.shadowColor = 'rgba(0,0,0,.6)';
  g.shadowBlur = 4;
  g.strokeRect(r.x - 2, r.y - 2, r.w + 4, r.h + 4);
  if (label) {
    g.font = '12px sans-serif';
    const tw = g.measureText(label).width + 10;
    const y = r.y - 20 >= 0 ? r.y - 20 : r.y + r.h + 4;
    g.fillStyle = '#ff7eb6';
    g.fillRect(r.x - 2, y, tw, 17);
    g.fillStyle = '#0b0d1c';
    g.shadowBlur = 0;
    g.fillText(label, r.x + 3, y + 12.5);
  }
  g.restore();
}
