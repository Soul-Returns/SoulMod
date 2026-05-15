# Soul UI framework — roadmap

Live tracker for the rebuild of every Soul UI surface (HUDs, screens, alerts) onto the
NanoVG + Soul-composer framework that landed in **P1 + P2**. Update this file after each
sub-phase commit.

## Status

| Phase | Scope | Status |
|---|---|---|
| **P1** | NanoVG runtime (lwjgl-nanovg, Inter fonts, NvgRenderer / NvgFont / NvgFrame / NvgPipState / NvgPipRenderer, GlDebugMixin) | ✅ shipped |
| **P2.1** | Composer + Text/Box/Column + padding/background/size modifiers | ✅ shipped |
| **P2.2** | Row/Spacer/Surface + Alignment + Arrangement + width/height/fillMax modifiers | ✅ shipped |
| **P2.3** | Input system (HitRegion, SoulInput, clickable/scrollable modifiers, Button, hover) | ✅ shipped |
| **P2.4** | Toggle, Slider (drag), Tabs, ScrollableList (self-clamping), Theme | ✅ shipped |
| **P2.5** | SoulHud register API + SoulHudElement + SoulHudRegistry + SoulScreen + GuiEditScreen overlay fix | ✅ shipped |
| **P3** | Migrate every HUD to the new framework | ✅ shipped |
| **P4** | Migrate every Screen, retire owo-ui dependency | ✅ shipped |
| **P5** | Polish: dropdowns, anchor system, schema migration, animations, theme variants | 🔄 partial |

## P3 outcome

All 7 HUDs migrated to `SoulHud.register` + composables. Legacy `gui/lib/tracker/` package
deleted entirely (TrackerOverlay, TrackerOverlayRegistry, TrackerOverlayRenderer,
TrackerOverlayElement, TrackerSettings, TrackerSettingsStore, TrackerInputHandler — gone).
`GuiLayoutApi.updateTrackerOverlay`, `TrackerOverlayElement` GuiElement subclass,
`TRACKER_OVERLAY_*` hit-region kinds, and the `NvgSmokeTest` scaffolding all removed.
`GuiRenderer.renderHud` still handles `ItemTrackerElement` for non-Soul features that may
still need it; that path retires in P4 once nothing references it.

Files in the new state:
- `ui/hud/LegionHud.kt`, `BobbinHud.kt`, `PartyHud.kt`, `MineshaftCorpsesHud.kt`,
  `SeasoningHud.kt`, `FishingHud.kt`, `FishingFestivalHud.kt` — all `SoulHud.register`-based
- `features/fishing/FishingHudSettings.kt` — tab/sort/limit/scroll persistence at
  `config/soul/fishing_hud.json`
- Cloud sync still mirrors `gui_layout.json` + `stats.json` + `config.json5` as before. The
  `fishing_hud.json` is **not** synced — UI preferences are user-local. Reassess in P4 if
  the user wants them synced.

---

## P3 — Migrate HUDs

Each item: rewrite the existing HUD as a `@SoulComposable fun` registered via
`SoulHud.register(...)`. Retire the legacy `ui/hud/*Hud.kt` file + the corresponding
`TextBlockElement` / `ItemTrackerElement` it used to call into.

State remains in the existing `features/<domain>/*State` singletons — the composables just
read from those. No state migration needed.

### Order of attack (smallest blast radius first)

1. **Festival sticker** (`features/fishing/FishingTrackerOverlay`'s `FESTIVAL_STICKER_ID`
   text block) — one-line text. Easy warmup; verifies the NVG render path replaces
   `TextBlockElement` cleanly.
2. **Legion HUD** (`ui/hud/LegionHud.kt`) — simple per-tick counter, no per-feature state.
3. **Bobbin HUD** (`ui/hud/BobbinHud.kt`) — count + alert badge. Mostly text.
4. **Party HUD** (`ui/hud/PartyHud.kt`) — member list rendering; touches `PartyManager` state.
5. **Mineshaft Corpses HUD** (`ui/hud/MineshaftCorpsesHud.kt`) — per-type rows with counts;
   first non-trivial layout.
6. **Seasoning HUD** (`ui/hud/SeasoningHud.kt`) — multi-line text, `[Reset Session]` click
   region. Re-implement the click with `Button` or `Surface.clickable(...)`. **Owns a
   click handler today via a custom hit-region** in the legacy `gui/lib/` system —
   migration retires that handler.
7. **Fishing Tracker overlay** (`features/fishing/FishingTrackerOverlay` + the entire
   `gui/lib/tracker/` package) — the biggest migration. Replaces:
   - `TrackerOverlay` / `TrackerTab` / `TrackerRow` / `TrackerSortOption` /
     `TrackerSettings` / `TrackerSettingsStore` / `TrackerOverlayRegistry` /
     `TrackerOverlayElement` / `TrackerOverlayRenderer` / `TrackerInputHandler`
   - …with a Soul-composer composable using `Surface { Column { Tabs(...); ScrollableList { ... }; Row { Button(...); Button(...); Button(...) } } }`.
   - The settings file moves: `config/soul/tracker_settings.json` → either a Soul-framework
     equivalent or just inlined as a `PersistentStats` field (decide during the migration).
8. **Alerts** (`util/RenderUtils.kt :: showAlert + renderAlerts`) — center-of-screen big
   text + bell chime, with a 6 s fade-out. **Animated, so this is the first widget that
   needs the animation API from P5**. May need to ship a minimal animation primitive
   (frame-deltatime + interpolation helper) early; alternatively keep `RenderUtils` on the
   legacy `GuiGraphics` path through P3 and migrate as part of P5.

### Retirement checklist after every HUD migration

- [ ] Delete the old `ui/hud/XxxHud.kt`
- [ ] Remove its `register()` call from `Soul.registerFeatures()`
- [ ] Remove obsolete imports
- [ ] Confirm no other code path references the file (grep)
- [ ] Reload + test in-game (panel renders, position via `/soul gui` works, scale via wheel works)

### Retirement of legacy GUI pipeline

After **every** HUD is on `SoulHud`:
- `gui/lib/GuiRendering.kt :: GuiRenderer.renderHud` becomes a no-op for layouts with only
  `SoulHudElement`s — delete the dispatch switch (or keep as no-op fall-through).
- `MinecraftGuiRenderContext`, `GuiRenderContext`, the `TextBlockElement` /
  `ItemTrackerElement` / `TrackerOverlayElement` subclasses, and the corresponding
  `GuiLayoutApi.updateTextBlock` / `updateTrackerOverlay` API can be deleted.
- `GuiLayoutManager` becomes pure persistence for `SoulHudElement` only — simplifies the
  Gson type adapter, drops the legacy `when` switches.
- `gui/lib/tracker/` deleted entirely (replaced by composable Fishing tracker).
- The `SoulGuiHudAdapter.renderHud` legacy snapshot call gets removed; the adapter becomes
  a thin shim that just calls `SoulHud.dispatchAll`.

---

## P4 outcome

All three owo-ui screens migrated. `io.wispforest.owo.ui.*` imports are gone from Kotlin
code; `modImplementation("io.wispforest:owo-lib:...")` still ships at runtime because the
**owo-config** annotation processor remains (it generates `SoulConfig.java` from
`SoulConfigModel.java`). UI half is dead-code at runtime — keep an eye on the dep size if
that ever matters; for now removing it would force re-jigging the AP wiring for no real win.

Shipped in P4:

- **UpdateModal** (`update/UpdateModal.kt`) — `SoulScreen` with title + body + two buttons.
- **Profile Viewer** (`profileviewer/gui/ProfileViewerScreen.kt`, `DungeonsTab.kt`) — Tabs
  strip, ScrollableList body, Surface cards. Built a new `ProgressBar` composable
  (`ui/foundation/ProgressBar.kt`) for the dungeon XP bars.
- **Config screen** (`config/gui/SoulConfigScreen.kt` + `ui/config/registry/ConfigSections.kt`
  + `ui/config/model/*` + `ui/config/search/*`) — declarative extension maps survive
  unchanged; the row/icon owo-ui renderers under `ui/config/components/*` and
  `ui/config/rows/RowBuilders.kt` were deleted. Built supporting widgets:
  - `TextField` (`ui/foundation/TextField.kt`) — single-line editable text with focus +
    cursor + selection.
  - `Slider` (continuous) / per-step rendering — `SoulIntSlider` discrete variant
    inlined as a `Slider` configuration rather than its own widget.
  - `Image` composable + `NvgRenderer.loadImage` for the Discord / GitHub social icons in
    the title bar.
- **GuiEditScreen** stays on vanilla `Screen` per the original recommendation — it's an
  edit tool, not gameplay UI, and the cost of migrating outweighs the consistency win.
  Got significant feature growth (anchor preset menu, corner indicators, dynamic bounds)
  but the underlying base is still vanilla Screen.

---

## P5 — Polish

Mixed bag of UX + framework features. Several major items shipped while others are still
ahead. **Don't trust the "not started" status from before — read this section.**

### Shipped in P5

- **`Dropdown` + `MultiSelectDropdown`** (`ui/foundation/Dropdown.kt`). Trigger pill + popup
  overlay with checkbox (multi) or accent-bar (single) option indicators. Popup is painted
  via the new deferred-overlay mechanism (see below), opens upward or downward depending on
  panel-local room (`computePopupY` reads `SoulInput.panelHeight`), and supports a
  `popupMaxHeight` cap with mouse-wheel scrolling for long option lists (used by the
  fishing tracker's 19-variant Category dropdown).
- **Deferred overlay pass + modal click routing.** `SoulInput.queueOverlay(action)` queues
  paint lambdas that `SoulHud.renderOne` drains after the main tree's `root.draw(...)`, so
  popups land on top of every regular sibling. `SoulInput.beginModal()` flips a flag that
  routes subsequent `recordRegion` calls into a separate `modalRegions` list; click / scroll
  / hover dispatch only consult that list while a popup is open, so a sibling dropdown's
  trigger or a button under the popup can't steal the click.
- **Panel size on `SoulInput`.** `panelWidth` / `panelHeight` exposed (set by
  `NvgFrame.submit`) so widgets can reason about their containing panel — the popup
  direction picker uses this instead of screen-relative math, so a HUD anchored near the top
  of the screen with a footer dropdown opens the popup upward into the panel's open space.
- **Anchor alignment system.** `HudHorizontalAnchor` / `HudVerticalAnchor` fields on
  `SoulHudElement` change the pivot edge (Start / Center / End on each axis). `SoulHud.
  resolveBaseX/Y` apply the alignment using the latest measured size, so a Center-anchored
  HUD sits dead-center regardless of resolution / GUI scale / content size. Right-click in
  `/soul gui` opens a context menu with five corner presets (Top Left / Top Right / Bottom
  Left / Bottom Right / Center) + a **Settings** entry that deep-links to the HUD's
  registered config category via `SoulConfigScreen(initialCategory, initialSubcategory)`.
  A small yellow dot marks each enabled HUD's pivot pixel while the editor is open.
- **`gui_layout.json` schema migration.** `GuiLayoutManager.CURRENT_SCHEMA_VERSION = 2`;
  loadOrInitialize / reload probe the raw JSON for the `schemaVersion` key (Gson defaults
  can't be trusted — `Unsafe` bypasses Kotlin constructor defaults on `SoulHudElement`,
  leaving the new enum fields null) and wipe the file when it's missing or below the current
  version. Per-frame `SoulHud.ensureLayoutElement` then re-seeds defaults.
- **`Modifier.weight(...)`** on `Row` / `Column`. Two-phase measure: unweighted children
  measure intrinsically, leftover space distributed proportionally to weighted children. Used
  for the fishing tracker's centered title cell and full-width dropdown row.
- **`Button.centerLabel`** parameter. Wraps the label in `Row(fillMaxWidth, Center)` so a
  `fillMaxWidth()` button centers its text instead of pinning to the left.
- **`NvgRenderer.filledTriangle`** primitive. Used by the dropdown caret instead of a
  text-based glyph (Inter doesn't cover ▾ / ▴).
- **Dynamic GUI Edit bounds.** `SoulHudRegistry.recordMeasured(id, w, h)` is written in
  `SoulHud.renderOne` after the tree draws; `GuiEditScreen` and `GuiEdit.findHitElement`
  read it back via `lastMeasured(id)` so selection boxes match the actual rendered HUD
  size instead of the registered max bound.

### Still ahead in P5

- **Theme variants** (Dark / Light / HighContrast) + custom accent color toggle.
- **Animation library** (`animateFloatAsState`, easing primitives, frame-deltatime tracker
  on `NvgFrame.submit`).
- **Keyboard navigation** (Tab focus traversal across clickables, arrow keys for sliders
  and scroll lists). `TextField` already handles char + edit keys via
  `SoulInput.queueChar` / `queueEditKey`.
- **A11y / high-contrast variant + reducedMotion toggle.**
- **Move framework docs out of CLAUDE.md** into a dedicated `docs/ui-framework.md`. CLAUDE.md
  has been growing; a split would keep the main file a high-level index.

---

## P5 — Polish

### Theme variants
- Add a `cfg.general.ui.theme: enum { Dark, Light, HighContrast }` toggle. `SoulTheme`
  swaps the active `SoulColors` based on it.
- Custom accent color — `cfg.general.ui.accentColor: Int` (hex picker in config).

### Animation library
- `animateFloatAsState(target, durationMs, easing) -> Float` Compose-style helper.
- `Spring(...)` and `Tween(...)` easing primitives.
- Frame-deltatime tracker on `NvgFrame.submit` so composables can compute time-based
  interpolation without each one tracking timestamps.
- Apply to: alert fade-in/out, Tab indicator slide, hover state cross-fade.

### Keyboard input
- Tab / Shift+Tab focus navigation through clickables.
- Arrow keys for sliders / scroll lists.
- `keyTyped` capture for `TextField`.

### a11y / high-contrast variant
- High-contrast `SoulColors` variant (pure black/white/yellow palette).
- Larger default font sizes when high-contrast is on.
- Possibly: a `cfg.general.ui.reducedMotion` flag that bypasses animation library.

### Perf
- Profile `SoulComposer.build` allocations under the smoke-test HUD. Per-frame tree
  rebuild costs about N allocations where N = node count. If profiler shows GC pressure,
  consider node pooling or partial-rebuild optimization.

### Documentation
- Move the framework section in `CLAUDE.md` into its own `docs/ui-framework.md` once
  P3 / P4 land. Keep CLAUDE.md as the high-level index.

---

## Cross-cutting things to remember

- **Element id is identity.** `GuiLayoutApi.updateTextBlock` / `updateTrackerOverlay` /
  `SoulHud.ensureLayoutElement` all drop any existing element with the matching id but a
  different type. This is intentional — type-swap during refactor doesn't leak ghost
  elements. (CLAUDE.md has the writeup.)
- **Per-tick self-heal pattern.** Every feature whose HUD element is layout-persisted
  re-asserts the element each tick / frame so it survives `gui_layout.json` reload (sync
  pull, /soul gui reset, etc.). For `SoulHud.dispatchAll` this is built-in. Migrating
  features need to follow the same pattern.
- **`GlDebugMixin` filter** is currently scoped to `"No active program"`. If new
  benign messages appear during P3/P4 development (e.g. when adding image/text-field
  support), investigate first, only then add the pattern. Don't blanket-suppress.
- **owo-config stays even after owo-ui dies.** The annotation processor generating
  `SoulConfig.java` from `SoulConfigModel.java` is independent of owo-ui rendering.

---

## Decision log (so we don't re-litigate)

- **PIP framework over raw FBO bind.** Mojang 1.21.11 removed `bindWrite`; NanoVG can't
  draw into the main FBO directly. Using Mojang's PIP framework is the only path that
  composites correctly. ([log entry: GL_INVALID_OPERATION investigation, P1.6])
- **DPR = viewport / contentSize, not OS DPR.** Mojang's PIP oversampling determines
  rasterization quality; OS DPR doesn't matter inside a PIP texture. ([P1.6 blurry text fix])
- **Soul scale = nvgScale transform, not PIP-size scale.** `element.scale` is now a
  visual transform applied inside the block, not a multiplier on PIP dimensions, so
  content visually grows with scale instead of getting more empty space around it.
  ([P2.5 follow-up])
- **respectMinecraftGuiScale defaults to false.** "Consistent sizing across setups" is
  the Soul-framework convention. Users who want vanilla-like scaling flip the toggle.
- **One-frame-delayed hover.** Hit regions are recorded during draw; hover set is
  computed post-flush; next compose reads it. Trying to avoid the 1-frame delay would
  require a hit-test-only pre-pass (duplicated layout work). Imperceptible in practice.
- **`onScroll(newOffset)` not `onScroll(delta)`.** ScrollableList self-clamps to
  `[0, maxScroll]` and passes the new offset. Caller stores it as-is — no clamp boilerplate
  at every call site. ([P2.4 ScrollableList bottom-limit fix])
- **Click coords → content coords transform.** Click + scroll events arrive in absolute
  GUI-scaled coords. `SoulInput.flush` does `(c.x - panelOriginX) / panelScale` to convert
  to content coords matching where hit regions were recorded. Without the divide,
  hit-tests fail by `panelScale - 1` of bias. ([P2.5 follow-up])
- **owo-ui retires, owo-config stays.** Different things despite the shared `wispforest`
  package. ([P4 plan])
- **Popup overlay = deferred draw + modal regions, not a separate render pass.** Popups
  paint after the main tree via `SoulInput.queueOverlay`, flushed by
  `SoulHud.renderOne`. Click routing is *also* segregated via `SoulInput.beginModal()` —
  while a popup is open, only regions recorded inside the overlay block are dispatched
  against, so sibling triggers / buttons can't steal clicks. Considered a global overlay
  layer but rejected: too much plumbing for the single use case today (dropdowns inside
  HUDs), and the current model keeps all hit regions per-panel which matches the
  existing input model. ([P5 dropdown shipping]).
- **Dropdown direction picker uses panel-local room.** Earlier draft used screen-relative
  math; broke for HUDs anchored near the screen edge with a tall popup that still
  overflowed the PIP texture. Switching to `SoulInput.panelHeight` made the math match what
  actually clips. ([P5 follow-up after popup-clipping bug])
- **`gui_layout.json` schema check reads raw JSON.** Gson applies Kotlin constructor
  defaults *only* when every primary-ctor field has a default (the synthetic no-arg ctor
  exists). `GuiLayout` qualifies, `SoulHudElement` doesn't (no-default `id`). So the
  deserialized `schemaVersion` value can't be trusted to indicate file presence — it
  inherits the Kotlin default `CURRENT_SCHEMA_VERSION` on old files. Solution: probe the
  raw `JsonObject` for the key and wipe when missing. ([P5 anchor system NPE fix])
- **HUD anchor fields nullable in JSON, non-null in Kotlin.** First attempt made them
  nullable so old files would round-trip cleanly; reverted because the data model was uglier
  for every reader. Final shape: `HudHorizontalAnchor` / `HudVerticalAnchor` with Kotlin
  defaults, plus the schema-version wipe to handle old files in one shot.
  ([P5 anchor system iteration])
