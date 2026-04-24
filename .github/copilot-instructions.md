# Copilot Instructions — Soul

## Build & Run

```sh
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

### Config system

- `MainConfig` is the root config class with nested category classes annotated `@ConfigCategory`.
- Config values are accessed via a direct chain: `config.renderCategory.hudScaleSubCategory.chatScale`.
- `ConfigManager` loads/saves `config/soul/config.json`. `ConfigMigration` handles version upgrades.
- Config UI is a custom widget-based system under `config/lib/ui/` with a theme engine (`ThemeManager`).
- Sliders use `@SliderNumberInput(min, max, step, decimals)`. Always format values with `Locale.ROOT` to avoid locale-specific decimal separators (`,` vs `.`).

### Mixins

- Mixin classes live in `src/main/java/com/soulreturns/mixin/` and are written in **Java** (not Kotlin).
- Registered in `soul.mixins.json` under the `client` array (client-only mod). `defaultRequire: 1` means all injectors must match ≥1 target — use `require = 0` for methods that may not exist in the target class's own bytecode.
- **Static methods in Mixin classes must be `private`** — Mixin rejects non-private statics. Put shared helpers in utility classes (e.g. `RenderHelper`) instead.
- Access wideners go in `soul.accesswidener`.
- MixinExtras 0.5.0 is available: `@WrapOperation`, `@Local`, `@ModifyExpressionValue` etc.

### HUD scaling

All HUD element scaling lives in `src/main/java/com/soulreturns/mixin/render/` and uses `RenderHelper.pushScaledMatrix(context, scale, pivotX, pivotY)` — a push/translate/scale/translate pattern around a pivot point. Sliders are in `HudScaleSubCategory.kt`.

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

### GUI system

A custom immediate-mode-style GUI framework lives under `gui/lib/` (`GuiRendering`, `GuiLayoutManager`, `GuiElements`, `GuiEdit`). Screens extend this framework rather than using vanilla Minecraft screen widgets directly.

## Conventions

- **Package root**: `com.soulreturns` — mod ID is `soul`.
- **Kotlin everywhere except Mixins**: All game logic, config, GUI, and utilities are in Kotlin. Only Mixin classes are Java.
- **Singleton features**: Use `object` declarations, not classes, for features.
- **Config access**: Always read from the config chain at point of use — never cache config values so toggling takes effect immediately.
- **Locale-safe formatting**: Use `String.format(Locale.ROOT, ...)` wherever floats/doubles are formatted for display or parsing — the system locale may use `,` as a decimal separator.
- **Shared run directory**: `run/` is shared; launch from the IDE via the `:1.21.11` run config.
