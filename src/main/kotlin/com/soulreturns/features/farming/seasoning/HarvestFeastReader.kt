package com.soulreturns.features.farming.seasoning

import com.soulreturns.core.events.Events
import com.soulreturns.data.model.HarvestFeastSnapshot
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * Watches for the Harvest Feast chest GUI and publishes a [HarvestFeastSnapshot] each time
 * one is opened and parseable.
 *
 * Title check uses a substring (`"Harvest Feast"`) so it covers the "Grand Harvest Feast" event
 * variant. Item filter is also a substring (`"Feast Milestone"`) so renamed-but-recognisable
 * milestones still match.
 *
 * The decoder follows Hypixel's cumulative-progress convention: the in-progress milestone
 * (`0 < X < Y`) carries the true cumulative seasoning total. See the `HarvestFeastSnapshot`
 * docs for the full semantic of `total`.
 */
object HarvestFeastReader {
    private val logger = SoulLogger("Soul/HarvestFeast")

    private const val FEAST_MENU_TITLE_NEEDLE = "Harvest Feast"
    private const val MILESTONE_NAME_NEEDLE = "Feast Milestone"
    private val DONATIONS_PATTERN = Regex("(\\d+(?:,\\d+)*)\\s*/\\s*(\\d+(?:,\\d+)*)\\s+Donations")

    private var lastReadScreen: AbstractContainerScreen<*>? = null

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> tick(client) })
    }

    private fun tick(client: Minecraft) {
        val screen = client.screen as? AbstractContainerScreen<*>
        if (screen == null) {
            lastReadScreen = null
            return
        }
        if (screen === lastReadScreen) return
        if (!screen.title.string.contains(FEAST_MENU_TITLE_NEEDLE)) {
            lastReadScreen = null
            return
        }
        val pairs = collectMilestonePairs(screen)
        if (pairs.isEmpty()) return // menu opened but server hasn't populated slots yet

        val targets = pairs.map { it.second }.distinct().sorted()
        val total = decodeTotal(pairs)
        Events.publish(HarvestFeastSnapshot(total = total, targets = targets))
        lastReadScreen = screen
        logger.info("Harvest Feast snapshot: total=$total, targets=$targets")
    }

    private fun collectMilestonePairs(screen: AbstractContainerScreen<*>): List<Pair<Long, Long>> {
        val pairs = mutableListOf<Pair<Long, Long>>()
        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (stack.isEmpty) continue
            if (!stack.hoverName.string.contains(MILESTONE_NAME_NEEDLE)) continue
            val pair = parseDonationsFromLore(stack) ?: continue
            pairs += pair
        }
        return pairs
    }

    /**
     * Cumulative-progress decoder:
     *  - In-progress (`0 < X < Y`) → return X.
     *  - All `0/Y` → fresh event, return 0.
     *  - All maxed / transitioning → null.
     */
    private fun decodeTotal(pairs: List<Pair<Long, Long>>): Long? {
        val inProgress = pairs.firstOrNull { (x, y) -> x in 1L..<y }
        if (inProgress != null) return inProgress.first
        if (pairs.all { it.first == 0L }) return 0L
        return null
    }

    private fun parseDonationsFromLore(stack: ItemStack): Pair<Long, Long>? {
        val lore = stack.get(DataComponents.LORE) ?: return null
        for (line in lore.lines) {
            val raw = MessageDetector.stripColorCodes(line.string)
            val m = DONATIONS_PATTERN.find(raw) ?: continue
            val x = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val y = m.groupValues[2].replace(",", "").toLongOrNull() ?: continue
            return x to y
        }
        return null
    }
}
