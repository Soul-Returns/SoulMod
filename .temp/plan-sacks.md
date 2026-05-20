# Plan brief — Sack support + clipboard-copy upgrades

> **For a fresh Claude Code session.** Paste this whole file as the kickoff prompt (or invoke `/plan` and reference it). It is intentionally self-contained — the project's CLAUDE.md + CLAUDE.local.md are loaded automatically by SessionStart, so this brief focuses on the work itself.

## Status of the working tree

- Mayor-Diana profit tracker work has been **stashed** as `stash@{0}` ("Diana profit tracker WIP — postponed for sack support"). Do NOT pop it; finish the work below first.
- The pre-stash plan for the Diana tracker is still at `~/.claude/plans/quizzical-puzzling-key.md` for reference if it helps shape future plans (no need to read unless curious).

## Big picture

Four related deliverables, listed in **suggested ship order** (each is independently mergeable):

1. **Chest-only `/soul dev copyOpenedGui`** (small) — current dump includes the bottom 36 player slots; restrict to the upper container portion only.
2. **`Alt + Right-Click` chat-copy modifier** (small/medium) — new fourth modifier mode that captures hover-tooltip components in addition to the visible line, so multi-part Hypixel chat (`[Sacks] +30 items. (Last 5s.)` style) can be inspected fully.
3. **Sack chat reader** (medium) — uses the tooltip data from (2) as its protocol surface to apply incremental sack-content deltas.
4. **Sack GUI reader + state** (medium/large) — opens an authoritative snapshot path: when a player opens a Hypixel sack screen, the mod parses every slot's count from its lore and reconciles against the in-memory state. This is the source of truth; the chat reader is the incremental delta channel between snapshots.

Ship (1) and (2) first as standalone PRs — both are cleanly scoped and don't depend on the sack data model. (3) and (4) are tightly coupled and should go together (or in close succession: (4) first to establish the state, then (3) to feed it).

---

## Deliverable 1 — Chest-only `copyOpenedGui`

### What's there today

`src/main/kotlin/com/soulreturns/features/dev/DevKeybindHandler.kt` — `copyOpenedGui()` (around line 100). Iterates every non-empty slot in `screen.menu.slots` and dumps the JSON. For a 4-row sack the dump is 72 slots (36 chest + 36 player), of which ~36-40 are non-empty — the player inventory pollutes every diagnostic dump.

Example of the current output is at `/mnt/c/Users/soul/Documents/Minecraft/gui.json` (Combat Sack, 72 slots; slots 0-35 are sack contents, slots 36-71 are the player's hotbar+inventory+offhand). The slots from index 36 onward in that file are the player's actual carried items (visible Erudite Deific Spade, FAKE_SHURIKEN, SKYBLOCK_MENU, etc.) — exactly the noise we want gone.

### Approach

Skip slots whose `slot.container` is the player's `Inventory`. This handles every menu type uniformly (chest, anvil, beacon, Hypixel-custom GUIs) — no hardcoded "drop last 36 slots" math, no per-menu-class dispatch.

```kotlin
import net.minecraft.world.entity.player.Inventory
// inside copyOpenedGui():
val playerInv = Minecraft.getInstance().player?.inventory
for ((idx, slot) in menu.slots.withIndex()) {
    if (playerInv != null && slot.container === playerInv) continue
    val stack = slot.item
    if (stack.isEmpty) continue
    slotsArr.add(itemToJson(stack).apply { addProperty("slot", idx) })
}
```

The `===` identity check is intentional — slot.container is the same Inventory instance for every player-inventory slot the menu exposes.

### Optional polish (skip for v1 unless cheap)

Add a second JSON key `playerInventoryIncluded: false` for clarity in saved dumps, and bump `slotCount` to reflect filtered count. Up to the implementer.

### Verification

- Open a Combat Sack in-game, hit the copyOpenedGui keybind, paste into a scratch file — slot indices should run 0..35 only, total non-empty ≈ 36.
- Open the regular Player Inventory (E key) → screen IS `AbstractContainerScreen` with `InventoryMenu`, and all slots ARE on the player inventory → output is empty `slots: []`. Document this as expected: nothing to do, that's "no chest open from the tool's POV." If the user complains, surface a chat message `"§7Open container has no non-player slots."` and short-circuit.

### Touch points

- `src/main/kotlin/com/soulreturns/features/dev/DevKeybindHandler.kt::copyOpenedGui` — only file to modify.
- (Optional) `assets/soul/lang/en_us.json` keybind tooltip can be updated to mention the chest-only behavior.

---

## Deliverable 2 — `Alt + Right-Click` chat-copy with tooltip extraction

### What's there today

- `src/main/kotlin/com/soulreturns/features/chat/ChatRightClickCopy.kt` — handles plain RC (full message plain text), Shift+RC (single line plain), Ctrl+RC (full message with `§`-codes preserved).
- `src/main/java/com/soulreturns/mixin/render/ChatScreenRightClickCopyMixin.java` — HEAD inject on `ChatScreen.mouseClicked`, reads `MouseButtonEvent.hasShiftDown() / hasControlDown()` and forwards to the Kotlin handler.
- `util/MessageDetector.stripColorCodes` strips the full Hypixel `§.` family (including `§y` / `§u` / `§x` placeholder codes).
- `util/Chat.kt :: Component.toLegacyText` reconstructs `§`-coded text from a `Component` tree.

### Approach

Add a fourth modifier mode: **Alt+RC** copies the same content as Ctrl+RC (full message with color codes) **plus** any hover-tooltip components embedded in the message. The output format is a JSON envelope so downstream parsers (the future sack chat reader) don't have to re-derive structure from a flat blob.

#### Output shape

```json
{
  "message": "§e§l[Sacks] §a+30 items §7(Last 5s.)",
  "messagePlain": "[Sacks] +30 items (Last 5s.)",
  "lines": [
    {
      "text": "§e§l[Sacks] §a+30 items §7(Last 5s.)",
      "tooltip": "§a+25 §fRed Mushroom\n§a+5 §fBrown Mushroom\n§7\n§eTotal: §a30 items"
    }
  ]
}
```

- `message` / `messagePlain` are convenience top-level joins (`\n`-separated when the batch is multi-line — same `addedTime` grouping `resolveFullMessageText` already does).
- `lines` is the per-line breakdown; each line carries its own tooltip text. Tooltips are concatenated `§`-coded for the same reason the message itself is — `style.hoverEvent.value` is a `Component`, so `toLegacyText` works directly.
- When a line has no hover event, omit the `tooltip` field (don't emit `null` / `""` — keep the JSON terse for grepping).

This is JSON for one reason: future automation. A human reading the clipboard wants to see the structure (Hypixel's sack-summary tooltips have ~15 lines of per-item breakdown). The Ctrl+RC mode stays plain-text-only for the existing use case.

#### Tooltip extraction

`Component` is a tree of `MutableComponent` nodes, each with a `Style` carrying an optional `HoverEvent`. The codepath:

```kotlin
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

fun extractHoverTexts(component: Component): List<Component> {
    val out = mutableListOf<Component>()
    fun walk(c: Component) {
        val hover = c.style.hoverEvent
        if (hover is HoverEvent.ShowText) out += hover.value()
        c.siblings.forEach(::walk)
    }
    walk(component)
    return out
}
```

The exact HoverEvent class shape changed across 1.21.x — in 1.21.11 Mojmap it's sealed: `HoverEvent.ShowText(Component)`, `HoverEvent.ShowItem(...)`, `HoverEvent.ShowEntity(...)`. Pattern-match the variant via `is HoverEvent.ShowText` (the only one we care about for chat tooltips today). Use `if/else if` chains, NOT `when (hover)` on the sealed class subjects — CLAUDE.md notes that enum/sealed `when` emits a `$WhenMappings` synthetic that Fabric's KnotClassLoader can fail to resolve at runtime.

Per Hypixel chat protocol, the hover-text is almost always attached to the **root component** of a single `GuiMessage`, not split across siblings. The walk-and-collect above handles both cases defensively.

Multiple tooltips per line are possible (e.g. a clickable username in the middle of a sentence has its own hover). Concatenate them with `\n---\n` separators in the output, or pick the first non-null — implementer's call; recommend "first non-null" for v1 since multi-hover lines are rare.

#### Wiring

1. `ChatScreenRightClickCopyMixin.java`: read `click.hasAltDown()` and forward as a fourth boolean arg.
2. `ChatRightClickCopy.handleRightClick(x, y, shift, ctrl, alt)`: add `alt` branch BEFORE the existing `shift / ctrl / else` chain. Alt takes priority over Shift+Ctrl combinations.
3. The Alt branch builds the JSON envelope via Gson and copies the serialised string. Reuse the `resolveFullMessageText(chat, fcs, withCodes = true)` path to find the matching batch — that's where `lines` come from.

#### Lang + tooltip discoverability

There's no config toggle to add — Alt+RC is the same feature as the existing right-click-copy, just a new modifier. Add a line to the existing `text.config.soul/config.option.general.chat.enableRightClickCopy.tooltip` mentioning all four modifiers: plain / Shift / Ctrl / Alt.

### Verification

- Trigger a `[Sacks] +30 items. (Last 5s.)` line in-game by adding items to a sack. Hover the line manually to see what Hypixel's tooltip contains.
- Alt-right-click the line, paste the clipboard somewhere — expect the JSON envelope with `tooltip` populated.
- Non-tooltip lines (plain chat) should still work: copy structure populated, `tooltip` field absent. The other three modifiers (plain / Shift / Ctrl) MUST behave exactly as before — don't regress them.

### Touch points

- `src/main/kotlin/com/soulreturns/features/chat/ChatRightClickCopy.kt` — add `alt` parameter + Alt branch + tooltip extraction helper.
- `src/main/java/com/soulreturns/mixin/render/ChatScreenRightClickCopyMixin.java` — read alt modifier, plumb through.
- (Optional) `assets/soul/lang/en_us.json` — extend the right-click-copy tooltip description.

---

## Deliverable 3 — Sack chat reader

> Implement AFTER (2) lands, since this is the first real consumer of the Alt-RC tooltip extraction.

### Background — the Hypixel "[Sacks]" chat protocol

When the player picks up items that route into a sack, Hypixel batches the change and sends one chat line every ~5 seconds during active gain/loss:

```
[Sacks] +30 items. (Last 5s.)
[Sacks] -12 items. (Last 5s.)
```

The line is clickable / hoverable; the hover tooltip contains the per-item breakdown:

```
+25  Red Mushroom
+5   Brown Mushroom

Total: +30 items
```

That tooltip is **structured Component data** — exactly what Deliverable 2 surfaces. The chat reader walks the same component tree internally (no need to wait for the user to Alt-RC; it's a regular ChatMessage event the listener can subscribe to and walk the hover directly).

### What to build

- `data/sacks/SackEvents.kt` — `data class SackTooltipReceived(val deltas: Map<String /* displayName */, Long>, val totalDelta: Long, val raw: Component)`.
- `features/sacks/SackChatReader.kt` — `object` singleton subscribing to `ChatMessage`. On every `[Sacks] ([+-])\d+ items?\.` line: extract the root hover Component, parse the per-item lines from its plain-text rendering (or directly from the tree), publish a `SackTooltipReceived` event, AND apply the deltas to `SackState`.
- The reader must be **defensive against the displayName not being in the catalog**. Hypixel doesn't expose `skyblockId` in the sack tooltip — only display names — so the catalog from Deliverable 4 is what maps `"Red Mushroom"` → `RED_MUSHROOM`. Unrecognised names log a single warn and are dropped (not silently — we want to see catalog gaps).

### Parsing the tooltip

The tooltip text is `\n`-separated. Lines look like:

```
§a+25 §fRed Mushroom
§a+5 §fBrown Mushroom
§7
§eTotal: §a30 items
```

Strip `§.` codes (`MessageDetector.stripColorCodes`), then per non-blank, non-`Total:` line:

```
([+-])(\d+)\s+(.+)
```

Captures (sign, count, displayName). Skip the trailing `Total: N items` line — we recompute the total from the per-item sum and verify it matches; a mismatch logs warn (Hypixel parsing drift, missing catalog entry).

### Touch points

- New: `src/main/kotlin/com/soulreturns/data/sacks/SackEvents.kt`.
- New: `src/main/kotlin/com/soulreturns/features/sacks/SackChatReader.kt`.
- Wire in `Soul.kt :: registerFeatures()` after PriceCache + the existing chat-driven readers.

---

## Deliverable 4 — Sack GUI reader + state

### Background — Hypixel's sack inventory

Opening `Sacks Menu` (the hub item) → category submenus → individual sack screens (Combat Sack, Mining Sack, Farming Sack, Fishing Sack, Foraging Sack, plus specials like Lapis Sack, Pearl Sack, Spooky Sack, Mythological Sack, Talisman Bag, etc.).

Each sack screen is an `AbstractContainerScreen` with a `ChestMenu` (rows vary by sack — 3-6 typically). Each item slot displays:
- `customName` — the item display name with rarity color (e.g. `§fRed Mushroom`).
- `lore` — multi-line description. The current-count is in the lore as a line like `Stored: §a1,234` or `Held: §a567 §7/§e1,000`. Hypixel's exact wording varies; capture the live format by Alt-RCing a sack open then checking the lore lines. Plan for both `Stored:` and `Held:` patterns.

### What to build

#### `data/sacks/SackCatalog.kt`

Hardcoded enum or JSON catalog of every sack-able item. Key facts per entry:
- `skyblockId` (e.g. `RED_MUSHROOM`) — the source of truth for the data model.
- `displayName` (e.g. `"Red Mushroom"`) — the lookup key from sack tooltip parsing.
- `sackKind: SackKind` (COMBAT / MINING / FORAGING / FARMING / FISHING / SPECIAL / etc.) — for grouping and per-sack reconciliation.

Source the initial list from SkyHanni-REPO's `constants/Sacks.json` (or equivalent) — that's the canonical community-maintained list. Mirror it as `docs/item-catalog/seeds/sacks.json` (parallel to the `diana-drops.json` mirror added during the stashed Diana work) so the future catalog-backend can ingest it.

`SackKind` is an enum: COMBAT, MINING, FORAGING, FARMING, FISHING, SPECIAL, AGRONOMY (Pest Sack), POCKET_GEMSTONE (Gemstone Sack), CANDY (event), ENCHANTED_AGRONOMY, etc. Keep it open-ended — Hypixel adds new sacks each release.

#### `features/sacks/SackState.kt`

Singleton holding `private val counts: ConcurrentHashMap<String /* skyblockId */, Long>`. Per-profile keying — store in `PersistentStats` (mirrors how seasoning / dragon / fishing stats are kept), OR in a dedicated `config/soul/sacks.json` (mirrors the standalone profit-tracker pattern). Recommend **standalone file** for two reasons: (a) the sack map can be large (hundreds of entries), bloating the shared `stats.json`; (b) it's easy to wire into the cloud-sync engine later as its own `SyncKind`.

API:
- `get(itemId: String): Long`
- `getAll(): Map<String, Long>` — defensive snapshot
- `setSnapshot(snap: Map<String, Long>, sackKind: SackKind?)` — replaces ONLY the keys belonging to `sackKind` if non-null (used by the GUI reader on a per-sack open) or the entire map when null (rare; full reset).
- `applyDeltas(deltas: Map<String, Long>)` — additive; used by the chat reader.
- `resetAll()` — debug.

Tick-debounced JSON save (same pattern as `DragonProfitTracker`).

#### `features/sacks/SackGuiReader.kt`

Hook into `ScreenEvents.AFTER_INIT` (already wired in `Soul.kt`). When the new screen is `AbstractContainerScreen<*>` and its title matches a known sack (`SackCatalog.sackKindByScreenTitle(screen.title.string)`), schedule a one-frame-delay scan (Hypixel populates slot items via packets a tick or two after the screen opens — scanning at AFTER_INIT shows the placeholder pane). On scan:

1. For each non-empty slot whose container is NOT the player Inventory (reuse Deliverable 1's filter logic — extract it into a helper).
2. Resolve `displayName → skyblockId` via `SackCatalog.byDisplayName`.
3. Parse the count from the lore. Walk `stack.get(DataComponents.LORE)?.lines()` and regex-match each line; the count is the first numeric capture from the matching line.
4. Build a per-sack `Map<String, Long>` snapshot.
5. Call `SackState.setSnapshot(snap, sackKind)` — replaces just that sack's keys, leaving other sacks' state intact.

The reader fires once per screen open. Multiple opens in quick succession debounce naturally because each open re-runs the scan.

#### Dev commands

- `/soul dev sackGet <skyblockId>` — print the cached count.
- `/soul dev sackDump [<kind>]` — dump the full map (optionally filtered by kind) sorted by count desc.
- `/soul dev sackReset` — wipe state.

### Touch points

- New: `src/main/kotlin/com/soulreturns/data/sacks/SackCatalog.kt`, `data/sacks/SackKind.kt`.
- New: `src/main/kotlin/com/soulreturns/features/sacks/SackState.kt`, `SackGuiReader.kt`, plus `SackChatReader.kt` from Deliverable 3.
- New: `docs/item-catalog/seeds/sacks.json`.
- Modify: `src/main/kotlin/com/soulreturns/Soul.kt` — wire `SackState.init()` + `SackChatReader.register()` + `SackGuiReader.register()` in `registerFeatures()`.
- Modify: `src/main/kotlin/com/soulreturns/commands/subcommands/DevSubcommand.kt` — add the three dev commands above.

---

## Conventions / gotchas to keep in mind

These apply to everything below; mostly already in CLAUDE.md but worth repeating because they bite repeatedly:

- **Mojmap names.** `Component` not `Text`, `Minecraft` not `MinecraftClient`, `GuiGraphics` not `DrawContext`, `Player` not `PlayerEntity`, `MouseButtonEvent` not `Click`. The codebase migrated off Yarn.
- **Never `when (enumValue)` or `when (sealedClassValue)`.** Emits `$WhenMappings` synthetic that Fabric's KnotClassLoader can fail to resolve, producing a deferred `NoClassDefFoundError` on first branch hit. Use `if (x == A) … else if (x == B) … else …`. `when (someString)` / `when (someInt)` / subjectless `when { … }` are safe.
- **Static Java methods can't be called as Kotlin properties.** `Util.getPlatform()`, `SharedConstants.getCurrentVersion()` — always the explicit method-call form.
- **Locale-safe formatting.** `String.format(Locale.ROOT, …)` everywhere — system locale can use `,` as decimal separator.
- **`cfg` is read at point of use.** Never cache config values.
- **owo-config nests are properties (no parens), leaves are getter methods (parens):** `cfg.dev.updates.checkForUpdates()` NOT `cfg.dev().updates().checkForUpdates()`.
- **ktlint is clean.** Run `cmd.exe /c "set JAVA_HOME=C:\Users\soul\.jdks\jbr-21.0.11&&gradlew.bat :1.21.11:ktlintFormat"` on touched files before opening a PR. The compileKotlin recipe is the same with `:1.21.11:compileKotlin`.
- **Never `git commit` or `git push` without an unambiguous instruction.** Stage freely; stop and report.
- **Existing trackers / readers as templates.** `FishingTracker` (chat-driven counters into `PersistentStats`), `DragonProfitTracker` (standalone JSON, partitioned state, tick-debounced save), `LocationReader` / `ProfileReader` (tab-list polling pattern), `MineshaftCorpses` (per-tick container scan). Read at least one before writing the analogous new component.

---

## Suggested PR sequencing

1. **PR-1: Chest-only `copyOpenedGui`** — 1 file change, 5 LOC, no tests, ships in 10 minutes.
2. **PR-2: Alt+RC chat-copy with tooltip extraction** — 2 files, ~80 LOC. Standalone diagnostic feature; unblocks tooling for understanding sack tooltip shapes empirically before writing the parser.
3. **PR-3 (or 4 + 3 combined): Sack catalog + state + GUI reader** — first establishes the data model and snapshot path. State is empty until first sack is opened, but state persists between sessions.
4. **PR-4: Sack chat reader** — incremental deltas now flow into the established state. The chat protocol can be reverse-engineered using the Alt+RC clipboard dump from PR-2.

Up to whoever picks this up whether to merge 3 and 4 into one PR — the chat reader is small once the catalog exists. The sequencing above just maximises the number of independently mergeable units in case the work fragments across sessions.

---

## Open questions to surface to the user mid-implementation

These shouldn't block PR-1 / PR-2 (they're scoped enough not to need answers). PR-3 / PR-4 SHOULD pause on these:

1. **Sack catalog source.** Reuse SkyHanni-REPO's `constants/Sacks.json` directly (LGPL-2.1 attribution already on the Used Software screen), or write our own curated list? Recommendation: ingest SkyHanni's list as the seed but maintain it locally — same approach the `DianaDrops.json` seed plan used.
2. **State storage.** Standalone `config/soul/sacks.json` (recommended; mirrors profit-tracker pattern) vs. integrating into `PersistentStats.Data` (simpler but bloats the shared blob).
3. **Cloud sync.** Should sack state sync between devices? If yes, register a new `SyncKind.SACKS` alongside `CONFIG` / `GUI_LAYOUT` / `STATS`. Recommend deferring sync to a follow-up PR — get the local path solid first.
4. **HUD / display surface.** Is there a planned HUD to render sack contents (a "show me top 10 most-stored items" overlay)? Or is the state purely backend for future features (a "you're nearly full on X" alert)? Default v1: state-only, no HUD; user-facing feature comes later.
