package com.soulreturns.features.mining.metaldetector

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.MetalDetectorConfig
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.Theme
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.tracker.TimerInfo
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSegments
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import net.minecraft.client.gui.screens.Screen
import java.util.Locale

/**
 * Metal Detector loot panel: one row per item found, grouped Tools / Powder / Other (the Filter
 * dropdown; Tools only by default). Session and Total both come from the `metaldetector` stats
 * namespace ([MetalDetectorLoot]).
 */
object MetalDetectorHud : TrackerHud<MetalDetectorHud.LootRow>("metal_detector_tracker", "Metal Detector", "mining") {
    const val COLUMN_FOUND = "found"
    const val COLUMN_AMOUNT = "amount"

    private const val COLOR_TOOL = 0xFFFF5555.toInt()
    private const val COLOR_POWDER = 0xFFFF55FF.toInt()

    data class LootRow(val name: String, val group: String, val found: Long, val amount: Long)

    override val settings: TrackerSettings get() = MetalDetectorHudSettings

    override val spec: TrackerSpec<LootRow> by lazy {
        TrackerSpec(
            id = "metal_detector_tracker",
            title = "Metal Detector",
            width = 280,
            columns = listOf(
                TrackerColumn(
                    id = COLUMN_AMOUNT,
                    chipLabel = "Items",
                    toggleLabel = "Amount received",
                    cellValue = { row, _ -> row.amount },
                    totalValue = { rows, _ -> rows.filter { it.group != MetalDetectorLoot.GROUP_POWDER }.sumOf { it.amount } },
                    formatCell = { compact(it) },
                    isCaption = true,
                    cellWidth = 40f,
                ),
                TrackerColumn(
                    id = COLUMN_FOUND,
                    chipLabel = "Treasures",
                    toggleLabel = "Times found",
                    cellValue = { row, _ -> row.found },
                    // The scope's own count: the filter narrows the list, not what you dug up.
                    totalValue = { _, tab -> counts(tab).treasures },
                ),
            ),
            sorts = listOf(
                TrackerSort(COLUMN_FOUND, "Times found", compareByDescending { it.found }, COLUMN_FOUND),
                TrackerSort(COLUMN_AMOUNT, "Amount", compareByDescending { it.amount }, COLUMN_AMOUNT),
                TrackerSort("alpha", "Alphabetical", compareBy { it.name }),
            ),
            rowsProvider = ::buildRows,
            rowLabel = { it.name },
            rowLabelColor = { groupColor(it.group) },
            filterVariants = { listOf(MetalDetectorLoot.GROUP_TOOLS, MetalDetectorLoot.GROUP_POWDER, MetalDetectorLoot.GROUP_OTHER) },
            filterPredicate = { row, selected -> row.group in selected },
            filterLabel = { sel ->
                val groups = sel - MetalDetectorHudSettings.NONE
                if (sel.isEmpty()) "Groups: all" else if (groups.isEmpty()) "Groups: none" else "Groups: " + groups.sorted().joinToString(", ")
            },
            hideList = { MetalDetectorHudSettings.showsNoRows() },
            onResetSession = { MetalDetectorLoot.resetSession() },
            isVisible = { MetalDetectorLoot.hudVisible() },
            timerLine = {
                val s = MetalDetectorLoot.handle.read()
                TimerInfo(duration(s.sessionMs), paused = !MetalDetectorLoot.hudVisible())
            },
            headerExtra = ::headerLines,
            emptyText = { "(nothing found yet)" },
            scrollableList = { cfg.mining.metalDetector.hudScrollable },
        )
    }

    override fun configuredOn(): Boolean = cfg.mining.metalDetector.hud

    override fun settingsScreen(parent: Screen?): Screen =
        SoulConfigScreen.forFeature(parent, "mdloot")

    override fun readTransform(): HudTransform = cfg.mining.metalDetector.hudTransform

    override fun writeTransform(transform: HudTransform) = update { copy(hudTransform = transform) }

    override fun defaultTransform(): HudTransform = SoulConfig().mining.metalDetector.hudTransform

    override fun drawBackground(): Boolean = cfg.mining.metalDetector.hudBackground

    override fun backgroundColor(): Int = cfg.mining.metalDetector.hudBackgroundColor.effective()

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Background", { cfg.mining.metalDetector.hudBackground }) {
            update { copy(hudBackground = !hudBackground) }
        },
        HudMenuToggle("Scrolling list", { cfg.mining.metalDetector.hudScrollable }) {
            update { copy(hudScrollable = !hudScrollable) }
        },
        HudMenuToggle("All tools warning", { cfg.mining.metalDetector.toolsAlert }) {
            update { copy(toolsAlert = !toolsAlert) }
        },
    )

    private fun update(block: MetalDetectorConfig.() -> MetalDetectorConfig) {
        ConfigManager.update { copy(mining = mining.copy(metalDetector = mining.metalDetector.block())) }
    }

    private fun counts(tab: TrackerTab): DetectorLootCounts {
        val s = MetalDetectorLoot.handle.read()
        return if (tab == TrackerTab.Session) s.session else s.total
    }

    private fun headerLines(): List<TrackerSegments> {
        val c = counts(settings.tab)
        val lines = ArrayList<TrackerSegments>(3)
        lines.add(
            listOf(
                "Tool sets " to Theme.TEXT_DIM,
                String.format(Locale.ROOT, "%,d", c.sets) to Theme.ACCENT,
                "  last " to Theme.TEXT_DIM,
                (if (c.timedSets > 0) duration(c.lastSetMs) else "-") to Theme.TEXT,
                "  avg " to Theme.TEXT_DIM,
                (if (c.timedSets > 0) duration(c.setTimeMs / c.timedSets) else "-") to Theme.TEXT,
            ),
        )
        val pct = if (c.treasures > 0) 100.0 * c.tools / c.treasures else 0.0
        lines.add(
            listOf(
                String.format(Locale.ROOT, "%.1f%%", pct) to Theme.ACCENT,
                " of treasures were tools" to Theme.TEXT_DIM,
            ),
        )
        return lines
    }

    private fun buildRows(tab: TrackerTab): List<LootRow> {
        val c = counts(tab)
        val names = LinkedHashSet<String>(MetalDetectorLoot.TOOLS.keys)
        names.addAll(c.found.keys)
        return names.map { LootRow(it, MetalDetectorLoot.group(it), c.found[it] ?: 0L, c.amount[it] ?: 0L) }
    }

    private fun groupColor(group: String): Int =
        if (group == MetalDetectorLoot.GROUP_TOOLS) COLOR_TOOL else if (group == MetalDetectorLoot.GROUP_POWDER) COLOR_POWDER else Theme.TEXT

    private fun compact(n: Long): String =
        if (n >= 10_000) String.format(Locale.ROOT, "%.1fk", n / 1000.0) else String.format(Locale.ROOT, "%,d", n)

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}
