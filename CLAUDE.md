# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **IMPORTANT — keep this file current.** When you discover a non-obvious Hypixel SkyBlock convention, a project quirk, a new shared utility, an init-order constraint, or anything that future sessions would have to re-derive from scratch, **write it down here before the conversation ends.** A new Claude Code session starts with an empty short-term memory — anything not in `CLAUDE.md` (or in your persistent memory under `~/.claude/projects/.../memory/`) is gone. Prefer adding to an existing section if one fits; otherwise create a new H2/H3 section. If the finding is more user-specific than project-specific (preferences, workflow), save it as a memory instead.

## Repository overview

Soul is a **client-side Fabric mod** for Minecraft (Hypixel SkyBlock features), written in **Kotlin**. **Java is used only for Mixin classes** and for `SoulConfigModel.java` (consumed by an annotation processor) and `RenderHelper.java` (Java statics callable from Mixins). Mod ID is `soul`, package root is `com.soulreturns`. Stonecutter is used to maintain a single source tree across Minecraft versions; only `1.21.11` is currently active.

**Mappings: Mojang Mappings (`loom.officialMojangMappings()`).** The codebase migrated off Yarn — `Minecraft` (not `MinecraftClient`), `GuiGraphics` (not `DrawContext`), `Component` (not `Text`), `Player` (not `PlayerEntity`), `MouseButtonEvent` (not `Click`), etc. New code must follow Mojang naming. `Identifier` happens to remain `Identifier` in Mojmap 1.21.11 (Mojang adopted the Yarn name); `Util` remains `net.minecraft.util.Util`.

## Build & run

PowerShell is the assumed shell.

```powershell
# Build the active version (1.21.11)
.\gradlew.bat ":1.21.11:build"

# Build all versions and collect JARs into build/libs/<mod version>/
.\gradlew.bat buildAndCollect

# Refresh dependencies after editing gradle.properties
.\gradlew.bat --refresh-dependencies
```

If Gradle picks the wrong JDK: `$env:JAVA_HOME = "C:\Users\soul\.jdks\jbr-21.0.10"`. Required Java is **21** (Minecraft ≥1.21).

**Static analysis:** `ktlint` 12.1.1 and `detekt` 1.23.7 are wired in but both run with `ignoreFailures = true` — they print warnings during `:check` but never break the build. Style rules are deliberately relaxed in `.editorconfig` (filename / function-naming / class-naming disabled, trailing commas allowed, wildcard imports forbidden). Tasks: `ktlintCheck`, `ktlintFormat`, `detekt`, `detektGenerateBaseline`. **There are no automated tests** — don't expect or invent a `test` task.

In-IDE runs use the `:1.21.11` run config. The `run/` directory is shared across versions (`runDir = "../../run"` in `build.gradle.kts`).

### Hot reload (dev iteration loop)

The project SDK is **JetBrains Runtime (JBR)** which has Enhanced HotSwap built in. To get full hot-swap (method bodies, lambdas, and JBR-only: new methods/fields):

1. IntelliJ: Settings → Build → Debugger → HotSwap → "Reload classes after compilation: Always" + "Make project before HotSwap".
2. Run via **Debug** (Shift+F9), not Run — without the debugger attached the JVM rejects HotSwap.
3. Edit code → Ctrl+F9 (Build Project) → IntelliJ reloads classes in the running JVM.
4. Resources (lang JSON, textures, JSON5 config): F3+T in-game (resource pack reload). Independent of HotSwap.

**What does not hot-reload:** Mixin changes (target classes are already loaded and transformed), `Soul.kt :: onInitializeClient()` (entrypoint already ran), feature `register()` blocks (already executed), class hierarchy changes. Mixin code should be kept stable — iterate the Kotlin features that mixins call into.

## Backend coordination — `prompts/` folder

Mod-side changes that need server-side counterparts (new endpoints, schema migrations, admin UI tweaks) are handed off via Markdown prompts in `prompts/`. Each file is a self-contained brief for the SkyBackend agent — it covers wire format, schema, controller pattern (`MineshaftVisitController` is the canonical template per the backend's CLAUDE.md), tests, and "what NOT to do" lists. Examples on disk today: `backend-sync-prompt.md`, `backend-realtime-prompt.md`, `backend-presence-migration.md`, plus delta prompts like `backend-sync-defaults-changes.md` and `backend-presence-online-fix.md`. When the mod ships a change that touches the backend contract, write the prompt to `prompts/<feature>-<topic>.md` rather than waving at the backend agent in chat — the file is durable, can be reviewed before sending, and re-readable when the agent comes back with questions.

## Releases

Push a `v*` tag to trigger the GitHub Actions release workflow:

```powershell
git tag v2.1.0
git push origin v2.1.0
```

This calls `buildAndCollect`, then creates a **draft** release on GitHub with the production mod JAR attached. `buildAndCollect` only collects `remapJar` — the sources/dev JARs are not copied into `build/libs/<mod-version>/` and therefore never get uploaded. The user edits and publishes the draft manually. JARs must be named `soul-<version>+<mcVersion>.jar` for the in-game updater to recognize them — the build system produces this automatically.

## Architecture

### Layered package structure

The codebase is organised into 5 functional layers. New code should live in the layer matching its job:

```
com.soulreturns/
├── core/            ← framework primitives (event bus, annotations)
├── data/            ← read-only state holders + readers (location, profile, model events)
├── features/        ← gameplay features (singleton objects, organised by domain)
├── ui/              ← everything visual (HUDs, config screen helpers, theme, components)
├── platform/        ← transport plumbing (HTTP, threading, mixin bridges)
├── config/          ← owo-config model + holder + cfg accessor
├── gui/             ← layout machinery (GuiLayoutManager, MinecraftGuiRenderContext)
├── render/          ← SDF shaders, RoundRectRenderer, SoulRenderPipelines
├── stats/           ← PersistentStats (profile-keyed)
├── update/          ← UpdateChecker, Updater, UpdateModal
├── util/            ← logger, chat, message handler, MobSpotter (LOS-gated mob lookup)
├── commands/        ← /soul command + subcommands
├── profileviewer/   ← SPV (its own subtree)
└── Soul.kt          ← entrypoint
```

Read/write rules between layers:
- **`data/`** publishes events but never depends on `features/` or `ui/`.
- **`ui/hud/`** reads from `data/`/`features/`/`stats/`, never holds gameplay state.
- **`features/`** subscribes to `data/` events, owns gameplay state.
- **`platform/`** is leaf code — depended on, never depends back into the mod.
- Cross-layer reach is allowed; sideways reach within a layer is allowed.

### Multi-version build (Stonecutter)

- `settings.gradle.kts` declares Minecraft target versions; currently only `1.21.11` is active and `vcsVersion = "1.21.11"`.
- `stonecutter.gradle.kts` defines swap constants and version-dependent parameters.
- Each `versions/<mc_version>/gradle.properties` pins Mojmap-related deps and Fabric API versions.
- Stonecutter preprocessor comments are available for future version splits but **are not currently used** — don't introduce them speculatively:
  ```java
  //? if >=1.21.11 {
  // code for 1.21.11+
  //?} else {
  /*code for older versions
  *///?}
  ```

### owo-config code generation (build pipeline)

The config wrapper class `SoulConfig` is **generated** from `SoulConfigModel.java` by owo-config's annotation processor. `build.gradle.kts` wires this carefully:

1. A dedicated `generateOwoConfig` JavaCompile task runs the AP and emits `SoulConfig.java` to `build/generated/sources/owoConfig/...`.
2. `compileKotlin`, `compileJava`, every `Jar` task, and the ktlint/detekt source-set tasks all `dependsOn(generateOwoConfig)` so the generated source exists before anything reads from it.
3. `compileJava` runs with `-proc:none` because the AP already ran in step 1 — re-running it would duplicate output.

**Practical implication:** if `SoulConfig` looks stale or unresolved after editing `SoulConfigModel.java`, run `gradlew :1.21.11:build` (or `buildAndCollect`) to regenerate before relying on Kotlin compile errors.

### Mod entrypoint: init order matters

`Soul.kt :: onInitializeClient()` has a **fixed order** that other code depends on:

1. `SoulConfigHolder.init()` — runs `LegacyConfigMigrator.runIfPresent()` AND `migrateOwoConfigPaths()` (for the `general/dev` restructure), then loads the owo-config wrapper. The `cfg` accessor is unusable before this.
2. `MessageHandler.register()` — must precede feature registration so features can subscribe.
3. `PersistentStats.init()` — loads `config/soul/stats.json` AND subscribes to `ProfileChanged` for active-slot promotion.
4. `LocationReader.register()` — starts publishing `AreaChanged` / `SublocationChanged`.
5. `ProfileReader.register()` — starts publishing `ProfileChanged` (drives PersistentStats keying).
6. `BackendAuth.loadCached()` — **must run before** `PresenceService.start()` so the presence ping has a token. Lives in `platform/http/`.
7. `PresenceService.start()`.
8. `UpdateChecker.checkAsync()` + `Updater.cleanupPendingDeletes()` (Windows-only deferred deletes from prior auto-updates).
9. Screen event registrations for `UpdateModal` (TitleScreen + world join).
10. `HighlightManager.loadGroups()`, `TooltipHandler.register()`, `GuiLayoutManager.configure(...)`, `SpecialGuiElementRegistry.register(...)`.
11. `registerCommands()`, `registerFeatures()`.
12. `GuiLayoutManager.loadOrInitialize()` — must come before sync engine starts so all three sync artifacts have loaded their local state.
13. `registerSyncArtifacts(...)` + `SyncEngine.start(...)` — reconciles in the background; if a remote pull is fresher than local, `onAfterPull` hot-reloads the affected subsystem (config / gui_layout / stats).
14. `BackendNotificationCenter.register()` + `RealtimeClient.start(...)` — **last**. The notification center must be wired before the realtime client connects so it doesn't miss events that fire during the first message dispatch. Notification rendering rides on `RenderUtils.showAlert` (same path as `/soul dev testAlert`), so there's no HUD element to register here.

**Within `registerFeatures()`** two ordering constraints apply, both for the same reason — the consumer reads state owned by the producer on the same tick:

- `BobbinSpotter.register()` before `BobbinHud.register()`.
- `MineshaftCorpses.register()` before `LapisCorpseAlert.register()`, `VanguardCorpseAlert.register()`, `LittlefootAlert.register()`, `MineshaftVisitTracker.register()`, and `MineshaftCorpsesHud.register()` — all five consume `MineshaftCorpses.byType` / `totalOf(...)`.

### Event bus (`core/events/`)

In-process pub/sub used to decouple data sources (readers) from consumers (features, HUDs).

```kotlin
// Lambda subscription:
Events.subscribe<AreaChanged> { event -> /* … */ }

// Annotation subscription (one method per event type on a singleton):
object MyFeature {
    fun register() { Events.subscribe(this) }
    @HandleEvent fun onArea(event: AreaChanged) { /* … */ }
}

// Publish (synchronous, on the calling thread):
Events.publish(AreaChanged(prev, next))
```

- Handlers run **synchronously** on whatever thread `publish` was called from — most events fire from the client tick thread.
- Handler exceptions are logged and swallowed; one bad handler can't take down the bus.
- Inheritance is **not walked** — subscribe to the concrete subclass you want.
- Handler lists are `CopyOnWriteArrayList`; the class→handlers map is `ConcurrentHashMap`. Subscribing from inside a handler is safe.

### Read-only state holders (`data/location/`, `data/profile/`, `data/skyblock/`)

Pattern: a `Reader` polls Minecraft state on every client tick and feeds an `Api` singleton. The `Api` exposes read-only getters and publishes a `*Changed` event on transition only. **Every feature that needs SkyBlock location or profile info reads from the API; no feature parses the tab list itself.**

- **`LocationApi.currentArea`** / **`currentSublocation`** — fed by `LocationReader`. Area from tab list `Area:` virtual entry; sublocation from scoreboard sidebar `⏣` line. Restrictive ASCII regex strips lobby state suffixes (e.g. Garden's `ൠ x8` pest indicator). Both go `null` outside SkyBlock.
- **`SkyblockApi.isOnSkyblock`** — fed by `SkyblockReader`. Checks the scoreboard sidebar's **title** against `Regex("SK[YI]BLOCK(?: CO-OP| GUEST)?(?: [♲☀Ⓑ])?")` after color-code stripping. The regex (lifted from SkyHanni's `SkyBlockLocationData`) covers every Hypixel variant: baseline `SKYBLOCK`, `SKYBLOCK CO-OP`, `SKYBLOCK GUEST`, plus single-char suffix markers for Stranded (`♲`) / Ironman (`☀`) / Bingo (`Ⓑ`). The `SK[YI]BLOCK` allows the `SKIBLOCK` typo on Hypixel Alpha. **Don't replace this with an exact-equality check** — Co-op and Bingo profiles will silently report `isOnSkyblock = false` and break presence + any feature gating on this signal. Publishes `OnSkyblockChanged` on transition.
- **`ProfileApi.currentProfile`** — fed by `ProfileReader`. Tab-list `Profile:` (or `Profile (Co-op):`, `Profile (Stranded):`) entry. Drives `PersistentStats` slot selection — see "Persistent stats" below.

Every consumer goes through these APIs directly — or subscribes to `AreaChanged` / `SublocationChanged` / `OnSkyblockChanged` from `data/model`.

### Feature pattern

Features are **Kotlin singleton `object`s** under `features/<domain>/` that expose a `register()` function called from `Soul.registerFeatures()`:

```kotlin
object MyFeature {
    fun register() {
        // subscribe to events, register tick listeners, etc.
        Events.subscribe(this)
    }
    @HandleEvent fun onSomething(e: AreaChanged) { /* … */ }
}
```

Never use classes for features. State (if any) lives on the singleton itself. Features that have a HUD push the *view* into `ui/hud/` and keep the *state* in `features/`.

### Config system (owo-config)

- Model is `SoulConfigModel.java` annotated `@Config(name = "soul/config", wrapperName = "SoulConfig")`.
- Generated `SoulConfig` is held in `SoulConfigHolder.INSTANCE`; access from any Kotlin code via the top-level `cfg` accessor.
- Read at point of use, **never cache**: `cfg.render.hudScale.chatScale()`. Toggles take effect immediately.
- Config file lives at `config/soul/config.json5`. Add new fields directly on the model with owo-config annotations (`@RangeConstraint`, `@Nest`, `@SectionHeader`, etc.).
- `LegacyConfigMigrator` runs before `SoulConfig.createAndLoad()` and migrates the old `config/soul/config.json` to the new format. It also detects and re-migrates the old flat dotted-key format if found.
- Translation keys follow `text.config.soul/config.option.<path>` (the `/` is from `@Config(name = "soul/config")`). All keys are in `src/main/resources/assets/soul/lang/en_us.json`. The config screen also constructs `text.config.soul/config.category.*`, `.group.*`, and `.title` keys.
- Sliders use `@SliderNumberInput(min, max, step, decimals)`. Always format values with `Locale.ROOT` to avoid locale-specific decimal separators (`,` vs `.`).

### Config UI architecture

Post-P4.3 the config screen is a single `SoulScreen` (`config/gui/SoulConfigScreen.kt`) built on the Soul UI framework — declarative composables, per-frame rebuild, no manual `rebuildContent` calls. The pure-data helpers under `ui/config/` survive; the owo-ui based renderers / row builders / social icons were deleted in P4.4.

| File | Responsibility |
|---|---|
| `config/gui/SoulConfigScreen.kt` | The whole screen: top nav (Soul + version + breadcrumb + search), collapsible sidebar, scrollable body with `OptionSection` / `ActionSection` / `LinkSection`, Move GUI footer, Done button. State (active cat/sub, search query, scroll offsets, expanded categories, keybind capture target) lives as `@Volatile` fields and the composer re-reads them every frame. Implements `ConfigScreenContext` for action callbacks. |
| `ui/config/registry/ConfigSections.kt` | **All declarative extension maps** (see table below) + the `isOptionVisible` predicate. Edit this to add new sections, links, action rows, or visibility rules. |
| `ui/config/model/CategoriesCollector.kt` | Walks the owo-config wrapper into a normalized `List<CategoryEntry>`. |
| `ui/config/search/ConfigSearchFilter.kt` | Pure search filter over the categories list. |
| `ui/config/model/Entries.kt` | Data classes (`CategoryEntry`, `SubcategoryEntry`, `LinkTarget`, `ActionRowSpec`) + the `ConfigScreenContext` interface. |

**Visual structure inside the card** (`panel` color, `radiusMedium` rounded corners):
- **Top nav** (full card width, half-rounded top, dark `0xFF111111` + bottom box-shadow): `Soul` title + version label on the left (sidebar width), vertical line splitter, breadcrumb + search field on the right.
- **Sidebar** (left column under top nav, half-rounded **only on the bottom-left** so the card's other corners stay clean): one uniform `0xFF161616` bg, right-side directional shadow bleeding into the body. Inside: the categories ScrollableList and a separate footer Box containing the Move GUI button (the inner Box isolates the button's hit region from the scroll list's items — without it, items scrolled below the viewport extended their click regions into the footer area).
- **Body** (right of sidebar): scrollable column of section cards (`panelInset` Surfaces). Option / Action / Link rows are all pinned to `ROW_HEIGHT = 32f` so toggles (18), sliders (14), text fields (18), and buttons (27) all center inside the same vertical slot.

**Option rows** use SpaceBetween: label cluster on the left (optional `↳` glyph + label), control cluster on the right (slider/toggle/text field + per-option ↺ reset chip when value ≠ default).

**Extension points (all in `ConfigSections.kt`):**

| Map | Purpose | Example entry |
|---|---|---|
| `explicitSections` | Group flat depth-2/3 fields into labeled sections | `"render" → "highlights" → [("Item Highlights", {fieldNames…})]` |
| `linkSections` | Cross-navigation buttons inside a sub | `"farming" → "pestFarming" → [LinkTarget("Configure Pest Equipment Highlighting", "render", "highlights")]` |
| `actionRows` | Label + button rows that run an arbitrary callback receiving `ConfigScreenContext` | `"dev" → "config" → [ActionRowSpec("Reload Config from Disk", "Reload") { ctx -> ctx.reloadConfig() }]` |
| `virtualSubs` | Subcategories with no backing config fields | `"farming" → ["pestFarming"]` |
| `categoryOrder` | Explicit sidebar order; unlisted cats fall to the end | `["general", "render", "fishing", "mining", "farming", "profileViewer", "dev"]` |
| `optionVisibility` | Conditional visibility (parent toggle gates child) | `"render.highlights.usePestVest" → { cfg.render.highlights.highlightPestEquipment() }` |
| `optionDepth` | Visual indent depth for chained dependents (default 1). Set ≥ 2 when an option's gate depends on another already-gated option, so the UI shows the nesting | `"dev.debug.logging.logRealtime" → 2` (gated by `logBackend`, which is itself gated by `debugMode`) |
| `rebuildOnChange` | Boolean toggles whose change rebuilds content (so visibility-dependent rows update live) | `setOf("render.highlights.highlightPestEquipment")` |
| `keybindOptions` | String fields rendered as keybind pickers (capture mode) | `setOf("dev.keybinds.copyOpenedGui", …)` |

- A `↳` glyph is auto-prepended to any option whose path is a key in `optionVisibility` — visual hint that it's a child of another setting.
- Auto-grouping by path segment applies for options with depth ≥ 4. For depth-2/3 options without an explicit section, the section label falls back to the subcategory's own display name.
- Search filter (`filteredCategories`) hides categories with no matches; visibility filter (`isOptionVisible`) hides individual rows. Hidden options are also excluded from search results.
- **Sidebar text colors:** category headers white (`Theme.TEXT`), subcategories gray (`Theme.TEXT_DIM`) → white when selected (selection is also indicated by the accent background).
- **Dev category banner:** when `activeCategory == "dev"`, a translatable warning label (`text.config.soul/config.dev.warning`) is rendered above the scroll area.

**Keybind capture flow:** keybind buttons (paths in `ConfigSections.keybindOptions`) call `requestKeybindCapture(opt)` on the screen. The screen's `keyPressed` / `mouseClicked` overrides intercept the next input while `capturingKeybind` is non-null, set the option, save, and clear the capture state. Esc clears the binding.

## Soul UI framework

> **Roadmap.** See `docs/ui-framework-roadmap.md` for the live tracker of what's shipped (P1+P2 = NVG runtime + composer + foundation widgets + Minecraft integration) and what's next (P3 HUD migrations, P4 screen migrations + owo-ui retirement, P5 polish). Update it after each sub-phase.

### NanoVG runtime (`platform/render/nvg/`)

> **Origin.** Lifted from [Odin](https://github.com/odtheking/Odin) — BSD 3-Clause. Original backend by Aton; design by Stivais. Every file under `platform/render/nvg/` carries the attribution header. Don't remove the credit when refactoring.

The mod uses LWJGL's NanoVG bindings for crisp vector rendering of the Soul UI framework (font glyphs, rounded rects, anti-aliased curves, etc.). NanoVG is a raw OpenGL library; **Minecraft 1.21.11's `RenderDevice` removed the legacy `GlStateManager._glBindFramebuffer` API Odin's PIP code relied on**, so the integration here is novel — not a 1:1 port.

**Files (5):**

- **`NvgRenderer.kt`** — singleton wrapping `nvgCreate`/`nvgBegin/EndFrame`. Public API: `beginFrame(logicalW, logicalH, dpr)` / `endFrame()`, transform stack (`push`/`pop`/`scale`/`translate`/`rotate`/`globalAlpha`), intersection-aware scissor stack, primitives (`rect`/`hollowRect`/`gradientRect`/`circle`/`line`/`dropShadow`/`halfRoundedRect`), text (`text`/`textShadow`/`textWidth`/`drawWrappedString`/`wrappedTextBounds`). Image/SVG support skipped for v1.
- **`NvgFont.kt`** — TTF wrapper, holds bytes in a `cachedBytes: ByteArray`. Each `buffer()` call returns a fresh direct `ByteBuffer` — NanoVG retains the pointer in `nvgCreateFontMem`, so the buffer must outlive the font; `NvgRenderer.fontMap` keeps it referenced.
- **`NvgFrame.kt`** — entry point for feature code. `submit(context, x, y, w, h, scale, block)` queues a PIP state; `submitFullScreen(context, block)` is the convenience for screen-wide rendering. Brackets the block with `SoulInput.startFrame/flush` for input dispatch, and with `nvgScale(scale, scale)` for the per-panel transform.
- **`NvgPipState.kt`** + **`NvgPipRenderer.kt`** — Mojang `PictureInPictureRenderer` integration. `NvgPipRenderer` extends Mojang's PIP base class; its `renderToTexture(state, poseStack)` opens a `RenderSystem.getDevice().createCommandEncoder().createRenderPass(...)` block (same pattern as `RoundRectRenderer`) on `RenderSystem.outputColorTextureOverride`, calls `GL33C.glBindSampler(0, 0)` (**critical** — without it Mojang's sampler overrides NanoVG's per-texture parameters and text renders invisible), then runs `nvgBeginFrame` → state lambda → `nvgEndFrame`. Mojang composites the resulting PIP texture into the main scene at the state's screen rect.
- **DPR computation in `NvgPipRenderer`.** Mojang allocates the PIP texture at viewport-dimensions chosen by its own oversampling rule (typically `2× requested size` at standard GUI scale). We **must** pass the actual ratio (`viewport.width / state.contentWidth`) to `nvgBeginFrame` as DPR — otherwise NanoVG rasterizes glyphs at 1× and the projection stretches them, producing blurry text. The diagnostic log line `PIP renderToTexture: drawFBO=N viewport=... dpr=N` fires on first PIP submission so the ratio is visible in `logs/latest.log`.

**Fonts bundled** at `src/main/resources/assets/soul/fonts/` (SIL OFL 1.1; license at `LICENSE-Inter.txt`): `Inter-Regular.ttf`, `Inter-Medium.ttf`, `Inter-SemiBold.ttf`. Loaded lazily via classpath on first font usage.

**Image support.** `NvgRenderer.loadImage(resourcePath)` loads a PNG/JPG/GIF from the classpath, decodes via NanoVG's bundled stb_image, and returns a NVG image handle cached for the JVM lifetime. `NvgRenderer.image(x, y, w, h, handle, alpha)` paints it. Used by the `Image` composable (`ui/foundation/Image.kt`) and by the config top-nav social icons (`assets/soul/textures/gui/{github,discord}.png` — 14×14 in the title bar, 0.7 idle / 1.0 hover alpha).

**Inter glyph coverage gotcha.** Inter covers Basic Latin + Latin-1 + Latin Extended A/B + common punctuation (including guillemets like `›`), but **not the Unicode Geometric Shapes block** (U+25xx — triangles `▾ ▸ ▼ ▶ ◀ ▲`) nor Miscellaneous Symbols. Those render as the tofu "missing glyph" box. Stick to basic ASCII + Latin Extended punctuation for any UI character — e.g. `+` / `−` for tree-view expand/collapse, `›` for breadcrumb separators, `↺` (which happens to be present) for the reset icon. When introducing a new glyph, smoke-test it in `/soul config` before assuming it works. **For triangle/arrow shapes specifically, draw a vector primitive instead of trying to embed a glyph:** `NvgRenderer.filledTriangle(x1, y1, x2, y2, x3, y3, color)` paints a solid triangle from three points. Used by `Dropdown.kt`'s caret indicator — scalable, font-independent, sharper than any glyph rendering at HUD sizes.

**LWJGL dependency.** `lwjgl-nanovg:3.3.3` (matches Minecraft's bundled lwjgl) + four-platform natives (`windows`, `linux`, `macos`, `macos-arm64`) declared in `build.gradle.kts` via `modImplementation` + `include` so natives bundle into the released jar.

**GL debug noise filter.** `mixin/render/GlDebugMixin` intercepts `com.mojang.blaze3d.opengl.GlDebug.printDebugLog(IIIIIJJ)V` at HEAD; reads the message C-string via `MemoryUtil.memUTF8`; cancels for known-benign patterns (currently `"No active program"` — emitted hundreds-of-times-per-second by Mojang's PIP composite validation after NanoVG zeros `glUseProgram(0)` in `nvgEndFrame`). Real GL errors still bubble. Add to `SUPPRESSED_PATTERNS` only after investigating.

**Text shadow rendering — two gotchas, learned the hard way.** `NvgRenderer.textShadow` is deliberately a plain two-pass — paint pure-black shadow at `(+1, +1)`, paint main on top — *not* the same +1 offset with `NVG_DESTINATION_OUT` cleanup, and *not* an `nvgFontBlur` softened shadow. Both fancier approaches were tried and produced *non-deterministic per-row dropouts* — some text rows would render with a clean shadow while neighbours rendered with none at all. Two failure modes:

1. **`nvgFontBlur` interacts badly with the FontStash atlas.** NanoVG keys glyph cache entries by `(font, size, blur)`, so each character at blur=0 and blur=0.5 occupies a separate slot. Under HUD-text load (~10 rows × 4 cells × multiple passes = lots of glyph traffic) the atlas fills, LRU eviction drops the less-recently-used blur=0.5 entries, and earlier-queued shadow quads end up sampling whatever overwrote their slots — manifesting as silently-missing shadow on some rows.
2. **`nvgGlobalCompositeOperation` mid-frame seems to produce every-other-row shadow dropouts.** Painting shadow → switching to `NVG_DESTINATION_OUT` → painting the main glyph as a stencil to erase the AA crossover → switching back to `NVG_SOURCE_OVER` and painting main is mathematically clean and works perfectly for a single row, but when called repeatedly inside one NanoVG frame the alternating composite-op flushes interact with FontStash in a way that drops half the shadows. The exact mechanism wasn't pinned down, but reproduction was reliable.

What lets the plain two-pass look clean at +1 px is the **bundled heavy Inter weights + the `hudBoldFont` default-on toggle** (`cfg.general.ui.hudBoldFont = true`). `NvgRenderer.boldVariantOf` skips a tier (Regular → SemiBold, Medium → Bold, SemiBold → Black) so HUD text renders with ~3–4 px thick strokes at body size, and the anti-aliased edge of each glyph is only ~0.5 px — the halo crossover region where shadow leaks under the main glyph's AA shrinks to a sliver. At thinner Regular weight the same +1 offset produces a visible bordered/glowy artefact. Don't move the offset back to +2 "to be safe" — the heavier strokes already handle it, and +2 makes the shadow read as a doubled-text artefact rather than a drop shadow. The Inter weight ladder bundled at `assets/soul/fonts/`: Regular, Medium, SemiBold, **Bold**, **Black** (Bold/Black added for `boldVariantOf` to reach).

**Third gotcha — round ONCE for two-pass shadow rendering, not twice.** `kotlin.math.round(Float)` is half-to-even (banker's rounding), not half-up. At fractional `y` values — which occur whenever the panel sits at a non-integer screen origin or composes at a non-integer effective scale — `round(y)` and `round(y + 1f)` can disagree by **0, 1, or 2** px depending on which half-pixel each value lands on (e.g. `round(23.5)=24, round(24.5)=24` → delta 0; `round(24.5)=24, round(25.5)=26` → delta 2). Drawing the shadow at `nvgText(round(x + 1f), round(y + 1f))` and the main at `nvgText(round(x), round(y))` therefore alternates between 0/1/2 px offset per row, visible as a clearly different shadow on every-other-row. **Always round the base position once and add the integer offset afterward** (`val sy = round(y); nvgText(sy + 1f, …)`). Odin doesn't hit this because their `round(x + 2f) − round(x)` delta happens to be 2 regardless of banker's rounding at half-pixel `x`, but anything that's not exactly +2 has to follow the round-once rule.

### Soul UI framework (`ui/`)

Declarative Compose-style UI framework that produces a `SoulNode` tree → measures → draws via the NanoVG runtime. Replaces ad-hoc `GuiRenderContext`-based HUDs and (eventually, in P4) replaces owo-ui for screens.

**Conceptual model.** Stateless composables; per-frame full rebuild (no slot table, no incremental composition); state lives in feature singletons that composables read at compose time. State changes auto-show next frame because the tree rebuilds.

**Packages:**

```
ui/composer/                 runtime + modifier system
  SoulComposable.kt          @SoulComposable annotation (intent marker, not compile-enforced)
  SoulComposer.kt            thread-local tree builder; SoulComposer.current.composable(...)
                             helper; nextAutoKey() for stable per-frame keys
  SoulNode.kt                base node + SoulConstraints + SoulMeasured; measure/draw protocol;
                             open `draw(...)` so ScrollableList can wrap children w/ scissor
  SoulModifier.kt            chain interface + PaddingElement / BackgroundElement (with
                             `CornerRounding`: Full/Top/Bottom/TopLeft/TopRight/BottomLeft/
                             BottomRight via halfRoundedRect / cornerRoundedRect) /
                             GradientBackgroundElement (linear gradient bg) /
                             BoxShadowElement (with `ShadowSide`: All uses
                             NvgRenderer.dropShadow ring; directional uses a gradient strip
                             on one side, `color` defaults to translucent black but should
                             be set to the panel's bg color for a "panel extends + fades"
                             bleed instead of a dark patch) /
                             SizeElement / FillElement / WeightElement + extension API
                             (.padding/.background/.gradientBackground/.boxShadow/.size/
                             .width/.height/.fillMaxWidth/.weight/...). `.weight(N)` is
                             honored ONLY by Row / Column when their own main axis is
                             bounded — weighted children share the leftover space after
                             unweighted siblings measure at intrinsic, proportionally to
                             their weights.
  ModifierUtils.kt           internal helpers: totalPaddingHorizontal/Vertical, contentOffset,
                             applySizeOverride (handles SizeElement AND FillElement),
                             drawBackgrounds (paints BoxShadow first so the bg covers its
                             hollow interior, then BackgroundElement / GradientBackgroundElement
                             in chain order)
  Alignment.kt               HorizontalAlignment / VerticalAlignment / Arrangement enums +
                             alignHorizontal / alignVertical / arrangeAlong helpers
  InputModifiers.kt          ClickableElement + ScrollableElement + TooltipElement +
                             recordHitRegions helper (handles all three: clickable/scrollable
                             register a HitRegion with a key; tooltip records a separate
                             tooltip region that's hit-tested at end-of-frame, no key needed)

ui/foundation/               composable primitives
  Text.kt                    single-line glyph rendering
  Image.kt                   loads PNG/JPG/GIF from classpath via NvgRenderer.loadImage,
                             paints into a sized rect; per-frame alpha for hover/dim effects
  Box.kt                     stacked-children container
  Column.kt / Row.kt         arrangement + cross-axis alignment + gap (size-to-content unless
                             fillMaxX overrides constraints; arrangement uses post-constraint
                             size so SpaceBetween / SpaceAround / SpaceEvenly are meaningful
                             only when there's bounded extra space)
  Spacer.kt                  sized-from-modifier empty box
  Surface.kt                 themed rounded background (Box + Theme.colors.panel + radius + padding)
  Button.kt                  hover-aware clickable surface; `accent` boolean → accent/accentDim
                             hover state; `centerLabel: Boolean = false` wraps the label in
                             `Row(fillMaxWidth, Center)` so a `fillMaxWidth()` button centers
                             its text instead of pinning to the left
  Toggle.kt                  pill switch; off=panelHover (visible against `panelInset` row
                             cards — using `panelInset` would make the track blend in),
                             on=accent, hover variants
  Tabs.kt                    segmented selector; per-tab key = `TabKey(prefix, index)` for
                             stable hover state; each tab records its own hit region at
                             `depth+1` so the strip's hit region doesn't shadow individual tabs
  Slider.kt                  draggable; press captures slider's key; while pressed each frame
                             reads SoulInput.cursorX and emits onChange; click also jumps value
  ScrollableList.kt          scissor-clipped vertical list with mouse wheel; **self-clamping**:
                             widget computes maxScroll = contentHeight - viewportHeight, clamps
                             internally, passes new offset (not delta) to onScroll callback;
                             onScroll signature: `(newOffset: Float) -> Unit`
  Dropdown.kt                Two widgets sharing trigger + popup chrome via `DropdownChrome`
                             constants and `measureTrigger`/`drawTriggerChrome`/`drawPopupChrome`
                             helpers:
                             - `Dropdown(...)` single-select; click row → onSelect(idx) AND
                               closes popup. Selected row painted with `accent` text color +
                               left-edge accent bar.
                             - `MultiSelectDropdown(...)` checkbox-per-row; click row →
                               onToggle, popup STAYS open so the user can flip several.
                             Both take `popupMaxHeight: Float? = null`. When set, the popup
                             clamps to that height, scissor-clips the option list, and a
                             mouse-wheel scroll region is recorded — used by the fishing
                             tracker's 19-variant Category dropdown. Scroll state lives in a
                             module-scoped `popupScrollOffsets: ConcurrentHashMap<Any, Float>`
                             keyed by trigger key, persisting across close/reopen within a
                             session. Both use a triangle caret (`NvgRenderer.filledTriangle`)
                             since Inter doesn't cover ▾/▴.

                             **Popup as deferred overlay.** Each open popup wraps its draw
                             block in `SoulInput.queueOverlay { ... }` so the host
                             (`SoulHud.renderOne`) drains it AFTER the main tree's
                             `root.draw`. That puts the popup visually on top of later
                             siblings (the rest of the footer column, the reset button,
                             etc.).

                             **Direction picker.** `computePopupY` reads
                             `SoulInput.panelHeight` (the PIP texture height set by
                             `NvgFrame.submit`) and picks the side of the trigger with the
                             most panel-local room. Falls back to the bigger side when
                             neither fits the popup whole. Earlier draft used screen-relative
                             math; broke for HUDs near the screen edge with a popup that
                             still overflowed the PIP texture. Panel-local math matches what
                             actually clips.

                             **Click routing.** `SoulInput.beginModal()` fires at the top
                             of each overlay block. While modal mode is active, hit regions
                             land in a separate `modalRegions` list and ALL click / scroll /
                             hover dispatch consults only that list. Sibling triggers (other
                             dropdowns, buttons in the same panel) recorded earlier in the
                             frame become inert until the popup closes, so they can't steal
                             clicks meant for the open popup. A panel-wide dismiss scrim is
                             recorded inside the modal so anywhere outside the option rows
                             closes the popup.

                             **State ownership.** Caller owns the `expanded: Boolean` flag
                             (keep it on the feature singleton — transient, not persisted;
                             `FishingHudSettings.{sortDropdownOpen, columnDropdownOpen,
                             categoryDropdownOpen}` is the model). Scroll offset is internal
                             to the framework via the module-scoped map.

ui/input/
  HitRegion.kt               { key, x, y, w, h, depth, onClick?, onScroll? }
  SoulInput.kt               singleton frame state. Per frame:
                             - startFrame(cursorX, cursorY, panelOriginX, panelOriginY, panelScale)
                               sets state, clears regions; **cursor is divided by panelScale** so
                               widgets reading SoulInput.cursorX get content coords matching their
                               unscaled hit regions
                             - recordRegion called by layout drawSelf; **clips against the
                               active NanoVG scissor** (`NvgRenderer.currentScissorBounds`) —
                               regions entirely outside are dropped, partially-inside regions
                               have their rect shrunk to the visible portion. This is what
                               keeps `ScrollableList` items that scrolled out of the viewport
                               from intercepting clicks meant for siblings rendered after the
                               scrollable (e.g. a footer button below the list).
                             - queueClick/queueScroll/queueRelease called by SoulGuiHudAdapter
                               from Fabric mouse events; coords arrive in absolute GUI-scaled
                               space
                             - flush() at end of block: hit-test queued events
                               (`(event.x - panelOriginX) / panelScale` → content coords) →
                               invoke handlers, set pressedKey on click; clear pressedKey on
                               release; update hoveredKeys from cursorX/Y vs regions
                             - hover state is **one-frame delayed** (recorded post-draw; next
                               compose reads). Imperceptible.
                             - pressedKey survives the cursor leaving the region's bounds —
                               that's what makes Slider drag work outside the track
                             - tooltipRegions tracked separately from hit regions; the host
                               (SoulScreen) calls `findHoveredTooltip()` inside its NvgFrame
                               block AFTER the main draw to paint the tooltip overlay on top.
                             - **deferred overlays** via `queueOverlay(action)` +
                               `flushOverlays()`. Widgets that must paint over their later
                               siblings (Dropdown popups, future tooltips with custom bgs)
                               queue a lambda; the host (`SoulHud.renderOne`) drains the
                               queue right after `root.draw` so the deferred paint is the
                               last thing rendered in the panel. The lambda can record hit
                               regions itself — they still land before `flush` dispatches.
                             - **modal mode** via `beginModal()`. While `modalActive` is
                               true (set by an open popup's overlay block), all subsequent
                               `recordRegion` calls land in a separate `modalRegions` list,
                               and click / scroll / hover dispatch consult ONLY that list.
                               Sibling widgets recorded earlier in the frame become inert.
                               Resets on next `startFrame`. Without this, two sibling
                               dropdowns at the same tree depth would share the same
                               `INTERACTIVE_DEPTH_BOOST` and a click on dropdown B's
                               trigger could open B instead of dismissing A's popup.
                             - **`panelWidth` / `panelHeight`** exposed as public read-only
                               (set by `NvgFrame.submit`). Widgets like Dropdown read these
                               to reason about their containing PIP texture's bounds —
                               e.g. picking popup open-direction based on which side of the
                               trigger has more room INSIDE the texture, not just on screen.
                             - **`panelOriginX/Y` + `panelScale`** also public read-only —
                               useful for screen-space conversions in custom widgets.

ui/theme/
  SoulTheme.kt               colors / dimens / typography tokens. SoulColors mirrors legacy
                             `Theme.kt` (dark palette w/ accent blue); SoulDimens has radius +
                             padding scales; SoulTypography has title/heading/body/caption/mono
                             roles each w/ a `SoulTextStyle(font, size)`. Full theme system
                             (light/dark/high-contrast variants) is P5 polish.

ui/runtime/                  Minecraft integration
  SoulHudRegistry.kt         id → (width, height, defaults, content) map; ConcurrentHashMap
  SoulHud.kt                 register API + per-frame dispatchAll. Features call
                             `SoulHud.register(id, w, h, defaultAnchorX, ..., content)` once
                             during Soul.registerFeatures(). dispatchAll runs each frame
                             (from SoulGuiHudAdapter.renderHud); per registered HUD it
                             self-heals the layout element via ensureLayoutElement
                             (matches the per-tick upsert pattern of TrackerOverlay /
                             TextBlock features — survives gui_layout.json reload).
                             effectiveScaleFor(scale) is the canonical scale computation:
                               element.scale × cfg.general.ui.globalScale ×
                               (respectMinecraftGuiScale ? 1 : 1/window.guiScale)
                             — used by renderOne AND by GuiEditScreen/GuiEdit for matching
                             selection-box bounds.
  SoulScreen.kt              abstract Minecraft Screen subclass hosting a Soul composition.
                             Subclass it, override @SoulComposable Content(). Render +
                             mouseClicked (MouseButtonEvent in 1.21.11) / mouseReleased /
                             mouseScrolled wire automatically into SoulInput.

                             **GUI-Scale-independent rendering.** Every SoulScreen always
                             renders against a fixed `TARGET_GUI_SCALE = 2f` baseline — the
                             screen looks visually identical at every user's GUI Scale
                             setting. The render impl: keep the PIP composite rect at the
                             full logical viewport `(0, 0, width, height)` so the screen
                             fills the physical display, but compose at a "composable space"
                             of `(width × actualGuiScale / TARGET, height × … / TARGET)` and
                             set `NvgFrame.submit(scale = TARGET / actualGuiScale)` so the
                             nvgScale inside maps composable coords back to the logical
                             target. Cursor math via `cursorLocal / panelScale` lines up
                             automatically. **Inside `Content()` use the protected
                             `composableWidth` / `composableHeight` properties for layout
                             math, NOT the Mojang `width` / `height` Screen fields** — those
                             are still in GUI-logical units and would make `width * 0.9f` /
                             similar references snap to a different physical size on every
                             user's setup. `composableWidth/Height` read from
                             `SoulInput.panelWidth/Height` so they reflect the current
                             frame's composable bounds.
```

**Scale model (this took a few iterations to get right):**

A HUD's `element.scale` no longer grows the PIP region with empty space — it's now an `nvgScale(scale, scale)` transform applied inside the block. The PIP texture is allocated at `(width × scale, height × scale)` so the scaled content fits; the composable composes at intrinsic `(width, height)`; NanoVG scales it visually on draw. Hit regions stay in unscaled content coords; cursor + click coords are divided by `panelScale` in `SoulInput` so the comparison aligns.

`cfg.general.ui.respectMinecraftGuiScale` (**default false**) toggles whether Soul HUDs scale with Minecraft's GUI Scale. When off (default), the panel renders at a fixed physical-pixel size regardless of the user's GUI Scale setting — the Soul-framework convention is "consistent sizing across setups." When on, behaves like vanilla HUDs.

`cfg.general.ui.globalScale` (slider 0.5–2.0) is a per-user multiplier on top of every individual element's scale.

**SoulHud lifecycle for new features:**

1. Define a `@SoulComposable fun MyHud()` containing the composable tree.
2. From `Soul.registerFeatures()` call `SoulHud.register(id = "my_hud", width = 220, height = 160, content = ::MyHud)`. Optional params worth knowing:
   - `defaultHorizontalAnchor` / `defaultVerticalAnchor` (`HudHorizontalAnchor` / `HudVerticalAnchor`) — pivot edge for the element. `Start`/`Top` = top-left corner sits at the anchor (legacy). `Center`/`Center` = centered. `End`/`Bottom` = right/bottom edge sits at the anchor. See "Anchor alignment system" below.
   - `settingsCategory` / `settingsSubcategory` — deep-link target for the right-click "Settings" entry in `/soul gui`. See "GUI Edit context menu" below.
3. That's it. The HUD is positioned via `/soul gui` (`SoulHudElement` is a `GuiElement` subclass — persists in `gui_layout.json`), scaled via per-element wheel + global slider, and survives layout-file reloads via the per-frame self-heal in `dispatchAll`.

**Anchor alignment system.** `SoulHudElement` carries `horizontalAnchor: HudHorizontalAnchor` and `verticalAnchor: HudVerticalAnchor` enum fields (Start/Center/End and Top/Center/Bottom). `SoulHud.resolveBaseX/Y` apply the alignment by shifting the on-screen origin by `measured_size × effective_scale × {0, 0.5, 1}` depending on the anchor, so a Center-anchored HUD sits dead-center horizontally regardless of resolution / GUI scale / content size. Both `dispatchAll`'s render path AND `GuiEditScreen` / `GuiEdit.findHitElement`'s selection bounds go through the same `resolveBaseX/Y` so editor hit-tests stay aligned with the rendered panel. **Defaults today:** Fishing/Seasoning trackers `Start, Top` (top-left). Bobbin/Legion/Mineshaft Corpses `Center, Top` (top-center). Party `End, Top` (top-right).

**GUI Edit context menu (`/soul gui`).** Right-clicking a `SoulHudElement` opens a small popover with five corner presets — **Top Left / Top Right / Bottom Left / Bottom Right / Center** — followed by two per-HUD toggle entries and a **Settings** link:
- Anchor presets: each one calls `GuiLayoutManager.updateSoulHudAnchor(id, anchorX, anchorY, hAnchor, vAnchor)` which snaps `anchorX/Y` to a corner fraction and zeros the offset.
- **HUD Background: ON/OFF** — flips this element's `showBackground` override via `GuiLayoutManager.updateSoulHudShowBackground(id, value)`. Label reads `SoulHud.shouldDrawBackground(id)` so it reflects the **effective** state (global gate AND per-HUD).
- **Use Minecraft Font: ON/OFF** — analog for the per-HUD `useMinecraftFont` override. Same global-wins semantics.
- **Settings** — opens `SoulConfigScreen(initialCategory, initialSubcategory)` jumping to the HUD's registered category/subcategory (e.g. Bobbin → `fishing` / `bobbinTime`; Legion → `render` / `overlays`).

The menu is built fresh per-frame by `GuiEditScreen.buildContextMenuItems(elementId)` so the toggle labels always reflect the live state. A small black-bordered yellow dot is also drawn at each enabled HUD's pivot pixel while the editor is open so the user can see *which* corner is anchored.

**HUD-wide style overrides (global + per-HUD).** Four boolean toggles in `cfg.general.ui`, each with a matching nullable per-HUD override on `SoulHudElement` and a context-menu toggle entry in `/soul gui`:

| Global key | Per-HUD field | Helper | Default | Effect when ON |
|---|---|---|---|---|
| `hudBackground` | `showBackground: Boolean?` | `SoulHud.shouldDrawBackground(id)` | `true` | `Surface` paints its `panel` background |
| `useMinecraftFont` | `useMinecraftFont: Boolean?` | `SoulHud.shouldUseMinecraftFont(id)` | `false` | (Stub) swap Inter for Mojang's font. *Wired into config + menu, rendering path NOT implemented yet — see "Future work" below.* |
| `hudTextShadow` | `useTextShadow: Boolean?` | `SoulHud.shouldDrawTextShadow(id)` | `false` | `Text` composables use `NvgRenderer.textShadow` instead of `text` — black drop shadow behind glyphs |
| `hudBoldFont` | `useBoldFont: Boolean?` | `SoulHud.shouldUseBoldFont(id)` | `false` | Per-glyph font swap via `NvgRenderer.boldVariantOf(font)` — Regular → Medium → SemiBold (SemiBold stays SemiBold; we don't ship Bold/Black) |

Per-HUD nullable fields all default to `null` ("follow global"). Old `gui_layout.json` files deserialize as null (Gson `Unsafe` leaves nullable references as null), so no schema bump needed.

**Formula:** all four helpers return `global && (perHud ?: true)`. **Global is the master.** Per-HUD `true` / unset → respects the global value. Per-HUD `false` → forces off for that HUD even when global is on. Per-HUD `true` cannot override global `false` back on.

**Where the wiring happens.** The HUD-background override is read by each HUD's `Content()` (passes `panel` color or `0x00000000` to `Surface(color = ...)`). The text-shadow + bold-font overrides are read **automatically** by every `Text` composable via `SoulInput.currentHudId` — set by `NvgFrame.submit` → `SoulInput.startFrame(currentHudId = hudId)`, scoped to the panel. New HUD code doesn't need to touch text rendering; the framework picks up the active HUD's settings transparently. Outside a HUD frame (`SoulScreen` content) `currentHudId` is null and both effects are off — screens manage their own font + emphasis explicitly via composable parameters.

**Context menu integration.** `GuiEditScreen.buildContextMenuItems(elementId)` adds four toggle rows (`HUD Background`, `Use Minecraft Font`, `Text Shadow`, `Bold Font`) below the corner presets. Labels read the effective resolver-helper result so they reflect the global+per-HUD merge in real time. Clicking flips the per-HUD nullable to the inverse of `current ?: true`. Each toggle has a dedicated `GuiLayoutManager.updateSoulHud<...>` setter that copies the `SoulHudElement` with the new value.

**`gui_layout.json` schema migration.** `GuiLayoutManager.CURRENT_SCHEMA_VERSION = 2`. On load, the raw JSON is probed for the `schemaVersion` key — **not** the deserialized field — because Gson uses `Unsafe` to bypass Kotlin constructor defaults on `SoulHudElement` (its `id` field has no default → no synthetic no-arg ctor), so the new alignment enum fields would deserialize as `null` from old files. When the key is missing OR `< CURRENT`, the in-memory layout is wiped to an empty `GuiLayout()` and immediately re-saved; per-frame `ensureLayoutElement` rebuilds entries from registration defaults. Users with a tweaked layout pay a one-time HUD-position reset. Bump `CURRENT_SCHEMA_VERSION` when a future field needs the same treatment.

**Dynamic selection bounds (`/soul gui`).** After each frame's compose + layout, `SoulHud.renderOne` records the root node's measured size into `SoulHudRegistry.recordMeasured(id, w, h)`. `GuiEditScreen.render` and `GuiEdit.findHitElement` read it back via `SoulHudRegistry.lastMeasured(id)` so the selection box tracks the **actual** rendered panel size, not the registered max bounds. Falls back to the registration `(width, height)` for HUDs that haven't drawn yet this session (e.g. their visibility gate is closed). Critical: the recording happens inside `NvgFrame.submit`'s deferred lambda, so the HUD id is captured by closure rather than read from a shared field — the lambda runs at PIP-flush time when the global "current id" would otherwise be stale.

**Dynamic selection bounds (`/soul gui`).** After each frame's compose + layout, `SoulHud.renderOne` records the root node's measured size into `SoulHudRegistry.recordMeasured(id, w, h)`. `GuiEditScreen.render` and `GuiEdit.findHitElement` read it back via `SoulHudRegistry.lastMeasured(id)` so the selection box tracks the **actual** rendered panel size, not the registered max bounds. Falls back to the registration `(width, height)` for HUDs that haven't drawn yet this session (e.g. their visibility gate is closed).

**Conditional visibility — return `Box {}`.** A HUD that shouldn't render right now (gate closed: wrong area, item not equipped, master toggle off, etc.) must still emit one root composable — `SoulComposer.build` requires exactly one. The canonical "render nothing" sentinel is a bare `Box {}` followed by `return` from the composable body. The Box measures to zero, takes no PIP texture cost, and stops the GUI-edit selection-box from gaining a stale measured size from a previous "visible" frame.

**Skyblock enchant gate.** `SkyblockItemUtils.hasArmorEnchant(player, enchantId)` reads `components.custom_data.enchantments.<id>` across the 4 equipment slots and returns `true` if any slot has a level > 0. Used by `LegionHud` (`ultimate_legion`) and `BobbinHud` (`ultimate_bobbin_time`) to render only when the wearer actually has the relevant enchant. Cheap enough to call per-frame from a composable. The numeric-level companion is `SkyblockItemUtils.highestArmorEnchantLevel(player, enchantId)`, which feeds the boost-percentage display on both HUDs. Per-stack helper: `SkyblockItemUtils.getSkyblockEnchantLevel(stack, enchantId)`.

**Global Skyblock rarity table.** `data/skyblock/SkyblockRarity.kt` mirrors SkyHanni's `LorenzRarity` — an enum mapping each Hypixel rarity tier (`COMMON` / `UNCOMMON` / `RARE` / `EPIC` / `LEGENDARY` / `MYTHIC` / `DIVINE` / `SPECIAL` / `VERY_SPECIAL` / `ULTIMATE`) to its display ARGB. Two entry points: `SkyblockRarity.forName(name)` returns the enum or `null`; `SkyblockRarity.colorFor(name, fallback = 0xFFFFFFFF)` returns the color or the fallback. Used by `SeaCreature.rarityColor()` to tint per-creature rows in the fishing tracker; reserved for item lore / pet tooltip / future rarity-aware features. Update the enum (not scattered constants) if Hypixel ever adds a new tier or changes a color.

**Legion / Bobbin Time boost formula.** Both Ultimate-tier armor enchants scale linearly with their on-screen count, per SkyHanni's `LegionBobbinOverlay.kt`:
- **Legion**: `boost% = level × 0.07 × min(nearbyPlayers, 20)` — 7 % per level per player within 30 blocks, capped at 20 players.
- **Bobbin Time**: `boost% = level × 0.2 × min(nearbyBobbers, 5)` — 20 % per level per `FishingHook` entity within 30 blocks, capped at 5 bobbers.

Both HUDs display the result on a second line as `Boost: +X.YY%` via `String.format(Locale.ROOT, ...)`. The cap constants live on the HUD object (`PLAYER_CAP` / `BOBBER_CAP`) and the multiplier as `BOOST_PER_LEVEL` — update those if Hypixel changes the formula.

**Fishing tracker visibility (`features/fishing/FishingVisibility`).** Tick-driven gate that combines four checks: (1) a SkyBlock rod (`FISHING_ROD_IDS` set covers all 14 vanilla + festival + bingo + lava variants) anywhere in the inventory, (2) `LocationApi.currentArea` in `FISHING_AREAS` (Hub / Crimson Isle / Spider's Den / Backwater Bayou / The Park / Farming Islands / Crystal Hollows / Dwarven Mines / Galatea / Jerry's Workshop / Lotus Atoll), (3) a water or lava block within 15 blocks of the player, (4) **first cast detected** — the local player's own `FishingHook` has been observed in water/lava at least once this session. The liquid check scans a 31×31×31 cube once per 20 ticks (~1 s) using a `BlockPos.MutableBlockPos` to avoid allocation, with `xz²` and `xyz²` short-circuits so most calls bail well under the cube's 30k positions. The bobber check runs every tick (cheap entity-list walk) until the sticky `firstCastDetected` flag flips true, then never again that session. A 60 s grace window after the last detected liquid keeps the HUD visible through brief chases away from the pond. Skipping the cube scan when the cheaper rod/area gates are already failing is the main reason the per-tick cost is negligible — most ticks never reach the block iteration. **Subscribes to `AreaChanged`**: every area transition zeroes the cached state (`firstCastDetected`, `lastLiquidAt`, `nearLiquidNow`, `hasRod`, scan counter) so leaving a fishing island silently re-arms the first-cast gate. Walking back into a pond without recasting won't reopen the HUD.

**`/soul gui` z-order fix for SoulHud.** `GuiEditScreen.render` calls `SoulHud.dispatchAll(context)` right after `renderTransparentBackground(context)` so HUDs re-render on top of the screen's blur layer. Without this they're visible but blurred during edit mode. Selection outline + label render afterward, on top of the now-crisp HUD.

**Adding a new `GuiElement` subclass.** Layout machinery has three `when` switches that need extending — failing to update one breaks `/soul gui` for that element type:
- `GuiLayoutManager.updateElementPosition` + `updateElementScale`
- `GuiLayoutManager.GuiRuntimeTypeAdapterFactory` (writer + reader — the `"type"` tag + class mapping in both directions)
- `GuiEdit.kt :: findHitElement` (selection hit-test) + `GuiEditScreen.render`'s element-bounds switch
- `GuiRendering.kt :: GuiRenderer.renderHud` dispatch (legacy `GuiRenderContext` path; new element types that render via NanoVG can be a no-op here, like `SoulHudElement`)

### HUD architecture

All HUDs live under `ui/hud/`. State-vs-view split: anything other code might want to read goes into a `features/<domain>/` state object; the HUD itself is pure presentation.

| HUD | View file | State source |
|---|---|---|
| Seasoning tracker | `ui/hud/SeasoningHud.kt` | `features/farming/seasoning/SeasoningState.kt` (driven by `SeasoningTracker` + `HarvestFeastReader`) |
| Legion counter | `ui/hud/LegionHud.kt` | None — count recomputed each tick (transient) |
| Bobbin time | `ui/hud/BobbinHud.kt` | `features/fishing/BobbinSpotter.kt` (count + alert state + alert decision logic) |
| Party overlay | `ui/hud/PartyHud.kt` | `features/party/PartyManager.kt` (chat-driven party state machine) |
| Mineshaft corpses | `ui/hud/MineshaftCorpsesHud.kt` | `features/mining/mineshaft/MineshaftCorpses.kt` (tab-list scan, by-type counts) |

HUDs read `cfg.<feature>.<flags>()` directly for enable/visibility flags — no extra `HudConfig` wrapper interface; the generated owo-config nested types already provide a typed surface. Each HUD pushes a text block via `GuiLayoutApi.updateTextBlock(...)` so it's positionable in `/soul gui`.

**Click handling on HUDs** (e.g. SeasoningHud's `[Reset Session]` line): registers a `ScreenMouseEvents.beforeMouseClick` listener and tracks its own bbox per render.

### Profile Viewer (SPV)

Opened via `/spv <username>`. Module under `profileviewer/`:

- **`SpvCommand`** — registers the command and dispatches to `ProfileViewerService`.
- **`ProfileViewerService`** — resolves UUID via `MojangApi`, fetches profiles from the backend via `BackendClient`, opens `ProfileViewerScreen`.
- **`MojangApi`** (under `profileviewer/api/`) — UUID resolution + cache.
- **`SpvExecutor`** — SPV-specific logging wrapper around `platform/concurrent/SoulExecutor`.
- **`ProfileViewerScreen`** — Soul UI `SoulScreen` subclass (post-P4.2 migration; was previously owo-ui). Centered card on an opaque page backdrop with a header (name + profile pager), `Tabs` strip, and `ScrollableList` body. State (current profile, active tab, scroll offset) lives as `@Volatile` fields and the composer re-reads them every frame — no manual rebuild.
- **`DungeonsTab`** — top-level `@SoulComposable fun DungeonsTabContent(member: JsonObject)`. Sections (`Catacombs` / `Classes` / `Catacombs Floors` / `Master Catacombs Floors` / `Totals`) are built from `Surface` cards containing `LevelRow` / `StatRow` / `FloorTable` composables; XP bars use the framework `ProgressBar`. All pure presentation — math and parsing remain in `service/DungeonsCalculator.kt` + `model/DungeonsView.kt`.

(There is no separate `SpvHttp`; SPV uses `BackendClient` from `platform/http/`.)

### Backend HTTP & auth (`platform/http/`)

- **`SoulHttp`** — bare `HttpClient` wrapper, sets `User-Agent: SoulMod/<version>/<mcVersion>`. Backend URL priority: system property `soul.backendUrl` → `cfg.dev.backend.backendUrlOverride()` → `https://sky.soulreturns.com`. **The `HttpClient` is deliberately NOT bound to `SoulExecutor`** — JDK `HttpClient.send()` dispatches its completion callbacks through the configured executor, and `SoulExecutor`'s 2-thread pool would deadlock the moment two blocking sends ran concurrently (both threads block on `send()`, neither thread can dispatch the inbound response). Letting `HttpClient` use its default internal executor keeps `SoulExecutor` free for the wrapper futures.
- **`BackendAuth`** — Mojang session-server handshake (`sessionService.joinServer` then `GET /authenticate`). Token cached in memory (`AtomicReference`) AND persisted to `config/soul/auth_token.txt` (token + expiry epoch ms) with a 23-hour client TTL matching the backend's 24-hour TTL — so restarts don't re-authenticate. 429 responses trigger a 5-minute backoff applied even to `forceRefresh = true` calls. `clear()` wipes both in-memory token and the cache file.
- **`BackendClient`** — authenticated GET with caching (`X-Backend-Expire-In` header sets TTL) and authenticated POST (no caching, JSON body via `JsonElement.toString()`). Both retry once on 401 via `BackendAuth.ensureAuthenticated(forceRefresh = true)`.
- **`PresenceService`** — event-driven `POST /presence/state` pushes on transitions. **Online signal is the live Mercure SSE connection itself** (backend updates `last_seen` on hub connect/disconnect); this service only publishes coarse state on changes — Hypixel server address, `isOnSkyblock`, current area, current sublocation. Subscribes to `AreaChanged` / `SublocationChanged` / `OnSkyblockChanged` plus a per-tick poll for server-address. State changes are coalesced with a 2 s debounce, and a `lastSent` snapshot dedupes redundant pushes. No heartbeat, no polling, no `/ping`.

`platform/concurrent/SoulExecutor` — fixed 2-thread daemon pool used by HTTP, presence, persistent-stats writes, and SPV. `SoulExecutor.log(...)` and `warn(...)` go through `SoulLogger("Soul/Backend")`, gated on `cfg.dev.debug.debugMode()`.

### Cloud sync (`platform/sync/`)

Per-account mirror of three mod data files to the backend (`config.json5`, `gui_layout.json`, `stats.json`). Local files remain the source of truth on disk — the engine is purely a mirror so a fresh install on a different machine can pull state back.

| File | Role |
|---|---|
| `SyncKind.kt` | Enum of mirrorable artifacts: `CONFIG`, `GUI_LAYOUT`, `STATS`. The `key` field is the URL suffix (`/sync/{key}`). |
| `SyncedArtifact.kt` | Data class binding a kind to a local `File`, a per-tick `enabled` predicate, and an `onAfterPull` reload hook called after a successful remote pull writes new bytes to disk. |
| `SyncMetadata.kt` | Sidecar persistence at `config/soul/sync_meta.json`. Per kind, records `pushedHash` (SHA-256 of bytes last successfully PUT) and `syncedAt` (server `updatedAt` epoch ms at last reconcile). |
| `SyncEngine.kt` | Orchestrator. `register(artifact)` → `start(masterEnabled)` from `Soul.kt`. Runs initial reconcile in background, then a tick-based change watcher every 200 ticks (~10 s), then a synchronous flush on `CLIENT_STOPPING`. **Owns its own single-thread `syncExecutor`** — never run sync work on `SoulExecutor`. Background: `SoulHttp`'s `HttpClient` uses `SoulExecutor` for async I/O, and `BackendClient.get/post` dispatch the actual HTTP into the same pool. If sync also ran there and called `.join()`, the 2-thread pool would deadlock against itself. Sync work blocks on `BackendClient.*.join()` from the `soul-sync` thread, which lets `SoulExecutor` stay free for the underlying network call. |

**Reconcile flow:** every 60 s tick the engine calls `GET /sync/status` once — returns `{kinds: {<key>: {updatedAt}}}` for kinds the player has pushed at least once (absent = "no remote"). For each artifact the engine then decides locally: (a) `localHash != meta.pushedHash` → push (no content GET); (b) status absent → push local if any (no content GET); (c) `status.updatedAt <= meta.syncedAt` → in sync, no-op (no content GET); (d) `status.updatedAt > meta.syncedAt` → fall through to a per-kind `GET /sync/{kind}` for the actual payload. Steady-state — when nothing changed and the realtime invalidate channel has been handling actual edits — every 60 s tick is one ~80-byte status GET and zero content GETs. `reconcileNow(kind)` (the realtime invalidate path) skips status and goes straight to per-kind `GET /sync/{kind}`, since we already know which kind changed.

**Per-kind GET `/sync/{kind}`** returns `{content, updatedAt}`:
- **404**: no remote yet → push local if present.
- **5xx / 401 / network**: log + one-shot `soulChat` warning per session ("Cloud sync unavailable — using local files"). No blocking, no command-gating; user keeps working on local state.
- **200**: three branches — (a) `localHash != meta.pushedHash` AND `meta.pushedHash` is non-empty → local has unpushed changes from the offline-last-session case, push wins; (b) `response.updatedAt > meta.syncedAt` AND hash differs → remote is fresher (admin-edit case, since the user is the only other writer and they aren't running two clients), write to disk and call `onAfterPull` on the client thread; (c) otherwise → in sync, no-op (but seed `pushedHash` if it was empty so we don't push needlessly next session).

The **same** reconcile runs at startup and on every 60 s tick — so admin edits made via the web UI propagate without a game restart. Local-side, **config and gui_layout don't wait for the next tick**: `SoulConfigScreen.removed()` and `GuiLayoutManager.save()` call `SyncEngine.notifyChanged(kind)`, which schedules a push ~2 s out (debounced — a burst of saves coalesces into one PUT). `STATS` deliberately doesn't call `notifyChanged` because `PersistentStats` writes every ~1 s during active gameplay; the 60 s scan plus `CLIENT_STOPPING` flush cover it. Pulls are **suppressed while `SoulConfigScreen` or `GuiEditScreen` is open** (`SyncEngine.isPullSuppressed()` checks `Minecraft.getInstance().screen`). Without that gate, a pull mid-edit would overwrite in-memory state, then the user's save-on-screen-close would push stale data back over the admin's change. Pushes still run while screens are open so the user's own edits propagate normally.

**Hot-reload hooks** wired by `Soul.registerSyncArtifacts(...)`:
- `CONFIG` → `SoulConfigHolder.reload()` **replaces** `INSTANCE` with a freshly-built wrapper (`SoulConfig.createAndLoad()`), not `INSTANCE.load()`. The model is reinstantiated so every field starts at its Java declared default; JSON is then deserialized on top. **Keys missing from JSON keep the model default** — which is what makes admin "reset one setting" work: stripping a key from the stored blob actually resets that field. `cfg.*` is read at point of use throughout the codebase so consumers pick up the new wrapper transparently.
- `GUI_LAYOUT` → `GuiLayoutManager.reload()`. **If the file is missing**, the in-memory layout is cleared so features re-seed via `updateTextBlock` next tick — same effect as the admin-reset path. If the file exists, it's parsed normally.
- `STATS` → `PersistentStats.reload()` (wipes in-memory storage, re-reads file; handles missing file as "start empty"). The next `ProfileChanged` event promotes the legacy bucket as usual.

**Admin "reset to defaults" propagation:** the mod publishes its declared defaults alongside every config push — `SyncedArtifact.defaultsJson` (implemented for `CONFIG` via `SoulConfigHolder.defaultsJson()`, walks every owo-config `Option` and builds a JSON tree matching `config.json5`'s structure). PUT body shape is `{content: "...", defaults: {...}}`. The backend stores both, and the admin web UI renders per-row "reset to default" buttons (visible when current ≠ default) plus a "reset all" button (fills the form with defaults; admin clicks Save to commit). When the admin clicks Save the backend writes the modified `content` and publishes a sync-invalidate — same path as any other admin edit, no special "reset" code path on either side. There is intentionally **no whole-blob delete / reset endpoint** — non-breaking changes don't need a blob wipe and tracking defaults at the field level keeps the UX better.

**Why no conflict path:** only one client per Mojang account can be online at a time (Hypixel server limitation), so concurrent writes are impossible by construction. The `pushedHash` check covers the only realistic edge case — last session pushed-or-tried-to-push and we don't know if it landed. No version vectors, no 409s, no `.bak` files.

**Toggles** live in `cfg.sync.*`: master `enabled` + per-kind `syncConfig` / `syncGuiLayout` / `syncStats`. Defaults all true. When master is off the engine is fully dormant — no initial reconcile, no tick watcher, no shutdown flush. Per-kind toggles only gate that specific artifact (and are hidden in the config UI when master is off).

**Backend contract** (`/sync/{kind}`):
- `GET /sync/{kind}` → 200 `{content: string, updatedAt: long}` | 404 (no data yet) | 401 | 429.
- `PUT /sync/{kind}` body `{content: string}` → 200 `{updatedAt: long}` | 400 | 401 | 413 (too large) | 429.
- `{kind}` is open-ended on the backend side (allows future splits like `stats_farming` without a contract change) but the client today registers exactly three.
- 256 KB max content size, enforced client-side as a safety net; backend should also reject larger.
- `X-Backend-Expire-In: 0` on GET responses — sync must always see fresh remote state.
- Auth: same `Authorization: <token>` bearer flow as every other endpoint.

### Realtime (`platform/realtime/`)

Long-lived Server-Sent Events subscriber to a Mercure hub, used for server-pushed signals: backend notifications, sync invalidation, and (planned) cross-user features like waypoint sharing and dungeon secret sync.

| File | Role |
|---|---|
| `RealtimeAuth.kt` | Calls `GET /realtime/token`, returns `{jwt, hubUrl, topics}`. Mercure uses a JWT bound to a topic allow-list; backend mints it from the bearer-token identity. |
| `MercureSseReader.kt` | SSE line-protocol parser (`data:`, `event:`, `id:`; ignores `retry:` and `:` comments). Reads until EOF; caller treats EOF as "reconnect". |
| `RealtimeClient.kt` | Single daemon thread (`soul-realtime`). Loop: fetch token → open SSE → read events → dispatch onto `Events` bus → on disconnect, exponential backoff and retry. **Owns its own thread, never on `SoulExecutor`** — same lesson as `SyncEngine` (the JDK `HttpClient` shares the executor for selector dispatch; long-lived reads would starve it). The HttpClient's selector threads do the actual I/O; our thread just drains the InputStream. |
| `RealtimeEvents.kt` | Typed `Event` subclasses published from `RealtimeClient.dispatch`: `SyncInvalidate(kind)`, `BackendNotification(message, severity)`. Add new event types here and a new `when` branch in `dispatch`. |

**Wire envelope** (per SSE `data:` line):
```json
{ "type": "sync-invalidate" | "notification", "data": { ... } }
```

Unknown `type` is logged and dropped, so the backend can introduce new message types without a coordinated mod release. The mod handles missing keys gracefully.

**Subscribers wired today:**
- `SyncEngine` subscribes to `SyncInvalidate` and calls `reconcileNow(kind)` — admin edits land immediately instead of waiting for the 60 s poll. Skipped while `SoulConfigScreen` / `GuiEditScreen` is open (same gate as periodic reconcile).
- `BackendNotificationCenter` (in `features/notifications/`) subscribes to `BackendNotification` and forwards each one to `RenderUtils.showAlert(text, color, scale=4.0f, durationMs=6_000)` — the same big-centered-text + bell-chime alert path used by `/soul dev testAlert`. Rendering is via `GuiMixin → RenderUtils.renderAlerts(context)`; no separate HUD element registration. Severity → color: `error` red, `warning` yellow, `info`/default white.

**Connection lifecycle:** started in `Soul.onInitializeClient()` as the final step (after `SyncEngine.start`). Gated by `cfg.sync.enabled()` — same master toggle as sync. Stopped on `CLIENT_STOPPING`. Reconnect backoff: 1 s → 2 s → 4 s → … capped at 60 s, resets on a clean disconnect (server-initiated close).

### Auto-update system

`UpdateChecker` polls `https://api.github.com/repos/Soul-Returns/SoulMod/releases/latest` on startup (respects config toggle) and stores the result in `latestUpdate`. `checkNow(callback)` is the manual variant used by `/soul checkForUpdates` — always fetches fresh regardless of the toggle.

`Updater.downloadAndSchedule()` streams the new JAR into `mods/.soul-update/<name>.part`, validates it as a ZIP, then moves it to `mods/`. Old JAR removal is cross-platform:

- **Linux/macOS**: deleted immediately (Unix allows unlinking open files).
- **Windows**: path written to `mods/.soul-update/pending-delete.txt`; `Updater.cleanupPendingDeletes()` is called on next startup to remove it once the file is no longer locked.

`UpdateModal` is the owo-ui dialog shown on the `TitleScreen` or on world join. `UpdateModal.dismissed` is an in-session flag — once dismissed it won't re-appear until the next launch.

### Mixins

- Mixin classes live in `src/main/java/com/soulreturns/mixin/` and are written in **Java** (not Kotlin).
- Registered in `src/main/resources/soul.mixins.json` under the `client` array (client-only mod). `defaultRequire: 1` means all injectors must match ≥1 target — use `require = 0` for methods that may not exist in the target class's own bytecode.
- **Static methods in Mixin classes must be `private`** — Mixin rejects non-private statics. Put shared helpers in `platform/mixinbridge/RenderHelper.java` or another bridge class.
- Access wideners go in `src/main/resources/soul.accesswidener`. Note `accesswidener v2 named` header — flips to `official` only on the 26.1 port.
- MixinExtras 0.4.1 is bundled: `@WrapOperation`, `@Local`, `@ModifyReturnValue` (used in 4 mixins). Vanilla `@Inject` for everything else.

**Mixin → Kotlin/Java bridges:**

| Bridge | Where | Called from |
|---|---|---|
| `RenderHelper.java` | `platform/mixinbridge/` (Java) | `GuiMixin`, `PlayerTabOverlayMixin`, `AbstractContainerScreenMixin`. Also called from `util/RenderUtils.kt`. |
| `SoulGuiHudAdapter` | `platform/mixinbridge/` (Kotlin) | `GuiMixin.renderHud` only. Bridges into `gui/lib/` rendering pipeline. |

**HUD-over-screen z-order.** `GuiMixin` injects at `Gui.render(...)` TAIL — that runs *before* `Screen.render` for the same frame, so a screen's dim/blur background overlays the HUD when an inventory is open. Fix: `SoulGuiHudAdapter.registerScreenOverlay()` registers a per-screen `ScreenEvents.afterRender` callback via `ScreenEvents.AFTER_INIT` that re-renders the HUD at `Screen.render` TAIL — after the screen's background, items, and tooltips. The two passes coexist: `Gui.render` TAIL handles the no-screen-open case; `Screen.afterRender` puts the HUD on top whenever a container screen is open. **Filtered to `AbstractContainerScreen` instances only** — i.e. bare inventory + chest-style menus (anvil, dispenser, Hypixel-custom GUIs, etc.). The Soul config screen, SPV screen, update modal, vanilla options, title screen, and other mods' screens are excluded so the HUD doesn't paint over their UI. Wired once from `Soul.onInitializeClient` near the other `SpecialGuiElementRegistry`/render registrations. The double-render when an inventory is open is harmless — text rendering is cheap, and the GUI layout is stateless across renders.

Other Kotlin classes called from mixins (not under `mixinbridge/` because they're not mixin-exclusive): `FarmingTimer`, `HighlightManager`, `RoundRectRenderer`, `SoulConfigHolder`, `RenderUtils`, `SkyblockItemUtils`, `DebugLogger`.

**Mojmap-named mixin classes** (post-Yarn migration). Some mixin files were renamed to match the new vanilla class targets:

| File | Targets |
|---|---|
| `ClientPacketListenerMixin` | `ClientPacketListener` (was `ClientPlayNetworkHandler` in Yarn) |
| `block/CactusHitboxMixin` | `CactusBlock` |
| `MultiPlayerGameModeMixin` | `MultiPlayerGameMode.destroyBlock` — drives `FarmingTimer` |
| `render/GuiMixin` | `Gui` (was `InGameHud`) — HUD scaling for hotbar, action bar, boss bar, scoreboard |
| `render/SneakHeightMixin` | `LivingEntity.getDimensions` |
| `render/AbstractContainerScreenMixin` | `AbstractContainerScreen` (was `HandledScreen`) — item highlighting |
| `render/ChatScreenMixin` | `ChatScreen` |
| `render/ChatComponentMixin` | `ChatComponent` (was `ChatHud`) |
| `render/PlayerTabOverlayMixin` | `PlayerTabOverlay` (was `PlayerListHud`) |
| `render/GameRendererMixin` | `GameRenderer` |

### HUD scaling

All HUD element scaling lives in `src/main/java/com/soulreturns/mixin/render/` and uses `RenderHelper.pushScaledMatrix(context, scale, pivotX, pivotY)` (now under `platform/mixinbridge/`) — a push/translate/scale/translate pattern around a pivot point. Sliders are in `HudScale` of `SoulConfigModel`.

Implemented elements and their pivots:

| Element | Mixin | Pivot |
|---------|-------|-------|
| Hotbar + bars | `GuiMixin` → `renderMainHud` | `(screenW/2, screenH)` bottom-center |
| Action bar | `GuiMixin` → `renderOverlayMessage` | `(screenW/2, screenH)` bottom-center |
| Boss bar | `GuiMixin` → `renderBossBarHud` | `(screenW/2, 0)` top-center |
| Scoreboard | `GuiMixin` → `renderScoreboardSidebar` | `(screenW, screenH/2)` right-center |
| Chat (unfocused) | `ChatComponentMixin` → `render` | `(0, screenH)` bottom-left |
| Chat (focused) | `ChatScreenMixin` → `render` | `(0, screenH)` bottom-left |
| Tab list | `PlayerTabOverlayMixin` → `render` | `(screenW/2, 0)` top-center |

Chat click hit-testing is fixed in `ChatScreenMixin` via `@WrapOperation` on `MouseButtonEvent.x()` / `MouseButtonEvent.y()` inside `mouseClicked` — applies the inverse transform so link clicks land correctly when chat is scaled.

**Pivot math**: `translate(px, py) → scale(s, s) → translate(-px, -py)` = scale around `(px, py)`.

### Rendering utilities

- `render/DrawContextRenderer` — extension helpers for rounded fills (`roundedFill`, `roundedFillCustomRadii`) using SDF shaders registered via `SoulRenderPipelines`.
- `render/RoundRectRenderer` — special GUI element registered with `SpecialGuiElementRegistry` for anti-aliased rounded corners.
- `ui/theme/Theme` — palette + `Surface` lambdas for the UI. **All custom rendering** in config/SPV/HUD code goes through these utilities — do not use raw `fillGradient` or GL calls for rounded shapes, do not hardcode ARGB colors.
- `ui/components/SoulSlider`, `ui/components/SoulIntSlider`, `ui/components/SoulToggle` — generic owo-ui components. `SoulSlider` is the continuous (Double) slider used for floats; `SoulIntSlider` is the discrete sibling with integer values and visible tick marks at each step. `RowBuilders.buildOptionRow` dispatches by the option's runtime type — `Int`/`Long` → `SoulIntSlider`, `Float`/`Double` → `SoulSlider` — so adding a new int-typed option with `@RangeConstraint` automatically gets the tick-mark slider.

### Logging & chat

Use `SoulLogger` for all console output. **Console `info`/`debug` are gated on the `dev.debug.debugMode` master toggle** — when it's off (the default), the mod is silent in the console except for `warn`/`error` lines. **Every call is still teed to the file log unconditionally**, so support reports can ship a complete `soul-latest.log` regardless of the user's debug state. Logger prepends `[tag]` to every message so it shows in launchers that hide the logger name. Sub-toggles under `dev.debug.logging.*` filter by category and **are hidden in the config UI when `debugMode` is off** (they have no effect there). All sub-toggles default `true` — flipping `debugMode` on gives you everything by default; trim from there. Log file lives at `config/soul/logs/soul-latest.log`.

`DebugLogger.logX(...)` calls follow the same split: the console side is gated on `debugMode` AND the per-category predicate, but the file side **always fires**. The rule is "the file is complete; the console is filtered." If you're adding a debug helper, write the file via `SoulFileLog.offer(...)` regardless of any toggle and only branch on the toggles for the console. `dev.debug.logToFile = false` is the one toggle that does silence the file side (checked inside `SoulFileLog` itself).

**Exception — message-category logs.** `DebugLogger.logMessageHandler / logCommandExecution / logSentMessage / logChatInput` are file-only (never reach the console) and gated on `dev.debug.includeMessagesInLog` — a sub-option of `logToFile`, **default off**. Active gameplay produces hundreds of these lines per minute, so they're opt-in. There's intentionally no console toggle for this category — there's no scenario where you want chat traffic in the in-game console. The on-startup `SoulFileLog.init()` rotates the previous session's file to `soul-YYYY-MM-DD-HHMMSS.log` and keeps the 10 most recent. A single daemon thread (`soul-log-writer`) drains a `LinkedBlockingQueue`, so log calls from any thread never block on I/O. Skip the tee with `cfg.dev.debug.logToFile = false` (default `true`). Lines look like `[2026-05-13 13:34:00.123] [soul-sync/INFO] [Soul/Sync] config: pushed` — bracketed columns mirror Minecraft's `latest.log` style and survive thread names with internal spaces (`Render thread`). Throwables get an inline stack trace.

```kotlin
private val logger = SoulLogger("Soul/MyFeature")
logger.info("something happened")
logger.warn("uh oh: {}", detail)  // SLF4J {} substitution supported
```

For in-game chat output use `soulChat()` from `com.soulreturns.util.Chat` — it prepends `[Soul]` and is safe to call from any thread:

```kotlin
soulChat("No update available.")
```

`util/MessageDetector.stripColorCodes` strips `§.` (any code, including Hypixel placeholder codes like `§y`, `§u`, `§x`, not just vanilla `§0-9a-fk-or`). Without this, embedded placeholder codes leak into parsed scoreboard/tab text. `util/MessageHandler` is the central chat-message pump that publishes `data/model/ChatMessage` events.

### Persistent stats (profile-keyed)

`stats/PersistentStats.kt` — per-Hypixel-SkyBlock-profile persistence for tracked numeric stats (seasonings, kills, sack counts, …). Stored at `config/soul/stats.json`. Public API is unchanged from v1; the storage layer is what's profile-aware.

**Storage shape (v2):**

```jsonc
{
  "version": 2,
  "profiles": {
    "Banana": { "seasonings": 64, "milestoneTargets": [5, 25, 75, 150, 250] },
    "Apple":  { "seasonings": 12, "milestoneTargets": [] },
    "_legacy": { /* pre-profile-detection bucket */ }
  }
}
```

**Active slot resolution:** `ProfileApi.currentProfile ?: LEGACY_KEY`. If no profile detected yet (early in session, or off SkyBlock), writes go into `_legacy`.

**Migration path** (transparent to callers):

- v1 files (no `profiles` key) load into `_legacy` and the file is rewritten in v2 format on next save.
- On the first `ProfileChanged` event with a non-null profile:
  - If the new profile slot is empty → promote `_legacy` into it.
  - If the new profile slot already has data → drop `_legacy` (avoids leaking pre-detection bytes into the wrong slot).

**API unchanged** — every existing caller just keeps working:

```kotlin
PersistentStats.current.seasonings           // active profile's data
PersistentStats.update { seasonings += 1 }   // mutates active profile
```

Adding a new tracked value is a single-line `var x: Long = 0L` field on `PersistentStats.Data` — Gson will fill it with the default for any older profile slot on read.

Tick-driven debounced save (max once per second), atomic write (temp file + rename), async via `SoulExecutor` so the client thread never blocks on disk. Use this for tracked counters / stats; keep user *preferences* in owo-config.

### Dev tools

- `/soul dev` subcommand bundles all developer-facing diagnostics: `getArea`, `getSubLocation`, `getProfile`, `listStatProfiles`, `resetSeasonings`, `clearAlerts`, `testAlert [<msg>]`, `testMessage <type> <msg>`. All literals are camelCase.
  - `getProfile` shows the active SkyBlock profile name from `ProfileApi`.
  - `listStatProfiles` enumerates every slot in `stats.json`, marking the active one and the `_legacy` bucket.
- `features/dev/DevKeybindHandler` — bypasses Minecraft's controls menu via tick-based `InputConstants.isKeyDown` polling. Six clipboard data dumps configured under `dev.keybinds.*` String options (path strings store key translation IDs like `key.keyboard.f6`): copy opened container GUI, item under cursor, held item, scoreboard, tab list, nearby entities (within 30 blocks). Each dump is JSON via Gson. Hover-slot access uses an access widener entry on `AbstractContainerScreen.hoveredSlot`.

### Fishing features

- `data/fishing/SeaCreatureCatalog` — bundled `assets/soul/sea_creatures.json` (snapshot of SkyHanni's `SeaCreatures.json` repo data). Builds `byCleanMessage` from each entry's `chat_message` plus stripped `alternate_messages`. `match(rawChat)` is one map probe. Refresh the JSON snapshot manually when SkyHanni adds new creatures — there's no live repo fetch.
- `data/skyblock/FishingFestivalState` — chat-driven festival state. The underway line (`FISHING FESTIVAL The festival is now underway`) is a "currently active" indicator: Hypixel sends it on real festival start, on server join while one is active, and sometimes duplicates it mid-festival (known bug). First occurrence while inactive → `Started` event + persist `festivalStartAt`; subsequent occurrences are no-ops. The concluded line (`… has concluded`) is reliable and authoritative — single fire per festival. A 1-hour safety cap from `firstSeenStartAt` ends the festival if the concluded message is somehow missed. `firstSeenStartAt` is persisted to `stats.json` so a mid-festival restart resumes the same bucket. **Mayor / Marina state is intentionally ignored** — special non-Marina mayors (Jerry perkpocalypse) can also fire festivals, and the chat lines are the only signal we trust.
- `features/fishing/FishingTracker` — always-on tracker for sea-creature catches + double-hooks + cocoons. Sticky-flag attribution mirroring `SkyHanni/SeaCreatureManager.kt`: `It's a Double Hook!(?: Woot woot!)?` line sets `pendingDoubleHook = true`; the next catch line consumes it and publishes `SeaCreatureCaught(creature, doubleHook, duringFestival, ts)`. Interleaver lines (autopet, `> Your bottle of thunder has fully charged!`, `A Reindrake forms from the depths.`, blank lines) do not reset the flag — they can legitimately land between the hook line and the catch line. Any other unrelated server line clears the flag. The double-hook chat line is subject to chat-compactor `(N)` coalescing; decoded with the same `lastCompactedBase/Count/At` delta logic as `SeasoningTracker`. **Cocoon line** (`CAUGHT! You cocooned a <SeaCreature>!`) is a kill-time outcome from cocoon equipment — counted into a separate `cocoons*` family, **does not** consume `pendingDoubleHook`, and **does not** increment the catch counter (the catch line already fired earlier in the encounter). The creature name in the cocoon line is matched against `SeaCreatureCatalog.byName(...)`; an unknown name (catalog out-of-date vs. a new festival creature) is still counted under the chat-line name so totals never drop data. Counters: all-time (`PersistentStats`), session (in-memory on the singleton), festival (additive bucket persisted to stats.json — cleared on `FishingFestivalEvent.Started`, retained through `Ended` so the HUD can show just-ended numbers).
- `features/fishing/FishingTimer` — session-only stopwatch for active fishing (mirrors `FarmingTimer` in spirit; resets per launch). **Active begins** when the local player's own `FishingHook` is in water or lava (`bobber.playerOwner === client.player && (bobber.isInWater || bobber.isInLava)`); other players' bobbers are ignored. Anchor captured at activation. **Active ends** on any of: (a) player moved > 50 blocks (xyz) from anchor — instant pause; (b) 5 s with both no own bobber AND no xyz movement; (c) 60 s hard cap since the bobber last existed (covers marathon sea creature fights where the bobber is gone but the player is still in combat in-area). Movement is **xyz only** — camera yaw/pitch deltas do not count, intentional so AFK rotation auto-rotators don't keep the timer alive. `FishingTimer.formatTime()` produces the `hh:mm:ss` string consumed by the HUD; `isPaused` toggles the `(Paused)` suffix. `resetSession()` is called from `FishingTracker.resetSession()` so the HUD Reset button clears both at once.
- `ui/hud/FishingHud` — per-creature panel. **Layout (top to bottom):**
  - **Header row 1**: "Fishing" centered (heading text).
  - **Header row 2**: timer (`hh:mm:ss` + optional `(Paused)`) on the left, Tabs (`Session` / `Total`) on the right via SpaceBetween. Outside an `AbstractContainerScreen` the Tabs widget collapses to a dim-color label showing just the active tab name (since clicks don't route there).
  - **Horizontal divider**, **scrollable list** of per-creature rows.
  - Each row: creature name (colored via `SeaCreature.rarityColor()` from the global `SkyblockRarity` table) on the left; right-side number cells in the order **CC → DH → Catches**, each at `NUMBER_CELL_WIDTH = 32f` with `Arrangement.Center`, separated by thin vertical `ColumnDivider`s. Hidden columns (toggled off in the Columns dropdown) are skipped entirely.
  - **Totals chip line**: appears below the list (before the footer divider) when ≥1 column is visible. Order: `CC: N (P.PP%)` `DH: N (P.PP%)` `Catches: N`. Arrangement = `SpaceBetween` when ≥2 chips, `End` when 1 chip (single chip flush-right). Percentages = `count / catches × 100`, `%.1f` via `Locale.ROOT`.
  - **Horizontal divider**, **footer** (only inside container screens, since clicks don't fire on bare HUD): three rows —
    1. Category dropdown (full width). Options: `"All"` + every `SeaCreature.variant` from the catalog, friendly-cased (`LAVA_CRIMSON_ISLE` → `Lava Crimson Isle`). `popupMaxHeight = 156f` (≈ 8 visible rows + scroll). Filter applies on `creature.variant`; creatures not in the catalog are dropped when a filter is active.
    2. Sort dropdown + Columns dropdown (50/50 via `.weight(1f)` each). Sort options: `Catches`, `Double Hooks`, `Cocoons`, `Rarity` (descending `SkyblockRarity.ordinal`, alpha tie-break), `Alphabetical`. Column-bound sorts (`Catches` / `DH` / `Cocoons`) hide when their column is toggled off; `Rarity` and `Alphabetical` always visible.
    3. **Reset Session** button (full width, centered label) — calls `FishingTracker.resetSession()` which also resets `FishingTimer`.
- Gated on `cfg.fishing.fishingHud.showHud()` + `cfg.fishing.fishingTracker.enableTracker()` + `SkyblockApi.isOnSkyblock` + `FishingVisibility.isVisible`. A separate `fishing_festival_sticker` HUD renders the festival countdown when active.
- HUD settings persisted at `config/soul/fishing_hud.json` (`tab`, `sort`, `scrollOffset`, `showCatches/DH/Cocoons`, `category` — null = All). Dropdown-open flags (`sortDropdownOpen`, `columnDropdownOpen`, `categoryDropdownOpen`) live on the singleton as `@Volatile` transients — never persisted.
- `features/fishing/BobbinSpotter` + `ui/hud/BobbinHud` — pre-existing Bobbin-Time feature (unchanged).
- `features/DoubleHookResponse` — pre-existing `/pc <message>` send on double-hook chat. Now event-bus + party-gated; still parses chat directly rather than subscribing to `SeaCreatureCaught` (v1 simplicity — could be migrated later if the user wants the party message to include the creature name).
- **Persisted fields** (`PersistentStats.Data`): `doubleHooksAllTime`, `catchesAllTime`, `cocoonsAllTime`, `doubleHooksByCreature: Map<String, Long>`, `catchesByCreature`, `cocoonsByCreature`, `festivalStartAt`, `festivalDoubleHooks`, `festivalCatches`, `festivalCocoons`, `festivalDoubleHooksByCreature`, `festivalCatchesByCreature`, `festivalCocoonsByCreature`. Maps are **immutable** (`Map<String, Long>`, not `MutableMap`) — replaced via `m + (k to v)` rather than mutated, so the save-time `Data.copy()` (shallow) is safe against concurrent writes.

#### Future tracker abstraction (don't extract early)

`FishingHud.kt` (~620 lines) + `FishingHudSettings.kt` (~165 lines) is currently a **one-off, not a reusable framework.** Everything in those files is hardcoded to the fishing domain — `Sort` enum names columns `Catches`/`DoubleHooks`/`Cocoons`, `CreatureRow` has three fixed numeric columns, `buildRows` reads `FishingTracker.session*ByCreature` + `PersistentStats.current.*ByCreature`, Category dropdown pulls from `SeaCreatureCatalog.variants()`, reset hardcodes `FishingTracker.resetSession()`, title is the literal `"Fishing"`. The old `gui/lib/tracker/` package WAS a generic abstraction; it was deleted in P3 because it had only one consumer and the Soul UI migration was easier flat.

**To add a similar tracker today** (e.g. Slayer Kills, Mineshaft Corpses with sort+filter, Mining Commissions): copy `FishingHud.kt` + `FishingHudSettings.kt`, rename, swap the data sources + column labels. Maybe 4–6 hours of work per new tracker, with substantial layout-scaffolding duplication (header, tabs, scrollable list, chips line, three-dropdown footer, reset button).

**When to extract a `TrackerHud<T>` abstraction**: at **N = 2 trackers**, not now. Abstracting from N=1 always picks the wrong abstraction boundary. Once we have two concrete examples, the right shared shape becomes obvious from where they diverge. The likely API at that point: a `TrackerSpec<T>` data class with `id`, `title`, `rowsProvider`, `columns: List<TrackerColumn<T>>`, `extraSorts: List<TrackerSort<T>>`, optional `categoryProvider` + `rarityProvider` + `onReset`, fed into a single `@SoulComposable fun TrackerHud(spec: TrackerSpec<T>)`. After the refactor, new trackers shrink to ~50 lines of spec each. Until then: copy.

**What IS already reusable** for any new tracker (so it's not zero leverage): every Soul UI primitive (`Surface`, `Column`, `Row`, `Tabs`, `ScrollableList`, `Dropdown` / `MultiSelectDropdown` with `popupMaxHeight` scroll, `Button`, `Text`, `ColumnDivider`); the persisted-settings + `@Volatile` transient-dropdown-flags pattern; `SkyblockRarity.colorFor` for rarity-tinted names; `LocationApi` + the `AreaChanged` event for visibility gates; the `Visibility` singleton template (`FishingVisibility`).

### Farming features

- `features/farming/seasoning/SeasoningTracker` — orchestrator with `@HandleEvent` methods. Subscribes to `ChatMessage` for the `RARE CROP! Seasoning` increment + to `HarvestFeastSnapshot` from the menu reader. Reset entrypoint for `/soul dev resetSeasonings`.
- `features/farming/seasoning/HarvestFeastReader` — cumulative-aware Harvest Feast menu reader. Finds the in-progress milestone with `0 < X < Y`; "all zero" sets count to 0; "all maxed" leaves count alone. Publishes `HarvestFeastSnapshot`.
- `features/farming/seasoning/SeasoningState` — single owner of seasoning-related mutable state. Funnels writes through `PersistentStats.update`. Exposes `total`, `targets`, `sessionChatGain`, `seasoningFarmingMs()` for the HUD.
- `features/farming/FarmingTimer` — session-only stopwatch (resets on client launch). Driven by `MultiPlayerGameModeMixin.destroyBlock` against a whitelist of harvestable Garden crop blocks. Pauses 2s after the last break (grace counted in active time). `(Paused)` indicator (`§c`) appended to the time line by SeasoningHud.
- `ui/hud/SeasoningHud` — Total / Farming Time / Per hour HUD. Gated on `cfg.farming.seasonings.enableTracker()` AND `LocationApi.isInArea("Garden")`. Hosts the `[Reset Session]` click handler.

### Mining / Mineshaft features

- `features/mining/mineshaft/MineshaftCorpses` — single tab-list scan per tick (only while in `Area: Mineshaft`). Parses rows of the form ` <Type>: NOT LOOTED|LOOTED` under the `Frozen Corpses:` header into `byType: Map<String, Counts(looted, unlooted)>`. Both `LapisCorpseAlert` and `MineshaftCorpsesHud` read from this — single source of truth, no duplicate parsing.
- `features/mining/mineshaft/LapisCorpseAlert` — listens for `Sending to Mineshaft...` chat. Within 10s, if `MineshaftCorpses.totalOf("Lapis") ≥ lapisCorpseThreshold`, fires `/pc !ptme Found N Lapis Corpse(s) in Mineshaft` once.
- `features/mining/mineshaft/VanguardCorpseAlert` — same trigger and window as Lapis but for `Vanguard`. Hypixel only ever spawns at most one Vanguard Corpse per mineshaft, so there is **no threshold slider and no count in the message** — just fires `/pc !ptme Found Vanguard Corpse in Mineshaft` once per send when `MineshaftCorpses.totalOf("Vanguard") > 0`.
- `features/mining/mineshaft/LittlefootAlert` — two-phase. (1) Pings `/pc !ptme Found Littlefoot` on the first LOS-confirmed sighting per mineshaft visit (resets on `AreaChanged`). (2) On the `SkyBlock Party Warp` chat, schedules a +2 s waypoint share (`/pc x: X, y: Y, z: Z`), preferring fresh LOS at fire time with the last LOS-confirmed sighting (within 60 s) as fallback. Both phases gate on the main toggle; waypoint phase additionally gates on the `autoShareLittlefootWaypoint` sub.
- `features/mining/mineshaft/MineshaftScoreboard` — one-shot reader for the top sidebar line. Returns `(type, fullIdentifier)` like `("ONYX", "ONYX_1")` only when `LocationApi.currentSublocation == "Glacite Mineshafts"`; null otherwise. Reused by visit tracking; could be reused by other features that need the active mineshaft instance.
- `features/mining/mineshaft/MineshaftVisitTracker` — opt-out (`cfg.dev.data.logMineshaftVisits`, default true). Lives under the **Dev → Data** subcategory — the home for backend data-collection toggles, distinct from `Dev → Debug → Logging` which is for client-side console logging gated on `debugMode`. Driven by `AreaChanged` + `ChatMessage`. On entry: classifies `VisitSource` (DISCOVERED / WARPED / UNKNOWN) from pre-entry chat signals (see "Mineshaft entry: discovered vs warped-in" below) and resets per-visit state. While inside: ticks to (a) capture the scoreboard identifier and (b) flip `littlefootFound` on first LOS. On exit: snapshots `MineshaftCorpses.byType` and POSTs `/mineshaft/visit` via `BackendClient.post`. Payload includes `warped: boolean` and `warpedBy: string?` (rank-stripped Mojang username, omitted when not warped). Fire-and-forget — network/4xx/5xx failures are logged and dropped (no client-side queueing). The `VisitSource` classification runs even when the telemetry toggle is off because the `!ptme` alerts depend on it.

## Hypixel SkyBlock conventions

Protocol / rendering quirks that recur across features — recorded here so we don't re-derive them per feature.

### Named-mob entity model

Hypixel renders most named mobs as a **`Player`-typed entity** at the mob's feet, plus an **invisible armor stand** ~2 blocks above carrying the level/health nameplate (`[Lv533] ❄✰❃ Littlefoot 50M/50M❤`). For position-sensitive features (waypoints, click targeting), prefer the Player entity — the armor stand reads ~2 blocks too high. `util/MobSpotter.findVisible(name)` already picks the Player variant when both are present.

### Line-of-sight is a ToS requirement

Hypixel forbids wallhack-style features. Any code that *acts* on an entity's presence (alerts, waypoint shares, automated chat) must verify the local player can actually see it — `Entity.hasLineOfSight(target)` must be true at the moment of the action. Scanning the entity list itself is fine; acting on through-wall data is not. `util/MobSpotter.findVisible` is the canonical helper; it returns `null` when LOS fails, and by design there is no "without LOS" variant — the ToS guard is part of the API surface, not opt-in.

### Party-chat `!ptme` protocol

`!ptme` is a community convention, **not** a Hypixel built-in. When `!ptme` appears in party chat, other members' mods (Skytils / SkyHanni / Patcher / etc.) react by running `/party transfer <author>` — making the requester the party leader so they can `/p warp` everyone in. When our mod sends `/pc !ptme Found Littlefoot` we're emitting that chat signal; we never run `/party transfer` ourselves.

**Always gate party-chat sends on `PartyManager.isInParty()`.** Every `!ptme` or `/pc x: y: z:` send site must check membership before calling `player.connection.sendCommand("pc …")`. Hypixel silently drops `/pc` when you're not in a party, so sending blind has no effect — but it also gives no feedback that something's wrong, which is worse for debugging than an explicit `DebugLogger.logFeatureEvent("… skipping !ptme — not in party")`. The gate also prevents the feature from "consuming" its trigger (e.g. marking `fired = true`) without anyone receiving the message; in our current code we still consume the trigger after logging the skip, since the trigger only makes sense for that specific mineshaft entry.

### `/p warp` mechanics

The warper **stays in their current world** — the other party members teleport TO the warper. Practical consequences:

- The finder's client retains the boss entity through and after the warp, so fresh LOS checks at `warp_msg + 2 s` still succeed.
- Hypixel's `SkyBlock Party Warp` chat message fires when the warp executes; it can take up to ~30 s after `/p warp` is run.
- Other members need ~1 s after that message to finish loading the destination instance. Coordinates shared at `warp_msg + 2 s` therefore land in everyone's chat *after* their client is ready to render a waypoint.

### Waypoint chat format

`x: N, y: N, z: N` (integer coords, comma-separated) in party chat is the de facto standard parsed by Skytils / SkyHanni / Patcher / similar waypoint mods. They auto-create an in-world waypoint from that string — **no user click required**. Emit exactly that format for waypoint sharing.

### `/party list` auto-refresh on Hypixel join

`PartyManager`'s state is chat-driven — it learns about a party only by parsing chat messages as they happen. After a game restart we start with no state and would miss any in-progress party until the next chat event mentioned it. To fix this, on the **first** `ClientPlayConnectionEvents.JOIN` per JVM session (and on every reconnect, since `DISCONNECT` re-arms), `PartyManager` waits ~2 s for chat to settle, then sends `/party list` and silently consumes the response:

- **Parsing** happens via the normal `MessageHandler.GAME/CHAT` → `handleServerMessage` path. No special routing.
- **Display suppression** is done by `ChatComponentAddMessageMixin` (Java mixin on `ChatComponent.addMessage(Component)`). On every chat-display attempt the mixin calls `PartyManager.shouldSuppressForDisplay(text)` and cancels via `ci.cancel()` when it returns `true`.
- `shouldSuppressForDisplay` is the predicate. It returns `true` only when (a) we're inside the 5 s expect window opened by `requestRefresh`, AND (b) the line matches a `/party list` block shape — dashes (ASCII `-` *or* the unicode dash family `‐..―`), `Party Members (N)`, `Party Leader:`, `Party Members:`, `Party Moderators:`, empty lines, or `You are not currently in a party.`. As a side-effect it counts dashes and closes the window after the **second** dashes line — both the in-party (`---` / header / leader / members / `---`) and no-party (`---` / "not in party" / `---`) responses are bracketed by exactly two dashes lines, so the same counter ends the window cleanly for both. **Do not** close the window on the "not in party" line itself — Hypixel still emits the trailing dashes line afterwards, and closing early would leak it into chat.
- `You are not currently in a party.` triggers `handleNotInParty()` in the normal parser, clearing any stale `currentState` and firing `PartyEvent.PartyDisbanded(prev, UNKNOWN)`.

**Why a mixin instead of `ClientReceiveMessageEvents.ALLOW_*`?** Other chat-heavy mods (SkyHanni, Skytils, Patcher) intercept chat earlier in the packet pipeline and can prevent our `ALLOW_*` handler from being called at all. Hooking `ChatComponent#addMessage` is the last gate before display, so it works regardless of which packet type or earlier hook the message took.

`PartyManager.requestRefresh()` is public — call it whenever you want to re-sync (e.g. a future `/soul refreshParty` command). It's idempotent and safe to call from any tick.

### Mineshaft entry: discovered vs warped-in

Two chat lines fire **before** `AreaChanged → Mineshaft` and exactly one of them precedes any given entry. They classify why the player ended up in the instance:

| Chat line | Means |
|---|---|
| `Sending to Mineshaft...` | Self-discovered — you triggered the mineshaft warp yourself (breaking glacite/ice in Dwarven Mines). |
| `Party Leader, <display>, summoned you to their server.` | Warped in — the party leader's `/p warp` pulled you into their instance. `<display>` includes the rank prefix; strip the bracketed rank to get the bare username. |

`MineshaftVisitTracker` listens to both, stamps them with timestamps, and resolves the next `AreaChanged → Mineshaft` to `VisitSource.DISCOVERED` / `VisitSource.WARPED` / `VisitSource.UNKNOWN` based on which (if either) is still fresh (10 s TTL). Exposes `isWarpedVisit()` for downstream gates.

**All `!ptme`-style features must skip when `MineshaftVisitTracker.isWarpedVisit()` is true** — the host already knows about the corpses / boss they discovered, re-pinging is noise. This is enforced today in `LapisCorpseAlert`, `VanguardCorpseAlert`, and both phases of `LittlefootAlert`. Source classification runs regardless of the `logMineshaftVisits` telemetry toggle so the gate works even for users who opted out of backend logging.

Other useful entry-confirmation lines (fire **after** `AreaChanged`, not used today but worth knowing):

- `[<rank>] <yourName> entered Glacite Mineshafts!` (inside a `-----` box) — discovery confirmation.
- ` ⛏ <yourName> entered the mineshaft!` (pickaxe glyph, single line) — warp-in confirmation.
- `MINESHAFT MODIFIERS!` — fires only for the discoverer; modifiers are determined at instance creation.

### Mineshaft type identifier (top scoreboard line)

In a Glacite Mineshaft, the top sidebar line looks like `§705/12/26 §8m6D§v§8M ONYX_1`. After stripping color codes (`MessageDetector.stripColorCodes`) the **last whitespace-separated token** is the mineshaft identifier — e.g. `ONYX_1`, `RUBY_3`, `JADE_2`. Split on `_`: prefix is the **type** (`ONYX`), suffix is a per-server **instance counter** (`1`). Treat the type list as open-ended — Hypixel can add new ones (Aquamarine, Peridot, etc.).

**Gating**: only treat that last token as a mineshaft identifier when `LocationApi.currentSublocation == "Glacite Mineshafts"`. Without that guard, the same parsing would extract garbage tokens from sidebars in unrelated areas (private island, hub, end, etc.) and the data would be poisoned. The sublocation check is cheap — do it first.

For the scoreboard iteration pattern, reuse the sidebar walk in `LocationReader.readSublocation`, but pick the **highest-score** line instead of the `⏣` line.

## Conventions that bite if ignored

- **Package root**: `com.soulreturns` — mod ID is `soul`. Subpackages match directory names (commands → `commands` plural, etc.). If you find a package declaration that doesn't match its directory, that's a bug to fix, not a convention.
- **Kotlin everywhere except Mixins + 2 specific Java files**: All game logic, config, GUI, and utilities are in Kotlin. Java is restricted to (1) Mixin classes, (2) `SoulConfigModel.java`, (3) `platform/mixinbridge/RenderHelper.java`.
- **Singleton features**: `object` declarations, never classes.
- **State-vs-view split**: HUDs go under `ui/hud/`; their backing state goes under `features/<domain>/`. Don't put `GuiLayoutApi.updateTextBlock` calls in `features/`.
- **owo-config `@Nest` fields are Java fields, not methods**: access the nested object as a property, then call leaf options as methods — `cfg.dev.updates.checkForUpdates()` NOT `cfg.dev().updates().checkForUpdates()`. Nests are properties (no parens), leaves are getter methods (parens).
- **Config access**: use the `cfg` accessor (e.g. `cfg.render.highlights.usePestVest()`). Never cache config values — always read at point of use so toggles take effect immediately.
- **Persistent stats access**: `PersistentStats.current.x` / `PersistentStats.update { x = ... }`. Never read `PersistentStats.knownProfiles()` for application logic — that's a debug surface.
- **SkyBlock location reads**: `LocationApi` (or subscribe to `AreaChanged` / `SublocationChanged`).
- **Static methods can't be called as Kotlin properties**: `Util.getPlatform()`, `SharedConstants.getCurrentVersion()` — Kotlin's getter→property syntax does NOT apply to *static* Java methods. Always use the explicit method-call form.
- **MC version string**: `SharedConstants.getCurrentVersion().name()` (Mojmap renamed `getGameVersion` → `getCurrentVersion`). Never derive the MC version from `Soul.version.substringAfter("+")` — in dev mode the mod version has no `+mcVersion` suffix.
- **Locale-safe formatting**: `String.format(Locale.ROOT, ...)` wherever floats/doubles are formatted for display or parsing — the system locale may use `,` as a decimal separator.
- **Theme for all UI**: config screen, SPV, HUDs, update modal must use `ui/theme/Theme.*` constants. Never hardcode ARGB colors in UI code.
- **Lang key prefix**: `text.config.soul/config.*` — the `/` is intentional, matching `@Config(name = "soul/config")`.
- **Shared run directory**: `run/` is shared across versions; launch from the IDE via the `:1.21.11` run config.
- **ktlint is clean** — `:check` reports zero warnings as of the last sweep. Keep it that way: run `ktlintFormat` on files you touch. If a manual-fix warning appears (most commonly "comment in `value_parameter_list` / `value_argument_list`" when an inline `// …` comment sits next to a parameter or argument), move the comment onto its own line above the param rather than suppressing the rule.
