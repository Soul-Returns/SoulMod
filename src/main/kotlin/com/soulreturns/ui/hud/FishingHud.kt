package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.features.fishing.FishingHudSettings
import com.soulreturns.features.fishing.FishingTracker
import com.soulreturns.stats.PersistentStats
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.height
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.ScrollableList
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Tabs
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme

/**
 * Fishing HUD — per-creature sea-creature tracker with tabs, sorting, paging, and reset.
 *
 * **Built fresh in P3 on the Soul UI framework** — replaces the legacy `FishingTrackerOverlay`
 * + `gui/lib/tracker/` package. Composables instead of imperative drawing; settings via the
 * per-feature [FishingHudSettings] instead of the generic `TrackerSettingsStore`.
 *
 * Layout:
 * ```
 * ┌─────────────────────────────────────────────┐
 * │ Fishing                    [Session][Total] │  ← header
 * │ ───────────────────────────────────────────  │
 * │ Sea Archer            47     12 DH          │  ← scrollable list,
 * │ Lord Jawbus            3                    │    sorted by sort key,
 * │ ...                                          │    limited by limit
 * │ ───────────────────────────────────────────  │
 * │ [Sort: Catches] [Show: Top 10]   [Reset]    │  ← footer
 * └─────────────────────────────────────────────┘
 * ```
 */
object FishingHud {
    private const val HUD_ID = "fishing_tracker"
    private const val SEPARATOR_HEIGHT = 1f

    fun register() {
        FishingHudSettings.init()
        SoulHud.register(
            id = HUD_ID,
            width = 280,
            height = 280,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.45,
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        val hudCfg = cfg.fishing.fishingHud
        val showHud =
            hudCfg.showHud() &&
                cfg.fishing.fishingTracker.enableTracker() &&
                SkyblockApi.isOnSkyblock
        if (!showHud) {
            Box {}
            return
        }
        val settings = FishingHudSettings.get()

        Surface(modifier = SoulModifier.Empty.fillMaxWidth()) {
            Column(gap = 6f, modifier = SoulModifier.Empty.fillMaxWidth()) {
                Header(settings)
                HorizontalDivider()
                List(settings)
                HorizontalDivider()
                Footer(settings)
            }
        }
    }

    @SoulComposable
    private fun Header(settings: FishingHudSettings.Settings) {
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = com.soulreturns.ui.composer.VerticalAlignment.Center,
        ) {
            Text(
                text = "Fishing",
                size = SoulTheme.typography.heading.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.heading.font,
            )
            Tabs(
                options = FishingHudSettings.Tab.values().map { it.name },
                selectedIndex = settings.tab.ordinal,
                onSelect = { idx ->
                    val newTab = FishingHudSettings.Tab.values()[idx]
                    if (newTab != settings.tab) {
                        settings.tab = newTab
                        settings.scrollOffset = 0f
                        FishingHudSettings.markDirty()
                    }
                },
                keyPrefix = "$HUD_ID.tabs",
            )
        }
    }

    @SoulComposable
    private fun List(settings: FishingHudSettings.Settings) {
        val rows = buildRows(settings)
        ScrollableList(
            scrollOffset = settings.scrollOffset,
            onScroll = { newOffset ->
                settings.scrollOffset = newOffset
                FishingHudSettings.markDirty()
            },
            modifier = SoulModifier.Empty.fillMaxWidth().height(140f),
            gap = 2f,
            key = "$HUD_ID.scroll",
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = "(no data yet)",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textFaint,
                    font = SoulTheme.typography.body.font,
                )
            } else {
                rows.forEach { row -> Row(row) }
            }
        }
    }

    @SoulComposable
    private fun Row(row: CreatureRow) {
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            gap = 6f,
        ) {
            Text(
                text = row.name,
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.body.font,
            )
            Row(gap = 6f) {
                Text(
                    text = "%,d".format(row.catches),
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.mono.font,
                )
                if (row.doubleHooks > 0L) {
                    Text(
                        text = "%,d DH".format(row.doubleHooks),
                        size = SoulTheme.typography.caption.size,
                        color = SoulTheme.colors.textDim,
                        font = SoulTheme.typography.mono.font,
                    )
                }
            }
        }
    }

    @SoulComposable
    private fun Footer(settings: FishingHudSettings.Settings) {
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            gap = 4f,
        ) {
            // Left group: sort + limit
            Row(gap = 4f) {
                Button(
                    label = "Sort: ${settings.sort.label}",
                    onClick = { FishingHudSettings.cycleSort() },
                    key = "$HUD_ID.sort",
                )
                Button(
                    label = "Show: ${formatLimit(settings.limit)}",
                    onClick = { FishingHudSettings.cycleLimit() },
                    key = "$HUD_ID.limit",
                )
            }
            Button(
                label = "Reset",
                onClick = { FishingTracker.resetSession() },
                key = "$HUD_ID.reset",
            )
        }
    }

    @SoulComposable
    private fun HorizontalDivider() {
        Box(
            modifier =
                SoulModifier.Empty
                    .fillMaxWidth()
                    .height(SEPARATOR_HEIGHT)
                    .background(SoulTheme.colors.separator),
        )
    }

    // ───────────────────── row data assembly ─────────────────────

    private data class CreatureRow(val name: String, val catches: Long, val doubleHooks: Long)

    private fun buildRows(settings: FishingHudSettings.Settings): List<CreatureRow> {
        val (catches, doubleHooks) =
            when (settings.tab) {
                FishingHudSettings.Tab.Session ->
                    FishingTracker.sessionCatchesByCreature to FishingTracker.sessionDoubleHooksByCreature
                FishingHudSettings.Tab.Total -> {
                    val stats = PersistentStats.current
                    stats.catchesByCreature to stats.doubleHooksByCreature
                }
            }
        val names = (catches.keys + doubleHooks.keys).toSet()
        val rows =
            names
                .map { name ->
                    val displayName = SeaCreatureCatalog.byName(name)?.name ?: name
                    CreatureRow(
                        name = displayName,
                        catches = catches[name] ?: 0L,
                        doubleHooks = doubleHooks[name] ?: 0L,
                    )
                }
                .sortedWith(
                    when (settings.sort) {
                        FishingHudSettings.Sort.Catches ->
                            compareByDescending<CreatureRow> { it.catches }.thenBy { it.name }
                        FishingHudSettings.Sort.DoubleHooks ->
                            compareByDescending<CreatureRow> { it.doubleHooks }.thenBy { it.name }
                    },
                )
        return if (settings.limit < 0) rows else rows.take(settings.limit)
    }

    private fun formatLimit(limit: Int): String = if (limit < 0) "All" else "Top $limit"
}
