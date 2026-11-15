package com.soulreturns.features.islandchests

import com.soulreturns.config.gui.render.SoulRichText
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.util.MessageDetector
import net.minecraft.network.chat.Component

private val rarityWords = SkyblockRarity.entries.sortedByDescending { it.name.length }.joinToString("|") { it.name.replace('_', ' ') }
private val rarityLine = Regex("^(?:[a-z] )?(?:SHINY )?($rarityWords)(?: [A-Z_]+)*(?: [a-z])?$")
private val petLevelPrefix = Regex("^\\s*\\[Lvl\\s+[^]]+]\\s*", RegexOption.IGNORE_CASE)

internal fun StoredChestItem.searchColor(): Int {
    for (line in loreLines.asReversed()) {
        val rarity = rarityLine.matchEntire(MessageDetector.stripColorCodes(line).trim())?.groupValues?.get(1) ?: continue
        SkyblockRarity.forName(rarity.replace(' ', '_'))?.let { return it.color }
    }
    val spans = SoulRichText.spans(Component.literal(displayNameRaw.orEmpty()))
    val color = spans.firstOrNull { petLevelPrefix.replace(it.text, "").isNotBlank() }?.color
    return color?.let { it or 0xFF000000.toInt() } ?: 0xFFEEEEEE.toInt()
}
