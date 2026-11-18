# SoulMod test server

A local Paper **26.2** server that imitates Hypixel SkyBlock closely enough for SoulMod to believe it
is on SkyBlock: the Hypixel Mod API handshake and `hyevent:location`, the sidebar, the tab list, the
action bar, chest menus and items with SkyBlock `custom_data`. Agents use it with the test client
(`tools/testclient/c`) to check rendering, screens, HUDs, mixins and packet handling without the user.

**Never Hypixel.** Offline mode, bound to `127.0.0.1:25590` only, and not part of the mod's Gradle
build. Everything it generates lives in `run/` and `cache/` (gitignored).

## Run it

```bash
tools/testserver/s up          # download Paper, build the plugin, start, wait for "Done (" (idempotent)
tools/testserver/s status      # ready | starting | stopped (exit 3)
tools/testserver/s cmd "hx help"   # one console command; prints what the server logged for it
tools/testserver/s log [N]     # last N console lines (default 60)
tools/testserver/s build       # rebuild the plugin only (runs the round-trip tests)
tools/testserver/s restart     # stop + up: picks up plugin and presets.json changes
tools/testserver/s down        # clean stop ("stop" on the console, TERM after 60 s)
```

A typical session with the client (lock it, so agents take turns):

```bash
tools/testserver/s up
tools/testclient/lock testclient bash -c '
  tools/testclient/c up                    # joins as SoulTester; preset "hub" is applied on first join
  tools/testclient/c run "/hx preset dungeon"
  tools/testclient/c shot dungeon
  tools/testclient/c run "/soul dev location"; tools/testclient/c shot location
  tools/testclient/c down'
tools/testserver/s down
```

What `s up` does, from a fresh clone:

1. Asks the PaperMC downloads API (`fill.papermc.io/v3`) for the latest 26.2 build, downloads it into
   `cache/` and checks its sha256 (offline: keeps using the cached jar).
2. Lets paperclip unpack the server into `run/` (`versions/`, `libraries/`); the plugin compiles
   against exactly those jars (26.x is unobfuscated, so server internals need no paperweight).
3. Builds `plugin/` with the repo's `gradlew -p` (its own build; tests run) and copies
   `SoulTestKit.jar` into `run/plugins/`.
4. Writes `server.properties` (offline, `127.0.0.1:25590`, flat, peaceful, no nether, spawn protection
   0, whitelist off), `eula.txt` and `ops.json` (`SoulTester`, offline UUID = UUIDv3 of
   `OfflinePlayer:SoulTester`).
5. Starts Java 25 (`/usr/lib/jvm/java-25-openjdk`, override with `SOUL_TESTSERVER_JAVA`) detached,
   stdin on the FIFO `run/console.in` (that is how `s cmd` talks to the console), output in
   `run/console.log`; ready when `Done (` appears (`READY_TIMEOUT`, default 240 s).

`up`/`down`/`restart` take `run/.s.lock`; the server process never holds it, nor the caller's stdout.
The plugin sets the gamerules (26.x snake_case: `advance_time false`, `advance_weather false`,
`keep_inventory true`, `fall_damage false`, no phantoms/patrols/traders), noon, clear weather,
cancels hunger and player damage, and hides join/quit messages.

`s cmd` prints the log lines that appeared until the log is quiet; a command that logs nothing returns
after ~7.5 s with no output. Prefix-less (`hx help`, not `/hx help`; a leading `/` is stripped).

## /hx commands

From the console every command acts on all online players (normally just SoulTester); a player runs
it on themselves (op only); `hx as <player> <subcommand…>` targets one player. Text arguments are
legacy-formatted: `§` or `&` codes.

| Command | What it does |
|---|---|
| `hx help` | the list below |
| `hx preset <hub\|dungeon\|end\|mineshaft>` | location + sidebar + tab + action bar of a preset, then its join chat (profile name + `Profile ID:`) |
| `hx reset` | clear sidebar, tab and action bar; location back to `resetLocation` (main lobby) |
| `hx location <name>` | a named location from presets.json, sent as `hyevent:location` |
| `hx location <mode> [map] [serverName]` | SkyBlock location by mode (`mining_3 Dwarven_Mines`; `_` in map = space; serverName defaults to a random `miniNNX`) |
| `hx sidebar <name\|clear>` | SkyBlock sidebar |
| `hx tab <name\|clear>` | SkyBlock tab list (80 fake entries) + header/footer |
| `hx actionbar <name\|clear\|text…>` | action bar text, re-sent every second until cleared |
| `hx chat <text…>` | one system chat line |
| `hx pet <line…> [\| xp line…]` / `hx pet none` / `hx pet reset` | replaces the body of the shown tab's `Pet:` widget (each part becomes one `§r §r…` body line; `none` = ` No pet selected`; `reset` = the preset's own) and re-sends the tab, e.g. `hx pet &7[Lvl 87] &6Griffin \| &e196,209&6/&e726.7k XP &6(27.0%)`. The override sticks across `hx tab` until reset |
| `hx title <title…> [\| subtitle…]` | title and subtitle |
| `hx menu <skyblock_menu\|equipment\|loadouts\|pets\|bazaar\|auction_house\|storage_1>` | chest GUI; clicks and drags cancelled, slot actions (`open:<menu>`, `swap:<menu>`, `close`) run. `swap` closes the menu and opens the next a tick later, as Hypixel switches storage pages (`storage_1` ↔ `storage_2` via the arrows at slots 53 / 45). `equipment` and `loadouts` carry the worn equipment in column 1, rows 1-4 (slots 10/19/28/37), as on Hypixel; `equipment` has an empty belt placeholder |
| `hx menu sacks` | "Sack of Sacks" as captured on Hypixel (owned sacks with real ids, `Capacity:` lore); clicking the Fishing, Enchanted Fishing, Enchanted Foraging, Flower or Bait sack opens it (title without "Large", items with real ids and `Stored: N/22.2k` lore, Go Back / Close / Pickup All). Counts per player in memory, seeded from the capture |
| `hx menu storage` | native Storage selector menu, nine Ender Chest pages and one Greater Backpack. `/storage`, `/enderchest n` and `/backpack n` navigate with real server menus; contents below the first navigation row support normal transfers. Empty the backpack, remove it with a right click, and install it in another unlocked slot to test cache identity invalidation. Unlike the older `storage_1` fixture, item transfers are not canceled. |
| `hx sacks add <ID> <n> [<ID> <n>…] [<s>s]` | changes sack counts (`n` may be negative), updates an open sack's slot like Hypixel's live slot updates, and sends `[Sacks] +N items, -M items. (Last Ns.)` with the per-item list only in the hover (`  +n Name (Sack)`); window default `30s` |
| `hx sacks say <ID> <n> [<ID> <n>…] [<s>s]` | sends only the `[Sacks]` summary, counts unchanged: the delayed report of a manual removal made earlier with `sacks set` |
| `hx sacks set <ID> <n>` / `hx sacks get <ID>` | set a count silently (no chat) / print it |
| `hx menu forge` / `hx menu hotm` | `The Forge` and `Heart of the Mountain` as captured on Hypixel (see "Forge"); `hx forge open` = `hx menu forge` |
| `hx forge qf <0-20>` | Quick Forge level (HotM item, every shown `Duration`, remaining time of running processes) |
| `hx forge slots <free\|three\|chamber>` | all idle / Refined Titanium ×2 + Mithril Plate busy / all 7 Gemstone Chamber (35m 5s at 30 %) |
| `hx forge lock <OUTPUT_ID…\|none\|default>` / `hx forge start <slot> <OUTPUT_ID>` / `hx forge status` | locked recipes / start a process / state |
| `hx give <skyblock id> [count]` | item from presets.json `items`; unknown ids get a generic COMMON item with `{id:"…"}` |
| `hx item` | prints the held item's `custom_data` SNBT (to check what the client sees) |
| `hx mob <name> <level> <hp> <max> [type]` | spawns an AI-less, silent mob (default `zombie`; any living type) 3 blocks in front of the player, plus an invisible marker armor stand above it with the nametag `§8[§7Lv51§8] §5<U+E078> §cEnderman§r §a6,000§f/§a6,000§c❤`, spawned right after so its id is the mob's + 1. `_` in the name = space; amounts take `6000`, `6,000`, `750k`, `1.5M`. A monster on a peaceful world switches it to easy |
| `hx mob damage <amount> [name]` | lowers the nametag health of every tracked mob (or those whose name contains `name`); a player's melee hit takes 10% of max. Real mob health never changes; at 0 mob and stand are removed |
| `hx mob <list\|clear>` | the tracked mobs with entity ids, or remove them all |
| `hx ticks <on\|off>` | Hypixel's per-tick `ClientboundPingPacket` (ids counting down), the server tick clock mods and the dev bridge count; off = only vanilla `set_time` every 20 ticks |
| `hx npc add <name> [real\|npc] [dx dz]` | a client-side fake player entity (tab info unlisted, entity id from 1900000) at an offset (default 3 0): `npc` = version-2 uuid like Hypixel's NPCs, `real` = version 4 |
| `hx npc walk <name> <radius>` / `npc remove <name\|all>` / `npc list` | walk it in a circle (20 position syncs/s, 0 stops) / remove / list |
| `hx md start <0-41>` | Mines of Divan Metal Detector: preset `divan` (Crystal Hollows location, `⏣ Mines of Divan` sidebar, no tab), four `Keeper of <X>` armor stands around a center 21 blocks above the player (each with a second unnamed stand 0.25 lower, like Hypixel's, plus 4 unnamed decoy stands), the `DWARVEN_METAL_DETECTOR` in hand; then, while it is held, every 0.5 s a BLOCKS `block.note_block.harp` at the player and 2 ticks later `TREASURE: X.Xm` (distance to the corner (x, y+1, z) of chest offset #n from the mod's table, rounded to 0.1) |
| `hx md start at <dx> <dy> <dz>` | same, with the chest at any offset from the center (one missing from the mod's list is never solved with a center: the list is trusted) |
| `hx md keepers <on\|off>` | name / un-name the keeper stands, now and for later starts (Hypixel names them only while you are near, so `off` exercises finding the center from the stand layout) |
| `hx md found` / `hx md stop` | the `You found … with your Metal Detector!` chat line (Emerald Hammer) / stop pinging and remove the keepers |
| `hx md loot <lapis\|golden\|diamond\|emerald\|gem\|gem2\|powder>` | one loot chat line: a Scavenged tool, `Flawed Sapphire Gemstone`, `Flawed Ruby Gemstone x2`, `+1,250 Gemstone Powder` (the powder format is a guess) |
| `hx md give\|take <lapis\|golden\|diamond\|emerald\|all>` | add / remove the nucleus tool items (drives the all-four-tools warning) |
| `hx corpse start <layout> [spot[:type]…]` | Glacite Mineshaft with the corpse locator's data (`corpse_spots.json` from the mod, copied into the plugin jar): location hop Dwarven Mines → Mineshaft (a new stay each start), sidebar `<date> <server> <layout>`, tab `Frozen Corpses:` rows, an armor stand per corpse on the chosen 0-based spots wearing its SkyBlock helmet id (`LAPIS_ARMOR_HELMET`, `ARMOR_OF_YOG_HELMET`, `MINERAL_HELMET`, `VANGUARD_HELMET`; type `lapis\|umber\|tungsten\|vanguard\|other`, `other` = an id no corpse uses; default spots 0 and 1, `FAIR_1` its Vanguard), flight, the player 14 blocks east of the first corpse; then the Organ Donor ding: BLOCKS harp at the player, pitch `floor(63·(2-(d/20)²))/63` for the nearest unlooted corpse, every 400 ms + 30 ms per block, silent beyond 20. A layout without data (`RUBY_C`) gets the sidebar only. Keys for the dimming: `hx give UMBER_KEY\|TUNGSTEN_KEY\|SKELETON_KEY` |
| `hx corpse tp <spot> [distance] [side]` / `hx corpse wall <spot> [on\|off]` / `hx corpse loot [spot]` | fly to `distance` (default 12) blocks east and `side` blocks south of a spot, facing it / a 5×5 stone wall 5 blocks east of the spot (`tp <spot> 9` = stand hidden, `tp <spot> 9 6` = in view; removed on `off`/`stop`) / `  <TYPE> CORPSE LOOT! ` for the nearest unlooted corpse (or that spot), its stand removed, its tab row LOOTED 16 ticks later |
| `hx corpse talisman <on\|off>` / `hx corpse status\|stop` | dings on or off (no Organ Donor; kept across starts) / list the corpses with distances / end |
| `hx preset dwarven` | Dwarven Mines (`mining_3`), `⏣ Royal Mines` sidebar, tab with the `Commissions:` widget as captured (`0%`, `47.5%`, `DONE`) plus a fifth row `Ice Walker Slayer: 30%` (no name-color keyword); `hx tab dwarven_nocomm` = the same tab without that widget (Commissions HUD menu fallback) |
| `hx menu commissions` / `hx menu commissions_2` | the `Commissions` menu (`generic_9x4`, captured lore): four commissions at slots 11/12/14/15 with #2 and #4 COMPLETED, or two at 12/14 with #2 COMPLETED |
| `hx coating on\|decoy\|off` | Divan's Powder Coating dust around you as captured: every 4 ticks 4 white + 4 gold `dust` packets (count 0, speed 1, override limiter + always show; the spread fields repeat the color: white 1/1/1, gold 1/0.6/0) within 0.4 blocks and 0.25-1.25 above the feet; `decoy` also bursts the same dust 4 blocks ahead, away from any player |
| `hx nucleus enter` | Crystal Nucleus: Crystal Hollows location, sidebar with the `<U+E067> Crystal Nucleus` sublocation, tab list (`Area: Crystal Hollows`, `Profile: Banana`); fresh run state |
| `hx nucleus run [speed] [normal\|rare\|claw]` | a whole run with the captured raw lines: per crystal (order Topaz > Amber > Amethyst > Jade > Sapphire, capture median legs × `speed`, default 0.15 ≈ 20 s) the sublocation, the NPC kit lines (Bal fight + retreat, Jungle Key, Goblin Egg, Apparatus, three keeper hand-ins + `You found all of the items!`), the 8-line found block `(n/5)` + re-punch spam, `Warping...` + enderman sound back to the nucleus; then 5 placed lines 1 s apart (explode 0.49) and the loot bundle (thunder) |
| `hx nucleus area <name…>` / `kit <crystal>` / `found <crystal>` | one step: sidebar sublocation (`Khazad-dûm`, …) / that crystal's NPC lines / its found block. Unknown crystal names pass through unvalidated |
| `hx nucleus place <crystal\|all>` / `loot <normal\|rare\|claw>` | placed lines (all found ones 1 s apart; the bundle follows the fifth) / the bundle alone (`rare`: Helix Fossil, Divan Fragment x2, Flawless Ruby, Jaderald, Lapidary I book; `claw`: Quick Claw) |
| `hx nucleus bal <spawn\|fight\|down\|locked>` | Bal: `The ground starts to shake…` / boss lines / death cry + retreat / `You haven't earned this Crystal!` |
| `hx nucleus spam` / `tab <on\|off>` / `stop` | `already obtained` + `blocks in the way` lines / a `Crystals:` tab widget mirroring the sim state (captured format: `Jade: ✖ Not Found` / `✖ Not Placed` / `✔ Placed`; like Hypixel's it follows found/placed lines 1.5 s late and keeps the finished run, all `✔ Placed`, for 4 s after the bundle) / cancel a scheduled run |
| `hx nucleus state <crystal> <missing\|found\|placed>` | change one crystal in the tab widget only, no chat (what a restart or a missed line leaves): exercises the checklist's tab restore |
| `hx terminal open <type> [letter\|COLOR] [seed]` | one of the six Floor 7 terminals (`startswith`, `select`, `panes`, `order`, `rubix`, `melody`) as a real container menu with Hypixel's title, size, items, sounds, chat and rules (see "F7 terminals"). Random valid board; the reply names the seed, and the same type, argument and seed give the same board again. `startswith` takes a letter (`L`), `select` a color (`LIME`, `GRAY`, `LIGHT_BLUE`…) |
| `hx terminal board <cid>` / `list` | opens a captured Appendix A board exactly (cid 13-38 of `docs/research/f7-native-terminals-2026-11-12.md`) / lists the types and the captured boards |
| `hx terminal lag <ms>` | the round trip of a click (pling, slot echo, error, close); default 150 ms (captured: 82-202 ms, median 164), `0` = same tick. Half delays the click's arrival (a melody lock is judged against the lane then, as on Hypixel), half every answer and animation step on the way back, so the client sees the melody lane that much late |
| `hx terminal freeze <ms>` | a server-wide lag spike as the client sees it: every packet to the player is held for that long, then sent at once in order (clicks are still taken and answered, the answers wait too) |
| `hx terminal counter <n> [total]` / `melody <pauseTicks>` / `melody aligned <on\|off>` / `world <on\|off>` / `status` / `close` | the completion counter (`n` done, total 7 by default) / melody's pause after a wrong lock (20) / the next melody lane's target is the locked column, so a lock on it right after the previous one skips it (off) / right-click on a terminal's command block in a loaded capture opens a random terminal (on) / settings and the open terminal / close it |
| `hx terminal selftest` | solves every type from what the open menu shows (5 boards each), tries every documented wrong input, compares every captured board to its own cells and checks the delay, all through real `InventoryClickEvent`s; prints `N checks, M failed` |
| `hx exp …` | the Experimentation Table: table, stakes, Chronomatron, Ultrasequencer, Superpairs, the end menus, the RNG Meter and fault knobs as raw container packets (see "Experimentation Table"); `hx exp selftest` prints `N checks, M failed` |
| `hx ss start [seed]` / `reset [seed]` | F7 Simon Says at its place in a loaded `f7-boss` capture (see "F7 Simon Says"): lamps out, no buttons, the start button at (110, 121, 91), round 1; `start` also applies the `dungeon` location, sidebar (F7), tab and action bar. Real right clicks on the start button and the grid drive it |
| `hx ss status` / `stop` | state, round, progress, the expected sequence and the last device events / stop the device's clock (blocks stay) |
| `hx ss selftest` | plays the device on a virtual clock (the press and step calls the right clicks make) and solves it only from the blocks: a normal run, the skip, four start presses, a wrong button, a slow second press; prints `N checks, M failed` |
| `hx lights start` / `reset` / `prefinish` / `status` / `stop` | F7 Lights at its place in a loaded `f7-boss` capture (see "F7 Lights"): 20 levers x 58-62, y 133-136, z 142 off, 20 lamps behind them dark; `start` also applies the `dungeon` location, sidebar (F7), tab and action bar. `prefinish`: the usual six levers on and every lamp lit but not complete, as a grid lit in Storm's phase (M7 party runs); touching a lever that is not on completes it. Real right clicks on the levers drive it |
| `hx lights selftest` | plays the device on a virtual clock (the six-lever solution in several orders, a lever flicked twice, a lever outside the six, every lever, a device finished early) and reads only the blocks; prints `N checks, M failed` |
| `hx arrows start [layout 1-9] [seed]` / `reset` / `status` / `stop` | F7 Arrow Align at its place in a loaded `f7-boss` capture (see "F7 Arrow Align"): one of the nine layouts as real item frames x -2, y 120-124, z 75-79 (arrows at random rotations, Start / End wool), sea lantern / blue terracotta behind each at x -3; `start` also applies the `dungeon` location, sidebar (F7), tab and action bar. Real right clicks on the frames drive it |
| `hx arrows selftest` | plays every layout on a virtual clock (solved from the frames and lanterns only), an over-rotation, a wool frame, a finished device; prints `N checks, M failed` |
| `hx goldor before` / `start` / `active` / `reset` / `status` | Goldor's phase start T (`GoldorStartSim`, research m7-boss §5 and round 2). `before`: `[BOSS] Storm: Pathetic Maxor, just like expected.`, the client reads "before Goldor". `start`: Storm's death as Hypixel plays it: his end line and the death strike (`entity.lightning_bolt.thunder` at volume 10000 at 45.5 170 42.5) now, the floor collapse (9 falling blocks at the Storm pad, sent straight to the viewer, removed 3 ticks later) at +59, T at +61, `Who dares` at +62. `active`: T and `Who dares` now; `reset`: unknown (the default, everything takes clicks). Before T: Arrow Align rotates but an aligned board does not complete, Lights lights but stays pre-finished, a terminal answers `This Terminal doesn't seem to be responsive at the moment.` 3 ticks later instead of opening (Hypixel's answer for a section not open; before T assumed), arrow lanterns stay dark. Hold-the-last-click: `dungeons.devices.arrowHoldLast`, `hx arrows start`, `hx goldor start`, solve all but the last arrow within 3 s: its completing click is held until T, the boss timers count `TERMINALS` down to it |
| `hx sharp start` / `reset` / `hit [cell]` / `status` / `stop` | F7 Sharpshooter at its place in a loaded `f7-boss` capture (see "F7 Sharpshooter"): the 3x3 target grid x 64/66/68, y 126-130, z 50 shot from the plate at 63 127 35; with weight on the plate one target at a time is an emerald block (7 or 8 distinct cells per run), a real arrow hit (or `hx sharp hit [r1c1..r3c3]`, default the current target) turns it back to blue terracotta and the next shows 0-10 ticks later; the last hit = pling + completed a device. `start` also applies the `dungeon` location, sidebar (F7), tab and action bar |
| `hx sharp selftest` | plays runs on a virtual clock (plate set by hand): 7 or 8 distinct targets, one emerald at a time, a wrong cell, the plate freed mid-run, a finished device that waits for a free plate; prints `N checks, M failed` |
| `hx party <none\|leader\|member> [names…]` | the answer to the client's next `hypixel:party_info` request. `leader A B`: you lead A and B; `member A B`: A leads, you and B are members. Offline names map to offline UUIDs |
| `hx rank <none\|vip\|vip+\|mvp\|mvp+\|mvp++\|youtuber\|staff\|admin> [prefix…]` | the answer to `hypixel:player_info` |
| `hx world list` | installed captures (`plugins/SoulTestKit/worlds/`) with their size, and the worlds loaded from them |
| `hx world load <name> [entry\|latest]` | rebuilds a dev bridge world capture in a dedicated void world `soulcap_<name>_<mode>` at the **original coordinates**: `entry` = every block as first seen, `latest` = with all later block changes, block entity updates and entity updates applied. Blocks are written straight into chunk sections (full states, no physics, no neighbour or fluid updates), block entities (player heads with their textures, signs, banners, chests, command blocks) are loaded from the captured NBT (Hypixel also sends skull data for positions that are air in the chunk, 378 of 412 in the F7 room; those are skipped and counted as "without a matching block", not as failures), armor stands / item frames / displays become inert copies (fresh UUID, invulnerable, no gravity, equipment slots locked). Time fixed at noon, weather clear, peaceful, no mob spawning, no random ticks; the chunks stay loaded (plugin tickets, up to 2048 columns) and are relit by Starlight. One load at a time; measured 3-6 ms of server time per chunk column (100 columns, 1.6 M blocks: 0.3-0.6 s, spread over ~10 ticks at 30 ms each, ~2.5 s wall with the world creation); the reply says what was built and how long it took |
| `hx world tp <name> [entry\|latest] [x y z]` | teleport the player into a loaded capture (default: its spawn; give the capture's coordinates), flying allowed |
| `hx world unload <name\|all> [entry\|latest]` | unload the void world and delete its folder (players are moved to the main world first) |
| `hx world compare <A> <B> x1 y1 z1 x2 y2 z2 [dx dy dz]` | block-by-block (full state), block entity (NBT) and entity (type, position, name, items) comparison of a region of A with the region of B shifted by (dx, dy, dz); prints `mismatches=N` for each and the first differences. A and B are Bukkit world names or `<capture>:<entry\|latest>` |
| `hx world selftest build [x y z]` | a recognizable structure at the player (or x y z) in two 24x24 clusters 160 blocks apart: stairs in every orientation and shape, slabs, levers, wire, fences, doors, trap doors, water, a textured player head, signs, banner, chests, a command block, a named armor stand with equipment, an item frame. Removes the stands/frames of an earlier build first. The self-test script `tools/devbridge/world-selftest` uses it |
| `hx world selftest noise x1 y1 z1 x2 y2 z2 [air%]` / `state x y z` | dense blob noise (4-block cells of 12 block types) straight into chunk sections, to measure capture cost and size at boss-room scale / the block data string at a position |
| `hx marks show <file\|latest> [filter…]` | marks from a `marks.ndjson` (dev bridge, `{kind:"mark", data:{…}}` lines) or a spots JSON array (`docs/research/f7-boss-spots-*.json`: `name`/`label`, `pos`, `yaw`, `pitch`, `phase`, `section`, `classes`) as labeled markers in your world: a text display `#n label` above the spot, a gold block at the position, and a thin red line from the eye along the facing. Filter words (all must match label, name, phase, section or classes) narrow it; `n>=85` keeps only marks numbered 85 and up in the source file. `latest` = the newest file in `plugins/SoulTestKit/marks/`; an absolute path works too |
| `hx marks add <file\|latest> [filter…]` | like `show` but keeps the markers already shown (numbers continue), to combine a spots JSON with a `marks.ndjson` |
| `hx marks list` / `tp <n>` / `clear` | the shown marks with their numbers / teleport to mark n (position and facing) / remove every marker entity |
| `hx storm start [F7\|M7] [flight]` | F7 Storm in a loaded `f7-boss` capture (see "F7 Storm"): dungeon location, sidebar and tab (`M7` only changes the sidebar floor to `(M7)`), then 40 ticks later the `Pathetic Maxor` line = P, the Storm entity, the pad poll, the pillars and the scripted opening. `flight` adds the out-of-range trip west |
| `hx storm stop` / `status` | clean up and restore the pillar blocks / P, current tick, poll k, each pillar's bottom y, direction, down polls and pending layers, Storm's position and mode |
| `hx storm park [x y z]` / `goto x y z` / `follow` / `away` / `back` | fly to the park spot (102.4, 183.0, 52.4) or a point at 9 b/s and hold / chase you (hover 5 blocks away, y172-176) / hide the entity from you (out of tracking range) / show it again (same entity id) |
| `hx storm phase high` / `giga [high]` / `chase` | high (spec §2.3): A = now, Storm climbs straight up 0.8 b/tick to +14.4 b (top at A+18) and holds; real `fireball` entities with `entity.wither.shoot` on the run-wide 40-tick grid (P+35 mod 40) on grid ticks in [A+18, A+162); L = A+162 he leaves the top (about 5 b diagonally down in 8 ticks), pauses, chases from A+190. giga: fly to (72.3, 179.9, 53.3), titles `6`..`1` 25 ticks apart, strike chat at `6`+145, chase at `6`+170; `giga high` instead drifts 6 b west at `6`+160 and starts the high phase at `6`+173; chase = `follow` |
| `hx storm crush` / `end` | force a crush now (its line per `line`, the boss bar step; no pillar destroyed) / send the end line |
| `hx storm line <on\|late N\|off>` | crush lines on time (default), N ticks late (M7: +26…48 in 3 of 16) or never (2 of 16): the helpers must read the crush from the boss bar and the pillar |
| `hx storm pillar <E\|WS\|WN> <down\|up> [layers]` | a batch (default 5 layers) on the next poll, as if you stood on the pad |
| `hx storm goldor [every N\|spot x y z [r]\|save]` / `goldor core` | the P2→P3 floor collapse now (9 falling blocks at the Storm pad, removed 3 ticks later), `Who dares trespass…` 5 ticks later, then `What do you think you are doing there!` on collapse+61+60k: every Nth check (default 3) or each check you stand within r (3) of the spot; `save` sends `Second Wind Activated! Your Spirit Mask saved your life!` in the same tick first / `The Core entrance is opening!` and stop |
| `hx section start` / `next` / `done` / `gate` / `core` / `status` / `selftest` | Goldor's sections (`LateBossSim`, see "Goldor sections and dragons"): `Who dares trespass…` with S1 open, the open section's next / full `<you> activated a terminal! (n/N)` (N = 7, 8, 7, 7), `The gate has been destroyed!`, `The Core entrance is opening!`; a section begins when the previous one is full and its gate is down |
| `hx dragon <red\|orange\|green\|blue\|purple>` / `stop` / `selftest` | an M7 P5 dragon spawn: 10 pulses 10 ticks apart (`flame` 20, `enchant` / `large_smoke` / `witch` 40 at the spawn x/z, y 19.0, forced), an AI-less `ender_dragon` at y 14 100 ticks after the first pulse, removed 60 ticks later |
| `hx hello` | re-send `hypixel:hello` (the client re-registers; the location follows) |
| `hx alpha <on\|off>` | Hypixel Alpha network: every sidebar footer reads `alpha.hypixel.net` instead of `www.hypixel.net` (kept across a rejoin; hello stays `PRODUCTION`, so only the sidebar tells) |
| `hx modapi` | hello state, subscriptions, current location, party, rank, last 15 mod API packets both ways |
| `hx textmode <inline\|components>` | see "Text" below |
| `hx list` | every name in every presets.json section |
| `hx reload` | reload presets.json without a restart |
| `hx as <player> <subcommand…>` | one player instead of all |

Templates in any text: `{player}`, `{date}` (MM/dd/yy, today), `{server}` (current location's
serverName), `{serverShort}` (`mini12AB` → `m12AB`, `mega5` → `M5`).

## F7 Storm

`hx storm` plays Storm's phase (P2) of Floor 7 in a loaded capture of the boss room, from
`docs/research/storm-crush-2026-11-12.md` (the mechanic, §1; Storm's schedule, §2) so that the Storm helpers can be
exercised end to end. Everything runs on the server clock: ticks of the player's world (`getGameTime()`, what
`set_time` carries), with P = the tick the `Pathetic Maxor` line is sent. Every chat line, title, sound, pad poll,
pillar layer and crush is also written to the server log as `[storm] tick=… P+… …` (`s log`), so a test can compare
the client's readout with the server truth.

```bash
tools/testserver/s cmd "hx world load f7-boss entry"                     # once; wait for "loaded f7-boss entry"
tools/testclient/c run "/hx world tp f7-boss entry 100.5 170 90.5"       # south of E, off the pads
tools/testclient/c run "/hx storm start"                                 # F7; M7 as the first argument
# purple pad: stand on x 111-117, z 91-97 at y 170 between P+581 and P+640 (the first piston sound), then step off
```

What runs after `start` (P+n, in server ticks):

| When | What |
|---|---|
| P-40 | `start` applies the `dungeon` location, sidebar (`dungeon_m7` for `M7`), tab and action bar; the pillars are scanned |
| P+0 | `§r§4[BOSS] Storm§r§c: §r§cPathetic Maxor, just like expected.`; Storm (an AI-less, silent, invulnerable wither named `Storm`, tracked every tick instead of every 3rd) at (103, 188, 53); a `§r§c§lStorm` boss bar at full |
| P+20k | the pad poll: the crush check first, then every pillar whose pad you stand on (glass square, y 170 ±0.5) or that `hx storm pillar` armed starts a batch of 5 layers in its direction, the first layer in the same tick |
| P+45 → 140 | hover, then drift to the park spot (102.4, 183.0, 52.4); with `flight` he flies west at 9 b/s to x 35 at P+215 and back to the park spot by P+385 (leaves your tracking range if you stand east of x ≈ 83) |
| P+424/449/474/499 | Giga 1 titles `§r§44`..`§r§41` (title text only, no subtitle) with `entity.ender_dragon.flap` (HOSTILE, volume 8, pitch 0.492) at you; the `The power of lightning…` line with the first |
| P+519 / P+524 / P+548 | `Storm's Giga Lightning hit you for 13,915.4 true damage.` / title cleared / `THUNDER LET ME BE YOUR CATALYST!` |
| P+648 → 659 | Storm leaves the park spot and flies south into E at 1 b/tick, stopping at (101.2, 179.2, 63.8) (inside the E footprint from about P+658) |
| crush | at a poll P+20k: a pillar that started a **downward** batch at poll k−1, k−2 or k−3 and whose 7×7 footprint (half-open) contains Storm's x/z crushes: `Ouch, that hurt!` (crush 1) / `Oof` (crush 2) in that tick (or late, or never: `hx storm line`), `entity.wither.hurt` (HOSTILE, volume 1) at Storm |
| crush + 8 / crush 1 + 14 | the boss bar steps to 0.95 (crush 1) / 0.40 (crush 2); after crush 1 it drops on to 0.45 |
| crush + 20 | `entity.zombie.break_wooden_door` (volume 3) and the pillar's blocks above y170 become air |
| crush 1 + 20 / + 30 | Storm starts chasing you / `⚠ Storm is enraged! ⚠` as chat and subtitle |
| crush 2 + 26 | `I should have known that I stood no chance.` |
| P+720 | with no crush yet, Storm starts chasing you |

Pillars (E purple, WS yellow, WN green; EN never moves and is not simulated) are the real blocks of the capture: the
7×7 column from y175 up (heights read from the center column: E 41 layers to y215, WS 47 to y221, WN 46 to y220) is
moved by one layer every 4 ticks, writing only the changed blocks. Bottom 175 at start, floor 169, top 189. Each layer
plays `block.piston.extend` (down) or `block.piston.contract` (up), BLOCKS, volume 10, pitch 0.4920635, at the pillar
corner (E 97/64, WS 43/64, WN 43/40, y = the layer starting to move). Reaching the floor or the top ends the batch,
reverses the direction (remembered) and plays `entity.iron_golem.hurt` (NEUTRAL, volume 10, pitch 0.4920635) at the corner
(y169 at the floor, y188 at the top). The first batch after a reversal starts 8 ticks after its poll; a batch already
started finishes when you step off; a poll that finds a pillar busy does nothing. `stop` writes the original blocks back.

**Measured (from the Hypixel recordings, spec §1, §2):** the poll grid and its +0/+1 start, 5 layers per batch, 4 ticks per
layer, floor/top, reversal at the floor and the top, the 3-poll crush window, the sounds, volumes, pitches and sources of
the pillar events, the chat lines and their ticks, Storm's park spot, the P+648 departure and his stop inside E, the Giga 1
title/strike/line ticks (P+424…519/548; M7 the same, docs/research/m7-boss-2026-11-13.md), the title spacing and strike offset of later Gigas (`6`+145), chase speed and
hover (about 9 b/s, 2-6 blocks, y172-176), the high phase's clock (ascent 0.8 b/tick for 14.4 b, leave-top at A+162, chase at about
A+190, `6`+173 after a Giga, fireball and `wither.shoot` in the same tick on a run-wide 40-tick grid with its phase fixed per run),
Goldor's 60-tick grid from the floor collapse (+61.4) with the death-zone line and the own save on it, the crush line
missing or late on M7, the boss bar plateaus (1.0, 0.45) and their steps after a crush.

**Assumed (code, not Hypixel fact):**
- M7 uses the F7 timings (measured equal on 8 M7 runs except later Gigas and the high phase, never reached); only the
  sidebar floor changes.
- The boss bar step at crush + 8 (M7: 3-18) and its color; the collapse as 9 falling blocks (Hypixel: about 2000 in one
  tick over x 90-112, z 30-52); collapse → `Who dares` at 5 ticks (M7: 3.7-13.6); the check at collapse + 61, not 61.4.
  That is M7's timing: on F7 solo `Who dares` comes 27.6-59 after the collapse and the checks follow the line (not simulated).
- The exact trigger area of a pad (the glass square, y 170 ±0.5; the border is not part of it) and the exact inside test of
  the crush (Storm's x/z against the half-open footprint at the poll tick; the doc's edge cases disagree within one block).
- Whether an upward batch arms a pillar: here it does not. The reversal batch counts as started at the poll that began it.
- Storm's route in the opening (straight lines between the measured points: the drift to the park spot, the flight west,
  the dash into E at 1 b/tick) and his chase (straight at you at 9 b/s, hover 5 blocks, y = your y + 3 clamped to 172-176);
  the P+720 fallback to chase; what he does after crush 2 (nothing).
- Which pillars exist at what height: moved as found in the capture; the pillar blocks `piston`/`moving_piston` that Hypixel
  places during a move are not reproduced (only the blocks of the column shift).
- Title timing (fade 0, stay 2 s, fade out 0.25 s), the sound source of the Giga flap (HOSTILE), the door-break position
  (pillar center), the `Giga Lightning` damage number, the `ENERGY HEED MY CALL!` line for later
  Gigas (`THUNDER…` for Giga 1), the iron-golem y at the top (188), the enrage delay (crush + 30).
- The fireball: a `LargeFireball` (`minecraft:fireball`) aimed at your chest with yield 0 and not incendiary, `entity.wither.shoot`
  as a HOSTILE sound packet at volume 1, removed after 80 ticks; the grid phase (P+35 mod 40, R3's); the 0.8 b/tick climb having no
  easing; the leave-top direction (toward you); the pause until A+190; the drift of `giga high` (6 b west to z 48 or 57 at 0.6
  b/tick). The `Storm's Lightning Fireball hit you…` chat (damage number assumed) is sent only when a fireball really hits you;
  Hypixel's own hit message was not checked. The opening flight's fireballs (P+77…399) are not simulated.
- The player takes no damage from any of it (the plugin cancels all player damage).
- Not simulated: the other dialogue lines, the other players, EN/red, lag, Storm sinking under a pillar's floor (y < 169).

`hx ticks off` gives only `set_time` (the clock the helpers read), `hx ticks on` adds the per-tick pings.
`hx terminal freeze <ms>` is the server stall.

## Goldor sections and dragons

`LateBossSim` plays what the section warning and the dragon countdown read, from `docs/research/m7-boss-2026-11-13.md`
(§2.4 and §3 of P3, P5 §3.1), on the world's game time; every line goes to the server log as `[late] tick=…`.

```bash
tools/testserver/s cmd "hx world load f7-boss entry"                     # once
tools/testclient/c run "/hx world tp f7-boss entry 69 125 136"           # the S2 area
tools/testclient/c run "/hx location dungeon"
tools/testclient/c run "/hx section start"                               # S1 open: the warning is up
tools/testclient/c run "/hx section done"; tools/testclient/c run "/hx section gate"   # S2 begins: it goes
tools/testclient/c run "/hx dragon red"                                  # countdown at (27, 21, 59) and above the crosshair
```

**Measured:** the counter and gate lines, the rule that a section begins with its predecessor full and its gate down,
the core line, the pulse packets (types, counts, x/z, y 19.0, 10 ticks apart), the dragon 100 ticks after the first
pulse at y 14. **Assumed:** the counter line's color codes and the player name in it, one terminal line for every
counter step (Hypixel mixes terminal, lever and device lines), the particle spread, the dragon's lifetime (60 ticks)
and that it has no AI; the test server runs at 20 TPS, so the dragon comes 100 game ticks after the first pulse where
Hypixel's 5 s are 98.7. `selftest` checks the section rule (gate before and after N/N) and the spawn schedule on a
virtual clock.

## F7 Simon Says

`hx ss` plays the section-1 device (`SimonSaysSim.java`) at its real blocks in a loaded `f7-boss` capture, from
`docs/research/f7-devices-2026-11-12.md` §3-§4, on the world's game time:

```bash
tools/testserver/s cmd "hx world load f7-boss entry"                    # once; wait for "loaded f7-boss entry"
tools/testclient/c run "/hx world tp f7-boss entry 108.5 120 93.5"      # in front of the device, facing it from the west
tools/testclient/c run "/hx ss start 7"                                 # seed 7; right-click the start button (110, 121, 91)
```

**Measured (recordings):** lamps `obsidian` / `sea_lantern` at x 111, buttons `stone_button[face=wall,facing=west]` at x 110
(y 120-123, z 92-95); first lamp 4 ticks after the start press, 8 ticks per lamp with the next one lit in the tick the last
goes out, the 16 buttons 10 ticks after the last lamp went out (never in front of a lamp still lit; that one 10 ticks after
it goes out); a correct press: `block.note_block.pling` (BLOCKS, 8, 4.047619) at the player; 4 ticks after a round's last
press the buttons go and the next round's first lamp lights in the same tick; rounds of 1, 2, 3, 4 lamps, the same sequence
growing; after the 4-lamp round a second pling, `<name> completed a device! (n/7)` and the buttons go. A press is answered
like Hypixel's: the cancelled interact re-sends the button and the block in front. The skip: a second start press while
the first display has not placed its buttons plays `entity.enderman.teleport` (HOSTILE, 8, 0); a third one starts a second
display of a new sequence one lamp longer on top of the first, and that sequence is the input (the first display's one lamp,
then the second's two; its buttons come while the third lamp is lit, 15 of them). A fourth plays the error sound and cuts
the second display after its lit lamp: the buttons come but every press does nothing until the next start press, which shows
a new two-lamp sequence.

**Assumed (code, not Hypixel fact):** a wrong button, and a start press while the device runs outside that pattern (for
example a second press after the first display's buttons, the "too slow" skip), play the error sound, remove the buttons and
reset to round 1 (only the "start while the buttons are up" case is recorded); presses before the input is open (in the skip,
between the first display's buttons and the 16th) do nothing; sequences are 4 distinct buttons; nothing happens once the
device is complete. Not simulated: other players, the `Inactive` / `Device` labels, M7.

## F7 Lights

`hx lights` plays the section-2 device (`LightsSim.java`) at its real blocks in a loaded `f7-boss` capture, from
`docs/research/f7-devices-2026-11-12.md` §10:

```bash
tools/testserver/s cmd "hx world load f7-boss entry"                    # once; wait for "loaded f7-boss entry"
tools/testserver/s cmd "execute in minecraft:soulcap_f7-boss_entry run tp SoulTester 60.5 132 138 0 -25"   # in front of the grid, facing south
tools/testclient/c run "/hx lights start"                               # right-click the levers (x 58-62, y 133-136, z 142)
```

**Measured (recordings):** `lever[face=wall,facing=north]` at z 142 (all off at the start), `redstone_lamp` behind each at
z 143 (all dark); a flip turns the lever on and plays `block.lever.click` (0.3, 0.587) at it, one tick later the lamp
behind it and the orthogonal neighbours' lamps are lit (never darkened); all 20 lit = `block.note_block.pling` (8, 4.047619)
at the player and `<name> completed a device! (n/7)`. The six levers (62,136) (58,136) (60,135) (60,134) (62,133) (58,133)
are the smallest set that lights everything and the only one any run used.

**Stated by the user, in no recording yet:** a lamp lit by two levers stays lit; any lever outside the six flips like the
others.

**M7 party runs (2026-11-12):** a grid lit in Storm's phase keeps its six levers on, every lamp lit, and is not complete;
a touch on a lever that was off completes it in Goldor's phase (`hx lights prefinish`).

**Assumed (code, not Hypixel fact):** a click on a lever that is already on turns it off and darkens the lamps no other lever
covers; the finished device ignores further clicks; nothing resets it.
To stand at an edge column, mind the walls: x 62.5 and x 58.5 at z 140.5 are inside blocks, use x 61.5 / 59.5 and
aim at the lever's own box (a wall lever sits at z 142.625-143, y +0.25..+0.75): a click above it hits the lamp.

## F7 Arrow Align

`hx arrows` plays the section-3 device (`ArrowAlignSim.java`) with real `ItemFrame` entities in a loaded `f7-boss` capture, from
`docs/research/f7-devices-2026-11-12.md` §11:

```bash
tools/testserver/s cmd "hx world load f7-boss entry"                    # once; wait for "loaded f7-boss entry"
tools/testserver/s cmd "execute in minecraft:soulcap_f7-boss_entry run tp SoulTester -0.4 120 77.5 90 25"   # where the user stood, facing west
tools/testclient/c run "/hx arrows start 7 5"                           # layout 7, seed 5; right-click the arrows
```

**Measured (recordings):** the frames face east in the blocks x -2 (cell `(124 - y) * 5 + (79 - z)`), the block behind each at x -3;
a click turns an arrow one step (+1, rotation 0-7) and plays `entity.item_frame.rotate_item`; the block behind an arrow is a sea
lantern exactly while it is at its target (blue terracotta otherwise; behind the wool frames a lantern, always); all arrows at
their targets = `block.note_block.pling` (8, 4.047619) at the player and `<name> completed a device! (n/7)`. The nine layouts are
Skyblocker's table (layouts 3, 6, 7, 8, 9 were in the recordings); arrows start at random rotations, never all aligned.

**Assumed (code, not Hypixel fact):** a click on a wool frame does nothing; the finished device ignores clicks. The capture
holds no frames at the device, so `start` removes any near it and spawns its own (as the loader's inert frames would
do); the click is a real right click on the entity, cancelled and handled by the sim (the frame never rotates by itself).

## F7 Sharpshooter

`hx sharp` plays the section-4 device (`SharpshooterSim.java`) on the capture's own blocks, from
`docs/research/f7-devices-2026-11-12.md` §12:

```bash
tools/testserver/s cmd "hx world load f7-boss entry"                    # once; wait for "loaded f7-boss entry"
tools/testclient/c run "/hx sharp start"                                # arms the device (a free plate is needed)
tools/testserver/s cmd "execute in minecraft:soulcap_f7-boss_entry run tp SoulTester 63.5 127 35.5 -11 0"   # onto the plate, facing the grid
tools/testserver/s cmd "hx sharp hit"                                   # or shoot with a bow; hits the current target
```

**Measured (recordings):** the grid x 64/66/68, y 126/128/130, z 50 is blue terracotta; with weight on the plate (63, 127, 35)
the first target shows 0-15 ticks later as an emerald block, one at a time; a hit turns it blue again, the next shows 0-10 ticks
later; 7 or 8 distinct cells per run; the last hit = `block.note_block.pling` (8, 4.047619) at the player and
`<name> completed a device! (n/7)`; the plate is free about 19-36 ticks after the completion.

**Assumed (code, not Hypixel fact):** stepping off the plate mid-run stops the run and clears the target; an arrow hit on any
other block does nothing; a finished device waits for a free plate. The plate is the capture's real pressure plate (vanilla
redstone: standing on it gives power 1); a hit is a real `ProjectileHitEvent` of an arrow on the target block, or `hx sharp hit`.

## F7 terminals

`hx terminal` plays the Floor 7 / Master 7 Goldor-phase terminals as real container menus, from the native, solver-free
capture in `docs/research/f7-native-terminals-2026-11-12.md` (and `f7-fight-mechanics-2026-11-11.md` for melody's
wrong lock). Rules live in `plugin/src/main/java/com/soulreturns/testkit/terminal/` (no Bukkit in there, unit-tested in
`TerminalModelTest`); `Terminals.java` turns them into menus, sounds, chat and the response delay.

| Type | Menu | Rules |
|---|---|---|
| `startswith` | `generic_9x5`, 3x7 at 10-16/19-25/28-34, `§rWhat starts with: 'L'?` | matching by display name; accepted: glint-override patch (an Enchanted Book or Bottle o' Enchanting already glows: accepted, no new patch); wrong item: enderman-teleport sound (volume 8, pitch 0), `Does not match!`, server close, a fresh board on reopen |
| `select` | `generic_9x6`, 4x7 at 10-16/19-25/28-34/37-43, `§rSelect all the LIME items!` | as starts-with; wrong color closes (user report, not captured) |
| `panes` | `generic_9x5`, 3x5 at 11-15/20-24/29-33 | red `Off` / lime `On`, a click toggles both ways, done when all 15 are On |
| `order` | `generic_9x4`, 2x5 at 11-15/20-24 | red panes `1`..`10` (count = number) turn lime in ascending order; wrong number: error sound + `Wrong number!`, menu stays open, nothing resets |
| `rubix` | `generic_9x5`, 3x3 at 12-14/21-23/30-32 | left, double click, drag phase and middle click: Red, Orange, Yellow, Green, Blue, Red; right click backwards; done when all nine match; no error response |
| `melody` | `generic_9x6`, target at 1-5 (mirrored 37-41), lanes 10-14/19-23/28-32, buttons 16/25/34 | lime pane steps every 10 ticks and bounces; lane 1 starts at column 1 moving right; lock while aligned: pling, the next lane is live at once (pane in the locked column, new target) but drawn only on the next step, the pane one on by then; a lock on it before that step is judged on the locked column (Hypixel's skip); a wrong lock stops the pane 20 ticks, the step loop runs on; third lock completes (one pling); wrong lock: error sound, animation pauses 20 ticks (user: about a second; the earlier run saw about 1.5 s), menu stays open |

Every accepted click answers after the round trip (`hx terminal lag`) with the success pling (`block.note_block.pling`,
BLOCKS, volume 8, pitch 4.047619, at the player) and the slot echo; the last action adds the completion pling (two
plings in all, melody one), a server close, the chat line `<name> activated a terminal! (n/7)` with a running
counter (wraps after the total) and the title/subtitle. Opening plays `block.lever.click` (0.5/1.0). Progress is never
kept: every open builds a new board, and the same seed just rebuilds the same one. Container clicks of an open
terminal are taken off the wire by a Netty tap in the player's pipeline (vanilla and Bukkit never see them), so
nothing answers before the delay: the client's own prediction (picked-up stack on the cursor, emptied slot) lasts
the whole delay, then the answer puts the cursor back to empty and re-sends the slot. That is also what lets a real
client produce the QUICK_CRAFT drag phase (click, then press again within the delay and drag a little); the
header-1 phase on a board slot counts as a forward action, as Hypixel accepts it.

Strings in the model are the doc's strings, i.e. the dev bridge's rendering, which prefixes every styled run with `§r`;
the server sends them without that first `§r`, so a client's bridge reads the doc's text back (`textmode inline`).

**Assumptions** (the capture does not show them; they are code, not Hypixel fact): the random generators (starts-with:
6-10 matches among the captured display names plus Golden Carrot, Name Tag, Nether Brick and Powered Minecart from the
earlier run, letters with 3+ names; select: 5-6 targets, four distractor colors, targets LIME/GRAY/CYAN unless given,
other titles such as `LIGHT GRAY` guessed; panes: 1-4 On; rubix any unsolved board; melody: target 1-5, lime starts
at column 1, 3, 4 for lanes 1-3, moving right); the select wrong-color error text and sound (same as starts-with); a
click on a selected item, a lime order pane, a filler or outside the grid does nothing; shift, number-key and drop
clicks count as forward; melody: locks during the pause, the lane switch or on an inactive row do nothing, a finished
lane turns white with a `Row Not Active` button, the lime pane stands still during the switch; the initial menu
contents arrive in the same tick as the open (Hypixel's snapshot follows about 0.2 s later); a spawn egg lacks the
`entity_data` component (its value was not captured). Not simulated: server freezes, other players, the world's
terminal label states.

`terminal-verify` (client-side check, needs the client; hold its lock, see the script header) opens all 20 captured
boards, compares what the client receives with Appendix A slot by slot, saves screenshots of the vanilla menus and runs
real clicks. In a loaded capture (`hx world load f7-boss entry`), right-clicking a terminal's command block or its head
opens a random terminal (`hx terminal world off` disables that), so they can be used in place.

## Experimentation Table

`hx exp` plays Hypixel's Experimentation Table (`ExperimentsSim.java`) as recorded on 2026-11-17
(`docs/research/experiments-2026-11-17.md` §1, §1.4, §1.5, §6, §7; raw dumps in `docs/research/raw/experiments-2026-11-17/`,
per-event fixtures in `src/test/resources/experiments/`). The rules live in `plugin/src/main/java/com/soulreturns/testkit/experiments/`
(no Bukkit; `ExperimentModelTest` compares the built table, stakes and Over menus with the fixtures, replays the recorded
Superpairs clicks of containers 17 and 36 to their recorded Rewards menus and runs the self-test). `plugin/src/main/resources/experiments/captured.json`
is cut from those fixtures: the three RNG Meter pages exactly as recorded (every slot's item JSON) and the final boards
of containers 17 and 36 (28 cards each, without the `Click any to proceed!` lines).

There is no server-side menu. Every screen is raw packets with Hypixel's own container ids (counting `%100+1`):
`block.lever.click` (BLOCKS 0.5/1.0), `open_screen`, one `set_slot` per menu slot and per player slot in the same tick,
the full `set_content` 4-6 ticks later. A click on a menu pages to the next one (a new `open_screen`, no close). In
game, sounds go out with the click's tick and slot changes one tick later, as a per-tick diff in slot order (unchanged
slots are never re-sent). Every click except a middle click (CLONE) that leaves the screen open is answered with a full
`set_content` 7-8 ticks later. Pitches are quantised to n/63 as Hypixel sends them. A Netty tap (`soul_exp_tap`) takes
the client's `container_click` / `container_close` for the sim's containers off the wire. Opening a real server
inventory or quitting drops the screen. Strings are the bridge's rendering minus the leading `§r` (as for the terminals).

| Command | What |
|---|---|
| `hx exp table [charges=0..3] [renewed=0..3] [addons=pending\|chrono\|done] [practice=on\|off]` | `Experimentation Table` (`generic_9x6`): status panes 20/21 and 23/24, Superpairs 22, Chronomatron 29, Ultrasequencer 33, Renew 31 (only at 0 charges; a click: chat `☕ You bought a bonus charge … (n/3)`, the table re-opens), RNG Meter 48 (page 1), Close 49 (server close), Experience Bottles 50. Knobs persist per player (default 1 charge, add-ons pending, practice on) |
| `hx exp stakes chrono\|ultra\|sp [status=<Tier>:<play\|low\|xp>]…` | the Stakes menus (`generic_9x5` / `9x5` / `9x6`); status sets a tier's last line to `Click to play!` / `Enchanting level too low!` / `Not enough experience!` (default: all playable, Superpairs Metaphysical too low). A playable tier starts its game, Go Back opens the table |
| `hx exp chrono <tier> [seed\|c6\|c22\|c26] [round=N]` | Chronomatron. `cN` replays recorded container N's colors; first note lit 16 ticks after the open, then every 18, lit 8 ticks; pling 1-2 ticks before its cells; the last note's cells and the clock (`Timer: N+2s`) in one tick. Input counts from the last pling's tick (recorded at tick 5625). An answer: echo pling now, lit next tick, unlit 8 later; the last answer: levelup (111/63) and the next round in the next tick. Wrong color: Over 3 ticks later, Esc: Over 4 ticks later |
| `hx exp ultra <tier> [seed\|c10\|c30] [round=N]` | Ultrasequencer. `cN` replays recorded container N's positions; numbers shown 41 + 6(N-1) ticks, then hidden with `Timer: N+3s` in one tick; input counts from the hide; each answer `entity.item.pickup` at the recorded rising pitch, the last one levelup; wrong number: Over next tick, Esc: 4 ticks later. Rewards pay the round whose input began (container 10: 5 numbers, 6 x 7k) |
| `hx exp sp <tier> [seed\|c17\|c36\|books\|instant\|plus3] [clicks=N] [rare=none\|meter\|header\|both\|selected]` | Superpairs. `c17`/`c36` = the recorded boards (start 23/24 clicks as recorded); `books` = container 36 with its 30k pair replaced by container 17's Thunderbolt VI (two different `Enchanted Book`s), `instant`/`plus3` = container 36 with Instant Find / +3 Clicks moved to slot 10. Pressure plate at 0.15 x remaining, burp on a mismatch (auto-hide 31 ticks after the click; a click on `?` only hides it, free; a click arriving in the hide's tick is a first click), levelup on a match, Instant Find (levelup + hats 107/113/119 over 6 ticks, next card and its partner revealed), +3 Clicks as second card (+3 -1, the first card stays open). At 0 clicks the next click shows the final board (`Click any to proceed!`, glint on matched cards and used powerups); a click after it opens `Superpairs Rewards` with `block.wood.break` (0.2/1.0); Esc anywhere: Rewards 4 ticks later. `rare=` deals the meter's Thunderbolt VII, an `ULTRA-RARE BOOK!` header book that is not on the meter, both, or the selected RNG drop (Growth VI: container 36's card) |
| `hx exp over chrono\|ultra <closed\|wrong\|time> <n> [tier=<Tier>]` / `rewards` | `Experiment Over` (`generic_9x3`, head 11, Decline 15) for chain / series n / container 17's `Superpairs Rewards`. Claim: server close, then the claim chat; Decline: server close, no chat, the stakes show their starting costs |
| `hx exp rng [1-3] [selected=<Name_With_Underscores>]` | `(n/3) Experimentation Table RNG` as recorded (Looting V selected, 0.0263 %); `selected=Growth_VI` makes a 5.3191 % book the selected entry (struck base odds, current odds, progress bar, `SELECTED`), Looting V unselected, and the meter head (4), Reset (51) and the table's head name it |
| `hx exp lag <ms>` / `freeze <ms>` / `split on\|off` | round trip (default 120 ms: half before the server judges a click, half on every answer) / every packet held that long, then sent in order / a tick's slot packets spread over two ticks |
| `hx exp resync` / `reopen` / `lateclick on\|off` | one extra `set_content` now / the open screen again with the same title and a new container id, every slot, the game goes on / Chronomatron and Ultrasequencer also count a click arriving one tick before the input window |
| `hx exp status` / `close` / `selftest` | screen, game state, knobs and the last 10 judged clicks / server close / the scenario suite |

`hx exp selftest` (also `s cmd "hx exp selftest"`, with or without a player) plays, through real click packets on a
session of its own (lag 0, nothing sent to the client): the open burst, table navigation, the RNG pages, Renew,
Superpairs stakes, the selected-drop page, Chronomatron timing and lateclick, c26 to its max-clicks line and Esc, c22's
wrong color and Decline, c6, Ultrasequencer c30 (7 rounds, wrong number) and c10 (Esc in round 6), Superpairs c36 (match,
mismatch, dismiss, Instant Find, +3 Clicks, out of clicks, final board, Rewards, claim), c17's last click, the rare
cards and the faults (resync, reopen, split, lag). Clicks are decided only from what the client was sent. Expect
`experiments selftest: N checks, 0 failed` in `s log` (failures follow as warnings). To click the menus in the test
client, use the slot pixel formula in `.agents/skills/test-client/SKILL.md` ("F7 terminals", `slot_xy` in `terminal-verify`).

**Assumptions** (not captured; code, not Hypixel fact):

- Uncaptured tiers: Chronomatron High to Transcendent as one row of colors (`model.js` GUESS_ROW); Ultrasequencer Supreme /
  Transcendent as 7x3 / 7x4 blocks at columns 1-7; Superpairs Beginner 7x2 and High / Grand 5x4 grids; Superpairs stakes
  XP except Transcendent's 300,000; Ultrasequencer retry costs equal Chronomatron's for the same tier name.
- Chronomatron: Pink's pitch (44/63); timer expiry (`Time ran out!`).
- Ultrasequencer: number items and pane colors from 9 on; pickup pitches beyond the recorded ones (+7.75/63 per answer); timer
  expiry (`Time ran out`).
- Both: the max-clicks chat line as plain text (only seen behind a SkyHanni prefix); right, shift, number-key and drop
  clicks ignored, a drag phase on a cell counts like a click; `over ultra` paying n + 1 rounds (as every capture that ended during an input).
- Superpairs: the rare cards (meter: diamond_block x7 `Enchanted Book` / `Rare Book!` / `Thunderbolt VII`; header:
  enchanted_book x7 `ULTRA-RARE BOOK!` / `Smite VII`; any selected drop but Growth VI built from its meter entry);
  +3 Clicks as a first card and Instant Find as a second card; clicks on face-up cards ignored; the final board takes
  a click 30 ticks after it shows (recorded: ignored at 3/21/24, taken at 36); timer expiry shows the final board.
- Menus: the full resync after menu clicks; a middle click on a button counts; Go Back on the RNG pages opens the table;
  Experience Bottles does nothing; locked tiers and add-ons on cooldown do nothing; practice (right-click on an add-on on
  cooldown opens its stakes; its claims change nothing); Renew's third price (150 levels, 450 bits); the RNG pages for a
  selected drop other than Looting V (odds = base x (1 + 2 x progress), fitted to Looting V's 0.0263 → 0.0293 % at 5.8 %).
- The 120 ms default round trip. Not simulated: XP, levels and bits, Metaphysical Serum, other players.

## Spirit Leap

`hx leap` (`LeapSim.java`) plays Hypixel's Spirit Leap menu as recorded in the M7 party runs
(`docs/research/spirit-leap-2026-11-13.md` §1), with that party as fake teammates:

- `leap setup [class] [boss|map]`: dungeon location, the M7 sidebar and a party tab with the five players at Hypixel's
  tab rows 1/5/9/13/17 (you Archer unless a class is given; whoever had that class becomes Archer): go9o Healer,
  Maaxnn Tank, PuppyGirlSunny Berserk, Waccoons Mage. `boss` flies you to 92 122 95 (F7 boss-room numbers in the
  overworld) and adds go9o as an NPC at SS (108.5, 121, 102.7) and Maaxnn in S1, so they are "seen"; send call-outs
  with `hx chat &9Party &8> &b[MVP&c+&b] PuppyGirlSunny&f: at i4!`. `map` flies you to -120 100 -120 (inside the
  dungeon room grid), adds the four as NPCs 40 blocks W/E/N/S and sends a synthetic M7 dungeon map (id 1024, 6x6
  rooms of 16 px from pixel 6, entrance/blood/puzzle colored) with you as the frame marker and them as blue markers in
  tab order, ten times over 10 s: Soul's map then shows their markers (leap menu map highlight; set `dungeons.mapEnabled`).
- `leap open [dead=a,b] [only=a,b] [order=a,b,c,d] [heads=ms] [stagger=ms]`: `Spirit Leap`, `generic_9x4`, nameless
  black panes, heads named `§b<name>` with the lore `§eClick to teleport!` or `§cThis player is currently dead!`, in
  run 1's order (11 Waccoons, 12 PuppyGirlSunny, 14 go9o, 15 Maaxnn) unless `order` is given. `heads` opens it with
  the head slots empty and fills them that late (slot updates), `stagger` spaces the heads apart (whole ticks): the
  lag shapes for the leap menu's per-tile readiness (Hypixel itself sends every slot in the open's frame). A click on
  an alive head answers 3 ticks later with the server close and `You have teleported to <name>!`; a click on a dead
  head or a pane gets no answer.
- `leap clicks`: every click on the menu since the last call (slot, click type, the head), also in `s log`: the
  server-side count for "one press, one click".
- `leap selftest`: builds the recorded and guessed menus and checks slots, names, lore and the answer per slot;
  expect `N checks, 0 failed`.

**Assumptions:** the slots for 2 and 3 teammates (12/14 and 11/13/15), no answer at all to a click on a dead head,
no teleport (the NPCs do not move you), offline skins, the `map` layout (rooms only, no doors; markers do not follow the NPCs).

## Forge

`hx menu forge` (also `hx forge open`) plays the Dwarven Mines forge (`ForgeSim.java`) as captured on 2026-11-16
(`docs/research/forge-2026-11-16.md` §1, §4a, §4b; raw `docs/research/raw/forge-2026-11-16/`). Every menu is a real
`generic_9x6` container; items carry `custom_data` `{id:"…"}` (pets `PET` + `petInfo`); legacy strings are the bridge's
rendering minus the leading `§r`. The data (123 recipes, 208 items, 9 category icons, Quick Forge costs) is
`plugin/src/main/resources/forge-data.json`, generated by `python3 tools/testserver/gen-forge-data.py` from our own captures
(committed; rerun only when the captures change).

```bash
tools/testserver/s cmd "hx forge slots three"       # busy slots count down once per second
tools/testserver/s cmd "hx forge qf 0"              # Quick Forge not unlocked: every Duration is the base time
tools/testserver/s cmd "hx forge lock REFINED_DIAMOND GLACITE_CHISEL"   # locked recipes: gray_dye + red 'Requires …'
tools/testclient/c run "/hx menu hotm"              # Quick Forge at slot 24: maxed, levelling or not unlocked
tools/testserver/s cmd "hx forge start 2 TITANIUM_DRILL_4"
```

| What | Encoding |
|---|---|
| `The Forge` | forge slots #1-#7 at 10-16: idle `§aSlot #N` furnace with the captured lore; busy = the output item (id, name, head texture) with `§7Currently making: §5<Name>` / `§7Time Remaining: §a<d h m s>`. 48 `Claim All` (`Nothing to claim!`), 49 Close, 50 `Forge Recipes` (`Recipe Book Unlocked: §a100§6%`, 25-segment bar, `123/123`; unlocked count follows `hx forge lock`). Border black panes, interior rows red |
| `Select Process (Slot #N)` | category icons at 11, 13, 15, 20, 22, 24, 29, 31, 33; 48 Go Back, 49 Close, 50 Search |
| category page | title = category, `(1/2) Gear` when paged; the 7x4 interior 10-16/19-25/28-34/37-43; 45 `Previous Page` / 53 `Next Page` (`§ePage N`), 48 Go Back. A recipe = the output item with its captured lore + `§eItems Required`, ingredient lines (name color, ` §r§8xN`, gemstone glyph, `§6300,000 Coins`), `§7Duration: §b<shown>`, `§eClick to select!` |
| `Confirm Process` | inputs as real items (id, stack = amount) at 10, 11, 19, 20, 28, 29, 37, 38 (the captured two-column layout; slots 12/21/30/39 are panes), coins = player head `§650,000,000 Coins` without id, 13 `§aForge Slot #N`, 16 the result, 31 red `§aConfirm` / `§cYou don't have the required items!`, 48 Go Back (`To <category>`) |
| locked recipe | `gray_dye`, red name, no id, one lore line `§cRequires …`; a click answers with the same red chat line. `lock default` = the 29 Kiwi had locked; ids not in that list get the assumed text `Requires Heart of the Mountain Tier 8` |
| `Heart of the Mountain` | `Quick Forge` at 24: level 20 diamond `§aQuick Forge` (`by §a30%§7.`, ENABLED); 1-19 emerald `§eQuick Forge` (`Level N§8/20`, current `by X%.`, `=====[ UPGRADE ]=====` block with the next level's percent and Mithril Powder cost); level 0 coal `§cQuick Forge` `Requires Pickobulus`. 45 Close, 48 Go Back, other slots panes |

Durations: base seconds (Kiwi's menus, or the Blueberry value ÷ 0.7 for recipes Kiwi never saw) × (1 − Quick Forge %), percent =
10 + 0.5 × level for 1-19, 30 at 20, rounded to whole seconds, zero units left out (`3h 9m`, `21s`). A running process stores only its
start: Time Remaining = shown duration at the **current** level − elapsed, so `hx forge qf` changes running processes too
(Hypixel behaves that way per the user, not captured). `hx forge slots three` puts the processes 0.5 h, 4 h 20 m and 8 h 40 m in;
`chamber` shows 35m 5s at 30 % whatever the level is.

**Not captured, so not simulated:** a finished/claimable process (a process that runs out stays at `1s`), a locked forge slot (all 7
are always unlocked), `Claim All` with something to claim, the Forge Recipes book (a click does nothing), the Search sign and its
`Results` page (a click prints a red chat line), a lime `Confirm` (nothing is ever started by the menu; use `hx forge start`),
clicks on busy slots, the enable/disable toggle and upgrade clicks of Quick Forge.

**Assumed:** 65 ingredient items the captures only show by name (mostly gemstones, Fine/Flawless, crystals, eggs, fossils) are a plain
`paper` in their rarity color with a rarity-only lore, ids from NotEnoughUpdates-REPO's name hints (the rest guessed from the name);
the item model (`hypixel_skyblock:…`) is not sent, `tooltip_style` is; filler panes are named ` ` like the other menus; non-integral
durations round to the nearest second; the stack size of an input is its amount even above 99 (up to 512).

## World captures

The dev bridge (`tools/devbridge/listen world start|status|stop|info|entities|merge`, skill `live-research`) copies the
chunks a client has loaded, as first seen, plus the later changes, into `<gameDir>/soul-dev/worlds/*.soulworld.gz`.
This server rebuilds one at the same world coordinates, so spots marked on Hypixel can be visited and checked:

```bash
tools/testserver/s world install ~/.local/share/PrismLauncher/instances/<instance>/minecraft/soul-dev/worlds/latest.soulworld.gz f7-boss
tools/testserver/s marks install docs/research/f7-boss-spots-2026-11-11.json     # or a marks.ndjson
tools/testserver/s cmd "hx world load f7-boss entry"                              # async: wait for the "loaded f7-boss entry" line (s log)
tools/testclient/c run "/hx world tp f7-boss entry 73 225 70"                      # inside the room, at the capture's own coordinates
tools/testclient/c run "/hx marks show latest tank"                               # labeled markers for the matching marks
```

Where they live: `tools/testserver/run/plugins/SoulTestKit/worlds/<name>.soulworld.gz` (and `…/marks/`), inside
`run/`, which is gitignored. Captures are Hypixel's map data: they are never committed (`.gitignore` has
`*.soulworld.gz`); the only tracked one is the tiny synthetic fixture `tools/devbridge/fixtures/selftest.soulworld.gz`
made with `hx world selftest build`. `s world list|rm NAME` manage the installed ones. Loaded worlds are real
folders `run/world/dimensions/minecraft/soulcap_<name>_<mode>`; `unload` (or the next `load` of the same name)
deletes them. The plugin reads format version 1 (`src/devbridge/kotlin/com/soulreturns/devbridge/world/WorldFormat.kt`)
and reports a newer one instead of guessing. `tools/devbridge/world-selftest` runs the whole pipeline end to end.

## Presets (data, no code)

Everything shown lives in `plugin/src/main/resources/presets.json`, in sections keyed by name:

- `presets`: `{location, sidebar, tab, actionbar, chat}` names, applied together by `hx preset`.
  `defaultPreset` is applied on a player's first join, `resetLocation` by `hx reset`. A rejoin
  re-sends the preset's `chat` too, as Hypixel prints its join lines on every server join.
- `locations`: `{serverName, serverType, lobbyName, mode, map}`; omitted fields are sent as absent.
  `serverType` is a `GameType` or `LobbyType` name (`SKYBLOCK`, `MAIN`).
- `sidebars`: `{title, lines[]}`, top line first, at most 32 lines.
- `tabs`: `{header, footer, columns[4][≤20]}`; missing rows are blank.
- `actionbars`: name → text.
- `chats`: name → system chat lines sent in order. `join` is Hypixel's join block, verbatim from a
  packet log: `§r§aYou are playing on profile: §eBanana` (co-op adds `§b (Co-op)`) then
  `§r§8Profile ID: <uuid>`.
- `items`: SkyBlock id → `{material, name, lore[], unique, glint, color, skullTexture, customData{},
  petInfo{}, id}`. `custom_data` becomes `{id, …customData, uuid, timestamp}` (`uuid`/`timestamp` only
  with `unique: true`), `petInfo` is stored as a JSON string with `{uuid}` replaced by the item uuid,
  `id` overrides the key (all pets are `PET`). JSON numbers become int (long if too big), decimals
  double, booleans byte, arrays lists.
- `menus`: `{title, rows, filler, slots: {"<slot>": "<item id>" | {inline item, action}}}`. Inline
  items without `id` get no `custom_data`, like Hypixel's GUI buttons.

To add one permanently, edit `presets.json` and `s restart`. To try one without rebuilding, put a file
with the same shape at `run/plugins/SoulTestKit/presets.json` (its entries replace or add to the
bundled ones by name) and run `hx reload`.

## What it imitates

- **Mod API** (`net.hypixel:mod-api` 1.0.2, bundled in SoulMod via Fabric `hypixel-mod-api`): on join
  the server pushes `hypixel:hello` (environment PRODUCTION); the client answers `hypixel:register`
  with its event subscriptions, which the plugin tracks; `hyevent:location` v1 goes out right after
  that and on every location change, only to subscribers. `hypixel:party_info` (v1 and v2),
  `hypixel:player_info` v1 and `hypixel:ping` v1 requests are answered; any other version gets the
  `INVALID_PACKET_VERSION` error response. Payloads are sent unconditionally as raw custom payloads,
  like Hypixel (no `minecraft:register` precondition). The byte format is pinned by
  `plugin/src/test/.../HypixelWireTest.java`: the plugin's encoder is decoded by Hypixel's own
  classes (and byte-compared with what they write), serverbound packets written by the library are
  parsed by the plugin.
- **Sidebar**: objective `SBScoreboard`, one team per line, invisible colour-code score holders, text
  in the team prefix and (past 16 characters) the suffix, continuing with the prefix's last codes;
  red score numbers stay visible as on Hypixel.
- **Tab list**: 80 fake entries `!A-a` … `!D-t` sorted into 4 columns of 20, widget text in display
  names (`Area:`, `Server:`, `Profile:`, `Skills:`, `Pet:`, `Players (n)`), header/footer; real
  players are unlisted for the viewer while a tab preset shows.
- **Action bar**: `system_chat` with `overlay=true` every second (not the `set_action_bar_text` packet).
- **Text**: legacy codes stay inside literal strings (`textmode inline`, the default), which is what a
  modern client gets from Hypixel and why mods strip codes from `getString()`. `textmode components`
  converts them into styled components instead, to test a parser against both. Item names and lore are
  never italic.
- **Items/menus**: SkyBlock names, rarity lines (`§d§l§kU§r §d§lMYTHIC DUNGEON SWORD §d§l§kU`),
  `custom_data` with `id`/`uuid`/`timestamp`/`petInfo`, attribute and dye tooltips hidden.
- **Mob nametags** (`hx mob`): the separate armor stand, its id right after the mob's, Hypixel's number
  format (commas below 100k, then `394.9k`, `5.2M`, `210M`) and current-health color (green above half,
  yellow above a quarter, red below), as in a packet log.

## What it does not imitate

- Real SkyBlock content: worlds, NPCs, real mob behavior, the dragon fight, dungeons, mining, Bazaar/AH
  logic, click handling beyond menu navigation. The sample texts are plausible, not captured from Hypixel.
- Any Hypixel chat command or server reply (`/party list`, `/warp`, `/pv`, `/locraw` …): SoulMod sends
  `/party list` on join and gets "Unknown or incomplete command".
- The `None` location Hypixel reports for ~2 s after a transfer, real server switches (the world
  stays the same; only the location packet and the server name change), rate limits.
- Party info is only sent on request (as on Hypixel); `hx party` does not push it.
- Resource-pack glyphs: the location marker is `⏣`, not Hypixel's private-use `U+E067`
  (a preset may use `\uE067`); tab entries show no skin heads (offline server).
- The server brand is `Paper`, the address `127.0.0.1`: anything SoulMod gates on `hypixel.net`
  (e.g. the realtime stream without `dev.realtimeAlwaysOn`) stays off.
