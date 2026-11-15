package com.soulreturns.testkit;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * SkyBlock items from item definitions (presets.json "items" or inline in a menu slot):
 * <pre>
 * { "material": "IRON_SWORD", "name": "§dHyperion", "lore": [...], "id": "HYPERION",
 *   "unique": true, "glint": true, "color": "#E7413C", "skullTexture": "&lt;base64&gt;",
 *   "customData": {...}, "petInfo": {...}, "action": "open:pets" | "close" }
 * </pre>
 * custom_data becomes {@code {id, <customData>, uuid, timestamp, petInfo}}: uuid and timestamp only for
 * "unique" (unstackable) items, petInfo as a JSON string with {uuid} replaced. A definition without
 * "id" and without the item key (menu buttons) gets no custom_data at all, like Hypixel's GUI items.
 */
public final class Items {
    private static final Gson GSON = new Gson();

    private Items() {}

    public static ItemStack build(String id, JsonObject def, int count) {
        Material material = Material.matchMaterial(str(def, "material", "PAPER"));
        if (material == null || !material.isItem()) material = Material.PAPER;
        ItemStack item = new ItemStack(material, Math.max(1, count));
        ItemMeta meta = item.getItemMeta();
        if (def.has("name")) meta.customName(Txt.item(def.get("name").getAsString()));
        if (def.has("lore")) {
            List<Component> lore = new ArrayList<>();
            def.getAsJsonArray("lore").forEach(line -> lore.add(Txt.item(line.getAsString())));
            meta.lore(lore);
        }
        if (def.has("glint")) meta.setEnchantmentGlintOverride(def.get("glint").getAsBoolean());
        if (def.has("color") && meta instanceof LeatherArmorMeta leather) {
            leather.setColor(Color.fromRGB(Integer.parseInt(def.get("color").getAsString().replace("#", ""), 16)));
        }
        if (def.has("skullTexture") && meta instanceof SkullMeta skull) {
            var profile = Bukkit.createProfile(UUID.randomUUID());
            profile.setProperty(new ProfileProperty("textures", def.get("skullTexture").getAsString()));
            skull.setPlayerProfile(profile);
        }
        item.setItemMeta(meta);
        // Hypixel shows its own stat lines; vanilla's attribute and dye tooltips never appear there.
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
            .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.DYED_COLOR).build());

        String skyblockId = str(def, "id", id);
        if (skyblockId == null) return item;
        JsonObject data = new JsonObject();
        data.addProperty("id", skyblockId);
        if (def.has("customData")) {
            for (Map.Entry<String, JsonElement> e : def.getAsJsonObject("customData").entrySet()) data.add(e.getKey(), e.getValue());
        }
        String uuid = UUID.randomUUID().toString();
        if (def.has("unique") && def.get("unique").getAsBoolean()) {
            data.addProperty("uuid", uuid);
            data.add("timestamp", new JsonPrimitive(System.currentTimeMillis()));
        }
        if (def.has("petInfo")) {
            data.addProperty("petInfo", GSON.toJson(def.get("petInfo")).replace("{uuid}", uuid));
        }
        return Nms.withCustomData(item, data);
    }

    /** `hx give` for an id without a definition: a COMMON item named after the id. */
    public static JsonObject fallback(String id) {
        JsonObject def = new JsonObject();
        Material guess = Material.matchMaterial(id);
        def.addProperty("material", guess != null && guess.isItem() ? guess.name() : "PAPER");
        StringBuilder name = new StringBuilder("§f");
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (name.length() > 2) name.append(' ');
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        def.addProperty("name", name.toString());
        var lore = new com.google.gson.JsonArray();
        lore.add("§f§lCOMMON");
        def.add("lore", lore);
        return def;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
}
