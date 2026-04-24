# Copilot Instructions — Soul

## Build & Run

```sh
# Build all versions
./gradlew build --parallel --build-cache

# Build a single Minecraft version (e.g. 1.21.11)
./gradlew :1.21.11:build

# Build all versions and collect JARs into build/libs/<mod version>/
./gradlew buildAndCollect

# Refresh dependencies after changing gradle.properties
./gradlew --refresh-dependencies
```

There are no tests or linters configured in this project.

## Architecture

This is a **client-side Fabric mod** for Minecraft (Hypixel SkyBlock), written in **Kotlin** with Java used only for Mixin classes. It uses **Stonecutter** to build a single source tree against multiple Minecraft versions (defined in `settings.gradle.kts`).

### Multi-version build (Stonecutter)

- `settings.gradle.kts` declares target versions (e.g. 1.21.8, 1.21.10, 1.21.11). `vcsVersion` is the primary development version.
- `stonecutter.gradle.kts` defines swap constants and version-dependent parameters.
- Each `versions/<mc_version>/gradle.properties` pins Yarn mappings, Fabric API, and other per-version dependencies.
- Version-specific code uses **Stonecutter preprocessor comments**:
  ```kotlin
  //? if >=1.21.10 {
  // code for 1.21.10+
  //?} else {
  /*code for older versions
  *///?}
  ```
  The active branch is uncommented; the inactive branch is wrapped in a block comment. Always maintain both branches when editing version-gated code.

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
- Config values are accessed via a direct chain: `config.fishingCategory.chatSubCategory.doubleHookMessageToggle`.
- `ConfigManager` loads/saves `config/soul/config.json`. `ConfigMigration` handles version upgrades.
- Config UI is a custom widget-based system under `config/lib/ui/` with a theme engine (`ThemeManager`).

### Mixins

- Mixin classes live in `src/main/java/com/soulreturns/mixin/` and are written in **Java** (not Kotlin).
- Registered in `soul.mixins.json` under the `client` array (this is a client-only mod).
- Access wideners go in `soul.accesswidener`.

### GUI system

A custom immediate-mode-style GUI framework lives under `gui/lib/` (`GuiRendering`, `GuiLayoutManager`, `GuiElements`, `GuiEdit`). Screens extend this framework rather than using vanilla Minecraft screen widgets directly.

## Conventions

- **Package root**: `com.soulreturns` — mod ID is `soul`.
- **Kotlin everywhere except Mixins**: All game logic, config, GUI, and utilities are in Kotlin. Only Mixin classes are Java (Mixin framework requirement).
- **Singleton features**: Use `object` declarations, not classes, for features.
- **Config access**: Always go through the `config` property chain — never cache config values; read them at point of use so toggling takes effect immediately.
- **Stonecutter guards**: When touching Minecraft API calls that differ across versions, wrap them in `//? if` blocks. Check existing files for the correct syntax pattern.
- **Shared run directory**: All versions share `../../run` so you only need one Minecraft instance for testing.
