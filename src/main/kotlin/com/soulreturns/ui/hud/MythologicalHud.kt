package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.skyblock.MythologicalMobCatalog
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.diana.MythologicalActivityTimer
import com.soulreturns.features.diana.MythologicalHudSettings
import com.soulreturns.features.diana.MythologicalMobTracker
import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.gui.lib.HudVerticalAnchor
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.hud.tracker.TimerInfo
import com.soulreturns.ui.hud.tracker.TrackerColumn
import com.soulreturns.ui.hud.tracker.TrackerHud
import com.soulreturns.ui.hud.tracker.TrackerSort
import com.soulreturns.ui.hud.tracker.TrackerSpec
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import java.util.Locale

/**
 * Mythological mob HUD — per-mob counter for Mayor Diana's Mythological Ritual event.
 *
 * **Three tabs:**
 *  - Session (in-memory, resets on launch / reset button)
 *  - Event (persisted, scoped to the current Hypixel mayor term — the mayor's name is
 *    intentionally not surfaced in the HUD; it's only the storage key)
 *  - Total (persisted all-time across every mayor)
 *
 * **Header:** active stopwatch from [MythologicalActivityTimer] on the left, tabs on the
 * right (framework default). The activity timer drives the bottom-line `mobs/hour` chip.
 *
 * **Visibility gate.** SkyBlock + Hub area + (master toggle OR per-feature toggle) + the
 * Mythological tracker not being globally disabled. Outside the Hub the HUD collapses to
 * `Box {}` so it doesn't follow the player into Garden / Dungeons / etc.
 */
object MythologicalHud {
    private const val HUD_ID = "mythological_tracker"

    /** One row per mob in the catalog (whether or not it has any kills yet). */
    data class MobRow(
        val name: String,
        val displayName: String,
        val rarity: String,
        val rarityOrdinal: Int,
        val count: Long,
        val cocoons: Long,
        /**
         * This row's share of the panel's headline total, in **tenths of a percent**
         * (e.g. `125` = 12.5 %). Precomputed at build time because `cellValue` only sees
         * its own row — the framework's per-row callback can't reach across rows for the
         * denominator. Honors `cfg.combat.diana.addCocoonsToTotal()` so numerator and
         * denominator agree with the chip's `Mobs:` total.
         */
        val percentTenths: Long,
    )

    private val spec: TrackerSpec<MobRow> by lazy {
        TrackerSpec(
            id = HUD_ID,
            title = "Mythological Mobs",
            width = 260,
            height = 360,
            defaultAnchorX = 0.01,
            defaultAnchorY = 0.30,
            defaultHorizontalAnchor = HudHorizontalAnchor.Start,
            defaultVerticalAnchor = HudVerticalAnchor.Top,
            settingsCategory = "combat",
            settingsSubcategory = "diana",
            tabs = listOf(TrackerTab.Session, TrackerTab.Event, TrackerTab.Total),
            columns =
                listOf(
                    TrackerColumn(
                        id = MythologicalHudSettings.COLUMN_COCOONS,
                        chipLabel = "CC",
                        toggleLabel = "Cocoons",
                        defaultVisible = true,
                        cellValue = { row, _ -> row.cocoons },
                        // Chip is rendered by the custom [ChipLine] override below — leave
                        // totalValue as a per-column sum so the framework's default chip path
                        // still works if the override is removed.
                        totalValue = { rows, _ -> rows.sumOf { it.cocoons } },
                        formatCell = { String.format(Locale.ROOT, "%,d CC", it) },
                        isCaption = true,
                    ),
                    TrackerColumn(
                        id = MythologicalHudSettings.COLUMN_PERCENT,
                        chipLabel = "%",
                        toggleLabel = "Percentage",
                        defaultVisible = true,
                        cellValue = { row, _ -> row.percentTenths },
                        // No meaningful chip — column percentages would always sum to 100,
                        // which is noise on the chip line. ChipShouldRender = false keeps
                        // the column toggleable + sortable while skipping the bottom chip.
                        totalValue = { _, _ -> 0L },
                        formatCell = { String.format(Locale.ROOT, "%.1f%%", it / 10.0) },
                        isCaption = true,
                        chipShouldRender = { false },
                        cellWidth = 42f,
                    ),
                    TrackerColumn(
                        id = MythologicalHudSettings.COLUMN_COUNT,
                        chipLabel = "Mobs",
                        toggleLabel = "Count",
                        defaultVisible = true,
                        // Per-row count rolls cocoons in when the toggle is on (mirrors the
                        // chip total + mobs/hr denominator computed in [ChipLine]).
                        cellValue = { row, _ ->
                            row.count + if (cfg.combat.diana.addCocoonsToTotal()) row.cocoons else 0L
                        },
                        totalValue = { rows, _ ->
                            val addCC = cfg.combat.diana.addCocoonsToTotal()
                            rows.sumOf { it.count + if (addCC) it.cocoons else 0L }
                        },
                        formatCell = { String.format(Locale.ROOT, "%,d", it) },
                    ),
                ),
            sorts =
                listOf(
                    TrackerSort(
                        id = MythologicalHudSettings.SORT_COUNT,
                        label = "Count",
                        comparator = compareByDescending { it.count },
                        requiresColumnId = MythologicalHudSettings.COLUMN_COUNT,
                    ),
                    TrackerSort(
                        id = MythologicalHudSettings.SORT_COCOONS,
                        label = "Cocoons",
                        comparator = compareByDescending { it.cocoons },
                        requiresColumnId = MythologicalHudSettings.COLUMN_COCOONS,
                    ),
                    TrackerSort(
                        id = MythologicalHudSettings.SORT_PERCENT,
                        label = "Percentage",
                        comparator = compareByDescending { it.percentTenths },
                        requiresColumnId = MythologicalHudSettings.COLUMN_PERCENT,
                    ),
                    TrackerSort(
                        id = MythologicalHudSettings.SORT_RARITY,
                        label = "Rarity",
                        comparator = compareByDescending { it.rarityOrdinal },
                    ),
                    TrackerSort(
                        id = MythologicalHudSettings.SORT_ALPHABETICAL,
                        label = "Alphabetical",
                        comparator = compareBy { it.displayName },
                    ),
                ),
            rowsProvider = ::buildRows,
            rowKey = { it.name },
            rowLabel = { it.displayName },
            rowLabelColor = { SkyblockRarity.colorFor(it.rarity) },
            onResetSession = { MythologicalMobTracker.resetSession() },
            scrollableList = { cfg.combat.diana.scrollableList() },
            isVisible = ::isHudVisible,
            timerLine = {
                // Timer text mirrors the active tab — Session shows the live session clock,
                // Event / Total show their persistent cumulative active-time clocks.
                // `(Paused)` is a live status indicator of the underlying activity timer
                // (not a property of any single tab) — surfaces on every tab so the user
                // can tell at a glance whether the clocks are currently accumulating.
                val tab = MythologicalHudSettings.tab
                val ms =
                    if (tab == TrackerTab.Session) {
                        MythologicalActivityTimer.totalMs
                    } else if (tab == TrackerTab.Event) {
                        MythologicalMobTracker.eventActiveMs()
                    } else {
                        MythologicalMobTracker.totalActiveMs()
                    }
                TimerInfo(
                    text = MythologicalActivityTimer.formatMs(ms),
                    paused = MythologicalActivityTimer.isPaused,
                )
            },
            chipOverride = { ChipLine() },
        )
    }

    fun register() {
        MythologicalHudSettings.init()
        SoulHud.register(
            id = HUD_ID,
            width = spec.width,
            height = spec.height,
            defaultAnchorX = spec.defaultAnchorX,
            defaultAnchorY = spec.defaultAnchorY,
            defaultHorizontalAnchor = spec.defaultHorizontalAnchor,
            defaultVerticalAnchor = spec.defaultVerticalAnchor,
            settingsCategory = spec.settingsCategory,
            settingsSubcategory = spec.settingsSubcategory,
        ) {
            TrackerHud(spec, MythologicalHudSettings)
        }
    }

    // ───────────────────── chip override ─────────────────────

    /**
     * Bottom chip line: `"CC: N"` flush-left (when the cocoon column is visible),
     * `"Mobs: N"` flush-left otherwise, and `"Mobs/hr: P"` flush-right. When both Mobs and
     * Cocoons are visible, both stack on the left side with a separator dot. Mobs/hour
     * uses the tab-appropriate active-time clock — Session uses the live timer, Event /
     * Total use the persisted clocks the tracker accumulates.
     */
    @SoulComposable
    private fun ChipLine() {
        val tab = MythologicalHudSettings.tab
        // if/else over enum (not when) — avoids Kotlin's synthetic `$WhenMappings` class
        // per CLAUDE.md's note. The tab really has three possible values here (the spec
        // includes Event); ordering the conditions so Total is the catch-all keeps the
        // intent clear.
        val baseTotal: Long
        val cocoonTotal: Long
        val activeMs: Long
        if (tab == TrackerTab.Session) {
            baseTotal = MythologicalMobTracker.sessionTotal
            cocoonTotal = MythologicalMobTracker.sessionCocoonsTotal
            activeMs = MythologicalActivityTimer.totalMs
        } else if (tab == TrackerTab.Event) {
            baseTotal = MythologicalMobTracker.eventTotal()
            cocoonTotal = MythologicalMobTracker.eventCocoonsTotal()
            activeMs = MythologicalMobTracker.eventActiveMs()
        } else {
            baseTotal = MythologicalMobTracker.totalAll()
            cocoonTotal = MythologicalMobTracker.totalCocoonsAll()
            activeMs = MythologicalMobTracker.totalActiveMs()
        }
        val total = baseTotal + if (cfg.combat.diana.addCocoonsToTotal()) cocoonTotal else 0L
        val perHour = MythologicalActivityTimer.perHour(total, activeMs)
        val showMobs = MythologicalHudSettings.isColumnVisible(MythologicalHudSettings.COLUMN_COUNT, true)
        val showCocoons = MythologicalHudSettings.isColumnVisible(MythologicalHudSettings.COLUMN_COCOONS, true)
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = VerticalAlignment.Center,
            gap = 8f,
        ) {
            // Left cluster — pack Mobs + Cocoons into a single sub-Row so SpaceBetween
            // still anchors the right-side mobs/hr chip flush-right.
            Row(verticalAlignment = VerticalAlignment.Center, gap = 6f) {
                if (showMobs) {
                    Text(
                        text = "Mobs: ${String.format(Locale.ROOT, "%,d", total)}",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.accent,
                        font = SoulTheme.typography.mono.font,
                    )
                }
                if (showMobs && showCocoons) {
                    Text(
                        text = "·",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.textFaint,
                        font = SoulTheme.typography.body.font,
                    )
                }
                if (showCocoons) {
                    Text(
                        text = "CC: ${String.format(Locale.ROOT, "%,d", cocoonTotal)}",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.accent,
                        font = SoulTheme.typography.mono.font,
                    )
                }
            }
            if (perHour > 0L) {
                Text(
                    text = "Mobs/hr: ${String.format(Locale.ROOT, "%,d", perHour)}",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.mono.font,
                )
            } else {
                // Empty placeholder so the SpaceBetween still anchors the left cluster flush-left.
                Text(
                    text = "",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.mono.font,
                )
            }
        }
    }

    // ───────────────────── row assembly ─────────────────────

    private fun buildRows(tab: TrackerTab): List<MobRow> {
        // if/else over enum — see [ChipLine] for the WhenMappings rationale.
        val counts: Map<String, Long>
        val cocoons: Map<String, Long>
        if (tab == TrackerTab.Session) {
            counts = MythologicalMobTracker.sessionByMob
            cocoons = MythologicalMobTracker.sessionCocoonsByMob
        } else if (tab == TrackerTab.Event) {
            counts = MythologicalMobTracker.eventByMob()
            cocoons = MythologicalMobTracker.eventCocoonsByMob()
        } else {
            counts = MythologicalMobTracker.totalByMob()
            cocoons = MythologicalMobTracker.totalCocoonsByMob()
        }
        val addCC = cfg.combat.diana.addCocoonsToTotal()
        // Headline denominator matches the chip's `Mobs:` value — the user's mental model
        // is "% of the number I see at the bottom of the panel".
        val grandTotal =
            MythologicalMobCatalog.all().sumOf { mob ->
                val c = counts[mob.name] ?: 0L
                val cc = cocoons[mob.name] ?: 0L
                c + if (addCC) cc else 0L
            }
        // Hide rows where BOTH counts are 0 — a mob with cocoons but no dig-out kills still
        // earns its row (rare but possible if a cocooned spawn was attributed to someone
        // else's dig). Mobs with no engagement at all stay hidden.
        return MythologicalMobCatalog.all()
            .mapNotNull { mob ->
                val count = counts[mob.name] ?: 0L
                val cocoonCount = cocoons[mob.name] ?: 0L
                if (count <= 0L && cocoonCount <= 0L) return@mapNotNull null
                val rowEffective = count + if (addCC) cocoonCount else 0L
                val pctTenths = if (grandTotal > 0L) rowEffective * 1000L / grandTotal else 0L
                MobRow(
                    name = mob.name,
                    displayName = mob.displayName,
                    rarity = mob.rarity,
                    rarityOrdinal = SkyblockRarity.forName(mob.rarity)?.ordinal ?: -1,
                    count = count,
                    cocoons = cocoonCount,
                    percentTenths = pctTenths,
                )
            }
    }

    private fun isHudVisible(): Boolean {
        if (!cfg.combat.diana.showMobHud()) return false
        if (!cfg.dev.trackers.mythologicalTracker()) return false
        if (!SkyblockApi.isOnSkyblock) return false
        // Mythological Ritual mobs only spawn in the Hub. The HUD respects that — outside the
        // Hub there's nothing to track. Tracker itself ignores chat regardless of area, so
        // counts can't grow there.
        return LocationApi.isInArea("Hub")
    }
}
