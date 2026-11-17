# SoulMod

Client-side Fabric mod for **Minecraft 26.2**, written in **Kotlin**, providing Hypixel SkyBlock features. Mod id: `soul`; package root: `com.soulreturns`.

## License and attribution

**GPL-3.0-or-later**, including the bundled devonian dungeon dataset. Every third-party library, font, atlas, texture, dataset, table, constant list or adapted code added to the source or jar needs attribution in the same change: `config/gui/UsedSoftware.kt` and `assets/soul/licenses/THIRD-PARTY-NOTICES.md` (paths relative to `src/main/kotlin/com/soulreturns/` and `src/main/resources/`, respectively). Include required license texts and check GPL compatibility. No upstream license means no permission to copy; raise unclear licensing before shipping. See `.agents/rules/porting.md` and `UsedSoftwareTest`.

## Build and checks

Install **JDK 25**; the repository pins Gradle and Fabric dependencies. Use the Gradle wrapper:

```bash
./gradlew compileKotlin compileJava  # compile main sources
./gradlew test                      # JVM tests
./gradlew build                     # build/libs/soul-<version>.jar
./gradlew processResources          # copy changed shaders, atlases and textures
```

Minecraft 26.2 is unobfuscated: use `net.fabricmc.fabric-loom`, no mappings, and access widener namespace `official`. Inspect unknown Minecraft APIs with `javap` on the merged jar in `.gradle/loom-cache/minecraftMaven/`. JVM smoke checks use a temporary working directory and absolute classpaths; they do not verify GPU rendering.

## Architecture and conventions

- `Soul.kt` registers feature `object` singletons. `core/events/` is synchronous: handlers run on the publishing thread; client state/render changes belong on the client thread.
- Kotlin for features; Java mixins only when vanilla interception is necessary, registered in `soul.mixins.json`, JAVA_25, no refmap.
- Config fields belong in `SoulConfig` and `ConfigRegistry`. Read `cfg` at point of use, never cache; mutate with `ConfigManager.update { copy(...) }`.
- User-facing features default off unless an existing documented exception applies. Keep the default UX simple and reuse existing controls and approved mocks. Existing mocks are design references, not files to update for unrelated changes.
- **Preserve player data across updates.** Renaming, moving, retyping or removing stored values needs automatic migration: `config/migrations/` plus a real fixture for config version bumps; schema-versioned load-time steps for stats and other player files. Back up unreadable data with `DataBackups` before rewriting it. Settings and `stats.json` remain local; do not restore settings cloud-sync.
- Avoid `when (enumValue)` (`$WhenMappings` can fail under KnotClassLoader); use `if/else` or `.name`. String/int/subjectless `when` is fine. Keep static Java getter call syntax and use `Locale.ROOT` for numeric formatting.
- Backend calls use `BackendClient` / `RealtimeClient`, not `SoulHttp` directly. Dev commands use `DevOutput` windows. Developer-only UI/commands are hidden behind `DevAccess.unlocked`; infrastructure behavior is not gated by UI visibility.
- Rendering uses the shared SDF/MSDF layer and `SoulUi.scale`, independent of Minecraft GUI Scale. Reuse `SoulScreenBase`, HUD hosts, tracker panels and shared `Alerts` / `AlertHud`. No raw OpenGL, NanoVG, owo-lib or Stonecutter; use kotlinx.serialization, not Gson.
- `/pc` and `!ptme` sends require `PartyManager.isInParty()`. Only conversational sends get `[Soul]`; protocol messages stay raw. Strip Hypixel colors with `MessageDetector.stripColorCodes`.
- Anything acting on an entity requires line of sight through `util/MobSpotter`. Never validate Hypixel area, creature or other evolving names against a closed set.
- Do not launch an account-bearing client, deploy a jar, publish a release, or access maintainer credentials without an explicit request.

## Topic rules

Read the matching file in **`.agents/rules/` before changing its area**. These files contain the portable technical contracts; `rule://<name>` in them refers to the corresponding Markdown file. Research notes, packet captures and legacy checkouts mentioned as evidence may be local-only and are not prerequisites for remote work. If an ignored `AGENTS.local.md` is present, also read it for workstation instructions; remote development does not require that file.

| Rules | Area |
|---|---|
| [build-run](.agents/rules/build-run.md), [mc-api](.agents/rules/mc-api.md) | Toolchain, packaging, Minecraft APIs, events |
| [render-layer](.agents/rules/render-layer.md), [config-menu](.agents/rules/config-menu.md), [hud](.agents/rules/hud.md) | Rendering, screens, config migrations, HUDs |
| [keybinds](.agents/rules/keybinds.md), [inventory](.agents/rules/inventory.md), [sounds](.agents/rules/sounds.md) | Input, inventories, audio |
| [backend](.agents/rules/backend.md), [updater](.agents/rules/updater.md) | HTTP/auth/realtime contracts, update verification |
| [skyblock-state](.agents/rules/skyblock-state.md), [profile-data](.agents/rules/profile-data.md), [stats](.agents/rules/stats.md), [catalogs-prices](.agents/rules/catalogs-prices.md) | State readers, profiles, persistence, catalogs/prices |
| [dungeons](.agents/rules/dungeons.md), [dragon-profit](.agents/rules/dragon-profit.md) | Dungeons, solvers, dragon tracking |
| [metaldetector](.agents/rules/metaldetector.md), [mineshaft](.agents/rules/mineshaft.md), [nucleus](.agents/rules/nucleus.md), [commissions](.agents/rules/commissions.md) | Mining features |
| [fishing](.agents/rules/fishing.md), [sacks](.agents/rules/sacks.md), [pet-hud](.agents/rules/pet-hud.md) | Fishing, sacks, pets |
| [experiments](.agents/rules/experiments.md) | Experimentation Table screens and solvers |
| [porting](.agents/rules/porting.md) | Feature ports and third-party attribution |
