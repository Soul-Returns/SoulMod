package com.soulreturns.features.mining.nucleus

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.NucleusConfig
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.Theme
import com.soulreturns.data.prices.PriceMode
import com.soulreturns.features.profit.dragon.DragonProfitAnnouncer
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.tracker.TimerInfo
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSegments
import com.soulreturns.ui.hud.tracker.TrackerSelect
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import net.minecraft.client.gui.screens.Screen
import java.util.Locale

/**
 * Crystal Nucleus run panel: runs, run times and rates in the header; one row per loot item (amount,
 * drop rate, value) plus the kit consumed (negative value); profit in the chip line. Values are
 * `count x price` at render, so the price mode re-values the whole history.
 */
object NucleusHud : TrackerHud<NucleusHud.Row>("nucleus_tracker", "Crystal Nucleus", "mining") {
    const val COLUMN_AMOUNT = "amount"
    const val COLUMN_RATE = "rate"
    const val COLUMN_VALUE = "value"
    const val GROUP_LOOT = "Loot"
    const val GROUP_POWDER = "Powder"
    const val GROUP_KIT = "Kit"

    private const val COLOR_RARE = 0xFFFFAA00.toInt()
    private const val COLOR_POWDER = 0xFFFF55FF.toInt()
    private const val COLOR_KIT = 0xFFFF7777.toInt()
    private const val COLOR_LOSS = 0xFFFF5555.toInt()

    /** No rate for kit and powder rows. */
    private const val NO_RATE = -1L

    /**
     * @property rate per mille of runs with this item, or [NO_RATE].
     * @property value coins: positive for loot, negative for kit, 0 = no quote.
     */
    data class Row(val name: String, val group: String, val amount: Long, val rate: Long, val value: Long, val rare: Boolean)

    override val settings: TrackerSettings get() = NucleusHudSettings

    override val spec: TrackerSpec<Row> by lazy {
        TrackerSpec(
            id = "nucleus_tracker",
            title = "Crystal Nucleus",
            width = 300,
            columns = listOf(
                TrackerColumn(
                    id = COLUMN_AMOUNT,
                    chipLabel = "Items",
                    toggleLabel = "Amount",
                    cellValue = { row, _ -> row.amount },
                    totalValue = { rows, _ -> rows.sumOf { it.amount } },
                    formatCell = ::compact,
                    isCaption = true,
                    cellWidth = 40f,
                ),
                TrackerColumn(
                    id = COLUMN_RATE,
                    chipLabel = "Rate",
                    toggleLabel = "Drop rate",
                    cellValue = { row, _ -> row.rate },
                    totalValue = { _, _ -> 0L },
                    formatCell = { if (it < 0L) "" else String.format(Locale.ROOT, "%.0f%%", it / 10.0) },
                    isCaption = true,
                    cellWidth = 34f,
                ),
                TrackerColumn(
                    id = COLUMN_VALUE,
                    chipLabel = "Coins",
                    toggleLabel = "Coins",
                    cellValue = { row, _ -> row.value },
                    totalValue = { rows, _ -> rows.sumOf { it.value } },
                    formatCell = { if (it == 0L) "-" else DragonProfitAnnouncer.formatCoins(it) },
                    cellWidth = 52f,
                ),
            ),
            sorts = listOf(
                TrackerSort(COLUMN_VALUE, "Coins", compareByDescending { it.value }, COLUMN_VALUE),
                TrackerSort(COLUMN_AMOUNT, "Amount", compareByDescending { it.amount }, COLUMN_AMOUNT),
                TrackerSort(COLUMN_RATE, "Drop rate", compareByDescending { it.rate }, COLUMN_RATE),
                TrackerSort("alpha", "Alphabetical", compareBy { it.name }),
            ),
            rowsProvider = ::buildRows,
            rowLabel = { it.name },
            rowLabelColor = ::rowColor,
            filterVariants = { listOf(GROUP_LOOT, GROUP_POWDER, GROUP_KIT) },
            filterPredicate = { row, selected -> row.group in selected },
            filterLabel = { sel -> if (sel.isEmpty()) "Groups: all" else "Groups: " + sel.sorted().joinToString(", ") },
            onResetSession = { NucleusRuns.resetSession() },
            isVisible = { NucleusRuns.trackerOn() && NucleusRuns.inHollows() },
            timerLine = { TimerInfo(duration(timeMs(settings.tab))) },
            headerExtra = ::headerLines,
            footerSelects = listOf(
                TrackerSelect(
                    id = "priceMode",
                    labelPrefix = "Price",
                    options = PriceMode.labels,
                    selectedIndex = { NucleusHudSettings.priceMode.ordinal },
                    onSelect = { NucleusHudSettings.priceMode = PriceMode.byIndex(it) },
                ),
            ),
            chipOverride = ::chipLine,
            emptyText = { "(no loot bundle yet)" },
            scrollableList = { cfg.mining.nucleus.hudScrollable },
        )
    }

    override fun configuredOn(): Boolean = NucleusRuns.trackerOn()

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "nucleus")

    override fun readTransform(): HudTransform = cfg.mining.nucleus.hudTransform

    override fun writeTransform(transform: HudTransform) = update { copy(hudTransform = transform) }

    override fun defaultTransform(): HudTransform = SoulConfig().mining.nucleus.hudTransform

    override fun drawBackground(): Boolean = cfg.mining.nucleus.hudBackground

    override fun backgroundColor(): Int = cfg.mining.nucleus.hudBackgroundColor.effective()

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Background", { cfg.mining.nucleus.hudBackground }) { update { copy(hudBackground = !hudBackground) } },
        HudMenuToggle("Scrolling list", { cfg.mining.nucleus.hudScrollable }) { update { copy(hudScrollable = !hudScrollable) } },
    )

    private fun update(block: NucleusConfig.() -> NucleusConfig) {
        ConfigManager.update { copy(mining = mining.copy(nucleus = mining.nucleus.block())) }
    }

    private fun counts(tab: TrackerTab): NucleusCounts {
        val s = NucleusRuns.handle.read()
        return if (tab == TrackerTab.Session) s.session else s.total
    }

    private fun timeMs(tab: TrackerTab): Long = if (tab == TrackerTab.Session) NucleusRuns.sessionTimeMs() else NucleusRuns.totalTimeMs()

    private fun headerLines(): List<TrackerSegments> {
        val tab = settings.tab
        val c = counts(tab)
        val hours = timeMs(tab) / 3_600_000.0
        val perHour = if (hours >= 1.0 / 60) String.format(Locale.ROOT, "%.1f", c.runs / hours) else "-"
        val runs: TrackerSegments = listOf(
            "Runs " to Theme.TEXT_DIM,
            String.format(Locale.ROOT, "%,d", c.runs) to Theme.ACCENT,
            "  per hour " to Theme.TEXT_DIM,
            perHour to Theme.TEXT,
        )
        val times: TrackerSegments = listOf(
            "Last " to Theme.TEXT_DIM,
            timeOrDash(c.lastRunMs) to Theme.TEXT,
            "  Median " to Theme.TEXT_DIM,
            timeOrDash(c.medianRunMs()) to Theme.TEXT,
            "  Best " to Theme.TEXT_DIM,
            timeOrDash(c.bestRunMs) to Theme.TEXT,
        )
        return listOf(runs, times)
    }

    /** Net profit over all loot minus all kit (not just the filtered rows), per run and per hour. */
    private fun chipLine(): TrackerSegments {
        val tab = settings.tab
        val c = counts(tab)
        val mode = NucleusHudSettings.priceMode
        val loot = c.loot.entries.sumOf { (name, n) -> NucleusItems.unitValue(name, mode) * n }
        val kit = c.kit.entries.sumOf { (name, n) -> NucleusItems.unitCost(name, mode) * n }
        val profit = loot - kit
        val color = if (profit < 0) COLOR_LOSS else Theme.ACCENT
        val line = arrayListOf("Profit " to Theme.TEXT_DIM, DragonProfitAnnouncer.formatCoins(profit) to color)
        if (c.runs > 0) {
            line += "  /run " to Theme.TEXT_DIM
            line += DragonProfitAnnouncer.formatCoins(profit / c.runs) to color
        }
        val hours = timeMs(tab) / 3_600_000.0
        if (hours >= 1.0 / 60) {
            line += "  /h " to Theme.TEXT_DIM
            line += DragonProfitAnnouncer.formatCoins((profit / hours).toLong()) to color
        }
        return line
    }

    private fun buildRows(tab: TrackerTab): List<Row> {
        val c = counts(tab)
        val mode = NucleusHudSettings.priceMode
        val threshold = NucleusRuns.rareThreshold()
        val rows = ArrayList<Row>(c.loot.size + c.kit.size)
        for ((name, amount) in c.loot) {
            val powder = NucleusItems.isPowder(name)
            val unit = NucleusItems.unitValue(name, mode)
            val rate = if (powder || c.runs == 0L) NO_RATE else (c.lootRuns[name] ?: 0L) * 1000 / c.runs
            rows += Row(name, if (powder) GROUP_POWDER else GROUP_LOOT, amount, rate, unit * amount, unit > 0 && unit >= threshold)
        }
        for ((name, used) in c.kit) {
            rows += Row(name, GROUP_KIT, used, NO_RATE, -NucleusItems.unitCost(name, mode) * used, rare = false)
        }
        return rows
    }

    private fun rowColor(row: Row): Int =
        if (row.rare) COLOR_RARE else if (row.group == GROUP_POWDER) COLOR_POWDER else if (row.group == GROUP_KIT) COLOR_KIT else Theme.TEXT

    private fun compact(n: Long): String =
        if (n >= 10_000) String.format(Locale.ROOT, "%.1fk", n / 1000.0) else String.format(Locale.ROOT, "%,d", n)

    private fun timeOrDash(ms: Long): String = if (ms <= 0L) "-" else duration(ms)

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}
