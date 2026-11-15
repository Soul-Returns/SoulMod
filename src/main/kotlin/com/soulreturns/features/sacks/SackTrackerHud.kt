package com.soulreturns.features.sacks

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.Theme
import com.soulreturns.data.prices.MarketValues
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.prices.PriceTooltip
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSettings
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.ui.hud.tracker.TimerInfo
import net.minecraft.client.gui.screens.Screen
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The sack item tracker panel on the shared [TrackerHud]: one row per tracked item with its amount
 * in the sacks, the net change this session, that change per hour and, optionally, coins per hour at
 * the market's fair value. Rows come from `sacks.tracked`; numbers from [SackState].
 *
 * Shown on SkyBlock while the list is non-empty. In the inventory (controls on hover) each row gets a
 * `×` that stops tracking it; the footer resets the session.
 */
object SackTrackerHud : TrackerHud<SackTrackerHud.SackRow>("sack_tracker", "Sack Tracker", "general") {
    const val COLUMN_AMOUNT = "amount"
    const val COLUMN_SESSION = "session"
    const val COLUMN_RATE = "rate"
    const val COLUMN_COINS = "coins"

    /** Cell value for "unknown": formats as a dash. */
    private const val UNKNOWN = Long.MIN_VALUE

    data class SackRow(
        val id: String,
        val name: String,
        val color: Int,
        val icon: ItemStack,
        val amount: Long,
        val session: Long,
        val perHour: Long,
        val coinsPerHour: Long,
    )

    override val settings: TrackerSettings get() = SackTrackerSettings

    override val spec: TrackerSpec<SackRow> by lazy {
        TrackerSpec(
            id = "sack_tracker",
            title = "Sacks",
            width = 270,
            columns = listOf(
                TrackerColumn(
                    id = COLUMN_AMOUNT,
                    chipLabel = "Amount",
                    toggleLabel = "Amount in sacks",
                    cellValue = { row, _ -> row.amount },
                    totalValue = { _, _ -> 0L },
                    formatCell = { if (it == UNKNOWN) "-" else compact(it) },
                    chipShouldRender = { false },
                    cellColor = Theme.TEXT,
                    cellWidth = 38f,
                ),
                TrackerColumn(
                    id = COLUMN_SESSION,
                    chipLabel = "Session",
                    toggleLabel = "Gained this session",
                    cellValue = { row, _ -> row.session },
                    totalValue = { _, _ -> 0L },
                    formatCell = { signed(it) },
                    chipShouldRender = { false },
                    cellWidth = 38f,
                ),
                TrackerColumn(
                    id = COLUMN_RATE,
                    chipLabel = "Per hour",
                    toggleLabel = "Per hour",
                    cellValue = { row, _ -> row.perHour },
                    totalValue = { _, _ -> 0L },
                    formatCell = { if (it == UNKNOWN) "-" else "${signed(it)}/h" },
                    chipShouldRender = { false },
                    isCaption = true,
                    cellWidth = 44f,
                ),
                TrackerColumn(
                    id = COLUMN_COINS,
                    chipLabel = "Coins/h",
                    toggleLabel = "Coins per hour",
                    defaultVisible = false,
                    cellValue = { row, _ -> row.coinsPerHour },
                    totalValue = { rows, _ -> rows.sumOf { if (it.coinsPerHour == UNKNOWN) 0L else it.coinsPerHour } },
                    formatCell = { if (it == UNKNOWN) "-" else PriceTooltip.coins(it.toDouble()) },
                    formatChipTotal = { PriceTooltip.coins(it.toDouble()) },
                    cellColor = 0xFFFFAA00.toInt(),
                    cellWidth = 40f,
                ),
            ),
            sorts = listOf(
                TrackerSort(COLUMN_SESSION, "Session", compareByDescending { it.session }, COLUMN_SESSION),
                TrackerSort(COLUMN_AMOUNT, "Amount", compareByDescending { it.amount }, COLUMN_AMOUNT),
                TrackerSort(COLUMN_RATE, "Per hour", compareByDescending { it.perHour }, COLUMN_RATE),
                TrackerSort(COLUMN_COINS, "Coins/h", compareByDescending { it.coinsPerHour }, COLUMN_COINS),
                TrackerSort("alpha", "Alphabetical", compareBy { it.name }),
            ),
            tabs = listOf(TrackerTab.Session),
            rowsProvider = { buildRows() },
            rowLabel = { it.name },
            rowLabelColor = { it.color },
            rowIcon = { it.icon },
            onRowRemove = { SackMenus.untrack(it.id) },
            onResetSession = { SackState.resetSessions() },
            isVisible = { cfg.sacks.hud && SkyblockState.onSkyblock && cfg.sacks.tracked.isNotEmpty() },
            timerLine = { if (cfg.sacks.hudTimer) TimerInfo(sessionClock()) else null },
            emptyText = { "Middle-click items in a sack to track them" },
            scrollableList = { cfg.sacks.tracked.size > 10 },
        )
    }

    override fun configuredOn(): Boolean = cfg.sacks.hud

    override fun settingsScreen(parent: Screen?): Screen =
        SoulConfigScreen.forFeature(parent, "sacks")

    override fun readTransform(): HudTransform = cfg.sacks.hudTransform

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(sacks = sacks.copy(hudTransform = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().sacks.hudTransform

    override fun drawBackground(): Boolean = cfg.sacks.hudBackground

    override fun backgroundColor(): Int = cfg.sacks.hudBackgroundColor.effective()

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Background", { cfg.sacks.hudBackground }) {
            ConfigManager.update { copy(sacks = sacks.copy(hudBackground = !sacks.hudBackground)) }
        },
        HudMenuToggle("Session timer", { cfg.sacks.hudTimer }) {
            ConfigManager.update { copy(sacks = sacks.copy(hudTimer = !sacks.hudTimer)) }
        },
    )

    /** Client running time since the first change after tracking or reset; per-hour rates use the same window. */
    private fun sessionClock(): String {
        val seconds = (SackState.sessionElapsedMs() ?: 0L) / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    private fun buildRows(): List<SackRow> {
        val counts = SackState.allCounts()
        return cfg.sacks.tracked.map { item ->
            val rate = SackState.ratePerHour(item.id)
            val fair = MarketValues.get(item.id.replace(':', '-'))?.fair
            SackRow(
                id = item.id,
                name = item.name.ifEmpty { item.id },
                color = item.color,
                icon = InventoryButtonIcons.stack(InventoryButtonIcons.ItemLook(item.item, item.skin, item.model, item.glint)),
                amount = counts[item.id]?.count ?: UNKNOWN,
                session = SackState.session(item.id)?.net ?: 0L,
                perHour = rate?.roundToLong() ?: UNKNOWN,
                coinsPerHour = if (rate != null && fair != null) (rate * fair).roundToLong() else UNKNOWN,
            )
        }
    }

    /** `950`, `4,494`, `22.2k`, `1.5M`: exact below 100k. */
    private fun compact(value: Long): String =
        if (value < 100_000L) String.format(Locale.ROOT, "%,d", value) else PriceTooltip.coins(value.toDouble())

    private fun signed(value: Long): String {
        val body = compact(kotlin.math.abs(value))
        return if (value > 0) "+$body" else if (value < 0) "-$body" else "0"
    }
}

/** The panel's UI state: sort and columns in `config.json`, everything else in memory. */
object SackTrackerSettings : TrackerSettings {
    override var tab: TrackerTab
        get() = TrackerTab.Session
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    override var sortId: String
        get() = cfg.sacks.sort
        set(value) {
            if (value == cfg.sacks.sort) return
            ConfigManager.update { copy(sacks = sacks.copy(sort = value)) }
        }

    @Volatile
    override var scrollOffset: Float = 0f

    override var filter: Set<String>
        get() = emptySet()
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    override fun isColumnVisible(columnId: String, defaultVisible: Boolean): Boolean =
        cfg.sacks.columns[columnId] ?: defaultVisible

    override fun setColumnVisible(columnId: String, visible: Boolean) {
        ConfigManager.update { copy(sacks = sacks.copy(columns = sacks.columns + (columnId to visible))) }
    }

    @Volatile override var sortDropdownOpen: Boolean = false

    @Volatile override var columnDropdownOpen: Boolean = false

    @Volatile override var filterDropdownOpen: Boolean = false

    override fun markDirty() = Unit
}
