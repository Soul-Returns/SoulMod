# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository overview

Soul is a **client-side Fabric mod** for Minecraft (Hypixel SkyBlock features), written in **Kotlin**. **Java is used only for Mixin classes** and for `SoulConfigModel.java` (consumed by an annotation processor). Mod ID is `soul`, package root is `com.soulreturns`. Stonecutter is used to maintain a single source tree across Minecraft versions; only `1.21.11` is currently active.

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

There are **no tests and no linters configured** — don't expect or invent a `test` task.

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

### Multi-version build (Stonecutter)

- `settings.gradle.kts` declares Minecraft target versions; currently only `1.21.11` is active and `vcsVersion = "1.21.11"`.
- `stonecutter.gradle.kts` defines swap constants and version-dependent parameters.
- Each `versions/<mc_version>/gradle.properties` pins Yarn mappings, Fabric API, and other per-version dependencies.
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
2. `compileKotlin`, `compileJava`, and every `Jar` task `dependsOn(generateOwoConfig)` so the generated source exists before Kotlin (which references `SoulConfig`) compiles.
3. `compileJava` runs with `-proc:none` because the AP already ran in step 1 — re-running it would duplicate output.

**Practical implication:** if `SoulConfig` looks stale or unresolved after editing `SoulConfigModel.java`, run `gradlew :1.21.11:build` (or `buildAndCollect`) to regenerate before relying on Kotlin compile errors.

### Mod entrypoint: init order matters

`Soul.kt :: onInitializeClient()` has a **fixed order** that other code depends on:

1. `SoulConfigHolder.init()` — runs legacy config migration AND owo-path migration (`migrateOwoConfigPaths` for the `general/dev` restructure), then loads the owo-config wrapper. The `cfg` accessor is unusable before this.
2. `MessageHandler.register()` — must precede feature registration so features can subscribe.
3. `PersistentStats.init()` — loads tracked counters from `config/soul/stats.json` before features may read them.
4. `BackendAuth.loadCached()` — **must run before** `PresenceService.start()` so the presence ping has a token.
5. `PresenceService.start()`.
6. `UpdateChecker.checkAsync()` + `Updater.cleanupPendingDeletes()` (Windows-only deferred deletes from prior auto-updates).
7. Screen event registrations for `UpdateModal` (TitleScreen + world join).
8. `HighlightManager.loadGroups()`, `TooltipHandler.register()`, `GuiLayoutManager.configure(...)`, `SpecialGuiElementRegistry.register(...)`.
9. `registerCommands()`, `registerFeatures()`.
10. `GuiLayoutManager.loadOrInitialize()` — last, so features have registered their GUI elements.

### Feature pattern

Features are **Kotlin singleton `object`s** that expose a `register()` function called from `Soul.registerFeatures()`:

```kotlin
object MyFeature {
    fun register() {
        // subscribe to events, set up listeners
    }
}
```

Never use classes for features.

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

### Auto-update system

`UpdateChecker` polls `https://api.github.com/repos/Soul-Returns/SoulMod/releases/latest` on startup (respects config toggle) and stores the result in `latestUpdate`. `checkNow(callback)` is the manual variant used by `/soul checkForUpdates` — always fetches fresh regardless of the toggle.

`Updater.downloadAndSchedule()` streams the new JAR into `mods/.soul-update/<name>.part`, validates it as a ZIP, then moves it to `mods/`. Old JAR removal is cross-platform:

- **Linux/macOS**: deleted immediately (Unix allows unlinking open files).
- **Windows**: path written to `mods/.soul-update/pending-delete.txt`; `Updater.cleanupPendingDeletes()` is called on next startup to remove it once the file is no longer locked.

`UpdateModal` is the owo-ui dialog shown on the `TitleScreen` or on world join. `UpdateModal.dismissed` is an in-session flag — once dismissed it won't re-appear until the next launch.

### Backend auth (`BackendAuth`)

Authentication uses a Mojang session-server handshake (calls `sessionService.joinServer` then `GET /authenticate`). The bearer token is:

- Cached in memory (`AtomicReference`) for the session.
- Persisted to `config/soul/auth_token.txt` (token + expiry epoch ms) with a 23-hour client TTL matching the backend's 24-hour TTL — so restarts don't re-authenticate.
- `loadCached()` must be called early in `onInitializeClient()`, before `PresenceService.start()`.
- 429 responses trigger a 5-minute backoff applied even to `forceRefresh = true` calls.
- `BackendAuth.clear()` wipes both in-memory token and the cache file.

### Backend URL resolution (`SoulHttp.backendBaseUrl()`)

Priority: system property `soul.backendUrl` → config `backendUrlOverride` → `https://sky.soulreturns.com`.

### Config system (owo-config)

- Model is `SoulConfigModel.java` annotated `@Config(name = "soul/config", wrapperName = "SoulConfig")`.
- Generated `SoulConfig` is held in `SoulConfigHolder.INSTANCE`; access from any Kotlin code via the top-level `cfg` accessor.
- Read at point of use, **never cache**: `cfg.render().hudScale().chatScale()`.
- Config file lives at `config/soul/config.json5`. Add new fields directly on the model with owo-config annotations (`@RangeConstraint`, `@Nest`, `@SectionHeader`, etc.).
- `LegacyConfigMigrator` runs before `SoulConfig.createAndLoad()` and migrates the old `config/soul/config.json` to the new format. It also detects and re-migrates the old flat dotted-key format if found.
- Translation keys follow `text.config.soul/config.option.<path>` (the `/` is from `@Config(name = "soul/config")`). All keys are in `src/main/resources/assets/soul/lang/en_us.json`. `SoulConfigScreen.kt` constructs `text.config.soul/config.category.*`, `.group.*`, and `.title` keys manually.
- Sliders use `@SliderNumberInput(min, max, step, decimals)`. Always format values with `Locale.ROOT` to avoid locale-specific decimal separators (`,` vs `.`).

### Config UI (`SoulConfigScreen`)

- Custom owo-ui screen in `config/gui/SoulConfigScreen.kt`. Sidebar lists categories and subcategories; content area shows options. Persistent header with breadcrumb (left) + search box (right) survives sub changes.
- All rendering uses `Theme.kt` constants (colors, radii, `Surface` lambdas). Never use raw colors in config/SPV UI — always reference `Theme.*`.
- **Sidebar text colors:** category headers white (`Theme.TEXT`), subcategories gray (`Theme.TEXT_DIM`) → white (`Theme.TEXT`) when selected (selection is also indicated by the accent background).

**Extension points (hardcoded maps inside `SoulConfigScreen`):**

| Map | Purpose | Example entry |
|---|---|---|
| `explicitSections` | Group flat depth-2/3 fields into labeled sections | `"render" → "highlights" → [("Item Highlights", {fieldNames…})]` |
| `linkSections` | Cross-navigation buttons inside a sub | `"farming" → "pestFarming" → [LinkTarget("Configure Pest Equipment Highlighting", "render", "highlights")]` |
| `actionRows` | Label + button rows that run an arbitrary callback | `"dev" → "config" → [ActionRow("Reload Config from Disk", "Reload", { wrapper.load(); rebuildContent() })]` |
| `virtualSubs` | Subcategories with no backing config fields | `"farming" → ["pestFarming"]` |
| `categoryOrder` | Explicit sidebar order; unlisted cats fall to the end | `["general", "render", "fishing", "mining", "farming", "profileViewer", "dev"]` |
| `optionVisibility` | Conditional visibility (parent toggle gates child) | `"render.highlights.usePestVest" → { cfg.render.highlights.highlightPestEquipment() }` |
| `rebuildOnChange` | Boolean toggles whose change rebuilds content (so visibility-dependent rows update live) | `setOf("render.highlights.highlightPestEquipment")` |
| `keybindOptions` | String fields rendered as keybind pickers (capture mode) | `setOf("dev.keybinds.copyOpenedGui", …)` |

- A `↳` glyph is auto-prepended to any option whose path is a key in `optionVisibility` — visual hint that it's a child of another setting.
- Auto-grouping by path segment applies for options with depth ≥ 4. For depth-2/3 options without an explicit section, the section label falls back to the subcategory's own display name (so every card always has a header).
- Search filter (`filteredCategories`) hides categories with no matches; visibility filter (`isOptionVisible`) hides individual rows. Hidden options are also excluded from search results.

**Dev category banner.** When `activeCategory == "dev"`, a translatable warning label (`text.config.soul/config.dev.warning`) is rendered above the scroll area. The Reload-config-from-disk action moved from the screen footer into `dev/config` as an action row.

### Profile Viewer (SPV)

Opened via `/spv <username>`. Module under `profileviewer/`:

- **`SpvCommand`** — registers the command and dispatches to `ProfileViewerService`.
- **`ProfileViewerService`** — resolves UUID via `MojangApi`, fetches profiles from the backend via `BackendClient`, opens `ProfileViewerScreen`.
- **`BackendClient`** — authenticated GET with caching. Handles 401 by re-authenticating via `BackendAuth`.
- **`SpvHttp`** — raw `java.net.http.HttpClient` wrapper. Backend URL priority: system property `soul.spv.backendUrl` → config `backendUrlOverride` → `https://sky.soulreturns.com`.
- **`ProfileViewerScreen`** — owo-ui screen with a top tab bar (currently: Dungeons). Uses `Theme.*` for all styling.
- **`DungeonsTab`** — renders dungeons stats with XP progress bars and floor completion tables.

### Mixins

- Mixin classes live in `src/main/java/com/soulreturns/mixin/` and are written in **Java** (not Kotlin).
- Registered in `src/main/resources/soul.mixins.json` under the `client` array (client-only mod). `defaultRequire: 1` means all injectors must match ≥1 target — use `require = 0` for methods that may not exist in the target class's own bytecode.
- **Static methods in Mixin classes must be `private`** — Mixin rejects non-private statics. Put shared helpers in a utility class (e.g. `RenderHelper`).
- Access wideners go in `src/main/resources/soul.accesswidener`. Note `accesswidener v2 named` header — flips to `official` only on the 26.1 port.
- MixinExtras 0.4.1 is bundled: `@WrapOperation`, `@Local`, `@ModifyReturnValue` (used in 4 mixins). Vanilla `@Inject` for everything else.

**Mojmap-named mixin classes** (post-migration). Some mixin files were renamed to match the new vanilla class targets:

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

All HUD element scaling lives in `src/main/java/com/soulreturns/mixin/render/` and uses `RenderHelper.pushScaledMatrix(context, scale, pivotX, pivotY)` — a push/translate/scale/translate pattern around a pivot point. Sliders are in `HudScale` of `SoulConfigModel`.

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

- `DrawContextRenderer` — extension helpers for rounded fills (`roundedFill`, `roundedFillCustomRadii`) using SDF shaders registered via `SoulRenderPipelines`.
- `RoundRectRenderer` — special GUI element registered with `SpecialGuiElementRegistry` for anti-aliased rounded corners.
- All custom rendering goes through these utilities — do not use raw `fillGradient` or GL calls for rounded shapes.

### Persistent stats (separate from owo-config)

`stats/PersistentStats.kt` — single typed `Data` data class persisted to `config/soul/stats.json`. Mutate via `PersistentStats.update { seasonings += 1 }`. Tick-driven debounced save (max once per second), atomic write (temp file + rename), async via `SoulExecutor` so the client thread never blocks on disk. Adding a new tracked value is a single-line `var x: Long = 0L` field on `Data`. Use this for tracked counters / stats; keep user *preferences* in owo-config.

### Location & message helpers

- `util/SkyblockLocation.kt` — `area` (current Hypixel SkyBlock island, read from tablist `Area: <X>` virtual entry: "Garden", "Hub", "Dwarven Mines", …) and `sublocation` (sub-area from the scoreboard sidebar's `⏣` line: "The Garden", "Ruins", …). Restrictive ASCII-letters-only regex strips lobby suffixes like the Garden's `ൠ x8` pest indicator. Cheap to poll (≤80 string comparisons per call).
- `util/MessageHandler.kt` / `MessageDetector.stripColorCodes` — strips `§.` (any code, including Hypixel placeholder codes like `§y`, `§u`, `§x`, not just vanilla `§0-9a-fk-or`). Without this, embedded placeholder codes leak into parsed scoreboard/tab text.
- `util/Chat.kt :: soulChat()` — thread-safe in-game chat output prefixed with `[Soul]`.

### Dev tools

- `/soul dev` subcommand bundles all developer-facing diagnostics (moved out of top-level): `getArea`, `getSubLocation`, `resetSeasonings`, `clearAlerts`, `testAlert [<msg>]`, `testMessage <type> <msg>`. All literals are camelCase.
- `features/dev/DevKeybindHandler` — bypasses Minecraft's controls menu via tick-based `InputConstants.isKeyDown` polling. Five clipboard data dumps configured under `dev.keybinds.*` String options (path strings store key translation IDs like `key.keyboard.f6`): copy opened container GUI, item under cursor, held item, scoreboard, tab list. Each dump is JSON via Gson. Hover-slot access uses an access widener entry on `AbstractContainerScreen.hoveredSlot`.

### Farming features

- `features/farming/SeasoningTracker` — chat-message increment on `RARE CROP! Seasoning` matches + cumulative-aware Harvest Feast menu reader (finds the in-progress milestone with `0 < X < Y`; "all zero" sets count to 0; "all maxed" leaves count alone). HUD shows Total / Farming Time / Per hour, gated on `cfg.farming.seasonings.enableTracker()` AND `SkyblockLocation.area == "Garden"`.
- `features/farming/FarmingTimer` — session-only stopwatch (resets on client launch). Driven by `MultiPlayerGameModeMixin.destroyBlock` against a whitelist of harvestable Garden crop blocks. Pauses 2s after the last break (grace counted in active time). `(Paused)` indicator (`§c`) appended to the time line.

### Package layout (Kotlin, under `com.soulreturns.`)

`api/` (backend HTTP, auth, presence) · `commands/` (incl. `subcommands/DevSubcommand`) · `config/` (model, holder, GUI, theme) · `features/` (gameplay features as singleton objects, incl. `dev/`, `farming/`, `itemhighlight/`, `mining/`, `party/`) · `gui/` (layout manager, lib) · `profileviewer/` (SPV) · `render/` (rendering utilities, SDF pipelines) · `stats/` (PersistentStats) · `update/` (update checker, downloader, modal) · `util/` (logger, chat, message handler, SkyblockLocation).

## Conventions that bite if ignored

- **Package root**: `com.soulreturns` — mod ID is `soul`.
- **Kotlin everywhere except Mixins**: All game logic, config, GUI, and utilities are in Kotlin. Only Mixin classes (and `SoulConfigModel.java`) are Java.
- **Singleton features**: `object` declarations, never classes.
- **owo-config `@Nest` fields are Java fields, not methods**: access the nested object as a property, then call leaf options as methods — `cfg.dev.updates.checkForUpdates()` NOT `cfg.dev().updates().checkForUpdates()`. Nests are properties (no parens), leaves are getter methods (parens).
- **Config access**: use the `cfg` accessor (e.g. `cfg.render.highlights.usePestVest()`). Never cache config values — always read at point of use so toggles take effect immediately.
- **Static methods can't be called as Kotlin properties**: `Util.getPlatform()`, `SharedConstants.getCurrentVersion()` — Kotlin's getter→property syntax does NOT apply to *static* Java methods. Ravel migration mistakenly converted these to `Util.platform` / `SharedConstants.currentVersion`; always use the explicit method-call form.
- **MC version string**: `SharedConstants.getCurrentVersion().name()` (Mojmap renamed `getGameVersion` → `getCurrentVersion`). Never derive the MC version from `Soul.version.substringAfter("+")` — in dev mode the mod version has no `+mcVersion` suffix.
- **Locale-safe formatting**: `String.format(Locale.ROOT, ...)` wherever floats/doubles are formatted for display or parsing — the system locale may use `,` as a decimal separator.
- **Theme for all UI**: config screen and SPV must use `Theme.*` constants. Never hardcode ARGB colors in UI code.
- **Lang key prefix**: `text.config.soul/config.*` — the `/` is intentional, matching `@Config(name = "soul/config")`.
- **Shared run directory**: `run/` is shared; launch from the IDE via the `:1.21.11` run config.
