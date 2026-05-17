# Soul

A client-side Fabric mod for **Hypixel SkyBlock** on Minecraft 1.21.11.

## Features

### Fishing

- **Fishing tracker.** Per-sea-creature breakdown of Catches / Double Hooks / Cocoons with Session and Total tabs, a fishing stopwatch, sortable columns, region filter (19 variants), and per-column visibility toggles. Auto-hides outside fishing islands and only appears once your own bobber actually hits water or lava.
- **Fishing Festival countdown** integrated into the Fishing HUD.
- **Bobbin Time overlay.** Shows the live boost % (`level × 0.2 × bobbers` capped at 5 bobbers) when the Ultimate Bobbin Time enchant is on worn armor, plus optional alert when too few bobbers are nearby.
- **Double Hook chat message.** Auto-sends a configurable party message on Double Hook.

### Mining

- **Mineshaft Corpses HUD** with by-type counts (Lapis, Vanguard, Tungsten, Umber, etc.).
- **`!ptme` party alerts** on Lapis (threshold-based), Vanguard, and Littlefoot sightings, with auto-waypoint share for Littlefoot on `/p warp`. Skips when you're the one being warped in.
- **Don Espresso alert** in Dwarven Mines.

### Farming

- **Seasoning tracker** for the Garden Harvest Feast: total + milestone targets + per-hour rate + farming stopwatch.
- **Item highlighting** for pest equipment + farming equipment, with optional Pest Vest highlight.
- **Custom item highlight** list.

### Combat / Boost overlays

- **Legion overlay.** Shows the live boost % (`level × 0.07 × nearby players` capped at 20) when the Ultimate Legion enchant is on worn armor.
- **Party overlay.** Chat-driven party state tracker — see leader, members, and join/leave events.

### Profile Viewer (`/spv <username>`)

Open any player's SkyBlock profile in-game. Tabs include Dungeons (with progress bars + class XP + per-floor times) and more. Cached profile data for snappy navigation.

### Quality-of-life

- **HUD scale sliders** for hotbar, tab list, boss bar, chat, action bar, and scoreboard.
- **`/soul gui` editor** with a 3×3 anchor grid (corners, edge midpoints, center) for every Soul HUD. Position is preserved on anchor swap, and per-HUD overrides for background / Minecraft font / text shadow / bold font.
- **Chat alerts** — render large centered overlays for configurable chat patterns.
- **Old sneak height** (1.8 sneak hitbox), **old cactus hitbox**, **double-sneak fix**.
- **Hide held-item tooltip**, **show SkyBlock item IDs in tooltips**.
- **Hide vanilla status effects** in inventory and/or HUD.

### Polish

- Custom UI framework (Soul UI) built on NanoVG — sharp text at any resolution, anti-aliased rounded corners, GUI-Scale-independent screens.
- Bundled Inter font (Regular / Medium / SemiBold / Bold / Black). Optional toggle to use Minecraft's font instead.
- Per-HUD text-shadow + bold-font controls.
- Auto-update from GitHub releases.
- Cloud sync of config, HUD layout, and stats across machines (per Mojang account).

## Commands

| Command | Purpose |
|---|---|
| `/soul` | Root command — config screen, GUI editor, subcommands. |
| `/soul config` | Open the config screen. |
| `/soul gui` | Open the HUD editor. |
| `/soul checkForUpdates` | Manual update check. |
| `/spv <username>` | Profile Viewer for any player. |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.11.
2. Drop the following into `mods/`:
   - `soul-<version>+1.21.11.jar` ([latest release](https://github.com/Soul-Returns/SoulMod/releases))
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
   - [owo-lib](https://modrinth.com/mod/owo-lib)
3. Launch. Open the config with `/soul config`.

## Third-party software

See **About → Used Software** in the config screen, or the in-game attribution list, for the full set of bundled / referenced projects (SkyHanni, Odin, Inter, owo-lib, Fabric API, No-Double-Sneak) with their licenses.

## License

MIT. See `LICENSE`.
