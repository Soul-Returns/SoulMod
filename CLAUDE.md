# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **IMPORTANT — two-file project memory.** This is the lean orientation card. A long-form encyclopedia lives at `CLAUDE.local.md` (gitignored, per-machine) — UI framework internals, per-feature implementation notes, backend protocol specifics, mixin index, etc. When you discover a non-obvious Hypixel SkyBlock convention or project quirk that future sessions would have to re-derive: **write it in CLAUDE.md if it's load-bearing for any task** (init order, naming conventions, ToS guards), **write it in CLAUDE.local.md otherwise** (subsystem details, single-feature gotchas). If the finding is more user-specific than project-specific (preferences, workflow), save it as a memory instead.

## Repository overview

> **LEGACY REPO (as of July 2026).** Active development moved to the rewritten mod at `../Soul` (`/mnt/c/Users/soul/projects/Soul`, targeting MC 26.x). This repo gets **no main-feature updates and no new public releases** — the user keeps it building for their own gameplay for a few more weeks. Expected change shape: selective backports of individual features from the new mod (e.g. Hide Foliage, Hide Stuck Diana Mobs). When backporting, verify mixin target signatures against 1.21.11 Mojmap — the new mod's MC version differs (e.g. `Player.isPickable` doesn't exist in 1.21.11 bytecode; the override lives on `LivingEntity`).

Soul is a **client-side Fabric mod** for Minecraft (Hypixel SkyBlock features), written in **Kotlin**. **Java is used only for Mixin classes** and for `SoulConfigModel.java` (consumed by an annotation processor) and `RenderHelper.java` (Java statics callable from Mixins). Mod ID is `soul`, package root is `com.soulreturns`. Stonecutter is used to maintain a single source tree across Minecraft versions; only `1.21.11` is currently active.

**Mappings: Mojang Mappings (`loom.officialMojangMappings()`).** The codebase migrated off Yarn — `Minecraft` (not `MinecraftClient`), `GuiGraphics` (not `DrawContext`), `Component` (not `Text`), `Player` (not `PlayerEntity`), `MouseButtonEvent` (not `Click`), etc. New code must follow Mojang naming. `Identifier` happens to remain `Identifier` in Mojmap 1.21.11 (Mojang adopted the Yarn name); `Util` remains `net.minecraft.util.Util`.

## Build & run

The canonical invocations (for a Linux checkout, or from IntelliJ on Windows):

```bash
./gradlew :1.21.11:build                 # build active version (1.21.11)
./gradlew buildAndCollect                # all versions → build/libs/<mod version>/
./gradlew --refresh-dependencies         # after editing gradle.properties
./gradlew :1.21.11:ktlintFormat          # auto-format touched files
```

The repo is checked out on the Windows side (`/mnt/c/Users/soul/projects/SoulMod` in WSL = `C:\Users\soul\projects\SoulMod` in Windows). The Linux `./gradlew` launcher does NOT work against this path — see the WSL recipe below for the working invocation.

If Gradle picks the wrong JDK: `export JAVA_HOME=$HOME/.jdks/jbr-21.0.10` (or wherever your JBR-for-Linux lives — any JDK 21 on `$PATH` works). Required Java is **21**.

**Claude / WSL → Windows build recipe.** This repo lives at `/mnt/c/Users/soul/projects/SoulMod` (a Windows checkout mounted via DrvFs). The Linux `./gradlew` launcher **does not work here** — Gradle's `FileHasher` throws `java.io.IOException: Input/output error` on `/mnt/c` paths the moment a build starts, regardless of `JAVA_HOME`. Use `cmd.exe` to drive the Windows-side launcher and the Windows-side JBR instead:

```bash
cmd.exe /c "set JAVA_HOME=C:\Users\soul\.jdks\jbr-21.0.11&&gradlew.bat :1.21.11:compileKotlin"
```

Two gotchas worth memorizing:
- **No spaces around `&&`** in the `set` chain — cmd.exe folds trailing whitespace into the variable value, so `set JAVA_HOME=... && gradlew.bat` ends up with `JAVA_HOME` pointing at a non-existent path with a trailing space and gradlew fails with "invalid directory".
- The default user task for "did my Kotlin change compile" is `:1.21.11:compileKotlin` — fastest signal, finishes in ~20-30 s warm. Use `:1.21.11:build` only when verifying the full mod jar (~minute+).

The user runs builds via IntelliJ in normal flow; this recipe is specifically the path that works from a Claude-in-WSL shell.

**Static analysis:** `ktlint` + `detekt` are wired with `ignoreFailures = true` — they print warnings during `:check` but never break the build. **There are no automated tests.** Don't invent or expect a `test` task.

In-IDE runs use the `:1.21.11` run config. `run/` is shared across versions.

For dev-iteration hot-reload + multi-version Stonecutter + owo-config codegen pipeline details, see `CLAUDE.local.md`.

## Backend coordination — `prompts/` folder

Mod-side changes that need server-side counterparts (new endpoints, schema migrations, admin UI tweaks) are handed off via Markdown prompts in `prompts/`. Each file is a self-contained brief for the SkyBackend agent (Symfony + Doctrine, runs in WSL at `~/projects/SkyBackend`).

**Layout** — one subdirectory per feature, files numbered in execution order:

```
prompts/
└── item-catalog/ 01-foundation.md, 02-admin-ui.md
```

(Older feature subdirs — `sync/`, `realtime/`, `presence/`, `mineshaft/` — have shipped and been pruned. Recreate the same `<feature>/0N-<topic>.md` layout for any new backend handoff.)

When the mod ships a change touching the backend contract, drop a numbered file under the right subdir (`prompts/<feature>/0N-<topic>.md`). New feature = new subdir; follow-ups = next number in that feature's subdir. `prompts/` is **gitignored** — keep your local prompts authoritative; the backend session reads them in WSL.

## Releases

> **Retired** — no new public releases (see the legacy note at the top). Kept for reference only.

Push a `v*` tag to trigger the GitHub Actions release workflow:

```bash
git tag v2.1.0 && git push origin v2.1.0
```

This calls `buildAndCollect`, creates a **draft** release with the production mod JAR attached. `buildAndCollect` only collects `remapJar`. JARs must be named `soul-<version>+<mcVersion>.jar` for the in-game updater. Edit the draft and publish manually.

## Architecture

### Layered package structure

```
com.soulreturns/
├── core/            ← framework primitives (event bus, annotations)
├── data/            ← read-only state holders + readers (location, profile, prices, model events)
├── features/        ← gameplay features (singleton objects, organised by domain)
├── ui/              ← everything visual (HUDs, config screen helpers, theme, components, tracker framework)
├── platform/        ← transport plumbing (HTTP, threading, mixin bridges, NanoVG, sync, realtime)
├── config/          ← owo-config model + holder + cfg accessor
├── gui/             ← layout machinery (GuiLayoutManager, MinecraftGuiRenderContext)
├── render/          ← SDF shaders, RoundRectRenderer, SoulRenderPipelines
├── stats/           ← PersistentStats (profile-keyed)
├── update/          ← UpdateChecker, Updater, UpdateModal
├── util/            ← logger, chat, message handler, MobSpotter, Component-to-legacy converter
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

### Mod entrypoint: init order

`Soul.kt :: onInitializeClient()` has a fixed order other code depends on. Abridged sequence (see `Soul.kt` source for full step-by-step rationale, full step-by-step in `CLAUDE.local.md`):

1. `SoulFileLog.init()` — open the dedicated log file first
2. `SoulConfigHolder.init()` — runs LegacyConfigMigrator + path migrations, then loads owo-config wrapper. `cfg` is unusable before this.
3. `MessageHandler.register()` — must precede feature registration
4. `PersistentStats.init()` — loads stats.json AND subscribes to `ProfileChanged`
5. `LocationReader` / `SkyblockReader` / `ProfileReader` start publishing `AreaChanged` / `SublocationChanged` / `OnSkyblockChanged` / `ProfileChanged`
6. `BackendAuth.loadCached()` → `PresenceService.start()` (auth must precede presence)
7. `UpdateChecker.checkAsync()` + `Updater.cleanupPendingDeletes()` (Windows-only deferred deletes from prior auto-updates)
8. `HighlightManager.loadGroups()`, `TooltipHandler.register()`, `GuiLayoutManager.configure(...)`, `SpecialGuiElementRegistry.register(...)` (RoundRect + NVG PIP renderers)
9. `SoulGuiHudAdapter.registerScreenOverlay()` — re-renders HUDs on top of open screens
10. `registerCommands()`, `registerFeatures()`
11. `GuiLayoutManager.loadOrInitialize()` — must come before sync engine starts
12. `registerSyncArtifacts(...)` + `SyncEngine.start(...)` — reconciles in background, calls `onAfterPull` hot-reload hooks when remote is fresher
13. `BackendNotificationCenter.register()` + `RealtimeClient.start(...)` — **last**. Notification center must be wired before the realtime client connects.

**Within `registerFeatures()`** three ordering constraints (producer-before-consumer on the same tick):
- `BobbinSpotter.register()` before `BobbinHud.register()`
- `MineshaftCorpses.register()` before `LapisCorpseAlert` / `VanguardCorpseAlert` / `LittlefootAlert` / `MineshaftVisitTracker` / `MineshaftCorpsesHud`
- `PriceCache.start()` → `DragonProfitTracker.init()` → `DragonProfitHud.register()` (cache must populate before HUD reads prices; tracker init must complete before HUD composes)

### Feature pattern

```kotlin
object MyFeature {
    fun register() { Events.subscribe(this) }
    @HandleEvent fun onArea(event: AreaChanged) { /* … */ }
}
```

Always `object`, never `class`. State (if any) lives on the singleton. Features that have a HUD push the *view* into `ui/hud/` and keep the *state* in `features/`.

### Event bus (`core/events/`)

Synchronous pub/sub. Lambda subscription: `Events.subscribe<AreaChanged> { e -> … }`. Annotation subscription: `Events.subscribe(this)` + `@HandleEvent fun on(e: SomeEvent)`. Handlers run on the publishing thread. Handler exceptions are caught + logged. Inheritance is **not walked** — subscribe to concrete types. Subscribing inside a handler is safe (`CopyOnWriteArrayList`).

### Read-only state holders

`LocationApi.currentArea` / `currentSublocation`, `SkyblockApi.isOnSkyblock`, `ProfileApi.currentProfile` — read directly, or subscribe to `AreaChanged` / `SublocationChanged` / `OnSkyblockChanged` / `ProfileChanged`. **Don't parse the tab list / scoreboard yourself** — go through these APIs.

### Config system (owo-config)

- Model: `SoulConfigModel.java`, annotated `@Config(name = "soul/config", wrapperName = "SoulConfig")`. Annotation processor generates `SoulConfig` wrapper.
- Held in `SoulConfigHolder.INSTANCE`; access via top-level `cfg` accessor.
- **Read at point of use, never cache**: `cfg.render.hudScale.chatScale()`. Toggles take effect immediately.
- Config file: `config/soul/config.json5`.
- Translation keys: `text.config.soul/config.option.<path>` (the `/` is from `@Config(name = "soul/config")`).
- Sliders use `@SliderNumberInput(min, max, step, decimals)`. Format values with `Locale.ROOT`.

For the config screen rendering pipeline, see `CLAUDE.local.md → Config UI architecture`.

### Default behaviour policy: opt-in for features

Every **user-facing gameplay feature** defaults OFF — HUDs, automated chat sends, item highlights, overlays. The mod must do nothing visible on a fresh install until the user enables what they want.

**Exceptions (default ON):** data tracking + infrastructure that's invisible from the player POV — `Trackers.fishingTracker` / `Trackers.profitTrackers`, `Data.logMineshaftVisits`, `Sync.*`, `ProfileViewer.enabled`, `Updates.checkForUpdates`, `Debug.logToFile`. Also **features backported from the new mod** (`Render.hideFoliage`, `Diana.hideStuckDianaMobs`) — with no public releases left, the opt-in policy no longer applies to them and the user wants them on out of the box.

**Sub-options stay ON when their master is OFF.** They're display / behaviour preferences inside a feature; they only matter once the master is on.

### Soul UI framework + HUD architecture

Declarative Compose-style framework producing a `SoulNode` tree → measures → draws via a NanoVG runtime (`platform/render/nvg/`, lifted from Odin BSD-3 + heavily extended). Stateless composables; per-frame full rebuild; state lives in feature singletons.

HUDs register via `SoulHud.register(id, w, h, ..., content)` from `Soul.registerFeatures()`. Each HUD splits state (in `features/`) from view (in `ui/hud/`).

A generic `TrackerHud<T>` framework under `ui/hud/tracker/` is used by `FishingHud` + `DragonProfitHud` for the standard panel layout (header / tabs / scrollable list / chip line / footer with filter/sort/columns/reset). New "general" trackers (count-only) and "profit" trackers (count + coin value) reuse this — see `CLAUDE.local.md → Tracker framework`.

NanoVG runtime is fragile in ways that matter — see `CLAUDE.local.md → Soul UI framework` for the load-bearing details (DPR computation, font shadow rendering, scissor handling, Mojang-font passthrough).

### Backend HTTP, sync, realtime, prices

The mod talks to a Symfony backend at `https://sky.soulreturns.com` via `SoulHttp` (auth) + `BackendClient` (authenticated GET/POST). Cloud sync mirrors `config.json5` / `gui_layout.json` / `stats.json` per-account via `SyncEngine`. Realtime invalidation via Mercure SSE keeps changes live without polling. Hypixel public APIs (bazaar + Elite lowest-BIN) feed `PriceCache` for profit math.

**Critical:** `SoulHttp.client` is NOT bound to `SoulExecutor`. The JDK HttpClient dispatches completion callbacks through its configured executor; `SoulExecutor`'s 2-thread pool would deadlock under concurrent blocking sends. Same lesson for `SyncEngine` and `RealtimeClient` — both own their own daemon threads.

For protocol specifics + endpoint shapes + reconciliation rules see `CLAUDE.local.md → Backend HTTP & auth` / `Cloud sync` / `Realtime` / `Price cache`.

### Persistent stats (profile-keyed)

`stats/PersistentStats.kt` — per-Hypixel-SkyBlock-profile persistence at `config/soul/stats.json`. Active slot resolution: `ProfileApi.currentProfile ?: LEGACY_KEY`. Access: `PersistentStats.current.x` / `PersistentStats.update { x = ... }`. Adding a new tracked value: single-line `var x: Long = 0L` field on `PersistentStats.Data`.

### Mixins

Mixin classes in `src/main/java/com/soulreturns/mixin/`, **Java only**. Registered in `src/main/resources/soul.mixins.json` under `client`. Access wideners in `src/main/resources/soul.accesswidener`. Bridges into Kotlin/Java helper classes (e.g. `RenderHelper.java`, `SoulGuiHudAdapter`) — see `CLAUDE.local.md → Mixins`.

## Hypixel SkyBlock conventions

Protocol / rendering quirks that recur across features — recorded here so we don't re-derive them per feature.

### Named-mob entity model

Hypixel renders most named mobs as a **`Player`-typed entity** at the mob's feet, plus an **invisible armor stand** ~2 blocks above carrying the level/health nameplate (`[Lv533] ❄✰❃ Littlefoot 50M/50M❤`). For position-sensitive features (waypoints, click targeting), prefer the Player entity — the armor stand reads ~2 blocks too high. `util/MobSpotter.findVisible(name)` already picks the Player variant when both are present.

### Line-of-sight is a ToS requirement

Hypixel forbids wallhack-style features. Any code that *acts* on an entity's presence (alerts, waypoint shares, automated chat) must verify the local player can actually see it — `Entity.hasLineOfSight(target)` must be true at the moment of the action. Scanning the entity list itself is fine; acting on through-wall data is not. `util/MobSpotter.findVisible` is the canonical helper; it returns `null` when LOS fails, and **by design there is no "without LOS" variant** — the ToS guard is part of the API surface.

### Party-chat `!ptme` protocol

`!ptme` is a community convention, **not** a Hypixel built-in. When `!ptme` appears in party chat, other members' mods react by running `/party transfer <author>` — making the requester the party leader so they can `/p warp` everyone in. When our mod sends `/pc !ptme Found Littlefoot` we're emitting that chat signal; we never run `/party transfer` ourselves.

**Always gate party-chat sends on `PartyManager.isInParty()`.** Hypixel silently drops `/pc` when you're not in a party — sending blind has no effect but also gives no feedback, making bugs hard to spot.

### `/p warp` mechanics

The warper **stays in their current world** — others teleport TO them. Practical consequences:
- The finder's client retains the boss entity through and after the warp; LOS checks at `warp_msg + 2 s` still succeed
- Hypixel's `SkyBlock Party Warp` chat fires when the warp executes (up to ~30 s after `/p warp`)
- Others need ~1 s after that message to load the destination instance — coords shared at `warp_msg + 2 s` land in everyone's chat after their client is ready

### Waypoint chat format

`x: N, y: N, z: N` (integer coords, comma-separated) in party chat is the de facto standard parsed by Skytils / SkyHanni / Patcher / similar waypoint mods — they auto-create an in-world waypoint from that string. Emit exactly that format for waypoint sharing.

### `/party list` auto-refresh on Hypixel join

On the **first** `ClientPlayConnectionEvents.JOIN` per JVM session (and on every reconnect), `PartyManager` waits ~2 s for chat to settle, then sends `/party list` and silently consumes the response — bootstraps party state without waiting for chat events. See `CLAUDE.local.md → Hypixel SkyBlock conventions` for the suppression-mixin details (the response is suppressed from chat via `ChatComponentAddMessageMixin`, gated on a 5 s expect window opened by `requestRefresh`).

## Conventions that bite if ignored

- **Package root**: `com.soulreturns` — mod ID is `soul`. Subpackages match directory names. If you find a mismatched package declaration, it's a bug to fix, not a convention.
- **Kotlin everywhere except Mixins + 2 specific Java files**: All game logic, config, GUI, and utilities are in Kotlin. Java is restricted to (1) Mixin classes, (2) `SoulConfigModel.java`, (3) `platform/mixinbridge/RenderHelper.java`.
- **Singleton features**: `object` declarations, never classes.
- **State-vs-view split**: HUDs go under `ui/hud/`; their backing state goes under `features/<domain>/`. Don't put `GuiLayoutApi.updateTextBlock` calls in `features/`.
- **owo-config `@Nest` fields are Java fields, not methods**: access nested objects as properties, then call leaf options as methods — `cfg.dev.updates.checkForUpdates()` NOT `cfg.dev().updates().checkForUpdates()`. Nests are properties (no parens), leaves are getter methods (parens).
- **Config access**: use the `cfg` accessor. Never cache config values — always read at point of use so toggles take effect immediately.
- **Persistent stats access**: `PersistentStats.current.x` / `PersistentStats.update { x = ... }`. Never read `PersistentStats.knownProfiles()` for application logic — that's a debug surface.
- **SkyBlock location reads**: `LocationApi` (or subscribe to `AreaChanged` / `SublocationChanged`).
- **Static Java methods can't be called as Kotlin properties**: `Util.getPlatform()`, `SharedConstants.getCurrentVersion()` — Kotlin's getter→property syntax does NOT apply to *static* Java methods. Always use the explicit method-call form.
- **Avoid `when (enumValue)` in Kotlin** — emits a synthetic `<Class>$WhenMappings` inner class for ordinal mapping that Fabric's KnotClassLoader can fail to resolve, producing a deferred `NoClassDefFoundError` only when that branch runs (not at startup). Use `.name` round-trips, `if/else if` chains, or sealed-class polymorphism instead. `when (someString)` / `when (someInt)` / subjectless `when { … }` are safe — only enum/sealed-class subjects trigger the synthetic. Cases in this codebase carry inline kdoc explaining the avoidance; preserve those notes.
- **MC version string**: `SharedConstants.getCurrentVersion().name()`. Never derive the MC version from `Soul.version.substringAfter("+")` — in dev mode the mod version has no `+mcVersion` suffix.
- **Locale-safe formatting**: `String.format(Locale.ROOT, ...)` wherever floats/doubles are formatted for display or parsing — the system locale may use `,` as a decimal separator.
- **Theme for all UI**: config screen, SPV, HUDs, update modal must use `ui/theme/Theme.*` constants. Never hardcode ARGB colors in UI code.
- **Lang key prefix**: `text.config.soul/config.*` — the `/` is intentional, matching `@Config(name = "soul/config")`.
- **Shared run directory**: `run/` is shared across versions; launch from the IDE via the `:1.21.11` run config.
- **ktlint is clean** — keep it that way: run `ktlintFormat` on files you touch. If a manual-fix warning appears (most commonly "comment in `value_parameter_list` / `value_argument_list`"), move the inline comment onto its own line above the param rather than suppressing the rule.

## Where to find more — `CLAUDE.local.md` index

`CLAUDE.local.md` (gitignored, per-machine) carries the deep reference material that doesn't fit here. Sections you'll want to load when working on specific subsystems:

- **Hot reload / dev iteration loop** — JBR HotSwap setup + what doesn't hot-reload
- **Multi-version build (Stonecutter)** — preprocessor comments + version-pinned deps
- **owo-config code generation** — annotation processor wiring + `generateOwoConfig` task graph
- **Config UI architecture** — `SoulConfigScreen` + `ConfigSections` registry (categoryOrder / virtualSubs / actionRows / optionVisibility / keybindOptions)
- **Soul UI framework deep-dive** — NanoVG runtime (DPR computation, font shadow rendering, scissor handling, Mojang-font passthrough), composer + modifier system, foundation widgets (Tabs, Dropdown, ScrollableList, Surface), Minecraft integration (`SoulHud`, `SoulScreen`), scale model, anchor system, GUI Edit context menu, HUD-wide style overrides
- **HUD architecture** — full HUD table + click handling
- **Tracker framework** — `TrackerSpec<T>` / `TrackerColumn<T>` / `TrackerSort<T>` / `TrackerSettings` / `TrackerHud` composable
- **Price cache** — bazaar + Elite lowest-BIN polling cadence + Hypixel bazaar bid/ask semantics + `SHARD_<NAME>` attribute-shard id convention
- **Profit trackers** — `DragonProfitTracker` (standalone, not extending the generic base) + `KillSource` lootshare partitioning + drop scanner with render-distance gating
- **Fishing features** — `FishingTracker` + sea-creature catalog + festival state + visibility gate
- **Mining / Mineshaft features** — corpses scan + per-type alerts + visit tracker + scoreboard reader
- **Farming features** — seasoning tracker + farming timer + harvest feast reader
- **Profile Viewer (SPV)** — command flow + dungeons tab math
- **Backend HTTP & auth** — `SoulHttp` / `BackendAuth` / `BackendClient` / `PresenceService`
- **Cloud sync** — `SyncEngine` reconciliation algorithm + per-kind GET behavior + admin-edit propagation
- **Realtime** — `RealtimeClient` Mercure SSE loop + event taxonomy + reconnect backoff
- **Auto-update system** — `UpdateChecker` + cross-platform `Updater` + Windows pending-delete handling
- **Mixins** — registration index, bridges, HUD-over-screen z-order, Mojmap renames
- **HUD scaling** — per-element mixins + pivot math + chat click hit-testing
- **Rendering utilities** — `DrawContextRenderer` / `RoundRectRenderer` / theme palette / SDF shaders
- **Logging & chat** — `SoulLogger` (console gated on debugMode, file always on), `soulChat`, message handler
- **Persistent stats internals** — profile-keyed JSON schema + migration path
- **Dev tools** — `/soul dev` subcommands + clipboard-dump keybinds + `Component.toLegacyText` extractor
- **Mineshaft entry: discovered vs warped-in** — chat-signal classification for `!ptme` gating
- **Mineshaft type identifier** — top scoreboard line parsing
- **Item catalog (forthcoming)** — see `prompts/item-catalog/` for the backend prompts; mod-side migration TBD

When adding new sections to `CLAUDE.local.md`, update this index so future sessions can find them.
