# Copilot Instructions — Soul

## Build & Run

```powershell
# Build the active version (1.21.11)
.\gradlew.bat ":1.21.11:build"

# Build all versions and collect JARs into build/libs/<mod version>/
.\gradlew.bat buildAndCollect

# Refresh dependencies after changing gradle.properties
.\gradlew.bat --refresh-dependencies
```

Set `$env:JAVA_HOME = "C:\Users\soul\.jdks\jbr-21.0.10"` before building if Gradle picks the wrong JDK.

There are no tests or linters configured in this project.

## Architecture

This is a **client-side Fabric mod** for Minecraft (Hypixel SkyBlock), written in **Kotlin** with Java used only for Mixin classes. It uses **Stonecutter** to build a single source tree against multiple Minecraft versions (defined in `settings.gradle.kts`).

### Multi-version build (Stonecutter)

- `settings.gradle.kts` declares target versions. Currently only `1.21.11` is active; `vcsVersion = "1.21.11"`.
- `stonecutter.gradle.kts` defines swap constants and version-dependent parameters.
- Each `versions/<mc_version>/gradle.properties` pins Yarn mappings, Fabric API, and other per-version dependencies.
- Stonecutter preprocessor comments are available for future version splits but are not currently used:
  ```java
  //? if >=1.21.11 {
  // code for 1.21.11+
  //?} else {
  /*code for older versions
  *///?}
  ```

### Feature pattern

Features are **Kotlin singleton objects** that expose a `register()` function called from `Soul.kt`:
```kotlin
object MyFeature {
    fun register() {
        // subscribe to events, set up listeners
    }
}
```
Feature registration happens in `Soul.registerFeatures()` after config and message handlers are initialized.

### Config system (owo-config)

- Config model is defined in `SoulConfigModel.java` with `@Config(name = "soul/config", wrapperName = "SoulConfig")`.
- The generated `SoulConfig` wrapper is held in `SoulConfigHolder.INSTANCE`; use the `cfg` top-level accessor from any Kotlin code.
- Config values are accessed as: `cfg.render().hudScale().chatScale()` — always read at point of use, never cache.
- Config file lives at `config/soul/config.json5`. Annotate new fields directly on the model class using owo-config annotations (`@RangeConstraint`, `@Nest`, `@SectionHeader`, etc.).
- `LegacyConfigMigrator` runs before `SoulConfig.createAndLoad()` and migrates the old `config/soul/config.json` to the new format. It also detects and re-migrates the old flat dotted-key format if found.
- Translation keys follow the pattern `text.config.soul/config.option.<path>` (note the `/` — it comes from `@Config(name = "soul/config")`). All keys live in `src/main/resources/assets/soul/lang/en_us.json`. `SoulConfigScreen.kt` constructs `text.config.soul/config.category.*`, `.group.*`, and `.title` keys manually.
- Sliders use `@SliderNumberInput(min, max, step, decimals)`. Always format values with `Locale.ROOT` to avoid locale-specific decimal separators (`,` vs `.`).

### Config UI (SoulConfigScreen)

- Custom owo-ui screen built in `config/gui/SoulConfigScreen.kt`. Sidebar lists categories and subcategories; content area shows options.
- Group labels inside subcategories are defined in `explicitSections` — a hardcoded map of `cat → sub → List<Pair<label, Set<fieldName>>>`. Options not in any explicit section fall into an unlabelled trailing card. Auto-grouping by path segment applies only to options with depth ≥ 4.
- All rendering uses `Theme.kt` constants (colors, radii, `Surface` lambdas). Never use raw colors in config/SPV UI — always reference `Theme.*`.

### Profile Viewer (SPV)

Opened via `/spv <username>`. Module lives under `profileviewer/`:

- **`SpvCommand`** — registers the command and dispatches to `ProfileViewerService`.
- **`ProfileViewerService`** — resolves UUID via `MojangApi`, fetches profiles from the backend via `BackendClient`, opens `ProfileViewerScreen`.
- **`BackendClient`** — authenticated GET with caching. Handles 401 by re-authenticating via `BackendAuth`.
- **`BackendAuth`** — Mojang session-server handshake; sends `x-minecraft-username` + `x-minecraft-server` to `/authenticate` and receives a bearer token.
- **`SpvHttp`** — raw `java.net.http.HttpClient` wrapper. Backend URL priority: system property `soul.spv.backendUrl` → config `backendUrlOverride` → `https://sky.soulreturns.com`.
- **`ProfileViewerScreen`** — owo-ui screen with a top tab bar (currently: Dungeons). Uses `Theme.*` for all styling.
- **`DungeonsTab`** — renders dungeons stats with XP progress bars and floor completion tables.

### Mixins

- Mixin classes live in `src/main/java/com/soulreturns/mixin/` and are written in **Java** (not Kotlin).
- Registered in `soul.mixins.json` under the `client` array (client-only mod). `defaultRequire: 1` means all injectors must match ≥1 target — use `require = 0` for methods that may not exist in the target class's own bytecode.
- **Static methods in Mixin classes must be `private`** — Mixin rejects non-private statics. Put shared helpers in utility classes (e.g. `RenderHelper`) instead.
- Access wideners go in `soul.accesswidener`.
- MixinExtras 0.4.1 is available: `@WrapOperation`, `@Local`, `@ModifyExpressionValue` etc.

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

## Conventions

- **Package root**: `com.soulreturns` — mod ID is `soul`.
- **Kotlin everywhere except Mixins**: All game logic, config, GUI, and utilities are in Kotlin. Only Mixin classes are Java.
- **Singleton features**: Use `object` declarations, not classes, for features.
- **Config access**: Use the `cfg` accessor (`cfg.render().hideHeldItemTooltip()`). Never cache config values — always read at point of use so toggles take effect immediately.
- **Locale-safe formatting**: Use `String.format(Locale.ROOT, ...)` wherever floats/doubles are formatted for display or parsing — the system locale may use `,` as a decimal separator.
- **Theme for all UI**: Config screen and SPV must use `Theme.*` constants. Never hardcode ARGB colors in UI code.
- **Lang key prefix**: `text.config.soul/config.*` — the `/` is intentional, matching `@Config(name = "soul/config")`.
- **Shared run directory**: `run/` is shared; launch from the IDE via the `:1.21.11` run config.

