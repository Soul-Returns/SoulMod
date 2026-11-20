package com.soulreturns.data.hypixel

import com.mojang.serialization.Dynamic
import com.soulreturns.util.SoulLogger
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.resources.Identifier
import net.minecraft.util.datafix.DataFixers
import net.minecraft.util.datafix.fixes.References
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.TooltipDisplay
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Decodes Hypixel's item blobs (`inventory.equipment_contents.data`, `inv_contents`, AH
 * `item_bytes`): base64 of a gzipped NBT `{i: [item…]}` in the **1.8** item format (numeric `id`,
 * `Damage`, `tag.display`, `tag.ExtraAttributes`, `tag.SkullOwner`), plus a `components` compound
 * Hypixel adds with modern `minecraft:item_model` / `minecraft:tooltip_style` values.
 *
 * Vanilla's own data fixer upgrades the 1.8 part from data version 99, the oldest schema it knows
 * (numeric ids, flattening, legacy names/lore into text, skull owner into a profile). The two
 * modern components are put back afterwards, and `custom_data` is reduced to the `ExtraAttributes`
 * contents, which is how Hypixel's live items carry their SkyBlock id. Works off the client thread.
 */
object LegacyItemDecoder {
    private val logger = SoulLogger("Soul/LegacyItems")

    private const val LEGACY_DATA_VERSION = 99

    /** 1.8 `HideFlags` bit for attribute modifiers. */
    private const val HIDE_MODIFIERS = 2

    /**
     * Numeric ids added in 1.8, which vanilla's id fix (written for 1.7 saves; 1.8 already saved names) does
     * not know, so they would upgrade to air. Hypixel's blobs use them: drills are prismarine shards, Mithril
     * is prismarine crystals, the Lapis Armor helmet a sea lantern. Block-only ids are left out.
     */
    private val V18_IDS = mapOf(
        165 to "slime", 166 to "barrier", 167 to "iron_trapdoor", 168 to "prismarine", 169 to "sea_lantern",
        179 to "red_sandstone", 180 to "red_sandstone_stairs", 182 to "stone_slab2",
        183 to "spruce_fence_gate", 184 to "birch_fence_gate", 185 to "jungle_fence_gate", 186 to "dark_oak_fence_gate", 187 to "acacia_fence_gate",
        188 to "spruce_fence", 189 to "birch_fence", 190 to "jungle_fence", 191 to "dark_oak_fence", 192 to "acacia_fence",
        409 to "prismarine_shard", 410 to "prismarine_crystals", 411 to "rabbit", 412 to "cooked_rabbit", 413 to "rabbit_stew",
        414 to "rabbit_foot", 415 to "rabbit_hide", 416 to "armor_stand", 423 to "mutton", 424 to "cooked_mutton", 425 to "banner",
        427 to "spruce_door", 428 to "birch_door", 429 to "jungle_door", 430 to "acacia_door", 431 to "dark_oak_door",
    )

    /** One stack per slot, [ItemStack.EMPTY] for empty or undecodable ones; empty list on a bad blob. */
    fun decode(base64: String): List<ItemStack> = decodeSlots(base64)?.map { it ?: ItemStack.EMPTY } ?: emptyList()

    /** Null if the blob or any occupied slot cannot be decoded; never mistakes a failed item for air. */
    fun decodeComplete(base64: String): List<ItemStack>? = decodeSlots(base64)?.let { slots ->
        if (slots.any { it == null }) null else slots.map { it!! }
    }

    /** One stack per slot, null for an occupied slot that cannot be decoded (never air); null on a bad blob. */
    fun decodeSlots(base64: String): List<ItemStack?>? {
        val list = try {
            NbtIo.readCompressed(ByteArrayInputStream(Base64.getDecoder().decode(base64)), NbtAccounter.unlimitedHeap())
                .getListOrEmpty("i")
        } catch (e: Exception) {
            logger.warn("Could not read an item blob: ${e.message}")
            return null
        }
        return List(list.size) { index ->
            if (list[index] !is CompoundTag) null else {
                val legacy = list.getCompoundOrEmpty(index)
                val stack = fix(legacy)
                if (stack.isEmpty && !legacy.isEmpty && legacy.getShortOr("id", -1) != 0.toShort()) null else stack
            }
        }
    }

    private fun fix(legacy: CompoundTag): ItemStack {
        if (legacy.isEmpty || legacy.getShortOr("id", 0) == 0.toShort()) return ItemStack.EMPTY
        val item = legacy.copy()
        V18_IDS[legacy.getShortOr("id", 0).toInt()]?.let { item.putString("id", "minecraft:$it") }
        val modern = item.remove("components") as? CompoundTag
        val extra = item.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes")
        val ops = Minecraft.getInstance().connection?.registryAccess()?.createSerializationContext(NbtOps.INSTANCE) ?: NbtOps.INSTANCE
        val stack = try {
            val fixed = DataFixers.getDataFixer().update(
                References.ITEM_STACK,
                Dynamic<Tag>(NbtOps.INSTANCE, item),
                LEGACY_DATA_VERSION,
                SharedConstants.getCurrentVersion().dataVersion().version(),
            )
            ItemStack.CODEC.parse(ops, fixed.value).resultOrPartial { logger.debug("Item fix: $it") }.orElse(ItemStack.EMPTY)
        } catch (e: Exception) {
            logger.warn("Could not upgrade ${extra.getStringOr("id", "?")}: ${e.message}")
            ItemStack.EMPTY
        }
        if (stack.isEmpty) return stack
        // The fixer only hides modifiers stored on the item; Hypixel's flag also hid the item type's defaults
        // (a sword's "6 Attack Damage"), which its own tooltips never show.
        if ((legacy.getCompoundOrEmpty("tag").getIntOr("HideFlags", 0) and HIDE_MODIFIERS) != 0) {
            val display = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT)
            stack.set(DataComponents.TOOLTIP_DISPLAY, display.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true))
        }
        if (!extra.isEmpty) stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra))
        modern?.getStringOr("minecraft:item_model", "")?.takeIf { it.isNotEmpty() }?.let(Identifier::tryParse)
            ?.let { stack.set(DataComponents.ITEM_MODEL, it) }
        modern?.getStringOr("minecraft:tooltip_style", "")?.takeIf { it.isNotEmpty() }?.let(Identifier::tryParse)
            ?.let { stack.set(DataComponents.TOOLTIP_STYLE, it) }
        return stack
    }
}
