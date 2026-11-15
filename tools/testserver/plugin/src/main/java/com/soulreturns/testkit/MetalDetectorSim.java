package com.soulreturns.testkit;

import org.bukkit.Material;
import org.bukkit.metadata.FixedMetadataValue;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Mines of Divan Metal Detector, as measured on Hypixel (2026-11-07): while the detector is held,
 * every 0.5 s a BLOCKS-source {@code block.note_block.harp} at the player's position, then the
 * {@code TREASURE: X.Xm} action bar with the distance to the integer corner (chestX, chestY + 1,
 * chestZ), rounded to 0.1. Four {@code Keeper of <X>} stands mark the center like the real ones
 * (each with a second unnamed stand 0.25 lower), plus unnamed decoy stands.
 */
public final class MetalDetectorSim {
    static final String DETECTOR = "DWARVEN_METAL_DETECTOR";

    /** Same table as the mod's MetalDetectorSolver.OFFSETS (Skyblocker's 42 chest spots). */
    static final int[][] OFFSETS = {
        {-38, -22, 26}, {38, -22, -26}, {-40, -22, 18}, {-41, -20, 22}, {-5, -21, 16}, {40, -22, -30}, {-42, -20, -28},
        {-43, -22, -40}, {42, -19, -41}, {43, -21, -16}, {-1, -22, -20}, {6, -21, 28}, {7, -21, 11}, {7, -21, 22},
        {-12, -21, -44}, {12, -22, 31}, {12, -22, -22}, {12, -21, 7}, {12, -21, -43}, {-14, -21, 43}, {-14, -21, 22},
        {-17, -21, 20}, {-20, -22, 0}, {1, -21, 20}, {19, -22, 29}, {20, -22, 0}, {20, -21, -26}, {-23, -22, 40},
        {22, -21, -14}, {-24, -22, 12}, {23, -22, 26}, {23, -22, -39}, {24, -22, 27}, {25, -22, 17}, {29, -21, -44},
        {-31, -21, -12}, {-31, -21, -40}, {30, -21, -25}, {-32, -21, -40}, {-36, -20, 42}, {-37, -21, -14}, {-37, -21, -22},
    };

    /** Keeper name -> its block position relative to the center (the mod adds the inverse). */
    private static final Map<String, int[]> KEEPERS = Map.of(
        "Diamond", new int[] {-33, 0, -3},
        "Lapis", new int[] {33, 0, 3},
        "Emerald", new int[] {3, 0, -33},
        "Gold", new int[] {-3, 0, 33});

    /** Raised so the chests (center y - 19..22) land around the player's feet in the flat world. */
    private static final int CENTER_RISE = 21;

    /** Unnamed armor stands around the center, so the mod's layout matcher has decoys to reject. */
    private static final double[][] DECOYS = {{-12.5, 0, 7.5}, {20.5, 0, -14.5}, {-33.5, 0, 30.5}, {5.5, -3, 5.5}};

    /** [keepers] carry the names; [props] are the second stand per keeper (0.25 lower, like Hypixel's) and decoys. */
    private record Session(int[] center, int[] chest, List<ArmorStand> keepers, List<ArmorStand> props, BukkitTask task) {}

    private final SoulTestKit plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean keepersNamed = true;

    public MetalDetectorSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    /** Returns a one-line description for the command sender. */
    public String start(Player player, int index) {
        if (index < 0 || index >= OFFSETS.length) throw new IllegalArgumentException("offset index 0.." + (OFFSETS.length - 1));
        return start(player, OFFSETS[index], "#" + index);
    }

    /** A chest at any offset from the center, e.g. one missing from the mod's list (free search). */
    public String startAt(Player player, int dx, int dy, int dz) {
        return start(player, new int[] {dx, dy, dz}, String.format(Locale.ROOT, "offset (%d, %d, %d)", dx, dy, dz));
    }

    private String start(Player player, int[] off, String label) {
        stop(player);
        plugin.applyPreset(player, "divan");
        plugin.clearTab(player);
        plugin.clearActionbar(player);

        Location at = player.getLocation();
        int[] center = {at.getBlockX(), at.getBlockY() + CENTER_RISE, at.getBlockZ()};
        int[] chest = {center[0] + off[0], center[1] + off[1], center[2] + off[2]};

        List<ArmorStand> keepers = new ArrayList<>();
        List<ArmorStand> props = new ArrayList<>();
        KEEPERS.forEach((name, rel) -> {
            Location loc = new Location(player.getWorld(), center[0] + rel[0] + 0.5, center[1] + rel[1], center[2] + rel[2] + 0.5);
            keepers.add(player.getWorld().spawn(loc, ArmorStand.class, s -> {
                s.setGravity(false);
                s.setCustomNameVisible(true);
                s.setPersistent(false);
                s.setMetadata("soulKeeper", new FixedMetadataValue(plugin, name));
                if (keepersNamed) s.customName(Txt.of("§6Keeper of " + name));
            }));
            props.add(prop(loc.clone().subtract(0, 0.25, 0)));
        });
        for (double[] d : DECOYS) props.add(prop(new Location(player.getWorld(), center[0] + d[0], center[1] + d[1], center[2] + d[2])));
        if (!holding(player)) {
            JsonElement def = plugin.presets().get("items", DETECTOR);
            ItemStack detector = Items.build(DETECTOR, def != null ? def.getAsJsonObject() : Items.fallback(DETECTOR), 1);
            if (player.getInventory().getItemInMainHand().getType().isAir()) player.getInventory().setItemInMainHand(detector);
            else player.getInventory().addItem(detector);
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> ping(player, chest), 10L, 10L);
        sessions.put(player.getUniqueId(), new Session(center, chest, keepers, props, task));
        return String.format(Locale.ROOT, "metal detector: center %d %d %d, chest %s at %d %d %d",
            center[0], center[1], center[2], label, chest[0], chest[1], chest[2]);
    }

    public boolean stop(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) return false;
        s.task.cancel();
        s.keepers.forEach(ArmorStand::remove);
        s.props.forEach(ArmorStand::remove);
        return true;
    }

    private ArmorStand prop(Location loc) {
        return loc.getWorld().spawn(loc, ArmorStand.class, s -> {
            s.setGravity(false);
            s.setPersistent(false);
        });
    }

    /** The found line, preceded by the chest block_update Hypixel sends at the treasure while you dig. */
    public void found(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) {
            player.sendBlockChange(new Location(player.getWorld(), s.chest[0], s.chest[1], s.chest[2]), Material.CHEST.createBlockData());
        }
        loot(player, "emerald");
    }

    /**
     * Hypixel only names the keeper stands while the player is near (data id 2 set, then back to
     * null). {@code off} clears the names (now and for later starts) so the mod has to solve without
     * a center; {@code on} names them again.
     */
    public void keepers(Player player, boolean named) {
        keepersNamed = named;
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;
        for (ArmorStand stand : s.keepers) {
            stand.customName(named ? Txt.of("§6Keeper of " + stand.getMetadata("soulKeeper").get(0).asString()) : null);
        }
    }

    /** Loot chat lines in Hypixel's captured format; the powder line's amount format is a guess. */
    static final Map<String, String> LOOT = Map.of(
        "lapis", "§r§aYou found §r§cScavenged Lapis Sword §r§awith your §r§cMetal Detector§r§a!",
        "golden", "§r§aYou found §r§cScavenged Golden Hammer §r§awith your §r§cMetal Detector§r§a!",
        "diamond", "§r§aYou found §r§cScavenged Diamond Axe §r§awith your §r§cMetal Detector§r§a!",
        "emerald", "§r§aYou found §r§cScavenged Emerald Hammer §r§awith your §r§cMetal Detector§r§a!",
        "gem", "§r§aYou found §r§a\uE003 Flawed Sapphire Gemstone §r§awith your §r§cMetal Detector§r§a!",
        "gem2", "§r§aYou found §r§a\uE010 Flawed Ruby Gemstone §r§8x2 §r§awith your §r§cMetal Detector§r§a!",
        "powder", "§r§aYou found §r§d+1,250 Gemstone Powder §r§awith your §r§cMetal Detector§r§a!");

    static final Map<String, String> TOOLS = Map.of(
        "lapis", "DWARVEN_LAPIS_SWORD", "golden", "DWARVEN_GOLD_HAMMER",
        "diamond", "DWARVEN_DIAMOND_AXE", "emerald", "DWARVEN_EMERALD_HAMMER");

    public void loot(Player player, String kind) {
        String line = LOOT.get(kind);
        if (line == null) throw new IllegalArgumentException("loot <" + String.join("|", LOOT.keySet()) + ">");
        Nms.sendSystemChat(player, Txt.of(line), false);
    }

    /** Gives (or takes) the four nucleus tools, or one of them by short name. */
    public void tools(Player player, String which, boolean give) {
        List<String> ids = which.equals("all") ? List.copyOf(TOOLS.values()) : List.of(TOOLS.getOrDefault(which, ""));
        if (ids.contains("")) throw new IllegalArgumentException("tool <all|" + String.join("|", TOOLS.keySet()) + ">");
        for (String id : ids) {
            if (give) {
                JsonElement def = plugin.presets().get("items", id);
                player.getInventory().addItem(Items.build(id, def != null ? def.getAsJsonObject() : Items.fallback(id), 1));
            } else {
                ItemStack[] contents = player.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] != null && Nms.customDataSnbt(contents[i]).contains("\"" + id + "\"")) player.getInventory().setItem(i, null);
                }
            }
        }
    }

    private static boolean holding(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        return Nms.customDataSnbt(held).contains("\"" + DETECTOR + "\"");
    }

    private void ping(Player player, int[] chest) {
        if (!player.isOnline()) {
            stop(player);
            return;
        }
        if (!holding(player)) return;
        Location at = player.getLocation();
        double dx = at.getX() - chest[0];
        double dy = at.getY() - (chest[1] + 1);
        double dz = at.getZ() - chest[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float pitch = (float) (dist >= 45 ? 1.0 : 1.0 + Math.min(1.0, (45 - dist) / 40) * 0.984);
        player.playSound(at, Sound.BLOCK_NOTE_BLOCK_HARP, SoundCategory.BLOCKS, 1f, pitch);
        String shown = String.format(Locale.ROOT, "%.1f", Math.round(dist * 10) / 10.0);
        // Hypixel's bar follows the harp by up to a few hundred ms.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                Nms.sendSystemChat(player, Txt.of("§r§66,357/6,178❤     §a605❈ Defense     §3§lTREASURE: §b" + shown + "m"), true);
            }
        }, 2L);
    }
}
