package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.features.profit.dragon.gui.DragonProfitHudSettings
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.util.soulChat
import java.util.Locale

/**
 * `/soul dragons summary` — the short version, in chat.
 *
 * The full breakdown is [com.soulreturns.features.profit.dragon.gui.DragonSessionScreen], which
 * `/soul dragons` opens. This is the glance: one line per dragon you have killed this session, plus
 * the same for all time, so the feature is verifiable without opening anything — including without
 * the panel, which is the point when someone reports that the panel is not showing.
 */
object DragonSummary {
    fun print() {
        if (!cfg.combat.dragons.enabled) {
            soulChat("§7Dragon tracking is §coff§7 — turn it on in §f/soul §7→ Combat → Dragons.")
            return
        }
        val mode = DragonProfitHudSettings.priceMode
        soulChat("§bDragons §8(${mode.displayName.lowercase(Locale.ROOT)} prices)")
        if (DropCatalogClient.isEmpty()) {
            // Everything below would read as "you have killed nothing" otherwise, which is a
            // different problem with a different fix.
            soulChat("§8  the drop catalog has not loaded — no loot can be identified yet")
        }
        printScope("Session", TrackerTab.Session)
        printScope("All time", TrackerTab.Total)
        soulChat("§8  /soul dragons for the full breakdown per dragon and drop")
    }

    private fun printScope(
        label: String,
        tab: TrackerTab,
    ) {
        val mode = DragonProfitHudSettings.priceMode
        val floor = cfg.combat.dragons.useNpcPriceIfHigher
        val lines = ArrayList<String>()
        for (type in DragonType.entries) {
            val summoned = DragonProfitTracker.killsFor(type, tab, KillSource.SUMMONED)
            val lootshared = DragonProfitTracker.killsFor(type, tab, KillSource.LOOTSHARE)
            if (summoned == 0L && lootshared == 0L) continue
            val net = DragonProfitTracker.profitFor(
                tab = tab,
                bucketFilter = setOf(type),
                mode = mode,
                useNpcFloor = floor,
            )
            lines.add(
                String.format(
                    Locale.ROOT,
                    "§7  %s: §f%,d§7 summoned, §f%,d§7 lootshare — §b%s",
                    type.displayName,
                    summoned,
                    lootshared,
                    DragonProfitAnnouncer.formatCoins(net),
                ),
            )
        }
        if (lines.isEmpty()) {
            soulChat("§7$label: §8nothing yet")
            return
        }
        val net = DragonProfitTracker.profitFor(tab = tab, mode = mode, useNpcFloor = floor)
        soulChat("§7$label: §b${DragonProfitAnnouncer.formatCoins(net)}§7 net")
        for (line in lines) soulChat(line)
    }
}
