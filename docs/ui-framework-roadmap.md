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
| **P4** | Migrate every Screen, retire owo-ui dependency | ⏳ next up |
| **P5** | Polish: animations, theming variants, a11y, docs | not started |

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

## P4 — Migrate Screens, retire owo-ui

The big migration. Owo-ui currently powers three screens, all of which need full
re-implementation on `SoulScreen`:

### 4.1 — `UpdateModal`
Simplest screen. Single panel with a title, message body, two buttons. Good first
migration to validate `SoulScreen` end-to-end.

### 4.2 — Profile Viewer (`profileviewer/`)
Medium complexity. Currently a `ProfileViewerScreen` with a top tab bar and a `DungeonsTab`
(stats with XP bars + floor completion tables). Migration:
- Tab bar → `Tabs` composable (already exists)
- Stats card layouts → `Surface` + `Row` + `Column` + custom progress-bar composable
  (new — small `Box` with two children, second one width-bound by `Modifier.width(progress * maxW)`)
- Floor table → `Column` of `Row`s, or a future `Grid`/`Table` composable
- Open via `/spv <username>` (`SpvCommand` already handles UUID resolution + backend fetch)

### 4.3 — Config screen (`config/gui/SoulConfigScreen` + `ui/config/*`)
Biggest single migration in P4. Current architecture (see `CLAUDE.md → "Config UI architecture"`):
- `SoulConfigScreen.kt` orchestrator
- `ui/config/registry/ConfigSections.kt` declarative extension maps (explicitSections,
  linkSections, actionRows, virtualSubs, categoryOrder, optionVisibility, optionDepth,
  rebuildOnChange, keybindOptions)
- `ui/config/rows/RowBuilders.kt` row builders
- `ui/config/components/*` button + icon renderers
- `ui/config/model/*` data classes
- `ui/config/search/ConfigSearchFilter.kt` search

Migration strategy:
- Keep `ConfigSections.kt` as-is — it's declarative metadata that's framework-agnostic. The
  consumers change.
- Build a new `SoulConfigScreen : SoulScreen` that walks owo-config's wrapper (same
  `CategoriesCollector` logic), and produces `Surface { Row { Sidebar + Column { ... } } }`.
- Sidebar = `Column` of `Button`s, one per category.
- Content area = `ScrollableList { ... }` with section headers + row composables.
- Each option type gets a composable: `BooleanRow` → `Toggle`, `IntRow` → `Slider` (int
  variant — add `IntSlider` to foundation if not already), `FloatRow` → `Slider`, `StringRow`
  → text field (need to add `TextField` composable in P4 — not yet in foundation), action
  rows → `Button`.
- Search filter remains a pure function over the categories list.
- Keybind capture flow — currently `RowBuilders.buildKeybindButton` + `ConfigScreenContext`.
  The new equivalent: a stateful Soul composable that exposes a capture-pending flag and
  swallows the next key event.

### 4.4 — `GuiEditScreen`
Decide: migrate or keep on vanilla Screen.
- Migrate pro: consistency with the rest of Soul UI; users see the same look across `/soul`
  and `/soul gui`.
- Migrate con: GuiEditScreen is a dev/edit tool, not gameplay UI. The current implementation
  is fine and works. Migration is busywork.
- **Recommendation:** leave as vanilla Screen unless a feature in P4.5+ needs it.

### 4.5 — Retire owo-ui dependency
Once 4.1–4.3 are done and nothing imports `io.wispforest.owo.ui.*`:
- Remove `modImplementation("io.wispforest:owo-lib:...")` from `build.gradle.kts`
- **Keep** the `annotationProcessor("io.wispforest:owo-lib:...")` and the `include(...)`
  for the annotation processor — `SoulConfigModel.java` still generates `SoulConfig.java`
  via owo-config's AP. Owo-config and owo-ui are separable concerns.

### Components needed for P4 that don't exist yet

Build these alongside the migrations they unblock:

- **`TextField`** — owo-ui-style single-line editable text. Needed by ConfigScreen string
  rows + the Soul UI search bar.
- **`IntSlider`** — discrete-step slider with tick marks. Owo-ui has it as `SoulIntSlider`;
  port to the Soul UI framework.
- **`ProgressBar`** — Box with a filled-portion child. Trivial; needed by SPV's XP bars.
- **`Icon`** — render either a raster image (PNG) or a path-vector glyph. Need at least
  raster for the Discord/GitHub icons in the config title bar. Image support is currently
  deferred from `NvgRenderer`; add `image(...)` / `createImage(...)` methods (port from
  Odin's NVGRenderer.kt:266-324) when this lands.
- **`Dropdown`** + **`MultiSelectDropdown`** — ✅ shipped. Trigger + above-trigger popup
  overlay. Single-select closes on row click; multi-select stays open and uses per-row
  checkboxes. First user: `FishingHud` (Sort dropdown + column-visibility dropdown). Popup
  is painted from `drawSelf` (NOT a separate overlay pass) so it shares the parent's
  scissor — sufficient because all current callers have headroom above the trigger inside
  the same panel. If a future widget needs to escape its parent panel, P5 can add a true
  overlay layer.

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
