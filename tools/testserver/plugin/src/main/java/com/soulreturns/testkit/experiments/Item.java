package com.soulreturns.testkit.experiments;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A menu item in the dev bridge's form ({@code docs/research/raw/experiments-2026-11-17}, the fixtures under
 * {@code src/test/resources/experiments}): id with namespace, count, name and lore as the bridge renders them
 * (a {@code §r} before every styled run), and only the components the captures show on Hypixel's items.
 * {@code glint} is the enchantment_glint_override component (null = not set), {@code texture} the head skin's
 * hash, {@code dye} {@code #rrggbb}, {@code customData} SNBT.
 */
public record Item(String id, int count, String name, List<String> lore, String texture, String dye, Boolean glint,
                   String tooltipStyle, String model, String customData) {
    public Item {
        lore = List.copyOf(lore);
    }

    public static Item of(String id, int count, String name, String... lore) {
        return new Item(id.contains(":") ? id : "minecraft:" + id, count, name, List.of(lore), null, null, null, null, null, null);
    }

    public static Item of(String id, String name, String... lore) {
        return of(id, 1, name, lore);
    }

    public static Item pane(String color) {
        return of(color + "_stained_glass_pane", "");
    }

    public static Item head(String texture, String name, List<String> lore) {
        return new Item("minecraft:player_head", 1, name, lore, texture, null, null, null, null, null);
    }

    /** The bridge's item JSON ({id, count, name, lore, custom_data?, components?{texture, dye, glint_override, tooltip_style, model}}). */
    public static Item fromBridge(JsonObject o) {
        List<String> lore = new ArrayList<>();
        if (o.has("lore")) for (JsonElement line : o.getAsJsonArray("lore")) lore.add(line.getAsString());
        JsonObject c = o.has("components") ? o.getAsJsonObject("components") : new JsonObject();
        return new Item(o.get("id").getAsString(), o.get("count").getAsInt(), o.get("name").getAsString(), lore,
            str(c, "texture"), str(c, "dye"), c.has("glint_override") ? c.get("glint_override").getAsBoolean() : null,
            str(c, "tooltip_style"), str(c, "model"), str(o, "custom_data"));
    }

    public JsonObject toBridge() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("count", count);
        o.addProperty("name", name);
        JsonArray l = new JsonArray();
        lore.forEach(l::add);
        o.add("lore", l);
        if (customData != null) o.addProperty("custom_data", customData);
        JsonObject c = new JsonObject();
        if (texture != null) c.addProperty("texture", texture);
        if (dye != null) c.addProperty("dye", dye);
        if (glint != null) c.addProperty("glint_override", glint);
        if (tooltipStyle != null) c.addProperty("tooltip_style", tooltipStyle);
        if (model != null) c.addProperty("model", model);
        if (!c.isEmpty()) o.add("components", c);
        return o;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    public Item withGlint(Boolean on) {
        return new Item(id, count, name, lore, texture, dye, on, tooltipStyle, model, customData);
    }

    public Item withLore(List<String> newLore) {
        return new Item(id, count, name, newLore, texture, dye, glint, tooltipStyle, model, customData);
    }

    public Item withCount(int n) {
        return new Item(id, n, name, lore, texture, dye, glint, tooltipStyle, model, customData);
    }

    public Item withName(String n) {
        return new Item(id, count, n, lore, texture, dye, glint, tooltipStyle, model, customData);
    }

    /** The id without namespace. */
    public String path() {
        return id.substring(id.indexOf(':') + 1);
    }

    public String plainName() {
        return strip(name);
    }

    public List<String> plainLore() {
        return lore.stream().map(Item::strip).toList();
    }

    /** Superpairs card identity (research §4.4): id + count + name + lore without "Click any to proceed!" + head texture. */
    public String cardKey() {
        List<String> l = new ArrayList<>(plainLore());
        if (!l.isEmpty() && l.get(l.size() - 1).equals("Click any to proceed!")) {
            l.remove(l.size() - 1);
            if (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
        }
        return id + "#" + count + "#" + plainName() + "#" + String.join("|", l) + "#" + Objects.requireNonNullElse(texture, "") + "#" + Objects.requireNonNullElse(dye, "");
    }

    public static String strip(String s) {
        return s == null ? "" : s.replaceAll("§.", "");
    }

    @Override
    public String toString() {
        return path() + (count != 1 ? " x" + count : "") + " '" + plainName() + "'" + (Boolean.TRUE.equals(glint) ? "*" : "");
    }
}
