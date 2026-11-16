package com.soulreturns.testkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
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
 * Chest GUIs from presets.json "menus": {title, rows, filler, slots: {"13": item id | inline definition}}.
 * Every click and drag is cancelled; a slot's "action" ("open:&lt;menu&gt;" or "close") runs on click.
 */
public final class Menus implements Listener {
    private final SoulTestKit plugin;

    public Menus(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    static final class Holder implements InventoryHolder {
        final String name;
        final Map<Integer, String> actions = new HashMap<>();
        Inventory inventory;

        Holder(String name) {
            this.name = name;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** @return false when no menu has that name. */
    public boolean open(Player player, String name) {
        if (name.equals("storage")) {
            plugin.storage().openOverview(player);
            return true;
        }
        if (name.equals("forge")) {
            plugin.forge().open(player);
            return true;
        }
        if (name.equals("hotm")) {
            plugin.forge().openHotm(player);
            return true;
        }
        JsonElement element = plugin.presets().get("menus", name);
        if (element == null) return false;
        JsonObject menu = element.getAsJsonObject();
        int rows = menu.has("rows") ? Math.clamp(menu.get("rows").getAsInt(), 1, 6) : 6;
        Holder holder = new Holder(name);
        Inventory inventory = Bukkit.createInventory(holder, rows * 9, Txt.of(menu.get("title").getAsString()));
        holder.inventory = inventory;
        if (menu.has("filler")) {
            JsonObject filler = new JsonObject();
            filler.addProperty("material", menu.get("filler").getAsString());
            filler.addProperty("name", " ");
            ItemStack pane = Items.build(null, filler, 1);
            for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, pane);
        }
        if (menu.has("slots")) {
            for (Map.Entry<String, JsonElement> e : menu.getAsJsonObject("slots").entrySet()) {
                int slot = Integer.parseInt(e.getKey());
                if (slot < 0 || slot >= inventory.getSize()) continue;
                ItemStack item;
                if (e.getValue().isJsonPrimitive()) {
                    String id = e.getValue().getAsString();
                    JsonElement def = plugin.presets().get("items", id);
                    item = Items.build(id, def != null ? def.getAsJsonObject() : Items.fallback(id), 1);
                } else {
                    JsonObject def = e.getValue().getAsJsonObject();
                    item = Items.build(null, def, def.has("count") ? def.get("count").getAsInt() : 1);
                    if (def.has("action")) holder.actions.put(slot, def.get("action").getAsString());
                }
                inventory.setItem(slot, item);
            }
        }
        player.openInventory(inventory);
        return true;
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        String action = holder.actions.get(event.getRawSlot());
        if (action == null || !(event.getWhoClicked() instanceof Player player)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (action.equals("close")) player.closeInventory();
            else if (action.startsWith("open:")) open(player, action.substring(5));
            // Hypixel's page switch: close the menu, then open the next one a tick later.
            else if (action.startsWith("swap:")) {
                player.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> open(player, action.substring(5)));
            }
        });
    }

    @EventHandler(ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }
}
