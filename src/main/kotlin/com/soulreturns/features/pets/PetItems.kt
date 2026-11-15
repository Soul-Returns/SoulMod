package com.soulreturns.features.pets

import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.util.MessageDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A pet item in the Pets menu or the Stats & Equipment pet slot (captured 2026-11, research §3): the
 * name `§r§e⭐ §r§7[Lvl 200] §r§6Golden Dragon§r§d ✦` (⭐ = favourite), lore ending in `Held Item: X`, and
 * `custom_data.petInfo` (JSON string) with exact total XP, tier, held item id, skin id and uuid.
 */
data class PetItem(
    val uuid: String?,
    val type: String?,
    val name: String,
    val level: Int,
    /** From the name color (Tier Boost already applied), else petInfo's tier. */
    val rarity: SkyblockRarity?,
    val skinned: Boolean,
    val exp: Double?,
    /** Held item id from petInfo (`HEPHAESTUS_RELIC`), null when it holds none. */
    val heldId: String?,
    /** Held item from the lore, as shown; null when the lore has no `Held Item:` line. */
    val held: PetHeld?,
    /** petInfo `active`: the summoned pet. */
    val active: Boolean,
)

object PetItems {
    private val NAME = Regex("(?:⭐ )?\\[Lvl ([\\d,]+)] (?:\\[\\d+(✦)] )?(.+?)( ✦)?")
    private val json = Json { ignoreUnknownKeys = true }

    /** [legacyName] and [legacyLore] are `§` strings; [petInfo] the raw JSON string, or null. */
    fun parse(legacyName: String, legacyLore: List<String>, petInfo: String?): PetItem? {
        val plain = MessageDetector.stripColorCodes(legacyName).trim()
        val m = NAME.matchEntire(plain) ?: return null
        val name = m.groupValues[3]
        val info = petInfo?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        return PetItem(
            uuid = info?.str("uuid"),
            type = info?.str("type"),
            name = name,
            level = m.groupValues[1].replace(",", "").toIntOrNull() ?: return null,
            rarity = PetChat.rarityOf(legacyName, name) ?: SkyblockRarity.forName(info?.str("tier")),
            skinned = m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty() || info?.str("skin") != null,
            exp = info?.get("exp")?.jsonPrimitive?.doubleOrNull,
            heldId = info?.str("heldItem"),
            held = legacyLore.firstNotNullOfOrNull(PetChat::heldIn),
            active = info?.get("active")?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

    /** `HEPHAESTUS_RELIC` → `Hephaestus Relic`, `PET_ITEM_TIER_BOOST` → `Tier Boost`, `GOLDEN_DRAGON` → `Golden Dragon`. */
    fun titleOf(id: String): String =
        id.removePrefix("PET_ITEM_").split('_').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercaseChar() } }
}
