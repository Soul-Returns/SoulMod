package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * {@code /hx leap}: Hypixel's Spirit Leap menu as recorded (docs/research/spirit-leap-2026-11-13.md §1) with the
 * user's M7 party as fake teammates. {@code setup} puts the player in a dungeon run (location, M7 sidebar, the
 * party tab at Hypixel's tab indices 1/5/9/13/17 with classes) and, with {@code boss}, two teammates as NPCs at
 * named F7 spots; {@code open} opens the menu; every click on it is recorded ({@code clicks}) so a test can prove
 * one press sent exactly one container click on the right slot.
 */
public final class LeapSim implements Listener {
    /** Teammates in the tab order of the recording's run 1 and their classes. */
    static final String[][] PARTY = {{"go9o", "Healer"}, {"Maaxnn", "Tank"}, {"PuppyGirlSunny", "Berserk"}, {"Waccoons", "Mage"}};

    /** Hypixel's head order in run 1 (it changes every run; slots 11, 12, 14, 15). */
    static final String[] MENU_ORDER = {"Waccoons", "PuppyGirlSunny", "go9o", "Maaxnn"};

    static final String TITLE = "Spirit Leap";
    static final String ALIVE = "§eClick to teleport!";
    static final String DEAD = "§cThis player is currently dead!";

    /** Click → teleport + close + chat: 3 ticks in the recording. */
    static final long ANSWER_TICKS = 3;

    private final SoulTestKit plugin;
    private final List<String> clicks = new ArrayList<>();
    private long openedAt;

    LeapSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    static final class Holder implements InventoryHolder {
        final Map<Integer, String> heads = new LinkedHashMap<>();
        final Set<String> dead = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * Slots for [n] heads: 4 → 11 12 14 15, 1 → 13 (recorded); 2 → 12 14 and 3 → 11 13 15 are guesses (row 2,
     * centred, the middle left free like the recorded four).
     */
    static int[] slots(int n) {
        return switch (n) {
            case 1 -> new int[]{13};
            case 2 -> new int[]{12, 14};
            case 3 -> new int[]{11, 13, 15};
            default -> new int[]{11, 12, 14, 15};
        };
    }

    static Holder build(List<String> names, Set<String> dead) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 36, Txt.of(TITLE));
        holder.inventory = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(Component.empty());
        pane.setItemMeta(pm);
        for (int i = 0; i < 36; i++) inv.setItem(i, pane);
        int[] at = slots(names.size());
        for (int i = 0; i < names.size() && i < at.length; i++) {
            String name = names.get(i);
            boolean isDead = dead.contains(name);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setPlayerProfile(Bukkit.createProfile(name));
            meta.displayName(Txt.item("§b" + name));
            meta.lore(List.of(Txt.item(isDead ? DEAD : ALIVE)));
            head.setItemMeta(meta);
            inv.setItem(at[i], head);
            holder.heads.put(at[i], name);
            if (isDead) holder.dead.add(name);
        }
        return holder;
    }

    /** Synthetic M7 map: 6x6 rooms of 16 px, 4 px apart from pixel 6; entrance (2,5), blood (2,0), a puzzle (0,3). */
    static final byte[] MAP_COLORS = new byte[128 * 128];

    /** You (frame) in room (2,2), the teammates in tab order in the rooms W, E, N, S of it (as their NPCs stand). */
    static final List<int[]> MAP_MARKERS = List.of(
        new int[]{0, marker(2), marker(2), 8},
        new int[]{1, marker(1), marker(2), 0},
        new int[]{1, marker(3), marker(2), 0},
        new int[]{1, marker(2), marker(1), 0},
        new int[]{1, marker(2), marker(3), 0});

    static {
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 6; c++) {
                byte color = c == 2 && r == 5 ? (byte) 30 : c == 2 && r == 0 ? (byte) 18 : c == 0 && r == 3 ? (byte) 66 : (byte) 63;
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) MAP_COLORS[(6 + r * 20 + y) * 128 + 6 + c * 20 + x] = color;
                }
            }
        }
    }

    /** Map marker units for the centre of room [cell]: pixel = (marker + 128) / 2. */
    private static int marker(int cell) {
        return 2 * (6 + cell * 20 + 8) - 128;
    }

    /**
     * {@code setup [class] [boss|map]}: dungeon location, M7 sidebar, the party tab. boss = stand in the F7 room with go9o
     * at SS and Maaxnn in S1; map = fly at -120 100 -120 (inside the dungeon room grid, so Soul's map draws markers) with
     * the four teammates 40 blocks around you.
     */
    String setup(Player player, String selfClass, String where) {
        plugin.applyLocation(player, "dungeon");
        plugin.applySidebar(player, "dungeon_m7");
        String self = selfClass == null ? "Archer" : cap(selfClass);
        List<String> col = new ArrayList<>();
        for (int r = 0; r < TabList.ROWS; r++) col.add("");
        col.set(0, "§a§lParty §f(5)");
        col.set(1, row(player.getName(), self));
        for (int i = 0; i < PARTY.length; i++) {
            String cls = PARTY[i][1].equalsIgnoreCase(self) ? "Archer" : PARTY[i][1];
            col.set(5 + 4 * i, row(PARTY[i][0], cls));
        }
        TabList.show(player, plugin.state(player), "", "", List.of(col), s -> plugin.fill(player, s));
        if ("map".equals(where)) {
            player.setAllowFlight(true);
            player.setFlying(true);
            player.teleport(new org.bukkit.Location(player.getWorld(), -120.5, 100, -120.5, 0f, 0f));
            plugin.devBridge().add(player, "go9o", true, -40, 0);
            plugin.devBridge().add(player, "Maaxnn", true, 40, 0);
            plugin.devBridge().add(player, "PuppyGirlSunny", true, 0, -40);
            plugin.devBridge().add(player, "Waccoons", true, 0, 40);
            // The dungeon map (id 1024, Hypixel's) a few times over 10 s, so Soul's scanner reads it once the floor is known.
            for (int i = 0; i < 10; i++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) Nms.sendMap(player, 1024, MAP_COLORS, MAP_MARKERS);
                }, 5 + 20L * i);
            }
            return "leap setup: dungeon, M7, you are " + self + "; at -120 100 -120 in the room grid, teammates 40 blocks W/E/N/S, dungeon map with their markers";
        }
        if (!"boss".equals(where)) return "leap setup: dungeon, M7, you are " + self;
        // F7 boss coordinates (the overworld has the same numbers): you at S1 terminal 4, go9o at SS, Maaxnn in S1.
        player.setAllowFlight(true);
        player.setFlying(true);
        player.teleport(new org.bukkit.Location(player.getWorld(), 92.5, 122, 95.5, 0f, 0f));
        plugin.devBridge().add(player, "go9o", true, 108.5 - 92.5, 102.7 - 95.5);
        plugin.devBridge().add(player, "Maaxnn", true, 109.5 - 92.5, 80.5 - 95.5);
        return "leap setup: dungeon, M7, you are " + self + "; in the boss room at 92 122 95 with go9o at SS and Maaxnn in S1";
    }

    private static String row(String name, String cls) {
        return " §8[§9285§8] §b" + name + " §f(§d" + cls + " XL§f)";
    }

    private static String cap(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * {@code open [dead=a,b] [only=a,b] [order=a,b,c,d] [heads=ms] [stagger=ms]}: the menu with Hypixel's run-1 order
     * unless given. {@code heads} opens it with the head slots empty and puts the heads in that much later (slot
     * updates, as a lagging server would), {@code stagger} spaces the heads apart; both round to whole ticks.
     */
    String open(Player player, String[] args) {
        List<String> names = new ArrayList<>(List.of(MENU_ORDER));
        Set<String> dead = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        long headsMs = 0;
        long staggerMs = 0;
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) continue;
            List<String> list = List.of(kv[1].split(","));
            switch (kv[0].toLowerCase(Locale.ROOT)) {
                case "dead" -> dead.addAll(list);
                case "only" -> names.removeIf(n -> list.stream().noneMatch(n::equalsIgnoreCase));
                case "order" -> names = new ArrayList<>(list);
                case "heads" -> headsMs = Long.parseLong(kv[1]);
                case "stagger" -> staggerMs = Long.parseLong(kv[1]);
                default -> { }
            }
        }
        Holder h = build(names, dead);
        String late = "";
        if (headsMs > 0 || staggerMs > 0) {
            Inventory inv = h.inventory;
            int n = 0;
            for (int slot : h.heads.keySet()) {
                ItemStack head = inv.getItem(slot);
                inv.setItem(slot, null);
                long ticks = Math.round((headsMs + n++ * staggerMs) / 50.0);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.getOpenInventory().getTopInventory() == inv) inv.setItem(slot, head);
                }, Math.max(0, ticks));
            }
            late = ", heads after " + headsMs + " ms" + (staggerMs > 0 ? " + " + staggerMs + " ms each" : "");
        }
        openedAt = System.currentTimeMillis();
        player.openInventory(h.inventory);
        return "leap menu open: " + h.heads + (dead.isEmpty() ? "" : " dead " + dead) + late;
    }

    /** Every click on the menu since the last {@code clicks}, then cleared. */
    List<String> takeClicks() {
        List<String> out = new ArrayList<>(clicks);
        clicks.clear();
        return out;
    }

    /** What the server does with a click on [slot]: the teammate leapt to, or null (pane, dead head: nothing). */
    static String answer(Holder h, int slot) {
        String name = h.heads.get(slot);
        if (name == null || h.dead.contains(name)) return null;
        return name;
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder h)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        String name = raw < 36 ? h.heads.get(raw) : null;
        String line = "+" + (System.currentTimeMillis() - openedAt) + " ms slot " + raw + " " + event.getClick() + " (" + event.getAction() + ")" + (name != null ? " → " + name + (h.dead.contains(name) ? " (dead)" : "") : "");
        clicks.add(line);
        plugin.getLogger().info("leap click: " + line);
        if (raw >= 36) return;
        String to = answer(h, raw);
        if (to == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) == h) player.closeInventory();
            Nms.sendSystemChat(player, Txt.of("§aYou have teleported to §b" + to + "§a!"), false);
        }, ANSWER_TICKS);
    }

    @EventHandler(ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }

    private static String plain(Component c) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c);
    }

    /** Builds every recorded and guessed shape and checks it against the research doc; the answer rule per slot. */
    String selftest(CommandSender sender) {
        int[] checks = {0};
        List<String> failed = new ArrayList<>();
        java.util.function.BiConsumer<Boolean, String> check = (ok, what) -> {
            checks[0]++;
            if (!ok) failed.add(what);
        };
        Holder four = build(List.of(MENU_ORDER), Set.of());
        check.accept(four.inventory.getSize() == 36, "size 36");
        Map<Integer, String> expect = new HashMap<>(Map.of(11, "Waccoons", 12, "PuppyGirlSunny", 14, "go9o", 15, "Maaxnn"));
        for (int i = 0; i < 36; i++) {
            ItemStack it = four.inventory.getItem(i);
            String want = expect.get(i);
            if (want == null) {
                check.accept(it != null && it.getType() == Material.BLACK_STAINED_GLASS_PANE, "slot " + i + " pane");
            } else {
                check.accept(it != null && it.getType() == Material.PLAYER_HEAD, "slot " + i + " head");
                check.accept(want.equals(Txt.strip(plain(it.getItemMeta().displayName()))), "slot " + i + " name " + want);
                List<Component> lore = it.getItemMeta().lore();
                check.accept(lore != null && lore.size() == 1 && Txt.strip(plain(lore.get(0))).equals("Click to teleport!"), "slot " + i + " lore");
                check.accept(want.equals(answer(four, i)), "click slot " + i + " leaps to " + want);
            }
        }
        check.accept(answer(four, 13) == null, "click on a pane does nothing");
        Holder dead = build(List.of(MENU_ORDER), Set.of("Waccoons"));
        List<Component> lore = dead.inventory.getItem(11).getItemMeta().lore();
        check.accept(lore != null && Txt.strip(plain(lore.get(0))).equals("This player is currently dead!"), "dead lore");
        check.accept(answer(dead, 11) == null, "click on a dead head does nothing");
        Holder one = build(List.of("Waccoons"), Set.of());
        check.accept("Waccoons".equals(answer(one, 13)) && one.heads.size() == 1, "duo: slot 13");
        String result = checks[0] + " checks, " + failed.size() + " failed" + (failed.isEmpty() ? "" : ": " + String.join("; ", failed));
        plugin.getLogger().info("leap selftest: " + result);
        sender.sendMessage((failed.isEmpty() ? "§a" : "§c") + "leap selftest: " + result);
        return result;
    }
}
