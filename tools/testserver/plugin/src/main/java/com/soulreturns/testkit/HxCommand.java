package com.soulreturns.testkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.soulreturns.testkit.modapi.Rank;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * /hx: plays Hypixel for the sender, for every online player when run from the console, or for one
 * named player with {@code hx as <player> <subcommand…>}.
 */
public final class HxCommand implements TabExecutor {
    private static final String[][] HELP = {
        {"preset <name>", "location + sidebar + tab + actionbar of a preset"},
        {"reset", "clear sidebar, tab, actionbar; location back to the lobby"},
        {"location <name>", "named location from presets.json, sent as hyevent:location"},
        {"location <mode> [map] [serverName]", "SkyBlock location by mode; '_' in map = space"},
        {"sidebar <name|clear>", "SkyBlock sidebar (teams, prefix/suffix lines)"},
        {"tab <name|clear>", "SkyBlock tab list widgets (80 fake entries) + header/footer"},
        {"actionbar <name|clear|text…>", "system chat overlay, repeated every second"},
        {"chat <text…>", "system chat line (legacy codes, & works)"},
        {"pet <line…> [| xp line…]|none|reset", "replace the tab Pet widget's body lines (one body line each side of |; none = ' No pet selected'), re-sends the tab"},
        {"title <title…> [| subtitle…]", "title + subtitle"},
        {"menu <name|sacks|forge|hotm>", "open a SkyBlock chest GUI (clicks cancelled); sacks = Sack of Sacks, click a sack to open it; forge = The Forge, hotm = Heart of the Mountain with Quick Forge (see forge)"},
        {"sacks add <ID> <n> [<ID> <n>…] [<s>s]", "change sack counts (n may be negative), update an open sack, send the [Sacks] summary (window default 30s)"},
        {"sacks say <ID> <n> [<ID> <n>…] [<s>s]", "send only the [Sacks] summary (counts unchanged): the delayed report of a manual removal made with sacks set"},
        {"sacks set <ID> <n>", "set a sack count silently (no chat), as with the summary disabled"},
        {"sacks get <ID>", "print a sack count"},
        {"forge open|hotm", "The Forge (7 slots, recipes of the 9 categories, Confirm Process) / Heart of the Mountain with the Quick Forge perk at slot 24, as captured; real menus, clicks navigate"},
        {"forge qf <0-20>", "Quick Forge level (0 = not unlocked; 10 + 0.5 x level %, 30 % at 20): changes the HotM item, every shown Duration and the remaining time of running processes"},
        {"forge slots <free|three|chamber>", "forge slot state: all idle / Refined Titanium x2 + Mithril Plate with different remaining times / all 7 Gemstone Chamber at 35m 5s (30 %); counting down once per second"},
        {"forge lock <OUTPUT_ID…|none|default>", "locked recipes (gray_dye + red 'Requires …', clicking echoes the line as chat): default = the 29 Kiwi had locked, none = all unlocked"},
        {"forge start <slot 1-7> <OUTPUT_ID>|status", "start a recipe in a slot with its shown duration (Confirm never starts anything) / Quick Forge, locks and slots"},
        {"give <skyblock id> [count]", "SkyBlock item with custom_data"},
        {"item", "print custom_data of the held item"},
        {"mob <name> <level> <hp> <max> [type]", "SkyBlock mob with a [Lv#] Name hp/max❤ armor-stand nametag; '_' in name = space"},
        {"mob damage <amount> [name]", "lower tracked mobs' nametag health (a melee hit takes 10%); 0 removes the mob"},
        {"mob <list|clear>", "list or remove the tracked mobs"},
        {"ticks <on|off>", "Hypixel's per-tick ClientboundPingPacket (the server tick clock); off = only vanilla set_time"},
        {"npc add <name> [real|npc] [dx dz]", "fake player entity (v2 NPC uuid, or v4 with real) at an offset; unlisted in tab"},
        {"npc walk <name> <radius>|remove <name|all>|list", "walk a fake player in a circle (0 stops) / remove / list"},
        {"md start <0-41>", "Metal Detector: Mines of Divan location, 4 keepers around a center, detector item; harp + TREASURE bar every 0.5 s while held (chest = offset #n)"},
        {"md start at <dx> <dy> <dz>", "Metal Detector: same, chest at any offset from the center (e.g. one missing from the mod's list)"},
        {"md found", "Metal Detector: the 'You found … with your Metal Detector!' chat line"},
        {"md stop", "Metal Detector: stop pinging, remove the keepers"},
        {"md keepers <on|off>", "Metal Detector: name or un-name the keeper stands (Hypixel names them only while you are near)"},
        {"md loot <lapis|golden|diamond|emerald|gem|gem2|powder>", "Metal Detector: one loot chat line (tool, gemstone, gemstone x2, Gemstone Powder)"},
        {"md give|take <lapis|golden|diamond|emerald|all>", "Metal Detector: add or remove nucleus tool items (all-tools warning)"},
        {"corpse start <layout> [spot[:type]…]", "Glacite Mineshaft: sidebar <layout> (AMBE_1, FAIR_1, …), tab Frozen Corpses rows, corpse stands wearing their SkyBlock helmet on the layout's spots (0-based; type lapis|umber|tungsten|vanguard|other; default spots 0 and 1, FAIR_1 its Vanguard), flight, Organ Donor ding (real formula and cadence)"},
        {"corpse tp <spot> [distance] [side] [up]|wall <spot> [on|off]|bury <spot> [on|off]", "Glacite Mineshaft: fly to <distance> blocks (default 12) east, <side> south and <up> (default 1) above a spot, facing it / a 5x5 stone wall 5 blocks east of a spot (tp <spot> 9 = hidden, tp <spot> 9 6 = in view) / a closed stone shell around a spot"},
        {"corpse cage <spot> [on|off]|place <spot> <fx> <fy> <fz>", "Glacite Mineshaft: connected iron bars around a spot, 3 high / move the spot's stand to block + fractions (-0.01 = a hair outside the block)"},
        {"corpse loot [spot]|talisman <on|off>|status|stop", "Glacite Mineshaft: '<TYPE> CORPSE LOOT!' for the nearest unlooted corpse (or that spot), its tab row flips 0.8 s later / dings on or off (no Organ Donor; kept across starts) / list corpses / end"},
        {"coating on|decoy|off", "Divan's Powder Coating dust around you as captured (4 white + 4 gold dust packets every 4 ticks, sent to everyone in the world); decoy = the same burst also 4 blocks ahead, away from any player"},
        {"nucleus enter", "Crystal Nucleus: Crystal Hollows location, sidebar sublocation, tab list; fresh run"},
        {"nucleus run [speed] [normal|rare|claw]", "Crystal Nucleus: a whole run with the captured lines (legs x speed, default 0.15), placing, loot bundle"},
        {"nucleus area <name…>", "Crystal Nucleus: sidebar sublocation (Khazad-dûm, Jungle Temple, …)"},
        {"nucleus kit|found <crystal>", "Crystal Nucleus: the NPC/Bal lines for that crystal, or its 8-line found block (n/5)"},
        {"nucleus place <crystal|all>", "Crystal Nucleus: placed lines (all: 1 s apart, bundle after the fifth)"},
        {"nucleus loot <normal|rare|claw>", "Crystal Nucleus: the loot bundle (rare: Divan Fragment x2, Jaderald, …; claw: Quick Claw)"},
        {"nucleus bal <spawn|fight|down|locked>", "Crystal Nucleus: Bal spawn / boss lines / retreat / 'You haven't earned this Crystal!'"},
        {"nucleus spam|tab <on|off>|stop", "Crystal Nucleus: re-punch + etherwarp spam; tab Crystals: widget; cancel a run"},
        {"nucleus state <crystal> <missing|found|placed>", "Crystal Nucleus: change one crystal in the tab widget only (no chat), like a restart or a missed line"},
        {"terminal open <type> [letter|COLOR] [seed]", "F7 terminal as a real menu (startswith, select, panes, order, rubix, melody) with Hypixel's title, layout, sounds and rules; the seed repeats a board"},
        {"terminal board <cid>|list", "open a captured Appendix A board exactly (cid 13..38) / list types and boards"},
        {"terminal lag <ms>|counter <n> [total]|melody <pause>|aligned <on|off>|world <on|off>|status|close", "response delay (default 150, 0 = same tick), completion counter, melody pause after a wrong lock / next lane target in the locked column (skip test), right-click on terminal command blocks in loaded captures"},
        {"terminal selftest", "solves every type and checks each documented wrong input, the captured boards and the delay through the real click path"},
        {"exp table [charges=0..3] [renewed=0..3] [addons=pending|chrono|done] [practice=on|off]", "Experimentation Table as Hypixel sends it (raw container packets, Hypixel's container ids): clicks page to Stakes / RNG Meter, Renew at 0 charges, Close"},
        {"exp stakes chrono|ultra|sp [status=<Tier>:<play|low|xp>]", "a Stakes menu; status = Click to play! / Enchanting level too low! / Not enough experience!; a playable tier starts its game"},
        {"exp chrono|ultra <tier> [seed|c6|c22|c26|c10|c30] [round=N]", "Chronomatron / Ultrasequencer directly; cN replays recorded container N's sequence"},
        {"exp sp <tier> [seed|c17|c36|books|instant|plus3] [clicks=N] [rare=none|meter|header|both|selected]", "Superpairs directly; c17/c36 = the recorded boards; rare cards: the meter's Thunderbolt VII, an ULTRA-RARE header book, or the selected RNG drop"},
        {"exp over chrono|ultra <closed|wrong|time> <n> [tier=<Tier>]|rewards", "Experiment Over (claim: close + chat, decline: close) / container 17's Superpairs Rewards"},
        {"exp rng [1-3] [selected=<Name_With_Underscores>]", "the RNG Meter pages as recorded; selected= makes another drop (e.g. Growth_VI, 5.3191 %) the selected one"},
        {"exp lag <ms>|freeze <ms>|split on|off|resync|reopen|lateclick on|off|status|close", "faults: round trip (default 120), a lag spike, slot packets over two ticks, an extra set_content, the open screen re-sent, a click one tick early counted"},
        {"exp selftest", "plays the recorded games through the real click path and checks menus, timings, sounds and end screens (server log: N checks, M failed)"},
        {"ss start [seed]|reset [seed]|status|stop", "F7 Simon Says at its place in a loaded f7-boss capture (dungeon location + F7 sidebar on start): lamps, button grid, start button, the three-press skip and the fourth press as recorded; presses are real right clicks"},
        {"lights start|reset|prefinish|status|stop", "F7 Lights at its place in a loaded f7-boss capture (dungeon location + F7 sidebar on start): 20 levers x 58-62, y 133-136, z 142, a lamp behind each; a right click turns a lever on and lights its lamp and the neighbours' lamps one tick later (a second click turns it off); all 20 lit = pling + completed a device. prefinish: the usual six levers on and every lamp lit but not complete (lit in Storm's phase); touching a lever that is not on completes it"},
        {"arrows start [layout 1-9] [seed]|reset|status|stop", "F7 Arrow Align at its place in a loaded f7-boss capture (dungeon location + F7 sidebar on start): one of the nine layouts as item frames x -2, y 120-124, z 75-79 (arrows at random rotations, Start / End wool), sea lantern / blue terracotta behind each at x -3; a right click turns an arrow one step (+1, the lantern is lit exactly at the target); all at target = pling + completed a device"},
        {"goldor before|start|active|reset|status", "Goldor's phase start T, when terminals, levers and devices take clicks: before = a Storm line, devices rotate and light but never complete, a terminal answers 'This Terminal doesn't seem to be responsive at the moment.' (arrow lanterns dark); start = Storm's death as Hypixel plays it: his end line + the death strike (thunder vol 10000 over his arena) now, the floor collapse (falling blocks at the Storm pad) at +59, T at +61 (lanterns on), Who dares at +62; active = T and Who dares now; reset = unknown (everything takes clicks, the default)"},
        {"sharp start|reset|hit [cell]|status|stop", "F7 Sharpshooter at its place in a loaded f7-boss capture (dungeon location + F7 sidebar on start): the 3x3 target grid x 64/66/68, y 126-130, z 50 shot from the plate at 63 127 35; with weight on the plate one target at a time turns into an emerald block (7 or 8 distinct cells per run), an arrow hit (or hx sharp hit) turns it back to blue terracotta and the next one shows 0-10 ticks later; the last hit = pling + completed a device"},
        {"sharp selftest", "plays runs on a virtual clock (plate set by hand): 7 or 8 distinct targets, one emerald at a time, wrong cell ignored, plate freed mid-run, finished device waits for a free plate"},
        {"leap setup [class] [boss|map]", "Spirit Leap: dungeon location, M7 sidebar and the party tab (go9o Healer, Maaxnn Tank, PuppyGirlSunny Berserk, Waccoons Mage at tab rows 1/5/9/13/17, you Archer unless given); boss = fly at 92 122 95 (F7 room numbers) with go9o as an NPC at SS and Maaxnn in S1; map = fly at -120 100 -120 (inside the dungeon room grid) with the four teammates as NPCs 40 blocks W/E/N/S"},
        {"leap open [dead=a,b] [only=a,b] [order=a,b,c,d] [heads=ms] [stagger=ms]|clicks|selftest", "Spirit Leap menu as recorded (generic_9x4, black panes, heads at 11/12/14/15, lore Click to teleport! / This player is currently dead!); heads=/stagger= leave the head slots empty and fill them that late (lag); an alive head answers 3 ticks later with close + 'You have teleported to X!', a dead one with nothing; clicks prints every click since the last call"},
        {"arrows selftest", "plays every layout on a virtual clock (solved from the frames and lanterns only), an aligned board before T (not complete) and a full turn after it, an over-rotation, a wool frame, a finished device"},
        {"lights selftest", "plays the device on a virtual clock (the six-lever solution in several orders, a lever flicked twice, an extra lever, every lever, a device finished early, lit before T and touched after it), reading only the blocks"},
        {"ss selftest", "plays the device on a virtual clock (normal run, skip, four presses, wrong button, slow second press), reading only the blocks"},
        {"storm start [F7|M7] [flight]", "F7 Storm in a loaded f7-boss capture: dungeon location + sidebar (M7 only changes the floor text), then 40 ticks later the Pathetic Maxor line = P; pad poll P+20k, moving pillars with piston sounds, crush rule, Giga 1 titles, Storm's scripted flight into E at P+648 (flight = also the out-of-range trip west)"},
        {"storm stop|status", "stop (clean up, restore the pillars) / P, tick, poll, pillars, Storm"},
        {"storm park [x y z]|goto x y z|follow|away|back", "Storm flies to the park spot / a point and holds, chases you, is hidden from you (out of tracking range) / shown again"},
        {"storm phase high|giga [high]|chase", "high: climb 14.4 b at 0.8 b/tick from A, leave the top at A+162, chase from A+190, real fireballs on the P+35 mod 40 grid; giga: fly to (72.3, 179.9, 53.3), titles 6..1, strike at 6+145 (giga high: drift west, climb at 6+173); chase"},
        {"storm crush|end", "force a crush now (its line per storm line, the boss bar step; no pillar destroyed) / send the end line"},
        {"storm line <on|late N|off>", "crush lines on time, N ticks late or never (the helpers then read the crush from the boss bar and the pillar)"},
        {"storm pillar <E|WS|WN> <down|up> [layers]", "a batch on the next poll, as if the pad was used (default 5 layers)"},
        {"storm goldor [core|every N|spot x y z [r]|save]", "the floor collapse (falling blocks at the Storm pad), Who dares 5 ticks later, then the death-zone line on collapse+61+60k every Nth check (default 3) or while you stand within r of a spot (save: a Spirit Mask save first); core = The Core entrance is opening!"},
        {"section start|next|done|gate|core|status|selftest", "Goldor's sections: Who dares (S1 open), the open section's next / full (n/N) counter line, The gate has been destroyed!, The Core entrance is opening!; a section begins when the previous one is full and its gate is down (the section warning)"},
        {"dragon <red|orange|green|blue|purple>|stop|selftest", "M7 P5 dragon spawn: 10 pulses 10 ticks apart (flame 20, enchant, large_smoke, witch at the spawn x/z, y 19), an AI-less ender_dragon at y 14 100 ticks after the first (the dragon countdown)"},
        {"party <none|leader|member> [names…]", "answer for hypixel:party_info requests"},
        {"rank <none|vip|vip+|mvp|mvp+|mvp++|youtuber|staff|admin> [prefix…]", "answer for hypixel:player_info"},
        {"hello", "re-send hypixel:hello (client re-registers, location follows)"},
        {"alpha <on|off>", "Hypixel Alpha network: every sidebar footer reads alpha.hypixel.net instead of www.hypixel.net (kept across rejoin; hello stays PRODUCTION, so only the sidebar tells)"},
        {"modapi", "mod API state: hello, subscriptions, last packets"},
        {"textmode <inline|components>", "legacy codes inside literal text (Hypixel) or as styles"},
        {"list", "names of all presets, locations, sidebars, tabs, actionbars, menus, items"},
        {"reload", "reload presets.json (jar + plugins/SoulTestKit/presets.json)"},
        {"world list|load <name> [entry|latest]|unload <name|all>|tp <name> [entry|latest] [x y z]", "dev bridge world captures rebuilt in void worlds at the original coordinates (entry = as first seen, latest = after the logged changes)"},
        {"world compare <A> <B> x1 y1 z1 x2 y2 z2 [dx dy dz]", "block, block entity and entity comparison of two worlds (<capture>:<entry|latest> names a loaded capture)"},
        {"world selftest build [x y z]", "recognizable structure (states, head, sign, stand, frame) in two clusters 160 blocks apart, for the capture end-to-end check"},
        {"marks show|add <file|latest> [filter…]|list|tp <n>|clear", "marks.ndjson or spots JSON as labeled markers (text, position block, facing line) in your world"},
        {"as <player> <subcommand…>", "run a subcommand for one player"},
    };
    private static final List<String> SUBCOMMANDS = Stream.concat(Stream.of("help"),
        Arrays.stream(HELP).map(h -> h[0].split(" ")[0])).distinct().toList();

    private final SoulTestKit plugin;
    private final CoatingSim coating;

    public HxCommand(SoulTestKit plugin) {
        this.plugin = plugin;
        this.coating = new CoatingSim(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            run(sender, args, null);
        } catch (Problem p) {
            sender.sendMessage("§c" + p.getMessage());
        }
        return true;
    }

    private static final class Problem extends RuntimeException {
        Problem(String message) {
            super(message, null, false, false);
        }
    }

    private void run(CommandSender sender, String[] args, Player only) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        switch (sub) {
            case "help" -> {
                sender.sendMessage("§6SoulTestKit /hx §7(fake Hypixel SkyBlock)");
                for (String[] h : HELP) sender.sendMessage("§e/hx " + h[0] + " §7- " + h[1]);
                return;
            }
            case "list" -> {
                for (String section : Presets.SECTIONS) {
                    sender.sendMessage("§e" + section + ": §f" + String.join(", ", plugin.presets().keys(section)));
                }
                return;
            }
            case "reload" -> {
                String error = plugin.reloadPresets();
                sender.sendMessage(error == null ? "§apresets reloaded" : "§cpresets.json: " + error);
                return;
            }
            case "textmode" -> {
                need(rest, 1, "textmode <inline|components>");
                Txt.mode = switch (rest[0].toLowerCase(Locale.ROOT)) {
                    case "inline" -> Txt.Mode.INLINE;
                    case "components" -> Txt.Mode.COMPONENTS;
                    default -> throw new Problem("textmode <inline|components>");
                };
                sender.sendMessage("§atext mode " + Txt.mode + " (re-apply presets to see it)");
                return;
            }
            case "world", "marks" -> {
                Player who = only != null ? only : sender instanceof Player p ? p : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                try {
                    if (sub.equals("world")) plugin.worlds().command(sender, who, rest);
                    else plugin.marks().command(sender, who, rest);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
                return;
            }
            case "section", "dragon" -> {
                Player who = only != null ? only : sender instanceof Player p ? p : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                if (who == null) throw new Problem("no player online");
                try {
                    List<String> out = sub.equals("section") ? plugin.late().section(who, rest) : plugin.late().dragon(who, rest);
                    for (String line : out) sender.sendMessage("§a" + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
                return;
            }
            case "storm" -> {
                Player who = only != null ? only : sender instanceof Player p ? p : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                if (who == null) throw new Problem("no player online");
                try {
                    for (String line : plugin.storm().command(who, rest)) sender.sendMessage("§a" + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
                return;
            }
            case "exp" -> {
                if (rest.length == 0 || !rest[0].equalsIgnoreCase("selftest")) break;
                Player who = only != null ? only : sender instanceof Player p ? p : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                sender.sendMessage("§a" + plugin.exp().selftest(who));
                return;
            }
            case "as" -> {
                need(rest, 2, "as <player> <subcommand…>");
                Player target = Bukkit.getPlayerExact(rest[0]);
                if (target == null) throw new Problem(rest[0] + " is not online");
                run(sender, Arrays.copyOfRange(rest, 1, rest.length), target);
                return;
            }
            default -> {
            }
        }
        if (!SUBCOMMANDS.contains(sub)) throw new Problem("unknown subcommand " + sub + " (hx help)");
        List<Player> targets = only != null ? List.of(only)
            : sender instanceof Player p ? List.of(p) : List.copyOf(Bukkit.getOnlinePlayers());
        if (targets.isEmpty()) throw new Problem("no player online");
        for (Player target : targets) runFor(sender, target, sub, rest);
    }

    private void runFor(CommandSender sender, Player target, String sub, String[] rest) {
        PlayerState state = plugin.state(target);
        String who = target.getName() + ": ";
        switch (sub) {
            case "preset" -> {
                need(rest, 1, "preset <" + String.join("|", plugin.presets().keys("presets")) + ">");
                String problem = plugin.applyPreset(target, rest[0]);
                if (problem != null) throw new Problem(problem);
                sender.sendMessage("§a" + who + "preset " + rest[0] + locationNote(target));
            }
            case "reset" -> {
                plugin.reset(target);
                sender.sendMessage("§a" + who + "reset" + locationNote(target));
            }
            case "location" -> {
                need(rest, 1, "location <name|mode> [map] [serverName]");
                if (rest.length == 1 && plugin.applyLocation(target, rest[0])) {
                    sender.sendMessage("§a" + who + "location " + rest[0] + locationNote(target));
                    return;
                }
                JsonObject location = new JsonObject();
                location.addProperty("serverName", rest.length > 2 ? rest[2] : "mini" + randomId());
                location.addProperty("serverType", "SKYBLOCK");
                location.addProperty("mode", rest[0]);
                if (rest.length > 1) location.addProperty("map", rest[1].replace('_', ' '));
                plugin.setLocation(target, location);
                sender.sendMessage("§a" + who + "location " + location + locationNote(target));
            }
            case "sidebar" -> {
                need(rest, 1, "sidebar <" + String.join("|", plugin.presets().keys("sidebars")) + "|clear>");
                if (rest[0].equals("clear")) plugin.clearSidebar(target);
                else if (!plugin.applySidebar(target, rest[0])) throw new Problem("unknown sidebar " + rest[0]);
                sender.sendMessage("§a" + who + "sidebar " + rest[0]);
            }
            case "tab" -> {
                need(rest, 1, "tab <" + String.join("|", plugin.presets().keys("tabs")) + "|clear>");
                if (rest[0].equals("clear")) plugin.clearTab(target);
                else if (!plugin.applyTab(target, rest[0])) throw new Problem("unknown tab " + rest[0]);
                sender.sendMessage("§a" + who + "tab " + rest[0]);
            }
            case "actionbar" -> {
                need(rest, 1, "actionbar <name|clear|text…>");
                if (rest.length == 1 && rest[0].equals("clear")) plugin.clearActionbar(target);
                else plugin.applyActionbar(target, String.join(" ", rest));
                sender.sendMessage("§a" + who + "actionbar " + String.join(" ", rest));
            }
            case "chat" -> {
                need(rest, 1, "chat <text…>");
                Nms.sendSystemChat(target, Txt.of(plugin.fill(target, String.join(" ", rest))), false);
                sender.sendMessage("§a" + who + "chat sent");
            }
            case "pet" -> {
                need(rest, 1, "pet <line…> [| xp line…]|none|reset");
                String joined = String.join(" ", rest);
                if (joined.equals("reset")) state.petLines = null;
                else if (joined.equals("none")) state.petLines = List.of("§r §r§7No pet selected");
                else state.petLines = Arrays.stream(joined.split("\\s*\\|\\s*")).map(l -> "§r §r" + plugin.fill(target, l)).toList();
                if (state.tab == null || !plugin.applyTab(target, state.tab)) throw new Problem("no tab shown (hx tab <name> first)");
                sender.sendMessage("§a" + who + "pet widget " + joined);
            }
            case "title" -> {
                need(rest, 1, "title <title…> [| subtitle…]");
                String[] parts = String.join(" ", rest).split("\\s*\\|\\s*", 2);
                target.showTitle(Title.title(Txt.of(parts[0]), Txt.of(parts.length > 1 ? parts[1] : "")));
                sender.sendMessage("§a" + who + "title sent");
            }
            case "menu" -> {
                need(rest, 1, "menu <" + String.join("|", plugin.presets().keys("menus")) + "|sacks|forge|hotm>");
                if (rest[0].equalsIgnoreCase("sacks")) plugin.sacks().openOverview(target);
                else if (!plugin.menus().open(target, rest[0])) throw new Problem("unknown menu " + rest[0]);
                sender.sendMessage("§a" + who + "opened " + rest[0]);
            }
            case "sacks" -> sacks(sender, target, who, rest);
            case "forge" -> {
                try {
                    for (String line : plugin.forge().command(target, rest, sender)) sender.sendMessage("§a" + who + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "give" -> {
                need(rest, 1, "give <skyblock id> [count]");
                String id = rest[0].toUpperCase(Locale.ROOT);
                int count = rest.length > 1 ? parseInt(rest[1]) : 1;
                JsonElement def = plugin.presets().get("items", id);
                ItemStack item = Items.build(id, def != null ? def.getAsJsonObject() : Items.fallback(id), count);
                target.getInventory().addItem(item);
                sender.sendMessage("§a" + who + "gave " + count + "x " + id + (def == null ? " (generic, no definition)" : ""));
            }
            case "item" -> {
                ItemStack held = target.getInventory().getItemInMainHand();
                sender.sendMessage(who + held.getType() + " custom_data " + Nms.customDataSnbt(held));
            }
            case "mob" -> mob(sender, target, who, rest);
            case "ticks" -> {
                need(rest, 1, "ticks <on|off>");
                boolean on = rest[0].equalsIgnoreCase("on");
                plugin.devBridge().ticks(target, on);
                sender.sendMessage("§a" + who + "per-tick ping " + (on ? "on" : "off"));
            }
            case "npc" -> {
                String usage = "npc add <name> [real|npc] [dx dz] | walk <name> <radius> | remove <name|all> | list";
                need(rest, 1, usage);
                switch (rest[0].toLowerCase(Locale.ROOT)) {
                    case "add" -> {
                        need(rest, 2, usage);
                        boolean real = rest.length > 2 && rest[2].equalsIgnoreCase("real");
                        double dx = rest.length > 4 ? Double.parseDouble(rest[3]) : 3, dz = rest.length > 4 ? Double.parseDouble(rest[4]) : 0;
                        sender.sendMessage("§a" + who + "npc " + plugin.devBridge().add(target, rest[1], real, dx, dz));
                    }
                    case "walk" -> {
                        need(rest, 3, usage);
                        sender.sendMessage("§a" + who + (plugin.devBridge().walk(rest[1], Double.parseDouble(rest[2])) ? "npc " + rest[1] + " walking r=" + rest[2] : "no npc " + rest[1]));
                    }
                    case "remove" -> {
                        need(rest, 2, usage);
                        if (rest[1].equalsIgnoreCase("all")) sender.sendMessage("§a" + who + plugin.devBridge().removeAll() + " npcs removed");
                        else sender.sendMessage("§a" + who + (plugin.devBridge().remove(rest[1]) ? "npc removed" : "no npc " + rest[1]));
                    }
                    case "list" -> plugin.devBridge().list().forEach(l -> sender.sendMessage("§7" + l));
                    default -> throw new Problem(usage);
                }
            }
            case "md" -> {
                need(rest, 1, "md <start <0-41>|found|stop>");
                switch (rest[0].toLowerCase(Locale.ROOT)) {
                    case "start" -> {
                        need(rest, 2, "md start <offset index 0-41> | md start at <dx> <dy> <dz>");
                        try {
                            boolean at = rest[1].equalsIgnoreCase("at");
                            if (at) need(rest, 5, "md start at <dx> <dy> <dz>");
                            String result = at
                                ? plugin.metalDetector().startAt(target, signedInt(rest[2]), signedInt(rest[3]), signedInt(rest[4]))
                                : plugin.metalDetector().start(target, signedInt(rest[1]));
                            sender.sendMessage("§a" + who + result);
                        } catch (IllegalArgumentException e) {
                            throw new Problem(e.getMessage());
                        }
                    }
                    case "found" -> {
                        plugin.metalDetector().found(target);
                        sender.sendMessage("§a" + who + "metal detector found line sent");
                    }
                    case "stop" -> sender.sendMessage("§a" + who + (plugin.metalDetector().stop(target) ? "metal detector stopped" : "no metal detector running"));
                    case "keepers" -> {
                        need(rest, 2, "md keepers <on|off>");
                        plugin.metalDetector().keepers(target, rest[1].equalsIgnoreCase("on"));
                        sender.sendMessage("§a" + who + "keeper names " + rest[1]);
                    }
                    case "loot", "give", "take" -> {
                        need(rest, 2, "md " + rest[0] + " <kind>");
                        String kind = rest[1].toLowerCase(Locale.ROOT);
                        try {
                            if (rest[0].equalsIgnoreCase("loot")) plugin.metalDetector().loot(target, kind);
                            else plugin.metalDetector().tools(target, kind, rest[0].equalsIgnoreCase("give"));
                        } catch (IllegalArgumentException e) {
                            throw new Problem(e.getMessage());
                        }
                        sender.sendMessage("§a" + who + "md " + rest[0] + " " + kind);
                    }
                    default -> throw new Problem("md <start <0-41>|found|stop|loot <kind>|give <tool|all>|take <tool|all>>");
                }
            }
            case "nucleus" -> nucleus(sender, target, who, rest);
            case "coating" -> {
                need(rest, 1, "coating <on|decoy|off>");
                String mode = rest[0].toLowerCase(Locale.ROOT);
                if (mode.equals("off")) sender.sendMessage("§a" + who + (coating.stop(target) ? "coating dust stopped" : "no coating dust running"));
                else if (mode.equals("on") || mode.equals("decoy")) {
                    coating.start(target, mode.equals("decoy"));
                    sender.sendMessage("§a" + who + "coating dust " + mode);
                } else throw new Problem("coating <on|decoy|off>");
            }
            case "terminal" -> {
                try {
                    sender.sendMessage("§a" + who + plugin.terminals().command(target, rest, sender));
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "exp" -> {
                try {
                    for (String line : plugin.exp().command(target, rest)) sender.sendMessage("§a" + who + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "corpse" -> {
                try {
                    for (String line : plugin.corpses().command(target, rest)) sender.sendMessage("§a" + who + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "ss" -> {
                try {
                    for (String line : plugin.simonSays().command(target, rest, sender)) sender.sendMessage("§a" + who + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "arrows" -> {
                try {
                    for (String line : plugin.arrows().command(target, rest, sender)) sender.sendMessage("§a" + who + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "goldor" -> {
                try {
                    for (String line : plugin.goldor().command(target, rest)) sender.sendMessage("§a" + who + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "sharp" -> {
                try {
                    for (String line : plugin.sharp().command(target, rest, sender)) sender.sendMessage("§a" + who + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "leap" -> {
                need(rest, 1, "leap setup [class] [boss|map] | open [dead=a,b] [only=a,b] [order=…] [heads=ms] [stagger=ms] | clicks | selftest");
                switch (rest[0].toLowerCase(Locale.ROOT)) {
                    case "setup" -> {
                        String cls = rest.length > 1 && !rest[1].equalsIgnoreCase("boss") && !rest[1].equalsIgnoreCase("map") ? rest[1] : null;
                        String where = Arrays.stream(rest).skip(1).filter(a -> a.equalsIgnoreCase("boss") || a.equalsIgnoreCase("map")).findFirst().orElse("").toLowerCase(Locale.ROOT);
                        sender.sendMessage("§a" + who + plugin.leap().setup(target, cls, where));
                    }
                    case "open" -> sender.sendMessage("§a" + who + plugin.leap().open(target, Arrays.copyOfRange(rest, 1, rest.length)));
                    case "clicks" -> {
                        List<String> clicks = plugin.leap().takeClicks();
                        sender.sendMessage("§a" + who + clicks.size() + " leap click(s)");
                        clicks.forEach(c -> sender.sendMessage("§7" + c));
                        plugin.getLogger().info("leap clicks: " + clicks.size() + (clicks.isEmpty() ? "" : " " + clicks));
                    }
                    case "selftest" -> plugin.leap().selftest(sender);
                    default -> throw new Problem("leap setup [class] [boss|map] | open [dead=a,b] [only=a,b] [order=…] [heads=ms] [stagger=ms] | clicks | selftest");
                }
            }
            case "lights" -> {
                try {
                    for (String line : plugin.lights().command(target, rest, sender)) sender.sendMessage("§a" + who + line);
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
            case "party" -> {
                need(rest, 1, "party <none|leader|member> [names…]");
                String mode = rest[0].toLowerCase(Locale.ROOT);
                if (!List.of("none", "leader", "member").contains(mode)) throw new Problem("party <none|leader|member> [names…]");
                if (mode.equals("member") && rest.length < 2) throw new Problem("party member needs the leader's name first");
                state.partyMode = mode;
                state.partyNames = mode.equals("none") ? new ArrayList<>() : new ArrayList<>(Arrays.asList(rest).subList(1, rest.length));
                sender.sendMessage("§a" + who + "party " + mode + " " + state.partyNames + " (answered on the next hypixel:party_info request)");
            }
            case "rank" -> {
                need(rest, 1, "rank <" + rankKeys() + "> [prefix…]");
                String key = rest[0].toLowerCase(Locale.ROOT);
                if (!rankKeys().contains(key)) throw new Problem("rank <" + rankKeys() + "> [prefix…]");
                state.rank = key;
                state.rankPrefix = rest.length > 1 ? Txt.legacy(String.join(" ", Arrays.asList(rest).subList(1, rest.length))) : null;
                sender.sendMessage("§a" + who + "rank " + key + (state.rankPrefix == null ? "" : " prefix " + state.rankPrefix));
            }
            case "hello" -> {
                plugin.modApi().sendHello(target);
                sender.sendMessage("§a" + who + "hypixel:hello sent");
            }
            case "alpha" -> {
                need(rest, 1, "alpha <on|off>");
                String mode = rest[0].toLowerCase(Locale.ROOT);
                if (!mode.equals("on") && !mode.equals("off")) throw new Problem("alpha <on|off>");
                state.alpha = mode.equals("on");
                if (state.sidebar != null) plugin.applySidebar(target, state.sidebar);
                sender.sendMessage("§a" + who + "sidebar footer " + (state.alpha ? "alpha.hypixel.net" : "www.hypixel.net"));
            }
            case "modapi" -> {
                sender.sendMessage("§e" + who + "hello " + (state.helloSent ? "sent" : "not sent")
                    + ", subscriptions " + state.subscriptions + ", location " + state.location
                    + ", party " + state.partyMode + " " + state.partyNames + ", rank " + state.rank);
                for (String line : state.modApiLog()) sender.sendMessage("§7  " + line);
            }
            default -> throw new Problem("unknown subcommand " + sub + " (hx help)");
        }
    }

    private String locationNote(Player target) {
        Integer v = plugin.state(target).subscriptions.get("hyevent:location");
        return v != null ? "" : " §e(location not sent: client has not subscribed to hyevent:location)";
    }

    private void nucleus(CommandSender sender, Player target, String who, String[] rest) {
        String usage = "nucleus <enter|run [speed] [normal|rare|claw]|area <name…>|kit <crystal>|found <crystal>|place <crystal|all>"
            + "|loot <normal|rare|claw>|bal <spawn|fight|down|locked>|spam|tab <on|off>|state <crystal> <missing|found|placed>|stop>";
        need(rest, 1, usage);
        NucleusSim sim = plugin.nucleus();
        String arg = rest.length > 1 ? String.join(" ", Arrays.asList(rest).subList(1, rest.length)) : "";
        try {
            String done = switch (rest[0].toLowerCase(Locale.ROOT)) {
                case "enter" -> sim.enter(target);
                case "run" -> sim.run(target, rest.length > 1 ? Double.parseDouble(rest[1]) : 0.15, rest.length > 2 ? rest[2] : "normal");
                case "area" -> { need(rest, 2, usage); sim.area(target, arg); yield "area " + arg; }
                case "kit" -> { need(rest, 2, usage); sim.kit(target, arg); yield "kit lines for " + arg; }
                case "found" -> { need(rest, 2, usage); sim.found(target, arg); yield "found " + arg; }
                case "place" -> { need(rest, 2, usage); sim.place(target, arg); yield "place " + arg; }
                case "loot" -> { sim.loot(target, rest.length > 1 ? rest[1] : "normal"); yield "loot bundle"; }
                case "bal" -> { need(rest, 2, usage); sim.bal(target, rest[1].toLowerCase(Locale.ROOT)); yield "bal " + rest[1]; }
                case "spam" -> { sim.spam(target); yield "spam lines"; }
                case "tab" -> { need(rest, 2, usage); sim.tab(target, rest[1].equalsIgnoreCase("on")); yield "Crystals widget " + rest[1]; }
                case "state" -> { need(rest, 3, usage); sim.state(target, rest[1], rest[2].toLowerCase(Locale.ROOT)); yield rest[1] + " " + rest[2] + " (tab only)"; }
                case "stop" -> sim.stop(target) ? "run stopped" : "no run scheduled";
                default -> throw new Problem(usage);
            };
            sender.sendMessage("§a" + who + "nucleus: " + done);
        } catch (IllegalArgumentException e) {
            throw new Problem(e.getMessage());
        }
    }

    private void sacks(CommandSender sender, Player target, String who, String[] rest) {
        String usage = "sacks add|say <ID> <n> [<ID> <n>…] [<s>s] | sacks set <ID> <n> | sacks get <ID>";
        need(rest, 2, usage);
        Sacks sacks = plugin.sacks();
        String id = rest[1].toUpperCase(Locale.ROOT);
        switch (rest[0].toLowerCase(Locale.ROOT)) {
            case "get" -> sender.sendMessage("§e" + who + id + " = " + sacks.count(target, id));
            case "set" -> {
                need(rest, 3, usage);
                sacks.set(target, id, signedLong(rest[2]));
                sender.sendMessage("§a" + who + id + " set to " + sacks.count(target, id) + " (no chat)");
            }
            case "add", "say" -> {
                need(rest, 3, usage);
                boolean apply = rest[0].equalsIgnoreCase("add");
                LinkedHashMap<String, Long> deltas = new LinkedHashMap<>();
                int window = 30;
                int i = 1;
                while (i < rest.length) {
                    if (rest[i].matches("\\d+s")) {
                        window = Integer.parseInt(rest[i].substring(0, rest[i].length() - 1));
                        i++;
                        continue;
                    }
                    if (i + 1 >= rest.length) throw new Problem(usage);
                    deltas.merge(rest[i].toUpperCase(Locale.ROOT), signedLong(rest[i + 1]), Long::sum);
                    i += 2;
                }
                sender.sendMessage("§a" + who + sacks.add(target, deltas, window, apply));
            }
            default -> throw new Problem(usage);
        }
    }

    private static long signedLong(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new Problem("not a number: " + s);
        }
    }

    private void mob(CommandSender sender, Player target, String who, String[] rest) {
        need(rest, 1, "mob <name> <level> <hp> <max> [type] | mob damage <amount> [name] | mob <list|clear>");
        Mobs mobs = plugin.mobs();
        switch (rest[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                List<String> lines = mobs.describe();
                sender.sendMessage("§e" + lines.size() + " mob(s)");
                for (String line : lines) sender.sendMessage("§7  " + line);
            }
            case "clear" -> sender.sendMessage("§a" + who + "removed " + mobs.clear() + " mob(s)");
            case "damage" -> {
                need(rest, 2, "mob damage <amount> [name]");
                String filter = rest.length > 2 ? String.join(" ", Arrays.asList(rest).subList(2, rest.length)).replace('_', ' ') : null;
                int hit = mobs.damage(amount(rest[1]), filter);
                sender.sendMessage("§a" + who + "damaged " + hit + " mob(s) by " + rest[1]);
            }
            default -> {
                need(rest, 4, "mob <name> <level> <hp> <max> [type]");
                int level = parseInt(rest[1]);
                EntityType type = EntityType.ZOMBIE;
                if (rest.length > 4) {
                    try {
                        type = EntityType.valueOf(rest[4].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        throw new Problem("unknown entity type " + rest[4]);
                    }
                }
                try {
                    Mobs.Mob mob = mobs.spawn(target, type, rest[0].replace('_', ' '), level, amount(rest[2]), amount(rest[3]));
                    sender.sendMessage("§a" + who + "spawned " + Txt.strip(MobNames.nametag(mob.name, mob.level, mob.hp, mob.max)));
                } catch (IllegalArgumentException e) {
                    throw new Problem(e.getMessage());
                }
            }
        }
    }

    private static long amount(String s) {
        try {
            return MobNames.parseAmount(s);
        } catch (NumberFormatException e) {
            throw new Problem("not an amount: " + s + " (6000, 6,000, 750k, 1.5M)");
        }
    }

    private static String rankKeys() {
        return String.join("|", Arrays.stream(Rank.values()).map(Rank::key).toList());
    }

    /** Hypixel-like instance id suffix, e.g. 57C. */
    private static String randomId() {
        return ThreadLocalRandom.current().nextInt(10, 99) + String.valueOf((char) ('A' + ThreadLocalRandom.current().nextInt(26)));
    }

    private static void need(String[] rest, int count, String usage) {
        if (rest.length < count) throw new Problem("usage: /hx " + usage);
    }

    private static int parseInt(String s) {
        try {
            return Math.clamp(Integer.parseInt(s), 1, 6400);
        } catch (NumberFormatException e) {
            throw new Problem("not a number: " + s);
        }
    }

    /** Unclamped, may be negative or 0 (offsets, list indices). */
    private static int signedInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new Problem("not a number: " + s);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        int offset = 0;
        if (args.length > 0 && args[0].equalsIgnoreCase("as")) {
            if (args.length == 2) return match(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            offset = 2;
        }
        int pos = args.length - offset;
        if (pos <= 0) return List.of();
        String current = args[args.length - 1];
        if (pos == 1) return match(offset == 0 ? SUBCOMMANDS : SUBCOMMANDS.stream().filter(s -> !s.equals("as")).toList(), current);
        String sub = args[offset].toLowerCase(Locale.ROOT);
        Presets p = plugin.presets();
        if (pos == 2) {
            return match(switch (sub) {
                case "preset" -> p.keys("presets");
                case "location" -> p.keys("locations");
                case "sidebar" -> with(p.keys("sidebars"), "clear");
                case "tab" -> with(p.keys("tabs"), "clear");
                case "actionbar" -> with(p.keys("actionbars"), "clear");
                case "menu" -> with(with(with(p.keys("menus"), "sacks"), "forge"), "hotm");
                case "sacks" -> List.of("add", "say", "set", "get");
                case "give" -> p.keys("items");
                case "party" -> List.of("none", "leader", "member");
                case "rank" -> Arrays.stream(Rank.values()).map(Rank::key).toList();
                case "textmode" -> List.of("inline", "components");
                case "mob" -> List.of("damage", "list", "clear");
                case "md" -> List.of("start", "found", "stop", "keepers", "loot", "give", "take");
                case "corpse" -> plugin.corpses().completions(1);
                case "nucleus" -> List.of("enter", "run", "area", "kit", "found", "place", "loot", "bal", "spam", "tab", "state", "stop");
                case "coating" -> List.of("on", "decoy", "off");
                case "terminal" -> plugin.terminals().completions(1, new String[0]);
                case "exp" -> plugin.exp().completions(1, new String[0]);
                case "ss" -> plugin.simonSays().completions(1);
                case "lights" -> plugin.lights().completions(1);
                case "arrows" -> plugin.arrows().completions(1);
                case "sharp" -> plugin.sharp().completions(1);
                case "forge" -> plugin.forge().completions(1, new String[0]);
                case "leap" -> List.of("setup", "open", "clicks", "selftest");
                case "world" -> List.of("list", "load", "unload", "tp", "compare", "selftest");
                case "marks" -> List.of("show", "list", "tp", "clear");
                case "storm" -> List.of("start", "stop", "status", "park", "goto", "follow", "away", "back", "phase", "crush", "line", "pillar", "goldor", "end");
                case "goldor" -> List.of("before", "start", "active", "reset", "status");
                case "section" -> List.of("start", "next", "done", "gate", "core", "status", "selftest");
                case "dragon" -> List.of("red", "orange", "green", "blue", "purple", "stop", "selftest");
                default -> List.<String>of();
            }, current);
        }
        if (sub.equals("world") && pos == 3 && List.of("load", "tp").contains(args[offset + 1].toLowerCase(Locale.ROOT))) return match(plugin.worlds().installedNames(), current);
        if (sub.equals("world") && pos == 3 && args[offset + 1].equalsIgnoreCase("unload")) return match(with(plugin.worlds().loadedNames(), "all"), current);
        if (sub.equals("world") && pos == 4 && List.of("load", "unload", "tp").contains(args[offset + 1].toLowerCase(Locale.ROOT))) return match(List.of("entry", "latest"), current);
        if (sub.equals("world") && pos == 3 && args[offset + 1].equalsIgnoreCase("selftest")) return match(List.of("build"), current);
        if (sub.equals("marks") && pos == 3 && args[offset + 1].equalsIgnoreCase("show")) return match(with(List.of(), "latest"), current);
        if (sub.equals("terminal")) return match(plugin.terminals().completions(pos - 1, Arrays.copyOfRange(args, offset + 1, args.length)), current);
        if (sub.equals("exp")) return match(plugin.exp().completions(pos - 1, Arrays.copyOfRange(args, offset + 1, args.length)), current);
        if (sub.equals("forge")) return match(plugin.forge().completions(pos - 1, Arrays.copyOfRange(args, offset + 1, args.length)), current);
        if (sub.equals("party")) return match(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), current);
        if (sub.equals("mob") && pos == 6) return match(List.of("zombie", "enderman", "skeleton", "spider", "pig", "iron_golem"), current);
        if (sub.equals("storm") && pos == 3) {
            return match(switch (args[offset + 1].toLowerCase(Locale.ROOT)) {
                case "start" -> List.of("F7", "M7", "flight");
                case "phase" -> List.of("high", "giga", "chase");
                case "pillar" -> List.of("E", "WS", "WN");
                case "goldor" -> List.of("core", "every", "spot");
                default -> List.<String>of();
            }, current);
        }
        if (sub.equals("storm") && pos == 4 && args[offset + 1].equalsIgnoreCase("pillar")) return match(List.of("down", "up"), current);
        return List.of();
    }

    private static List<String> with(List<String> keys, String extra) {
        List<String> all = new ArrayList<>(keys);
        all.add(extra);
        return all;
    }

    private static List<String> match(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
