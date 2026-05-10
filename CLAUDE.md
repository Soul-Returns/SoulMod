# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository overview

Soul is a **client-side Fabric mod** for Minecraft (Hypixel SkyBlock features), written in **Kotlin**. **Java is used only for Mixin classes** and for `SoulConfigModel.java` (consumed by an annotation processor). Mod ID is `soul`, package root is `com.soulreturns`. Stonecutter is used to maintain a single source tree across Minecraft versions; only `1.21.11` is currently active.

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

## Releases

Push a `v*` tag to trigger the GitHub Actions release workflow:

```powershell
git tag v2.1.0
git push origin v2.1.0
```

This calls `buildAndCollect`, then creates a **draft** release on GitHub with the JARs attached (sources and dev JARs excluded). The user edits and publishes the draft manually. JARs must be named `soul-<version>+<mcVersion>.jar` for the in-game updater to recognize them — the build system produces this automatically.

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

1. `SoulConfigHolder.init()` — runs legacy config migration, then loads the owo-config wrapper. The `cfg` accessor is unusable before this.
2. `MessageHandler.register()` — must precede feature registration so features can subscribe.
3. `BackendAuth.loadCached()` — **must run before** `PresenceService.start()` so the presence ping has a token.
4. `PresenceService.start()`.
5. `UpdateChecker.checkAsync()` + `Updater.cleanupPendingDeletes()` (Windows-only deferred deletes from prior auto-updates).
6. Screen event registrations for `UpdateModal` (TitleScreen + world join).
7. `HighlightManager.loadGroups()`, `TooltipHandler.register()`, `GuiLayoutManager.configure(...)`, `SpecialGuiElementRegistry.register(...)`.
8. `registerCommands()`, `registerFeatures()`.
9. `GuiLayoutManager.loadOrInitialize()` — last, so features have registered their GUI elements.

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

- Custom owo-ui screen in `config/gui/SoulConfigScreen.kt`. Sidebar lists categories and subcategories; content area shows options.
- Group labels inside subcategories are defined in `explicitSections` — a hardcoded map of `cat → sub → List<Pair<label, Set<fieldName>>>`. Options not in any explicit section fall into an unlabelled trailing card. Auto-grouping by path segment applies only to options with depth ≥ 4.
- All rendering uses `Theme.kt` constants (colors, radii, `Surface` lambdas). Never use raw colors in config/SPV UI — always reference `Theme.*`.

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
- Access wideners go in `src/main/resources/soul.accesswidener`.
- MixinExtras 0.4.1 is bundled: `@WrapOperation`, `@Local`, `@ModifyExpressionValue`, etc.

### HUD scaling

All HUD element scaling lives in `src/main/java/com/soulreturns/mixin/render/` and uses `RenderHelper.pushScaledMatrix(context, scale, pivotX, pivotY)` — a push/translate/scale/translate pattern around a pivot point. Sliders are in `HudScaleSubCategory` of `SoulConfigModel`.

Implemented elements and their pivots:

| Element | Mixin | Pivot |
|---------|-------|-------|
| Hotbar + bars | `InGameHudMixin` → `renderMainHud` | `(screenW/2, screenH)` bottom-center |
| Action bar | `InGameHudMixin` → `renderOverlayMessage` | `(screenW/2, screenH)` bottom-center |
| Boss bar | `InGameHudMixin` → `renderBossBarHud` | `(screenW/2, 0)` top-center |
| Scoreboard | `InGameHudMixin` → `renderScoreboardSidebar` | `(screenW, screenH/2)` right-center |
| Chat (unfocused) | `ChatHudMixin` → `render` | `(0, screenH)` bottom-left |
| Chat (focused) | `ChatScreenMixin` → `render` | `(0, screenH)` bottom-left |
| Tab list | `PlayerListHudMixin` → `render` | `(screenW/2, 0)` top-center |

Chat click hit-testing is fixed in `ChatScreenMixin` via `@WrapOperation` on `Click.x()` / `Click.y()` inside `mouseClicked` — applies the inverse transform so link clicks land correctly when chat is scaled.

**Pivot math**: `translate(px, py) → scale(s, s) → translate(-px, -py)` = scale around `(px, py)`.

### Rendering utilities

- `DrawContextRenderer` — extension helpers for rounded fills (`roundedFill`, `roundedFillCustomRadii`) using SDF shaders registered via `SoulRenderPipelines`.
- `RoundRectRenderer` — special GUI element registered with `SpecialGuiElementRegistry` for anti-aliased rounded corners.
- All custom rendering goes through these utilities — do not use raw `fillGradient` or GL calls for rounded shapes.

### Package layout (Kotlin, under `com.soulreturns.`)

`api/` (backend HTTP, auth, presence) · `commands/` · `config/` (model, holder, GUI, theme) · `features/` (gameplay features as singleton objects) · `gui/` (layout manager, lib) · `profileviewer/` (SPV) · `render/` (rendering utilities, SDF pipelines) · `update/` (update checker, downloader, modal) · `util/` (logger, chat, message handler).

## Conventions that bite if ignored

- **Package root**: `com.soulreturns` — mod ID is `soul`.
- **Kotlin everywhere except Mixins**: All game logic, config, GUI, and utilities are in Kotlin. Only Mixin classes (and `SoulConfigModel.java`) are Java.
- **Singleton features**: `object` declarations, never classes.
- **owo-config `@Nest` fields are Java fields, not methods**: access the nested object as a property, then call leaf options as methods — `cfg.updates.checkForUpdates()` NOT `cfg.updates().checkForUpdates()`.
- **Config access**: use the `cfg` accessor (`cfg.render().hideHeldItemTooltip()`). Never cache config values — always read at point of use so toggles take effect immediately.
- **MC version string**: `SharedConstants.getGameVersion().name()` — NOT `.name` (no synthetic property; `GameVersion` is a Java interface). Never derive the MC version from `Soul.version.substringAfter("+")` — in dev mode the mod version has no `+mcVersion` suffix.
- **Locale-safe formatting**: `String.format(Locale.ROOT, ...)` wherever floats/doubles are formatted for display or parsing — the system locale may use `,` as a decimal separator.
- **Theme for all UI**: config screen and SPV must use `Theme.*` constants. Never hardcode ARGB colors in UI code.
- **Lang key prefix**: `text.config.soul/config.*` — the `/` is intentional, matching `@Config(name = "soul/config")`.
- **Shared run directory**: `run/` is shared; launch from the IDE via the `:1.21.11` run config.
