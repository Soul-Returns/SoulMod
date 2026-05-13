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
12. `GuiLayoutManager.loadOrInitialize()` — last, so features have registered their GUI elements.

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

### Read-only state holders (`data/location/`, `data/profile/`)

Pattern: a `Reader` polls Minecraft state on every client tick and feeds an `Api` singleton. The `Api` exposes read-only getters and publishes a `*Changed` event on transition only. **Every feature that needs SkyBlock location or profile info reads from the API; no feature parses the tab list itself.**

- **`LocationApi.currentArea`** / **`currentSublocation`** — fed by `LocationReader`. Area from tab list `Area:` virtual entry; sublocation from scoreboard sidebar `⏣` line. Restrictive ASCII regex strips lobby state suffixes (e.g. Garden's `ൠ x8` pest indicator).
- **`ProfileApi.currentProfile`** — fed by `ProfileReader`. Tab-list `Profile:` (or `Profile (Co-op):`, `Profile (Stranded):`) entry. Drives `PersistentStats` slot selection — see "Persistent stats" below.

Every consumer goes through `LocationApi` directly — or subscribes to `AreaChanged` / `SublocationChanged` from `data/model`.

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

The config screen has been split into a thin orchestrator + several focused helpers under `ui/config/`. Editing the screen usually means editing one of the helpers, not `SoulConfigScreen.kt`.

| File | Responsibility |
|---|---|
| `config/gui/SoulConfigScreen.kt` | Lifecycle (`build`/`rebuildContent`), sidebar list management, breadcrumb, search, capture-keybind input. Implements `ConfigScreenContext` so helpers can call back into screen-only operations. |
| `ui/config/registry/ConfigSections.kt` | **All declarative extension maps** (see table below) + the `isOptionVisible` predicate. Edit this to add new sections, links, action rows, or visibility rules. |
| `ui/config/rows/RowBuilders.kt` | Builds option rows, action rows, link rows, labeled-card sections. Owns the per-rebuild reset-button slot tracking. |
| `ui/config/components/ConfigRenderers.kt` | Stateless `ButtonComponent.Renderer` factories: category header, sidebar item, footer button, action button. |
| `ui/config/components/SocialIcons.kt` | Discord + GitHub icon buttons in the title bar. |
| `ui/config/model/CategoriesCollector.kt` | Walks the owo-config wrapper into a normalized `List<CategoryEntry>`. |
| `ui/config/search/ConfigSearchFilter.kt` | Pure search filter over the categories list. |
| `ui/config/model/Entries.kt` | Data classes (`CategoryEntry`, `SubcategoryEntry`, `LinkTarget`, `ActionRowSpec`) + the `ConfigScreenContext` interface. |

**Extension points (all in `ConfigSections.kt`):**

| Map | Purpose | Example entry |
|---|---|---|
| `explicitSections` | Group flat depth-2/3 fields into labeled sections | `"render" → "highlights" → [("Item Highlights", {fieldNames…})]` |
| `linkSections` | Cross-navigation buttons inside a sub | `"farming" → "pestFarming" → [LinkTarget("Configure Pest Equipment Highlighting", "render", "highlights")]` |
| `actionRows` | Label + button rows that run an arbitrary callback receiving `ConfigScreenContext` | `"dev" → "config" → [ActionRowSpec("Reload Config from Disk", "Reload") { ctx -> ctx.reloadConfig() }]` |
| `virtualSubs` | Subcategories with no backing config fields | `"farming" → ["pestFarming"]` |
| `categoryOrder` | Explicit sidebar order; unlisted cats fall to the end | `["general", "render", "fishing", "mining", "farming", "profileViewer", "dev"]` |
| `optionVisibility` | Conditional visibility (parent toggle gates child) | `"render.highlights.usePestVest" → { cfg.render.highlights.highlightPestEquipment() }` |
| `rebuildOnChange` | Boolean toggles whose change rebuilds content (so visibility-dependent rows update live) | `setOf("render.highlights.highlightPestEquipment")` |
| `keybindOptions` | String fields rendered as keybind pickers (capture mode) | `setOf("dev.keybinds.copyOpenedGui", …)` |

- A `↳` glyph is auto-prepended to any option whose path is a key in `optionVisibility` — visual hint that it's a child of another setting.
- Auto-grouping by path segment applies for options with depth ≥ 4. For depth-2/3 options without an explicit section, the section label falls back to the subcategory's own display name.
- Search filter (`filteredCategories`) hides categories with no matches; visibility filter (`isOptionVisible`) hides individual rows. Hidden options are also excluded from search results.
- **Sidebar text colors:** category headers white (`Theme.TEXT`), subcategories gray (`Theme.TEXT_DIM`) → white when selected (selection is also indicated by the accent background).
- **Dev category banner:** when `activeCategory == "dev"`, a translatable warning label (`text.config.soul/config.dev.warning`) is rendered above the scroll area.

**Keybind capture flow:** `RowBuilders.buildKeybindButton` doesn't directly mutate any state — it calls `ctx.requestKeybindCapture(opt)`. The screen owns the `capturingKeybind` field privately and exposes `isCapturing(opt)` for the renderer's display. One-way data flow.

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
- **`ProfileViewerScreen`** — owo-ui screen with a top tab bar (currently: Dungeons). Uses `Theme.*` for all styling.
- **`DungeonsTab`** — renders dungeons stats with XP progress bars and floor completion tables.

(There is no separate `SpvHttp`; SPV uses `BackendClient` from `platform/http/`.)

### Backend HTTP & auth (`platform/http/`)

- **`SoulHttp`** — bare `HttpClient` wrapper, sets `User-Agent: SoulMod/<version>/<mcVersion>`. Backend URL priority: system property `soul.backendUrl` → `cfg.dev.backend.backendUrlOverride()` → `https://sky.soulreturns.com`.
- **`BackendAuth`** — Mojang session-server handshake (`sessionService.joinServer` then `GET /authenticate`). Token cached in memory (`AtomicReference`) AND persisted to `config/soul/auth_token.txt` (token + expiry epoch ms) with a 23-hour client TTL matching the backend's 24-hour TTL — so restarts don't re-authenticate. 429 responses trigger a 5-minute backoff applied even to `forceRefresh = true` calls. `clear()` wipes both in-memory token and the cache file.
- **`BackendClient`** — authenticated GET with caching (`X-Backend-Expire-In` header sets TTL) and authenticated POST (no caching, JSON body via `JsonElement.toString()`). Both retry once on 401 via `BackendAuth.ensureAuthenticated(forceRefresh = true)`.
- **`PresenceService`** — sends authenticated `GET /ping?server=<addr>` every 20 s on its own daemon thread so the backend knows who is online.

`platform/concurrent/SoulExecutor` — fixed 2-thread daemon pool used by HTTP, presence, persistent-stats writes, and SPV. `SoulExecutor.log(...)` and `warn(...)` go through `SoulLogger("Soul/Backend")`, gated on `cfg.dev.debug.debugMode()`.

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

Use `SoulLogger` for all console output — it prepends `[tag]` to every message so output is identifiable in launchers that hide the logger name:

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
