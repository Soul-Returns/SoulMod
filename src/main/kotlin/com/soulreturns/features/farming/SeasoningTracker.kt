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
    private const val FEAST_MENU_TITLE = "Harvest Feast"
    private val DONATIONS_PATTERN = Regex("(\\d+(?:,\\d+)*)\\s*/\\s*(\\d+(?:,\\d+)*)\\s+Donations")

    /**
     * Seasonings collected via chat increments *this client session only*.
     * Menu hard-updates intentionally do NOT contribute — they'd produce huge spikes (catching up
     * after offline farming) or sudden drops (a new event resetting the counter to 0), making the
     * per-hour rate meaningless.
     */
    @Volatile private var sessionChatGain: Long = 0L
    private var sessionStartNanos: Long = 0L
    /** Last screen instance we read the menu from — avoid re-parsing every tick of the same open. */
    private var lastReadScreen: AbstractContainerScreen<*>? = null

    fun register() {
        sessionStartNanos = System.nanoTime()

        MessageHandler.onServerMessage { handleChat(it) }

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            tryReadMenu(client)
            updateHud()
        })
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
        if (screen.title.string != FEAST_MENU_TITLE) {
            lastReadScreen = null
            return
        }
        val total = parseFeastMenu(screen) ?: return
        PersistentStats.update { seasonings = total }
        lastReadScreen = screen
        logger.info("Hard-updated seasonings from Harvest Feast menu: $total")
    }

    /**
     * Sums the "X / Y Donations" lines across all milestone slots in the chest.
     * Returns null if no parseable milestone line is found (= menu not yet populated).
     */
    private fun parseFeastMenu(screen: AbstractContainerScreen<*>): Long? {
        var total = 0L
        var foundAny = false
        for (slot in screen.menu.slots) {
            val stack = slot.item
            if (stack.isEmpty) continue
            val displayName = stack.hoverName.string
            if (!displayName.startsWith("Feast Milestone")) continue
            val per = parseDonationsFromLore(stack) ?: continue
            total += per
            foundAny = true
        }
        return if (foundAny) total else null
    }

    /** Reads the LORE component, finds the first "X/Y Donations" match, returns X (commas stripped). */
    private fun parseDonationsFromLore(stack: ItemStack): Long? {
        val lore = stack.get(DataComponents.LORE) ?: return null
        for (line in lore.lines) {
            val raw = MessageDetector.stripColorCodes(line.string)
            val m = DONATIONS_PATTERN.find(raw) ?: continue
            return m.groupValues[1].replace(",", "").toLongOrNull()
        }
        return null
    }

    // ───────────────────── HUD ─────────────────────

    private fun updateHud() {
        val total = PersistentStats.current.seasonings
        val perHour = computePerHour()
        // HUD is gated on (a) user toggle and (b) being on the Garden island. Tracking still
        // runs everywhere — only the on-screen overlay is suppressed off-island.
        val showHud = cfg.farming.seasonings.enableTracker() && SkyblockLocation.area == "Garden"
        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "Seasonings",
            lines = listOf(
                "Total: $total",
                "Per hour: ${if (perHour == null) "—" else "%,d".format(perHour)}"
            ),
            color = 0xFFFFFFFF.toInt(),
            enabled = showHud,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.4,
            defaultScale = 1.0f,
        )
    }

    private fun computePerHour(): Long? {
        val elapsedNanos = System.nanoTime() - sessionStartNanos
        if (elapsedNanos < 60_000_000_000L) return null   // < 1 minute → too noisy
        if (sessionChatGain <= 0L) return 0L
        val hours = elapsedNanos / 3_600_000_000_000.0
        return (sessionChatGain / hours).toLong()
    }
}
