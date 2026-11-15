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

    /** One stack per slot, [ItemStack.EMPTY] for empty or undecodable ones; empty list on a bad blob. */
    fun decode(base64: String): List<ItemStack> {
        val list = try {
            NbtIo.readCompressed(ByteArrayInputStream(Base64.getDecoder().decode(base64)), NbtAccounter.unlimitedHeap())
                .getListOrEmpty("i")
        } catch (e: Exception) {
            logger.warn("Could not read an item blob: ${e.message}")
            return emptyList()
        }
        return (0 until list.size).map { fix(list.getCompoundOrEmpty(it)) }
    }

    private fun fix(legacy: CompoundTag): ItemStack {
        if (legacy.isEmpty || legacy.getShortOr("id", 0) == 0.toShort()) return ItemStack.EMPTY
        val item = legacy.copy()
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
        if (!extra.isEmpty) stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra))
        modern?.getStringOr("minecraft:item_model", "")?.takeIf { it.isNotEmpty() }?.let(Identifier::tryParse)
            ?.let { stack.set(DataComponents.ITEM_MODEL, it) }
        modern?.getStringOr("minecraft:tooltip_style", "")?.takeIf { it.isNotEmpty() }?.let(Identifier::tryParse)
            ?.let { stack.set(DataComponents.TOOLTIP_STYLE, it) }
        return stack
    }
}
