# Mock parts

Do not open the kit sources unless a part is missing here. Every call below was run against the real exports.
Everything returns an HTML string: put it in a layer (`layers.soul.innerHTML = ...`, see `/kit/lean.js`).
`layers.soul` is in Soul px (1280x720 at Soul 1.5); `layers.screen` and `layers.gui` are in GUI px (640x360 at GUI 3).
Position anything with `ui.at(x, y, html, 'Label')`. Text is `§` colour codes (`§a` green, `§l` bold, `§7` gray, `§r` reset).

## Soul UI (Soul px) — `import { panel, row, toggle, seg, button, dropdown, textField } from '/kit/soul/ui.js'`
Controls with a `key` read and write `state[key]` by themselves; the page redraws.
- Panel: `panel(row('Sound', toggle('sound', state.sound)) + row('Sort', dropdown('sort', state.sort, [['name', 'Name'], ['value', 'Value']])), { width: 220, pad: 8 })`
- Row (label left, control right): `row('Label', control, { hint: 'small gray text' })`
- Toggle switch: `toggle('sound', state.sound)`; toggle that looks like a button: `toggleButton('Compact', 'compact', state.compact)`
- Segmented / tab strip: `seg('tab', state.tab, [['a', 'Session'], ['b', 'Total']])`
- Buttons: `button('Save', { key: 'save', variant: 'accent' })` (variants `accent`, `icon`, `tab`; `on: true` fills it)
- Dropdown: `dropdown('sort', state.sort, [['name', 'Name'], ['value', 'Value']], { width: 120 })`
- Text field: `textField('q', state.q, { placeholder: 'Search', width: 160 })`
- Slider and number field: `slider('scale', state.scale, { min: 0.5, max: 3, step: 0.1 })`, `stepper('n', state.n, { min: 0, max: 9 })`
- Collapsible section: `section('Display', rows, { open: true })`; confirm dialog: `confirmHtml({ title, text, ok })`

## HUD content (Soul px) — `import { trackerPanel, alertHud, notificationHud, mobHealthHud, textHud } from '/kit/soul/hud.js'`
- Tracker panel: `trackerPanel({ title: 'Sacks', timer: '00:12:41' | { text, paused: true }, tab: 'Session', scrollable: false, columns: [{ width: 38 }], rows: [{ label: 'Mithril', color: '#55ff55', icon: '#55ff55', cells: ['1,204'] }], chips: ['Total 1.4M'] })`; `interactive: true` adds the tab strip and footer
- Alert (big centered text): `alertHud({ title: 'Rare drop!', subtitle: 'Enchanted Mithril ×64', color: '#ff5555', more: 2 })`
- Toast stack: `notificationHud({ cards: [{ text: 'Profile sync is up to date.', color: '#ffffff' }] })`
- Mob health card: `mobHealthHud({ name: '§cVoidgloom Seraph IV', level: 655, current: '68.6M', max: '210M', fraction: 0.33 })`
- Text lines on a card: `textHud(['§6Purse: §e1.2M', '§7Bits: §b13,845'], { size: 10, align: 'left' })`; bare card: `card(html, { pad: 4 })`

## Items, inventories, tooltips (GUI px) — `/kit/mc/items.js`, `/kit/mc/inventory.js`, `/kit/mc/tooltip.js`
- Item: `const it = item({ id: 'REFINED_DIAMOND', mcId: 'minecraft:diamond', name: '§aRefined Diamond', lore: ['§7Crafted from 10 Diamonds', '', '§a§lUNCOMMON'], count: 4 })` (rarity comes from the last lore line; `icon: 'sword'` or `'orb:#ff55ff'` overrides the drawn icon)
- Icon in your own HTML (16 px in GUI px, scale it yourself in Soul px): `itemIcon(it)`; rarity line: `rarityLine('LEGENDARY', 'SWORD')`
- Chest or SkyBlock menu: `const c = chestScreen({ sw: env.gw, sh: env.gh, rows: 4, title: 'Sack of Sacks', items: skyblockMenu({ rows: 4, back: 'SkyBlock Menu', items: { [slotIndex(1, 4)]: it } }) }); layers.screen.innerHTML = c.html;`
- Player inventory: `layers.screen.innerHTML = playerScreen({ sw: env.gw, sh: env.gh, player: { hotbar: [it], main: [] } }).html`
- Hover tooltips on those slots (once, inside `if (first)`): `bindInventory(layers.screen, { tooltip: (slot) => cur.items[slot] })` with `cur = c` set in draw
- Tooltip on any of your own elements: put `${ui.tip(['§aBazaar', '§7Highest buy order'])}` in its tag; for an item `ui.tip([it.name, ...it.lore])`
- Standalone tooltip HTML: `tooltip(it, { mx: 100, my: 80, sw: env.gw, sh: env.gh })` (item or § lines; GUI px)

## Vanilla HUD — `frame: { hud: true }` or `frame: { hud: { ...options } }`
Hotbar, hearts, food, armor, XP, action bar, chat, SkyBlock scoreboard and a boss bar are on by default. Options (all optional):
`hotbar`, `selected`, `items` (9 CSS colors or null), `status`, `health`, `armor`, `food`, `xpLevel`, `actionBar: '§c6,267/5,892❤'`,
`chat: false`, `chatLines: [{ text: '§aHello', age: 0 }]`, `scoreboard: false`, `scoreTitle`, `scoreLines: ['Purse: §61.2M', '']`, `bossBar: null | [{ name, progress, color }]`, `title: '§cRARE DROP!'`, `subtitle`.

## Text and numbers — `import { mcText, num, short, esc } from '/kit/util.js'`
- `mcText('§aGreen §l§6Gold')` → HTML spans; `num(1234567)` → `1,234,567`; `short(1234567)` → `1.2M` (`15300` → `15.3k`); `esc(text)` escapes user text
- Colors: `MC_COLORS` (§ code → hex), `RARITY` (name → color and code)

## The page (`lean()` options, `draw` arguments)
- `ui.set('tab', 'history')` on a clickable element sets `state.tab`; `ui.toggle('open')` flips `state.open`; `ui.put(key, value)` from your own handler
- Soul px size of the layer: `env.W` x `env.H`; GUI px: `env.gw` x `env.gh`
- Extra CSS: `lean({ css: ['mock.css'] })`; name things users will comment on with `ui.at(..., 'Label')` or `data-mock-label="..."`
