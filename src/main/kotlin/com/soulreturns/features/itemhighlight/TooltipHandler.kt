package com.soulreturns.features.itemhighlight

import com.soulreturns.config.config
import com.soulreturns.util.SkyblockItemUtils
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.item.Item
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.text.Text

/**
 * Handles adding Skyblock ID to item tooltips
 */
object TooltipHandler {

    fun register() {
        ItemTooltipCallback.EVENT.register { stack, context, type, lines ->
            // Check if the feature is enabled
            if (!config.renderCategory.showSkyblockIdInTooltip) return@register

            // Get the Skyblock ID
            val skyblockId = SkyblockItemUtils.getSkyblockId(stack) ?: return@register

            // Add the Skyblock ID line to the tooltip
            lines.add(Text.literal("§7Skyblock ID: §e$skyblockId"))
        }
    }
}
