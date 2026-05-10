package com.soulreturns.features.farming

import com.soulreturns.config.cfg
import com.soulreturns.gui.lib.GuiLayoutApi
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.TextBlockElement
import com.soulreturns.stats.PersistentStats
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MessageHandler
import com.soulreturns.util.SkyblockLocation
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
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
 *
 * Per-hour and Farming Time are *seasoning-session* metrics: they reset whenever
 * [resetSession] / [reset] is invoked, while the underlying [FarmingTimer] keeps running
 * for any other future feature that wants the global "active farming" clock.
 */
object SeasoningTracker {
    private val logger = SoulLogger("Soul/Seasoning")
    private const val ELEMENT_ID = "seasoning_tracker"
    /** Substring rather than exact match — covers both "Harvest Feast" and "Grand Harvest Feast". */
    private const val FEAST_MENU_TITLE_NEEDLE = "Harvest Feast"
    /** Substring inside item display names — covers "Feast Milestone I..V" and any Grand-event variants. */
    private const val MILESTONE_NAME_NEEDLE = "Feast Milestone"
    private val DONATIONS_PATTERN = Regex("(\\d+(?:,\\d+)*)\\s*/\\s*(\\d+(?:,\\d+)*)\\s+Donations")
    private const val RESET_BUTTON_TEXT = "[Reset Session]"

    /** Chat-driven gain since this seasoning session started — reset by [resetSession] / [reset]. */
    @Volatile private var sessionChatGain: Long = 0L
    /** Snapshot of [FarmingTimer.totalMs] at session start; subtracted to get session-local farming time. */
    @Volatile private var farmingTimerCheckpointMs: Long = 0L

    /** Last screen instance we read the menu from — avoid re-parsing every tick of the same open. */
    private var lastReadScreen: AbstractContainerScreen<*>? = null

    /** Bbox of the [Reset Session] button on screen, set during HUD render, cleared when the line isn't shown. */
    @Volatile private var resetButtonBbox: IntArray? = null  // [x, y, w, h] or null

    fun register() {
        farmingTimerCheckpointMs = FarmingTimer.totalMs

        MessageHandler.onServerMessage { handleChat(it) }

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            tryReadMenu(client)
            updateHud()
        })

        // Per-screen mouse listener so the [Reset Session] button is clickable while any screen is open.
        ScreenEvents.AFTER_INIT.register(ScreenEvents.AfterInit { _, screen, _, _ ->
            ScreenMouseEvents.beforeMouseClick(screen).register(
                ScreenMouseEvents.BeforeMouseClick { _, click ->
                    tryHandleResetClick(click.x().toInt(), click.y().toInt())
                }
            )
        })
    }

    /** Wipe persisted total *and* session metrics — invoked by `/soul dev resetSeasonings`. */
    fun reset() {
        PersistentStats.update {
            seasonings = 0L
            milestoneTargets = emptyList()
        }
        resetSession()
    }

    /** Reset only the session metrics (chat gain + farming-time checkpoint). Persisted total + milestones untouched. */
    fun resetSession() {
        sessionChatGain = 0L
        farmingTimerCheckpointMs = FarmingTimer.totalMs
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
        val pairs = collectMilestonePairs(screen)
        if (pairs.isEmpty()) return  // menu not yet populated by server packet
        // Save the Y values regardless of state — we know them now.
        val targets = pairs.map { it.second }.distinct().sorted()
        PersistentStats.update { milestoneTargets = targets }

        val total = decodeTotal(pairs) ?: run {
            // All-maxed/transitioning: don't touch the count, but the targets are already saved.
            lastReadScreen = screen
            return
        }
        PersistentStats.update { seasonings = total }
        lastReadScreen = screen
        logger.info("Hard-updated seasonings from Harvest Feast menu: $total (targets=$targets)")
    }

    /** Iterate slots, parse each "Feast Milestone …" item's "X/Y Donations" lore line. */
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
     * Cumulative-progress decoder. Returns:
     *  - In-progress X (0 < X < Y) — the true cumulative total.
     *  - 0 — fresh event (all milestones at 0/Y).
     *  - null — all maxed or transitioning; caller leaves the count alone.
     */
    private fun decodeTotal(pairs: List<Pair<Long, Long>>): Long? {
        val inProgress = pairs.firstOrNull { (x, y) -> x in 1L..<y }
        if (inProgress != null) return inProgress.first
        if (pairs.all { it.first == 0L }) return 0L
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
        val data = PersistentStats.current
        val total = data.seasonings
        val targets = data.milestoneTargets
        val cfgFlags = cfg.farming.seasonings

        val showHud = cfgFlags.enableTracker() && SkyblockLocation.area == "Garden"
        val anyScreenOpen = Minecraft.getInstance().screen != null

        val lines = mutableListOf<String>()

        // Total: 64  OR  Total: 64/250
        lines += if (cfgFlags.showMaxMilestone() && targets.isNotEmpty())
            "Total: $total/${targets.last()}"
        else "Total: $total"

        // Next Milestone line
        if (cfgFlags.showNextMilestone()) {
            val next = targets.firstOrNull { it > total }
            lines += when {
                targets.isEmpty() -> "Next Milestone: ?"
                next == null      -> "Next Milestone: §c§lMaxed"
                else              -> "Next Milestone: $total/$next"
            }
        }

        // Farming Time
        if (cfgFlags.showFarmingTime()) {
            val timeStr = formatDuration(seasoningFarmingMs())
            lines += if (FarmingTimer.isPaused) "Farming Time: $timeStr §c(Paused)"
                     else "Farming Time: $timeStr"
        }

        // Per hour
        if (cfgFlags.showPerHour()) {
            val perHour = computePerHour()
            lines += "Per hour: ${if (perHour == null) "—" else "%,d".format(perHour)}"
        }

        // Reset session button — only while a screen is open (so it doesn't clutter normal play).
        if (showHud && anyScreenOpen) {
            lines += "§b§n$RESET_BUTTON_TEXT"
            // Compute & store the bbox each tick so click handling has fresh coords.
            updateResetButtonBbox(lineIndex = lines.size - 1)
        } else {
            resetButtonBbox = null
        }

        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "§aSeasonings",
            lines = lines,
            color = 0xFFFFFFFF.toInt(),
            enabled = showHud,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.4,
            defaultScale = 1.0f,
        )
    }

    /** Time spent farming since the last [resetSession]. The underlying [FarmingTimer] is not reset. */
    private fun seasoningFarmingMs(): Long =
        (FarmingTimer.totalMs - farmingTimerCheckpointMs).coerceAtLeast(0L)

    /**
     * Per-hour rate over [seasoningFarmingMs]. Returns:
     *  - `null` (HUD shows "—") only until we have 5s of active farming AND at least 1 chat gain —
     *    division-safe & avoids a "0 per hour" flicker before any seasonings drop.
     *  - `0` when no chat gain yet (rare, since chat increments tend to start the moment farming does).
     *  - the rate otherwise.
     *
     * "Farming time" is *active* breaking time (paused 2s after each crop), not wall-clock — a slow
     * cactus farm at 5 BPS spread across 5 minutes wall-clock might only accumulate ~30s of active
     * time, which is why the threshold is intentionally low.
     */
    private fun computePerHour(): Long? {
        val farmingMs = seasoningFarmingMs()
        if (farmingMs < 5_000L || sessionChatGain <= 0L) return null
        val hours = farmingMs / 3_600_000.0
        return (sessionChatGain / hours).toLong()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    // ───────────────────── reset button click ─────────────────────

    /**
     * Compute the rough on-screen bbox of the [Reset Session] line based on the layout's
     * current anchor/offset/scale. Stored in [resetButtonBbox] so [tryHandleResetClick] can
     * test against it on screen mouse clicks.
     */
    private fun updateResetButtonBbox(lineIndex: Int) {
        val mc = Minecraft.getInstance()
        val window = mc.window
        val element = GuiLayoutManager.getLayout().elements
            .filterIsInstance<TextBlockElement>()
            .firstOrNull { it.id == ELEMENT_ID } ?: return
        if (!element.enabled) return

        val baseX = (element.anchorX * window.guiScaledWidth).toInt() + element.offsetX
        val baseY = (element.anchorY * window.guiScaledHeight).toInt() + element.offsetY
        val scale = element.scale.coerceAtLeast(0.25f)
        val lineStep = (10f * scale).toInt().coerceAtLeast(4)

        // Title is rendered first, then each line at lineIndex offsets.
        val titleOffset = if (element.title != null) lineStep else 0
        val y = baseY + titleOffset + lineIndex * lineStep

        val width = (mc.font.width(RESET_BUTTON_TEXT) * scale).toInt()
        val height = (mc.font.lineHeight * scale).toInt()
        resetButtonBbox = intArrayOf(baseX, y, width, height)
    }

    private fun tryHandleResetClick(mouseX: Int, mouseY: Int) {
        val bbox = resetButtonBbox ?: return
        val (x, y, w, h) = bbox.let { listOf(it[0], it[1], it[2], it[3]) }
        if (mouseX in x..(x + w) && mouseY in y..(y + h)) {
            resetSession()
            logger.info("Session reset via [Reset Session] click")
        }
    }
}
