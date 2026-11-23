package com.soulreturns.features.sacks

import com.soulreturns.config.cfg
import com.soulreturns.features.dev.DevOutput
import java.util.Locale

/** `/soul dev sacks`: tracked items with count, source and ages, plus the parser's last word. */
object SackDebug {
    fun dump() {
        DevOutput.begin("/soul dev sacks")
        val now = System.currentTimeMillis()
        val counts = SackState.allCounts()
        DevOutput.line("§7enabled §f${cfg.sacks.hud}§7 · profile bucket §f${SackState.profileKey()}§7 · §f${counts.size}§7 counts · §f${SackState.learnedNames().size}§7 learned names")
        DevOutput.line("§7last summary: §f${SackState.lastSummary}")
        DevOutput.line("§7catalog: §f${com.soulreturns.data.sacks.SackCatalogClient.index.sacks.size}§7 sacks · reporter: §f${SackReporter.summary()}")
        DevOutput.line("")
        DevOutput.line("§eTracked")
        if (cfg.sacks.tracked.isEmpty()) DevOutput.line("§7  (none; middle-click an item in a sack menu)")
        for (item in cfg.sacks.tracked) {
            val count = counts[item.id]
            val session = SackState.session(item.id)
            val rate = SackState.ratePerHour(item.id)
            val state = if (count == null) {
                "§cno count"
            } else {
                String.format(
                    Locale.ROOT,
                    "§f%,d §7(%s %s ago, exact %s)",
                    count.count, count.source.name.lowercase(Locale.ROOT), age(now, count.updatedAt),
                    if (count.exactAt == 0L) "never" else age(now, count.exactAt) + " ago",
                )
            }
            val sessionText = if (session == null) "no change yet" else String.format(
                Locale.ROOT, "net %+,d over %s, %s/h", session.net, age(SackState.sessionElapsedMs(item.id) ?: 0L, 0L),
                rate?.let { String.format(Locale.ROOT, "%,.0f", it) } ?: "-",
            )
            DevOutput.line("§f  ${item.id} §7(${item.name}) $state §7· $sessionText")
        }
        if (SackState.unresolvedNames.isNotEmpty()) {
            DevOutput.line("")
            DevOutput.line("§eChat names without an id §7(open their sack once to learn them)")
            for (name in SackState.unresolvedNames) DevOutput.line("§7  $name")
        }
    }

    private fun age(now: Long, at: Long): String {
        val s = ((now - at) / 1000L).coerceAtLeast(0L)
        return if (s < 120) "${s}s" else if (s < 7200) "${s / 60}m" else "${s / 3600}h"
    }
}
