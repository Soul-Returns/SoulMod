package com.soulreturns.testkit.terminal;

import java.util.List;

/**
 * One container slot of a terminal as Hypixel sends it: registry id without namespace, the literal name
 * (legacy codes included, {@code ""} for fillers), count and lore. {@code selected} is the captured
 * enchantment-glint-override patch that an accepted starts-with / select click adds.
 */
public record Cell(String id, String name, int count, List<String> lore, boolean selected) {
    public static final Cell FILLER = new Cell("black_stained_glass_pane", "", 1, List.of(), false);

    public static Cell of(String id, String name) {
        return new Cell(id, name, 1, List.of(), false);
    }

    public static Cell of(String id, String name, int count) {
        return new Cell(id, name, count, List.of(), false);
    }

    public Cell withSelected(boolean value) {
        return new Cell(id, name, count, lore, value);
    }

    /** Items that already glow by themselves (captured: Enchanted Book, Bottle o' Enchanting). */
    public boolean intrinsicGlint() {
        return id.equals("enchanted_book") || id.equals("experience_bottle");
    }

    /** The glint a client shows: intrinsic or the override of a selected item. */
    public boolean glows() {
        return selected || intrinsicGlint();
    }

    /** Name without legacy color codes. */
    public String plainName() {
        return name.replaceAll("§.", "");
    }
}
