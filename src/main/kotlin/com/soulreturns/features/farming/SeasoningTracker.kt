package com.soulreturns.features.farming

import com.soulreturns.config.cfg
import com.soulreturns.gui.lib.GuiLayoutApi
import com.soulreturns.stats.PersistentStats
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MessageHandler
import com.soulreturns.util.SkyblockLocation
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * Tracks seasonings collected during the SkyBlock Garden Harvest Feast event.
 *
 * Sources of truth, in priority order:
 *  1. Chat: `RARE CROP! Seasoning (automatically donated)` → +1
 *  2. Menu: opening "Harvest Feast" reads the milestone donations and *hard sets* the total
 *     (covers offline farming, restarts, etc.)
 *
 * Tracking runs unconditionally; the config toggle only controls the HUD overlay.
 */
object SeasoningTracker {
    private val logger = SoulLogger("Soul/Seasoning")
    private const val ELEMENT_ID = "seasoning_tracker"
    /** Substring rather than exact match — covers both "Harvest Feast" and "Grand Harvest Feast". */
    private const val FEAST_MENU_TITLE_NEEDLE = "Harvest Feast"
    /** Substring inside item display names — covers "Feast Milestone I..V" and any Grand-event variants. */
    private const val MILESTONE_NAME_NEEDLE = "Feast Milestone"
    private val DONATIONS_PATTERN = Regex("(\\d+(?:,\\d+)*)\\s*/\\s*(\\d+(?:,\\d+)*)\\s+Donations")

    /**
     * Seasonings collected via chat increments *this client session only*. Used for the per-hour
     * rate; menu hard-updates intentionally do NOT contribute (they'd produce huge spikes after
     * offline farming or sudden drops at event resets, making the rate meaningless).
     */
    @Volatile private var sessionChatGain: Long = 0L

    /** Last screen instance we read the menu from — avoid re-parsing every tick of the same open. */
    private var lastReadScreen: AbstractContainerScreen<*>? = null

    fun register() {
        MessageHandler.onServerMessage { handleChat(it) }

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            tryReadMenu(client)
            updateHud()
        })
    }

    /** Wipe persisted total *and* session chat gain — invoked by `/soul dev resetSeasonings`. */
    fun reset() {
        PersistentStats.update { seasonings = 0L }
        sessionChatGain = 0L
    }

    // ───────────────────── chat increment ─────────────────────

    private fun handleChat(message: String) {
        // Hypixel sends e.g. "§6§lRARE CROP! §r§eSeasoning (automatically donated)" — strip codes first.
        if (!MessageDetector.containsPattern(message, "RARE CROP! Seasoning")) return
        PersistentStats.update { seasonings += 1 }
        sessionChatGain += 1
    }

    // ───────────────────── hard update from menu ─────────────────────

    private fun tryReadMenu(client: Minecraft) {
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
        val total = parseFeastMenu(screen) ?: return
        PersistentStats.update { seasonings = total }
        lastReadScreen = screen
        logger.info("Hard-updated seasonings from Harvest Feast menu: $total")
    }

    /**
     * Reads cumulative seasoning count from the milestone tiles.
     *
     * Hypixel's milestones display CUMULATIVE progress: a milestone with goal `Y` shows `X/Y`
     * where `X` is your total donation count *capped at Y*. Lower (already completed) milestones
     * show `Y/Y`. Higher (locked) milestones show `0/Y`. Exactly one milestone is "in-progress"
     * with `0 < X < Y`, and that `X` is the true cumulative total.
     *
     * Cases:
     *  - Found in-progress milestone (`0 < X < Y`) → return `X`.
     *  - All milestones at `0/Y` → fresh event, return `0` (so we reset the counter).
     *  - All maxed (`Y/Y` everywhere up to and including milestone V) OR transitioning between
     *    completed and next-not-yet-ticked → return `null` so the caller does NOT update the
     *    persisted total. Past the highest milestone there's no on-screen way to know the count.
     */
    private fun parseFeastMenu(screen: AbstractContainerScreen<*>): Long? {
        val pairs = mutableListOf<Pair<Long, Long>>()
        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (stack.isEmpty) continue
            if (!stack.hoverName.string.contains(MILESTONE_NAME_NEEDLE)) continue
            val pair = parseDonationsFromLore(stack) ?: continue
            pairs += pair
        }
        if (pairs.isEmpty()) return null
        // In-progress: 0 < X < Y. Should be at most one such milestone at any time.
        val inProgress = pairs.firstOrNull { (x, y) -> x in 1L..<y }
        if (inProgress != null) return inProgress.first
        // Fresh event start: every milestone shows 0/Y.
        if (pairs.all { it.first == 0L }) return 0L
        // All maxed or instant-transition state → can't determine; leave the counter alone.
        return null
    }

    /** Reads the LORE component, finds the first "X/Y Donations" match. Returns (X, Y) with commas stripped. */
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

    // ───────────────────── HUD ─────────────────────

    private fun updateHud() {
        val total = PersistentStats.current.seasonings
        val timeStr = FarmingTimer.formatTime()
        val timeLine = if (FarmingTimer.isPaused) "Farming Time: $timeStr §c(Paused)"
                       else "Farming Time: $timeStr"
        val perHour = computePerHour()
        val perHourLine = "Per hour: ${if (perHour == null) "—" else "%,d".format(perHour)}"
        // HUD is gated on (a) user toggle and (b) being on the Garden island. Tracking still
        // runs everywhere — only the on-screen overlay is suppressed off-island.
        val showHud = cfg.farming.seasonings.enableTracker() && SkyblockLocation.area == "Garden"
        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "Seasonings",
            lines = listOf(
                "Total: $total",
                timeLine,
                perHourLine
            ),
            color = 0xFFFFFFFF.toInt(),
            enabled = showHud,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.4,
            defaultScale = 1.0f,
        )
    }

    /** Per-hour rate based on this session's chat-driven gain divided by [FarmingTimer]'s active hours. */
    private fun computePerHour(): Long? {
        val farmingMs = FarmingTimer.totalMs
        if (farmingMs < 60_000L) return null   // < 1 minute of active farming → too noisy
        if (sessionChatGain <= 0L) return 0L
        val hours = farmingMs / 3_600_000.0
        return (sessionChatGain / hours).toLong()
    }
}
