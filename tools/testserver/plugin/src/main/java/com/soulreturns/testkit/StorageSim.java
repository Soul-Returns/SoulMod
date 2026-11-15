package com.soulreturns.testkit;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Native storage transfers with a separate navigation row, not a cancelled mock inventory. */
public final class StorageSim implements Listener {
    private final SoulTestKit plugin;
    private final Map<UUID, State> states = new HashMap<>();

    StorageSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    private static final class Holder implements InventoryHolder {
        final int page;
        final boolean backpack;
        Inventory inventory;

        Holder(int page, boolean backpack) {
            this.page = page;
            this.backpack = backpack;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class State {
        final Map<Integer, Inventory> ender = new HashMap<>();
        final Map<Integer, Inventory> backpacks = new HashMap<>();
        final Map<Integer, ItemStack> icons = new HashMap<>();
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> {
            State state = new State();
            for (int i = 1; i <= 9; i++) state.ender.put(i, page(i, false));
            state.ender.get(1).setItem(9, item("diamond", "§bStorage test diamonds", 24, "§7Searchable fixture lore"));
            state.ender.get(1).setItem(17, item("emerald", "§aStorage test emeralds", 12));
            state.ender.get(2).setItem(13, item("amethyst_shard", "§dStorage test crystal", 3));
            state.backpacks.put(1, page(1, true));
            state.backpacks.get(1).setItem(9, item("gold_ingot", "§6Storage test gold", 8));
            state.icons.put(1, backpack());
            return state;
        });
    }

    private static ItemStack item(String id, String name, int count, String... lore) {
        var type = Registry.ITEM.get(NamespacedKey.minecraft(id));
        if (type == null) throw new IllegalArgumentException("Unknown storage fixture item " + id);
        var stack = type.createItemStack(count);
        stack.editMeta(meta -> {
            meta.customName(Txt.item(name));
            if (lore.length > 0) meta.lore(List.of(lore).stream().map(Txt::item).toList());
        });
        return stack;
    }

    private static ItemStack backpack() {
        var definition = new JsonObject();
        definition.addProperty("material", "PLAYER_HEAD");
        definition.addProperty("name", "§5Greater Backpack");
        definition.addProperty("unique", true);
        return Items.build("GREATER_BACKPACK", definition, 1);
    }

    private static Inventory page(int number, boolean backpack) {
        var holder = new Holder(number, backpack);
        var title = backpack ? "Greater Backpack (Slot #" + number + ")" : "Ender Chest (" + number + "/9)";
        holder.inventory = Bukkit.createInventory(holder, backpack ? 45 : 54, Txt.of(title));
        for (int i = 0; i < 9; i++) holder.inventory.setItem(i, item("black_stained_glass_pane", " ", 1));
        holder.inventory.setItem(0, item("barrier", "§cClose", 1));
        holder.inventory.setItem(1, item("arrow", "§eBack", 1, "§7To Storage"));
        if (!backpack) {
            if (number > 1) holder.inventory.setItem(6, item("arrow", "§aPrevious Page", 1));
            if (number < 9) holder.inventory.setItem(7, item("arrow", "§aNext Page", 1));
        }
        return holder.inventory;
    }

    public void openOverview(Player player) {
        var state = state(player);
        var holder = new Holder(0, false);
        holder.inventory = Bukkit.createInventory(holder, 54, Txt.of("Storage"));
        for (int i = 0; i < 54; i++) holder.inventory.setItem(i, item("black_stained_glass_pane", " ", 1));
        holder.inventory.setItem(4, item("ender_chest", "§aEnder Chest", 1));
        holder.inventory.setItem(22, item("chest", "§aBackpacks", 1));
        for (int i = 1; i <= 9; i++) holder.inventory.setItem(8 + i,
            item("chest", "§aEnder Chest Page " + i, 1, "§7Also accessible via /enderchest " + i, "§eClick to view!"));
        for (int i = 1; i <= 18; i++) {
            ItemStack icon = state.icons.get(i);
            if (icon != null) {
                icon = icon.clone();
                int number = i;
                icon.editMeta(meta -> {
                    meta.customName(Txt.item("§aBackpack Slot " + number));
                    meta.lore(List.of(
                        Txt.item("§5Greater Backpack"),
                        Txt.item("§7This backpack has 36 slots."),
                        Txt.item("§7Also accessible via /backpack " + number),
                        Txt.item("§eClick to open!"),
                        Txt.item("§bRight-click to remove!")));
                });
            }
            holder.inventory.setItem(26 + i, icon != null ? icon
                : item(i <= 3 ? "gray_stained_glass_pane" : "red_stained_glass_pane",
                    i <= 3 ? "§7Empty Backpack Slot " + i : "§cLocked Backpack Slot " + i, 1,
                    i <= 3 ? "§eClick a backpack to add it!" : "§7Talk to Tia the Fairy!"));
        }
        player.openInventory(holder.inventory);
    }

    private void open(Player player, boolean backpack, int number) {
        var state = state(player);
        Inventory inventory = (backpack ? state.backpacks : state.ender).get(number);
        if (inventory != null) player.openInventory(inventory);
    }

    @EventHandler
    public void command(PlayerCommandPreprocessEvent event) {
        String[] args = event.getMessage().split("\\s+");
        if (!args[0].equalsIgnoreCase("/storage") && !args[0].equalsIgnoreCase("/enderchest")
            && !args[0].equalsIgnoreCase("/backpack")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.getItemOnCursor().isEmpty()) return;
        if (args[0].equalsIgnoreCase("/storage")) openOverview(player);
        else {
            int number;
            try { number = args.length > 1 ? Integer.parseInt(args[1]) : 1; }
            catch (NumberFormatException ignored) { return; }
            boolean backpack = args[0].equalsIgnoreCase("/backpack");
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> open(player, backpack, number), 3L);
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) return;
        int slot = event.getRawSlot();
        if (holder.page != 0 && (slot >= 9 || slot < 0)) return;
        if (holder.page == 0 && slot >= holder.inventory.getSize()) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (holder.page == 0 && slot >= 27 && slot <= 44) {
            int number = slot - 26;
            if (number > 3) return;
            var state = state(player);
            if (event.isRightClick() && state.icons.containsKey(number) && player.getItemOnCursor().isEmpty()) {
                var inventory = state.backpacks.get(number);
                for (int i = 9; i < inventory.getSize(); i++) {
                    var stack = inventory.getItem(i);
                    if (stack != null && !stack.isEmpty()) return;
                }
                player.setItemOnCursor(state.icons.remove(number));
                state.backpacks.remove(number);
                holder.inventory.setItem(slot, item("gray_stained_glass_pane", "§7Empty Backpack Slot " + number, 1, "§eClick a backpack to add it!"));
            } else if (!state.icons.containsKey(number) && !player.getItemOnCursor().isEmpty()) {
                var cursor = player.getItemOnCursor();
                var name = cursor.getItemMeta().customName();
                if (name == null || !PlainTextComponentSerializer.plainText().serialize(name).contains("Backpack")) return;
                state.icons.put(number, cursor.clone());
                state.backpacks.put(number, page(number, true));
                player.setItemOnCursor(null);
                openOverview(player);
            } else if (player.getItemOnCursor().isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> open(player, true, number));
            }
            return;
        }
        if (!player.getItemOnCursor().isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (holder.page == 0 && slot >= 9 && slot <= 17) open(player, false, slot - 8);
            else if (slot == 0) player.closeInventory();
            else if (slot == 1) openOverview(player);
            else if (!holder.backpack && slot == 6 && holder.page > 1) open(player, false, holder.page - 1);
            else if (!holder.backpack && slot == 7 && holder.page < 9) open(player, false, holder.page + 1);
        });
    }

    @EventHandler(ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) return;
        int firstItem = holder.page == 0 ? holder.inventory.getSize() : 9;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < firstItem)) event.setCancelled(true);
    }
}
