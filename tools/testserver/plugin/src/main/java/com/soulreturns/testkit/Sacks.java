package com.soulreturns.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Hypixel's sacks as captured live on 2026-11-07: {@code hx menu sacks} opens "Sack of Sacks" (owned sacks with
 * their real SkyBlock ids); clicking a modelled sack opens its menu, titled without "Large", whose items carry
 * their SkyBlock id and {@code Stored: N/22.2k} lore. {@code hx sacks add} changes counts, updates an open sack
 * menu's slot like Hypixel's live slot updates, and sends the {@code [Sacks]} chat summary whose per-item
 * breakdown sits only in the hover text. Counts live per player in memory, seeded from the capture.
 */
public final class Sacks implements Listener {
    private static final String CAPACITY = "22.2k";

    record SackItem(int slot, String id, String material, String name, long seed) {}

    record Sack(String id, String itemName, String title, int rows, List<SackItem> items) {
        int backSlot() {
            return rows * 9 - 6;
        }
    }

    /** Owned sacks in Sack of Sacks order (slot = index); only those with items open a menu. */
    private static final List<Sack> SACKS = new ArrayList<>();
    private static final Map<String, SackItem> ITEMS = new HashMap<>();
    private static final Map<String, Sack> SACK_OF = new HashMap<>();

    static {
        String[][] owned = {
            {"LARGE_ENCHANTED_COMBAT_SACK", "§5Large Enchanted Combat Sack"},
            {"LARGE_ENCHANTED_MINING_SACK", "§5Large Enchanted Mining Sack"},
            {"LARGE_ENCHANTED_FISHING_SACK", "§5Large Enchanted Fishing Sack"},
            {"LARGE_ENCHANTED_HUSBANDRY_SACK", "§5Large Enchanted Husbandry Sack"},
            {"LARGE_ENCHANTED_AGRONOMY_SACK", "§6Large Enchanted Agronomy Sack"},
            {"LARGE_ENCHANTED_FORAGING_SACK", "§5Large Enchanted Foraging Sack"},
            {"LARGE_GEMSTONE_SACK", "§6Large Gemstone Sack"},
            {"LARGE_NETHER_SACK", "§5Large Nether Sack"},
            {"LARGE_EVENTS_SACK", "§6Events Sack"},
            {"LARGE_COMBAT_SACK", "§5Large Combat Sack"},
            {"LARGE_MINING_SACK", "§6Large Mining Sack"},
            {"LARGE_FISHING_SACK", "§5Large Fishing Sack"},
            {"LARGE_HUSBANDRY_SACK", "§5Large Husbandry Sack"},
            {"LARGE_AGRONOMY_SACK", "§6Large Agronomy Sack"},
            {"LARGE_FORAGING_SACK", "§5Large Foraging Sack"},
            {"CRYSTAL_HOLLOWS_SACK", "§5Crystal Hollows Sack"},
            {"LARGE_LAVA_FISHING_SACK", "§5Large Lava Fishing Sack"},
            {"RUNE_SACK", "§6Rune Sack"},
            {"SILVER_TROPHY_FISHING_SACK", "§9Silver Trophy Fishing Sack"},
            {"SILVER_TROPHY_FROG_SACK", "§9Silver Trophy Frog Sack"},
            {"FLOWER_SACK", "§5Flower Sack"},
            {"LARGE_DUNGEON_SACK", "§6Dungeon Sack"},
            {"LARGE_WINTER_SACK", "§9Winter Sack"},
            {"LARGE_SLAYER_SACK", "§6Large Slayer Sack"},
            {"DWARVEN_MINES_SACK", "§5Dwarven Sack"},
            {"GARDEN_SACK", "§5Garden Sack"},
            {"SMALL_DRAGON_SACK", "§aSmall Dragon Sack"},
            {"BRONZE_TROPHY_FISHING_SACK", "§aBronze Trophy Fishing Sack"},
            {"BRONZE_TROPHY_FROG_SACK", "§aBronze Trophy Frog Sack"},
            {"SMALL_MUTATIONS_SACK", "§aSmall Mutations Sack"},
            {"WITCH_SACK", "§5Witch's Sack"},
            {"LARGE_BAIT_SACK", "§5Large Bait Sack"},
            {"VINYL_SACK", "§5Vinyl Sack"},
        };
        Map<String, Sack> menus = new HashMap<>();
        menus.put("LARGE_FISHING_SACK", new Sack("LARGE_FISHING_SACK", "", "Fishing Sack", 6, dense(new Object[][] {
            {"AGARIMOO_TONGUE", "mutton", "§9Agarimoo Tongue", 0}, {"ALLIGATOR_SKIN", "paper", "§5Alligator Skin", 2},
            {"BLUE_RING", "paper", "§9Blue Ring", 0}, {"BLUE_SHARK_TOOTH", "paper", "§9Blue Shark Tooth", 0},
            {"BROKEN_RADAR", "paper", "§9Broken Radar", 0}, {"BRONZE_BOWL", "paper", "§aBronze Bowl", 2},
            {"BUSTED_BELT_BUCKLE", "paper", "§9Busted Belt Buckle", 0}, {"CAN_OF_WORMS", "paper", "§aCan of Worms", 1},
            {"CHUM", "paper", "§aChum", 467}, {"CLAY_BALL", "clay_ball", "§fClay Ball", 26},
            {"DIVER_FRAGMENT", "paper", "§9Emperor's Skull", 0}, {"FROGCOIN", "paper", "§aFrogcoin", 13},
            {"GLOWING_MUSHROOM", "paper", "§aGlowing Mushroom", 0}, {"HALF_EATEN_MUSHROOM", "paper", "§aHalf-Eaten Mushroom", 9},
            {"INK_SACK", "ink_sac", "§fInk Sac", 12}, {"WATER_LILY", "lily_pad", "§fLily Pad", 241},
            {"LOTUS", "paper", "§aLotus", 4494}, {"NURSE_SHARK_TOOTH", "paper", "§aNurse Shark Tooth", 0},
            {"OLD_LEATHER_BOOT", "paper", "§5Old Leather Boot", 0}, {"PRISMARINE_CRYSTALS", "prismarine_crystals", "§fPrismarine Crystals", 397},
            {"PRISMARINE_SHARD", "prismarine_shard", "§fPrismarine Shard", 395}, {"RAW_FISH:3", "pufferfish", "§fPufferfish", 30},
            {"RAW_FISH", "cod", "§fRaw Cod", 34}, {"RAW_FISH:1", "salmon", "§fRaw Salmon", 59},
            {"RUSTY_COIN", "paper", "§aRusty Coin", 0}, {"SHARK_FIN", "paper", "§9Shark Fin", 0},
            {"SPONGE", "sponge", "§fSponge", 0}, {"TIGER_SHARK_TOOTH", "paper", "§5Tiger Shark Tooth", 0},
            {"TORN_CLOTH", "paper", "§aTorn Cloth", 1}, {"RAW_FISH:2", "tropical_fish", "§fTropical Fish", 4},
            {"WORM_MEMBRANE", "paper", "§aWorm Membrane", 20}, {"REVERSE_REFORGE_STONE_CORE", "paper", "§9eroC enotS egrofeR", 9},
        })));
        menus.put("LARGE_ENCHANTED_FISHING_SACK", new Sack("LARGE_ENCHANTED_FISHING_SACK", "", "Enchanted Fishing Sack", 5, bordered(new Object[][] {
            {"CONDENSED_WATER_LILY", "paper", "§9Condensed Lily Pad", 0}, {"ENCHANTED_CLAY_BALL", "clay_ball", "§aEnchanted Clay Ball", 0},
            {"ENCHANTED_CLAY_BLOCK", "clay", "§9Enchanted Clay Block", 0}, {"ENCHANTED_COOKED_FISH", "cooked_cod", "§9Enchanted Cooked Cod", 0},
            {"ENCHANTED_COOKED_SALMON", "cooked_salmon", "§9Enchanted Cooked Salmon", 0}, {"ENCHANTED_INK_SACK", "ink_sac", "§aEnchanted Ink Sac", 0},
            {"ENCHANTED_WATER_LILY", "lily_pad", "§aEnchanted Lily Pad", 6}, {"ENCHANTED_PRISMARINE_CRYSTALS", "prismarine_crystals", "§aEnchanted Prismarine Crystals", 0},
            {"ENCHANTED_PRISMARINE_SHARD", "prismarine_shard", "§aEnchanted Prismarine Shard", 0}, {"ENCHANTED_PUFFERFISH", "pufferfish", "§aEnchanted Pufferfish", 0},
            {"ENCHANTED_RAW_FISH", "cod", "§aEnchanted Raw Cod", 0}, {"ENCHANTED_RAW_SALMON", "salmon", "§aEnchanted Raw Salmon", 0},
            {"ENCHANTED_SHARK_FIN", "paper", "§5Enchanted Shark Fin", 0}, {"ENCHANTED_SPONGE", "sponge", "§aEnchanted Sponge", 0},
            {"ENCHANTED_CLOWNFISH", "tropical_fish", "§aEnchanted Tropical Fish", 0}, {"ENCHANTED_WET_SPONGE", "wet_sponge", "§9Enchanted Wet Sponge", 0},
            {"LOTUS_GOLD", "paper", "§5Gold Lotus", 1}, {"LOTUS_SILVER", "paper", "§9Silver Lotus", 62},
        })));
        menus.put("LARGE_ENCHANTED_FORAGING_SACK", new Sack("LARGE_ENCHANTED_FORAGING_SACK", "", "Enchanted Foraging Sack", 5, bordered(new Object[][] {
            {"ENCHANTED_ACACIA_LOG", "acacia_log", "§aEnchanted Acacia Log", 0}, {"ENCHANTED_BIRCH_LOG", "birch_log", "§aEnchanted Birch Log", 0},
            {"ENCHANTED_DARK_OAK_LOG", "dark_oak_log", "§aEnchanted Dark Oak Log", 0}, {"ENCHANTED_FIG_LOG", "player_head", "§aEnchanted Fig Log", 0},
            {"ENCHANTED_HELIX_LOG", "paper", "§aEnchanted Helix Log", 3}, {"ENCHANTED_HONEYCOMB", "paper", "§aEnchanted Honeycomb", 0},
            {"ENCHANTED_HONEYCOMB_BLOCK", "paper", "§9Enchanted Honeycomb Block", 0}, {"ENCHANTED_JUNGLE_LOG", "jungle_log", "§aEnchanted Jungle Log", 0},
            {"ENCHANTED_MANGROVE_LOG", "player_head", "§aEnchanted Mangrove Log", 0}, {"ENCHANTED_OAK_LOG", "oak_log", "§aEnchanted Oak Log", 0},
            {"ENCHANTED_RUBY_VEILSHROOM", "paper", "§aEnchanted Ruby Veilshroom", 0}, {"ENCHANTED_SEA_LUMIES", "paper", "§aEnchanted Sea Lumies", 0},
            {"ENCHANTED_SPRUCE_LOG", "spruce_log", "§aEnchanted Spruce Log", 0}, {"ENCHANTED_TENDER_WOOD", "paper", "§9Enchanted Tender Wood", 0},
            {"ENCHANTED_VINESAP", "paper", "§9Enchanted Vinesap", 0}, {"FIGSTONE", "paper", "§9Figstone", 0},
            {"HELIXIS", "paper", "§9Helixis", 0}, {"MANGCORE", "paper", "§9Mangcore", 0}, {"VEILSHROOM_BUNCH", "paper", "§9Veilshroom Bunch", 0},
        })));
        menus.put("FLOWER_SACK", new Sack("FLOWER_SACK", "", "Flower Sack", 5, bordered(new Object[][] {
            {"RED_ROSE:2", "allium", "§fAllium", 781}, {"RED_ROSE:3", "azure_bluet", "§fAzure Bluet", 943},
            {"RED_ROSE:1", "blue_orchid", "§fBlue Orchid", 774}, {"YELLOW_FLOWER", "dandelion", "§fDandelion", 0},
            {"ENCHANTED_DANDELION", "dandelion", "§aEnchanted Dandelion", 0}, {"ENCHANTED_POPPY", "poppy", "§aEnchanted Poppy", 0},
            {"ENDSTONE_ROSE", "poppy", "§9End Stone Rose", 43}, {"DOUBLE_PLANT:1", "lilac", "§fLilac", 841},
            {"RED_ROSE:5", "orange_tulip", "§fOrange Tulip", 840}, {"RED_ROSE:8", "oxeye_daisy", "§fOxeye Daisy", 846},
            {"DOUBLE_PLANT:5", "peony", "§fPeony", 776}, {"RED_ROSE:7", "pink_tulip", "§fPink Tulip", 783},
            {"RED_ROSE", "poppy", "§fPoppy", 0}, {"RED_ROSE:4", "red_tulip", "§fRed Tulip", 713},
            {"DOUBLE_PLANT:4", "rose_bush", "§fRose Bush", 842}, {"RED_ROSE:6", "white_tulip", "§fWhite Tulip", 843},
        })));
        menus.put("LARGE_BAIT_SACK", new Sack("LARGE_BAIT_SACK", "", "Bait Sack", 4, bordered(new Object[][] {
            {"WHALE_BAIT", "paper", "§9Whale Bait", 40}, {"DARK_BAIT", "paper", "§aDark Bait", 12},
            {"MINNOW_BAIT", "paper", "§aMinnow Bait", 64}, {"CARROT_BAIT", "carrot", "§fCarrot Bait", 0},
        })));
        for (String[] o : owned) {
            Sack menu = menus.get(o[0]);
            Sack sack = menu == null ? new Sack(o[0], o[1], null, 0, List.of()) : new Sack(o[0], o[1], menu.title, menu.rows, menu.items);
            SACKS.add(sack);
            for (SackItem item : sack.items) {
                ITEMS.put(item.id, item);
                SACK_OF.put(item.id, sack);
            }
        }
    }

    /** Hypixel's 9x6 sack layout: items packed from slot 0. */
    private static List<SackItem> dense(Object[][] rows) {
        List<SackItem> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) out.add(item(i, rows[i]));
        return out;
    }

    /** The smaller sacks: a glass border, items in the inner 7 columns from slot 10. */
    private static List<SackItem> bordered(Object[][] rows) {
        List<SackItem> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) out.add(item(10 + (i / 7) * 9 + i % 7, rows[i]));
        return out;
    }

    private static SackItem item(int slot, Object[] row) {
        return new SackItem(slot, (String) row[0], (String) row[1], (String) row[2], ((Number) row[3]).longValue());
    }

    private final SoulTestKit plugin;
    private final Map<UUID, Map<String, Long>> counts = new HashMap<>();

    public Sacks(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        /** Null = Sack of Sacks. */
        final Sack sack;
        Inventory inventory;

        Holder(Sack sack) {
            this.sack = sack;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private Map<String, Long> counts(Player player) {
        return counts.computeIfAbsent(player.getUniqueId(), k -> {
            Map<String, Long> seeded = new HashMap<>();
            ITEMS.values().forEach(i -> seeded.put(i.id, i.seed));
            return seeded;
        });
    }

    public void openOverview(Player player) {
        Holder holder = new Holder(null);
        Inventory inv = Bukkit.createInventory(holder, 54, Txt.of("Sack of Sacks"));
        holder.inventory = inv;
        fill(inv);
        for (int i = 0; i < SACKS.size(); i++) {
            Sack sack = SACKS.get(i);
            JsonObject def = new JsonObject();
            def.addProperty("material", "PLAYER_HEAD");
            def.addProperty("name", sack.itemName);
            def.add("lore", lore("§7Holds various items! Items you", "§7pickup go directly into your sacks.", "",
                "§7Capacity: §e22,176 of each item", "§8Multiple sacks sum their capacity!", "",
                sack.title != null ? "§eClick to open sack!" : "§8(not modelled on the test server)"));
            inv.setItem(i, Items.build(sack.id, def, 1));
        }
        inv.setItem(48, button("ARROW", "§aGo Back", "§7To Your Bags"));
        inv.setItem(49, button("BARRIER", "§cClose"));
        inv.setItem(50, button("CHEST", "§aInsert inventory", "§7Inserts your inventory items into", "§7your sacks.", "", "§eClick to put items in!"));
        player.openInventory(inv);
    }

    public void openSack(Player player, Sack sack) {
        Holder holder = new Holder(sack);
        Inventory inv = Bukkit.createInventory(holder, sack.rows * 9, Txt.of(sack.title));
        holder.inventory = inv;
        fill(inv);
        Map<String, Long> have = counts(player);
        long total = 0;
        for (SackItem item : sack.items) {
            long count = have.getOrDefault(item.id, 0L);
            total += count;
            inv.setItem(item.slot, stack(sack, item, count));
        }
        inv.setItem(sack.backSlot(), button("ARROW", "§aGo Back", "§7To Sack of Sacks"));
        inv.setItem(sack.backSlot() + 1, button("BARRIER", "§cClose"));
        inv.setItem(sack.backSlot() + 2, button("CAULDRON", "§aPickup All", "§7Attempts to pickup all items in this", "§7sack.", "",
            String.format(Locale.ROOT, "§7Total items: §e%,d", total), "", "§eClick to pickup!"));
        player.openInventory(inv);
    }

    private static ItemStack stack(Sack sack, SackItem item, long count) {
        JsonObject def = new JsonObject();
        def.addProperty("material", item.material.toUpperCase(Locale.ROOT));
        def.addProperty("name", item.name);
        String stored = String.format(Locale.ROOT, "§7Stored: %s%,d§7/%s", count > 0 ? "§e" : "§8", count, CAPACITY);
        def.add("lore", count > 0
            ? lore("§8" + sack.title, "", stored, "", "§bRight-Click for stack!", "§eClick to pickup!")
            : lore("§8" + sack.title, "", stored, "", "§8Empty sack!"));
        return Items.build(item.id, def, 1);
    }

    /** Set a count without any chat, as when items move while the summary is disabled. */
    public void set(Player player, String id, long count) {
        long next = Math.max(0, count);
        counts(player).put(id, next);
        refreshOpen(player, id, next);
    }

    /**
     * Add (or with a negative amount remove) items and send the {@code [Sacks]} summary: "+N items" / "-N items"
     * parts whose hover lists {@code +n Name (Sack)} per item, as on Hypixel.
     */
    public String add(Player player, LinkedHashMap<String, Long> deltas, int windowSeconds) {
        return add(player, deltas, windowSeconds, true);
    }

    /** With {@code apply} false only the summary is sent: the delayed report of changes made earlier via {@link #set}. */
    public String add(Player player, LinkedHashMap<String, Long> deltas, int windowSeconds, boolean apply) {
        Map<String, Long> have = counts(player);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        long plus = 0;
        long minus = 0;
        for (Map.Entry<String, Long> e : deltas.entrySet()) {
            String id = e.getKey();
            if (apply) set(player, id, have.getOrDefault(id, 0L) + e.getValue());
            if (e.getValue() == 0) continue;
            SackItem item = ITEMS.get(id);
            Sack sack = SACK_OF.get(id);
            String name = item != null ? Txt.strip(item.name) : Txt.strip(Items.fallback(id).get("name").getAsString());
            String where = sack != null ? sack.title : "Unknown Sack";
            if (e.getValue() > 0) {
                added.add(String.format(Locale.ROOT, "§a  +%,d §e%s§8 (%s)", e.getValue(), name, where));
                plus += e.getValue();
            } else {
                removed.add(String.format(Locale.ROOT, "§c  %,d §e%s§8 (%s)", e.getValue(), name, where));
                minus -= e.getValue();
            }
        }
        if (added.isEmpty() && removed.isEmpty()) return "nothing changed";
        Component message = Txt.of("§6[Sacks] ");
        String footer = "\n\n§8This message can be disabled in the settings.";
        if (!added.isEmpty()) {
            message = message.append(Txt.of(String.format(Locale.ROOT, "§a+%,d§e items", plus))
                .hoverEvent(HoverEvent.showText(Txt.of("§aAdded items:\n" + String.join("\n", added) + footer))));
        }
        if (!removed.isEmpty()) {
            if (!added.isEmpty()) message = message.append(Txt.of("§e, "));
            message = message.append(Txt.of(String.format(Locale.ROOT, "§c-%,d§e items", minus))
                .hoverEvent(HoverEvent.showText(Txt.of("§cRemoved items:\n" + String.join("\n", removed) + footer))));
        }
        message = message.append(Txt.of("§e.§8 (Last " + windowSeconds + "s.)"));
        Nms.sendSystemChat(player, message, false);
        return "+" + plus + " / -" + minus + " sent";
    }

    /** Like Hypixel's live slot updates: an open sack menu shows the new count at once. */
    private void refreshOpen(Player player, String id, long count) {
        if (!(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder holder) || holder.sack == null) return;
        for (SackItem item : holder.sack.items) {
            if (item.id.equals(id)) holder.inventory.setItem(item.slot, stack(holder.sack, item, count));
        }
    }

    public long count(Player player, String id) {
        return counts(player).getOrDefault(id, 0L);
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (holder.sack == null) {
                if (slot == 49) player.closeInventory();
                else if (slot < SACKS.size() && SACKS.get(slot).title != null) openSack(player, SACKS.get(slot));
                return;
            }
            if (slot == holder.sack.backSlot()) openOverview(player);
            else if (slot == holder.sack.backSlot() + 1) player.closeInventory();
        });
    }

    @EventHandler(ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }

    private static void fill(Inventory inv) {
        ItemStack pane = button("BLACK_STAINED_GLASS_PANE", " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private static ItemStack button(String material, String name, String... lore) {
        JsonObject def = new JsonObject();
        def.addProperty("material", material);
        def.addProperty("name", name);
        if (lore.length > 0) def.add("lore", lore(lore));
        return Items.build(null, def, 1);
    }

    private static JsonArray lore(String... lines) {
        JsonArray out = new JsonArray();
        for (String line : lines) out.add(line);
        return out;
    }
}
