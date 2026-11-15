package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Crystal Nucleus runs as captured on Hypixel (2026-11-07, 50 runs; 2026-11-08 keeper lines): the 8-line
 * found block, the placed lines, the ~22-line loot bundle, the NPC kit lines per crystal, Bal's lines,
 * the spam, the sidebar sublocation per crystal place, and a tab list (with an optional {@code Crystals:}
 * widget as captured 2026-11-08: {@code Jade: ✖ Not Placed}, lagging chat like Hypixel's).
 */
public final class NucleusSim {
    static final List<String> ORDER = List.of("Topaz", "Amber", "Amethyst", "Jade", "Sapphire");
    private static final Map<String, String> COLOR = Map.of(
        "Amethyst", "§5", "Topaz", "§e", "Sapphire", "§b", "Amber", "§6", "Jade", "§a");
    private static final Map<String, String> PLACE = Map.of(
        "Amethyst", "Jungle Temple", "Topaz", "Khazad-dûm", "Sapphire", "Lost Precursor City",
        "Amber", "Goblin Queen's Den", "Jade", "Mines of Divan");
    /** Median leg per crystal in the capture (previous event -> found), seconds. */
    private static final Map<String, Double> LEG = Map.of(
        "Topaz", 41.7, "Amber", 13.4, "Amethyst", 41.0, "Jade", 17.8, "Sapphire", 8.0);
    static final String NUCLEUS = "Crystal Nucleus";
    /** The widget follows a found/placed line ~1.5 s later (capture 2026-11-08). */
    private static final long TAB_LAG_TICKS = 30;
    /** After the bundle it shows the finished run (all placed) for 4-10 s before it resets. */
    private static final long TAB_BUNDLE_TICKS = 80;

    private static final String PURPLE_BAR = "§r§5§l" + "▬".repeat(64);
    private static final String AQUA_BAR = "§r§3§l" + "▬".repeat(64);

    private static final List<String> LOOT_HEAD = List.of(AQUA_BAR, "§r  §r§5§lCRYSTAL NUCLEUS LOOT BUNDLE ", "", "§r  §r§a§lREWARDS");
    private static final List<String> LOOT_GEMS = List.of(
        "§r    §r§9\uE015 Fine Amber Gemstone",
        "§r    §r§9\uE008 Fine Amethyst Gemstone",
        "§r    §r§9\uE053 Fine Jade Gemstone",
        "§r    §r§9\uE00D Fine Jasper Gemstone",
        "§r    §r§9\uE010 Fine Ruby Gemstone",
        "§r    §r§9\uE003 Fine Sapphire Gemstone",
        "§r    §r§9\uE01C Fine Topaz Gemstone",
        "§r    §r§a\uE015 Flawed Amber Gemstone §r§8x42",
        "§r    §r§a\uE008 Flawed Amethyst Gemstone §r§8x30",
        "§r    §r§a\uE053 Flawed Jade Gemstone §r§8x6",
        "§r    §r§a\uE010 Flawed Ruby Gemstone §r§8x82",
        "§r    §r§a\uE003 Flawed Sapphire Gemstone §r§8x72",
        "§r    §r§a\uE01C Flawed Topaz Gemstone §r§8x30",
        "§r    §r§2Mithril Powder §r§8x2,850");
    /** Extra lines per loot kind, inserted before the gems as Hypixel does for the rarer items. */
    private static final Map<String, List<String>> LOOT_EXTRA = Map.of(
        "normal", List.of("§r    §r§9Bob-omb §r§8x20"),
        "rare", List.of("§r    §r§6Helix Fossil", "§r    §r§5Divan Fragment §r§8x2", "§r    §r§5\uE010 Flawless Ruby Gemstone", "§r    §r§5Jaderald",
            "§r    §r§fEnchanted Book (Lapidary I§r§f)"),
        "claw", List.of("§r    §r§6Quick Claw", "§r    §r§5Divan Fragment"));
    private static final String LOOT_FOOTER = "§r§7Pick it up near the §r§5Nucleus Vault§r§7!";

    private static final Map<String, List<String>> KIT = Map.of(
        "Topaz", List.of(
            "§r§c[BOSS] Bal§r§f: Bah! Come forth my minions! Fly above, take them out!",
            "§r§c[BOSS] Bal§r§f: Grrrr! You won't be able to hit my Fire Bats!",
            "§r§c[BOSS] Bal§r§f: AGGHHHHHHHHHHH!",
            "§r§c§oThe boss looks weak and tired and retreats into the lava...",
            "§r§c[BOSS] Bal§r§f: You cannot best me!"),
        "Amethyst", List.of(
            "§r§cYou are entering the jungle temple, your speed is reduced and your jump boost will not work!",
            "§r§e[NPC] §r§bKalhuiki Door Guardian§r§f: §rA Jungle Key! I will open the door for you.",
            "§r§aThe door opens!"),
        "Sapphire", List.of(
            "§r§e[NPC] Professor Robot§r§f: §rThat's not one of the compon-",
            "§r§e[NPC] Professor Robot§r§f: §rWait a minute. This will work just fine."),
        "Amber", List.of(
            "§r§e[NPC] §r§6King Yolkar§r§f: §rYou need my §afoul stench §fagain? Bring me a §9Goblin Egg §fof any type!",
            "§r§e[NPC] §r§6King Yolkar§r§f: §rI'm covering you in my §afoul stench §ras we speak. It won't last long before it dissipates!",
            "§r§c[GUARD] Bhugtal§r§f: §r§eTHEY'RE STEALING THE CRYSTAL! GET THEM!"),
        "Jade", List.of(
            "§r§e[NPC] §r§6Keeper of Gold§r§f: §rExcellent! You have returned the §cScavenged Golden Hammer §rto its rightful place!",
            "§r§e[NPC] §r§6Keeper of Gold§r§f: §rUse your §cMetal Detector §rto find the remaining items!",
            "§r§e[NPC] §r§6Keeper of Lapis§r§f: §rExcellent! You have returned the §cScavenged Lapis Sword §rto its rightful place!",
            "§r§e[NPC] §r§6Keeper of Emerald§r§f: §rExcellent! You have returned the §cScavenged Emerald Hammer §rto its rightful place!",
            "§r§e[NPC] §r§6Keeper of Diamond§r§f: §rYou found all of the items! Behold... the §aJade Crystal§r!"));

    private static final Map<String, List<String>> BAL = Map.of(
        "spawn", List.of("§r§c§oThe ground starts to shake and the lava bubbles..."),
        "fight", List.of(
            "§r§c[BOSS] Bal§r§f: Bah! Come forth my minions! Fly above, take them out!",
            "§r§c[BOSS] Bal§r§f: Grrrr! You won't be able to hit my Fire Bats!"),
        "down", List.of(
            "§r§c[BOSS] Bal§r§f: AGGHHHHHHHHHHH!",
            "§r§c§oThe boss looks weak and tired and retreats into the lava...",
            "§r§c[BOSS] Bal§r§f: You cannot best me!"),
        "locked", List.of("§r§cYou haven't earned this Crystal! Come back when you have! >:-)"));

    private static final String ALREADY = "§r§cYou have already obtained this Crystal!";
    private static final String BLOCKS = "§r§cThere are blocks in the way!";

    /** missing / found / placed per crystal, in pedestal order. */
    private static final class Run {
        final LinkedHashMap<String, String> state = new LinkedHashMap<>();
        final List<BukkitTask> tasks = new ArrayList<>();
        String area = NUCLEUS;
        boolean tab;

        Run() {
            for (String c : List.of("Amethyst", "Topaz", "Sapphire", "Amber", "Jade")) state.put(c, "missing");
        }

        long count(String s) {
            return state.values().stream().filter(s::equals).count();
        }
    }

    private final SoulTestKit plugin;
    private final Map<UUID, Run> runs = new HashMap<>();

    public NucleusSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    private Run run(Player player) {
        return runs.computeIfAbsent(player.getUniqueId(), id -> new Run());
    }

    /** Crystal Hollows location, Crystal Nucleus sidebar, tab list; fresh run state. */
    public String enter(Player player) {
        stop(player);
        Run old = runs.remove(player.getUniqueId());
        Run r = run(player);
        if (old != null) r.tab = old.tab;
        plugin.clearSidebar(player);
        plugin.clearTab(player);
        plugin.applyLocation(player, "crystal_hollows");
        r.area = NUCLEUS;
        showSidebar(player, r);
        showTab(player, r);
        return "crystal hollows, " + NUCLEUS + ", run reset";
    }

    public void area(Player player, String area) {
        Run r = run(player);
        r.area = area;
        showSidebar(player, r);
    }

    /** The NPC lines that cost a kit item for this crystal (Bal's fight for Topaz). */
    public void kit(Player player, String crystal) {
        chat(player, KIT.getOrDefault(crystal(crystal), List.of()));
    }

    /** The 8-line found block (n/5) plus the re-punch spam. */
    public void found(Player player, String crystal) {
        String c = crystal(crystal);
        Run r = run(player);
        r.state.put(c, "found");
        long n = r.state.values().stream().filter(s -> !s.equals("missing")).count();
        List<String> lines = new ArrayList<>(List.of(
            PURPLE_BAR,
            "§r§f                       §r§5§l✦ CRYSTAL FOUND §r§7(" + n + "§r§7/5§r§7)",
            "§r§f                              §r" + COLOR.getOrDefault(c, "§7") + c + " Crystal",
            ""));
        if (n < 5) {
            lines.add("§r§f            §r§7Place this Crystal in the §r§5Crystal Nucleus §r§7and");
            lines.add("§r§f             §r§7find all §r§a5 §r§7Crystals in the §r§5Crystal Hollows §r§7to");
            lines.add("§r§f                               §r§7earn §r§6rare loot§r§7!");
        } else {
            lines.add("§r§f           §r§7You have found all of the Crystals! Good stuff!");
        }
        lines.add(PURPLE_BAR);
        lines.add(ALREADY);
        lines.add(ALREADY);
        chat(player, lines);
        showTabLagged(player, r);
    }

    /** Placed lines for one crystal, or every found one 1 s apart; the bundle follows the fifth. */
    public void place(Player player, String which) {
        Run r = run(player);
        List<String> todo = new ArrayList<>();
        if (which.equalsIgnoreCase("all")) {
            r.state.forEach((c, s) -> { if (s.equals("found")) todo.add(c); });
        } else {
            todo.add(crystal(which));
        }
        long tick = 0;
        for (String c : todo) {
            later(player, tick, () -> placeOne(player, c, "normal"));
            tick += 20;
        }
    }

    /** The placed lines (an etherwarp miss first, as in the capture); the bundle follows the last one. */
    private void placeOne(Player player, String c, String lootKind) {
        Run r = run(player);
        r.state.put(c, "placed");
        chat(player, List.of(BLOCKS, "§r§5§l✦ §r§dYou placed the §r" + COLOR.getOrDefault(c, "§7") + c + " Crystal§r§d!",
            "§r  §r§dKeep exploring the §r§5Crystal Hollows §r§dto find the rest!"));
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.49f);
        showTabLagged(player, r);
        if (r.count("placed") == r.state.size()) later(player, 2, () -> loot(player, lootKind));
    }

    /** The loot bundle; resets the run like Hypixel does (the tab widget only after a while). */
    public void loot(Player player, String kind) {
        List<String> extra = LOOT_EXTRA.get(kind);
        if (extra == null) throw new IllegalArgumentException("loot <" + String.join("|", LOOT_EXTRA.keySet()) + ">");
        List<String> lines = new ArrayList<>(LOOT_HEAD);
        lines.addAll(extra);
        lines.addAll(LOOT_GEMS);
        lines.add(AQUA_BAR);
        lines.add(LOOT_FOOTER);
        chat(player, lines);
        player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
        Run r = run(player);
        r.state.replaceAll((c, s) -> "missing");
        showTabLagged(player, r, TAB_BUNDLE_TICKS);
    }

    /** A whole run in capture order and leg times scaled by {@code speed}, ending with the bundle. */
    public String run(Player player, double speed, String lootKind) {
        if (!LOOT_EXTRA.containsKey(lootKind)) throw new IllegalArgumentException("loot <" + String.join("|", LOOT_EXTRA.keySet()) + ">");
        stop(player);
        Run r = run(player);
        r.state.replaceAll((c, s) -> "missing");
        long tick = 0;
        for (String c : ORDER) {
            long leg = Math.max(20, Math.round(LEG.get(c) * 20 * speed));
            later(player, tick + leg / 3, () -> area(player, PLACE.get(c)));
            later(player, tick + leg * 2 / 3, () -> kit(player, c));
            tick += leg;
            later(player, tick, () -> found(player, c));
            later(player, tick + 10, () -> {
                chat(player, List.of("§r§7Warping..."));
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.44f);
                area(player, NUCLEUS);
            });
        }
        tick += 30;
        for (String c : ORDER) {
            later(player, tick, () -> placeOne(player, c, lootKind));
            tick += 20;
        }
        return String.format(Locale.ROOT, "run scheduled: %.1f s, loot %s", tick / 20.0, lootKind);
    }

    public void bal(Player player, String what) {
        List<String> lines = BAL.get(what);
        if (lines == null) throw new IllegalArgumentException("bal <" + String.join("|", BAL.keySet()) + ">");
        chat(player, lines);
    }

    public void spam(Player player) {
        chat(player, List.of(ALREADY, ALREADY, BLOCKS, ALREADY, BLOCKS));
    }

    public void tab(Player player, boolean on) {
        Run r = run(player);
        r.tab = on;
        showTab(player, r);
    }

    /** Sets one crystal's state silently (tab widget only, no chat): what a restart or a missed line leaves. */
    public void state(Player player, String crystal, String state) {
        if (!List.of("missing", "found", "placed").contains(state)) throw new IllegalArgumentException("state <crystal> <missing|found|placed>");
        Run r = run(player);
        r.state.put(crystal(crystal), state);
        showTab(player, r);
    }

    public boolean stop(Player player) {
        Run r = runs.get(player.getUniqueId());
        if (r == null || r.tasks.isEmpty()) return false;
        r.tasks.forEach(BukkitTask::cancel);
        r.tasks.clear();
        return true;
    }

    /** Case-insensitive crystal name; unknown names pass through (the mod must not validate them). */
    private static String crystal(String name) {
        for (String c : COLOR.keySet()) if (c.equalsIgnoreCase(name)) return c;
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    private void later(Player player, long ticks, Runnable step) {
        Run r = run(player);
        r.tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) step.run();
        }, ticks));
    }

    private static void chat(Player player, List<String> lines) {
        for (String line : lines) Nms.sendSystemChat(player, Txt.of(line), false);
    }

    private void showSidebar(Player player, Run r) {
        List<String> lines = List.of(
            "§7{date} §8{serverShort}",
            "",
            " Late Autumn 23rd",
            " §710:30pm §b☽",
            " §7\uE067 §a" + r.area,
            "",
            " §2᠅ Mithril: §22,950,374",
            "Purse: §6196,375,704",
            "Bits: §b14,045",
            "",
            "§ewww.hypixel.net");
        List<String> filled = new ArrayList<>();
        for (String l : lines) filled.add(plugin.fill(player, l));
        Sidebar.show(player, "§e§lSKYBLOCK", filled);
    }

    /** Shows the current state {@code ticks} later: Hypixel's widget lags the chat lines. */
    private void showTabLagged(Player player, Run r, long ticks) {
        Map<String, String> snapshot = new LinkedHashMap<>(r.state);
        later(player, ticks, () -> showTab(player, r, snapshot));
    }

    private void showTabLagged(Player player, Run r) {
        showTabLagged(player, r, TAB_LAG_TICKS);
    }

    private void showTab(Player player, Run r) {
        showTab(player, r, r.state);
    }

    private void showTab(Player player, Run r, Map<String, String> state) {
        List<String> info = new ArrayList<>(List.of(
            "§b§lArea: §7Crystal Hollows",
            " Server: §8{server}",
            "",
            "§e§lProfile: §aBanana",
            " SB Level§f: §8[§4607§8] §b72§3/§b100 XP",
            ""));
        if (r.tab) {
            info.add("§b§lCrystals:");
            state.forEach((c, s) -> info.add(" " + COLOR.getOrDefault(c, "§7") + c + "§f: " + switch (s) {
                case "placed" -> "§a✔ Placed";
                case "found" -> "§e✖ Not Placed";
                default -> "§c✖ Not Found";
            }));
        }
        List<List<String>> columns = List.of(
            List.of("§a§lPlayers §f(1)", "§8[§9285§8] §b{player}"),
            info,
            List.of("§e§lPet:", " §7[Lvl 100] §6Mole"),
            List.of());
        TabList.show(player, plugin.state(player), "\n§bYou are playing on §e§lMC.HYPIXEL.NET\n",
            "\n§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET", columns, s -> plugin.fill(player, s));
    }
}
