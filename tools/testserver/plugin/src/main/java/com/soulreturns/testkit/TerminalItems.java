package com.soulreturns.testkit;

import com.soulreturns.testkit.terminal.Cell;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponents;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

/**
 * Terminal cells as item stacks, and back for the checks. Only what the capture lists is set: custom name, lore
 * and, on a selected item, the enchantment glint override. No hidden-tooltip flags, no custom_data.
 */
final class TerminalItems {
    private TerminalItems() {}

    static ItemStack stack(Cell cell) {
        ItemType type = Registry.ITEM.get(NamespacedKey.minecraft(cell.id()));
        if (type == null) throw new IllegalArgumentException("unknown item id minecraft:" + cell.id());
        ItemStack item = type.createItemStack(cell.count());
        item.editMeta(meta -> {
            meta.customName(Txt.item(Terminals.wire(cell.name())));
            if (!cell.lore().isEmpty()) meta.lore(cell.lore().stream().map(l -> Txt.item(Terminals.wire(l))).toList());
            if (cell.selected() && !cell.intrinsicGlint()) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    /** What a client would see differing from {@code cell}; empty when the stack is exactly that cell. */
    static List<String> mismatches(Cell cell, ItemStack item) {
        List<String> out = new ArrayList<>();
        String id = item == null ? "air" : item.getType().getKey().getKey();
        if (!id.equals(cell.id())) out.add("id " + id + " != " + cell.id());
        if (item == null) return out;
        if (item.getAmount() != cell.count()) out.add("count " + item.getAmount() + " != " + cell.count());
        Component name = item.getItemMeta().customName();
        if (name == null || !name.equals(Txt.item(Terminals.wire(cell.name())))) out.add("name " + name + " != " + cell.name());
        List<Component> lore = item.lore() == null ? List.of() : item.lore();
        if (!lore.equals(cell.lore().stream().map(l -> Txt.item(Terminals.wire(l))).toList())) out.add("lore " + lore + " != " + cell.lore());
        var nms = CraftItemStack.asNMSCopy(item);
        if (nms.hasFoil() != cell.glows()) out.add("glint " + nms.hasFoil() + " != " + cell.glows());
        boolean glintPatch = nms.getComponentsPatch().entrySet().stream().anyMatch(e -> e.getKey() == DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        if (glintPatch != (cell.selected() && !cell.intrinsicGlint())) out.add("glint override patch " + glintPatch);
        return out;
    }
}
