package com.soulreturns.data.prices

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * The market key of an [ItemStack], mirroring the backend's `AuctionItemNormalizer` (the `/prices` /
 * NEU convention):
 *
 * | item | key |
 * |---|---|
 * | ordinary | `custom_data.id`, legacy `:` → `-` (`INK_SACK:15` → `INK_SACK-15`) |
 * | pet | `<TYPE>;<tierIndex>`, `+100` at level 100–199, `+200` at 200 (`ENDER_DRAGON;4+100`) |
 * | single-enchant book | `ENCHANTMENT_<NAME>_<LVL>`; several enchants stay `ENCHANTED_BOOK` |
 * | rune | `<NAME>_RUNE;<tier>` |
 * | New Year cake | `NEW_YEAR_CAKE+<year>` |
 * | potion | `POTION_<NAME>;<level>` |
 *
 * Pets use `petInfo.tier`, the BASE rarity (a Tier Boost is a removable held item), and the level
 * from the display name `[Lvl N]`, as the backend does. A pet without readable `petInfo` has no key.
 *
 * Memoised on the last stack's `CustomData` and name instances: the tooltip callback asks every frame
 * for the hovered stack, and `copyTag()` is a deep copy.
 */
object MarketItemKey {
    /** Hypixel rarity → pet tier index, gaps included (`PHOENIX;7`/`;8` are SPECIAL/VERY_SPECIAL). */
    private val PET_TIERS = mapOf(
        "COMMON" to 0,
        "UNCOMMON" to 1,
        "RARE" to 2,
        "EPIC" to 3,
        "LEGENDARY" to 4,
        "MYTHIC" to 5,
        "DIVINE" to 6,
        "SPECIAL" to 7,
        "VERY_SPECIAL" to 8,
    )

    private val LEVEL = Regex("""\[Lvl (\d+)]""")
    private val COLOR_CODE = Regex("§.")
    private val json = Json { ignoreUnknownKeys = true }

    private class Memo(val data: CustomData, val name: Component, val key: String?)

    @Volatile private var memo: Memo? = null

    fun of(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val name = stack.hoverName
        val last = memo
        if (last != null && last.data === data && last.name === name) return last.key
        val key = try {
            resolve(data.copyTag(), name.string)
        } catch (_: Exception) {
            null
        }
        memo = Memo(data, name, key)
        return key
    }

    /** [tag] is the stack's `custom_data`; [displayName] is only read for a pet's level. */
    fun resolve(tag: CompoundTag, displayName: String): String? {
        val id = tag.getStringOr("id", "")
        if (id.isEmpty()) return null
        val key = when (id) {
            "PET" -> petKey(tag, displayName) ?: return null
            "ENCHANTED_BOOK" -> singleInt(tag.getCompoundOrEmpty("enchantments"))
                ?.let { (name, level) -> "ENCHANTMENT_${name.uppercase(Locale.ROOT)}_$level" } ?: id
            "RUNE", "UNIQUE_RUNE" -> singleInt(tag.getCompoundOrEmpty("runes"))
                ?.let { (name, tier) -> "${name.uppercase(Locale.ROOT)}_RUNE;$tier" } ?: id
            "NEW_YEAR_CAKE" -> tag.getInt("new_years_cake").map { "NEW_YEAR_CAKE+$it" }.orElse(id)
            "POTION" -> {
                val potion = tag.getStringOr("potion", "")
                val level = tag.getInt("potion_level")
                if (potion.isNotEmpty() && level.isPresent) "POTION_${potion.uppercase(Locale.ROOT)};${level.get()}" else id
            }
            else -> id
        }
        return key.replace(':', '-')
    }

    /** The one `name → int` entry of [tag], or null when it holds none or several. */
    private fun singleInt(tag: CompoundTag): Pair<String, Int>? {
        if (tag.size() != 1) return null
        val name = tag.keySet().first()
        val value = tag.getInt(name)
        return if (value.isPresent) name to value.get() else null
    }

    private fun petKey(tag: CompoundTag, displayName: String): String? {
        // Hypixel stores petInfo as a JSON string; accept a compound too.
        val compound = tag.getCompound("petInfo")
        val type: String
        val tier: String
        if (compound.isPresent) {
            type = compound.get().getStringOr("type", "")
            tier = compound.get().getStringOr("tier", "")
        } else {
            val raw = tag.getStringOr("petInfo", "")
            if (raw.isEmpty()) return null
            val info = json.parseToJsonElement(raw).jsonObject
            type = info["type"]?.jsonPrimitive?.content.orEmpty()
            tier = info["tier"]?.jsonPrimitive?.content.orEmpty()
        }
        val tierIndex = PET_TIERS[tier] ?: return null
        if (type.isEmpty()) return null
        val level = LEVEL.find(displayName.replace(COLOR_CODE, ""))?.groupValues?.get(1)?.toIntOrNull()
        val suffix = if (level == null) "" else if (level >= 200) "+200" else if (level >= 100) "+100" else ""
        return "${type.uppercase(Locale.ROOT)};$tierIndex$suffix"
    }
}
