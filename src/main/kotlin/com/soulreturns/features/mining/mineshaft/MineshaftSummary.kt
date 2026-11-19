package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.soulChat
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * `/soul mineshaft` — what this mineshaft is, and what every mineshaft has added up to.
 *
 * Chat rather than a screen, deliberately: this is a glance mid-grind, where opening a GUI would take
 * the player's hands off the game to answer a question that fits in six lines. The full breakdown —
 * per type, per instance, per corpse, spawn and loot rates — is in the Profile Viewer's **Soul** tab,
 * which this lane's backend namespace turns on, so there is exactly one place for detail and exactly
 * one place for a glance.
 *
 * The current-visit half is local and instant. The lifetime half is one `GET /mineshaft/stats`, cached
 * by the backend for 30 s, so hammering the command costs one request. It shows the **active
 * profile's** numbers, with the all-profiles total on one secondary line; until the profile id is
 * known it shows the total alone.
 */
object MineshaftSummary {
    fun print() {
        printCurrentVisit()
        printLifetime()
    }

    private fun printCurrentVisit() {
        val entered = MineshaftState.enteredAt
        if (entered == null) {
            soulChat("§7Not in a mineshaft right now.")
            return
        }

        val identifier = MineshaftState.serverInstance ?: "§8resolving…"
        val elapsed = Duration.between(entered, Instant.now())
        soulChat("§bMineshaft §f$identifier §7(${formatDuration(elapsed.seconds)})")

        val sourceLine = if (MineshaftState.source == VisitSource.DISCOVERED) {
            "§aself-discovered"
        } else if (MineshaftState.source == VisitSource.WARPED) {
            "§ewarped in by §f${MineshaftState.warpedBy ?: "?"}"
        } else {
            "§7unknown§8 (neither entry message arrived in time)"
        }
        soulChat("§7  source: $sourceLine")

        val m = cfg.mining.mineshaft
        val tracking = m.logVisits || m.corpsesHud || m.littlefootPtme || m.lapisPtme || m.vanguardPtme || m.vanguardGuild
        val corpses = MineshaftCorpses.byType
        if (!tracking) {
            // Distinguish "this mineshaft has no corpses" from "nothing is watching for them". Both
            // print an empty list otherwise, and only one of them is about the mineshaft.
            soulChat("§8  corpses: not tracked — every Mineshaft feature is switched off")
        } else if (corpses.isEmpty()) {
            soulChat("§7  corpses: §8none")
        } else {
            val rendered = corpses.entries
                .sortedWith(compareByDescending<Map.Entry<String, MineshaftCorpses.Counts>> { it.value.total }.thenBy { it.key })
                .joinToString("§7, ") { (type, counts) -> "§f$type §a${counts.looted}§7/§c${counts.total}" }
            soulChat("§7  corpses: $rendered")
        }
        if (MineshaftState.littlefootFound) soulChat("§7  §dLittlefoot§7 seen this visit")

        if (!m.logVisits) {
            soulChat("§8  visit logging is off — this one will not be recorded")
        }
    }

    private fun printLifetime() {
        val queued = MineshaftOutbox.pending()
        if (queued > 0) soulChat("§8  $queued visit(s) waiting to reach the backend (${MineshaftOutbox.lastOutcome})")

        // Read on the calling (client) thread, before the request: the profile in view is the one the
        // command was typed on.
        val profileId = SkyblockProfileState.profileId
        val profileName = SkyblockProfileState.current
        SoulScope.io.launch {
            val result = MineshaftStatsClient.stats(profileId)
            if (result !is BackendResult.Ok) {
                val failure = result as BackendResult.Failure
                soulChat("§8Lifetime stats unavailable: ${failure.message}")
                return@launch
            }
            val total = result.value
            if (total.totalVisits == 0) {
                soulChat("§8No mineshaft visits recorded yet.")
                return@launch
            }

            val own = total.forProfile(profileId)
            if (own == null) {
                // Profile id not known yet (or a backend without the split): the total is all there is.
                printStats("§bAll profiles", total)
            } else {
                val label = "§b${profileName ?: own.profileName ?: "This profile"}"
                if (own.totalVisits == 0) {
                    soulChat("$label§8: no mineshaft visits recorded on this profile yet.")
                } else {
                    printStats(label, own)
                }
                val unassigned = if (total.unknownProfileVisits > 0) {
                    "§8, ${total.unknownProfileVisits} of them without a profile"
                } else {
                    ""
                }
                soulChat(
                    "§8  all profiles: §7${total.totalVisits} visits, §dLittlefoot§7 ${total.littlefootFound} " +
                        "(${percent(total.littlefootFindRate)})$unassigned",
                )
            }
            soulChat("§8  full breakdown: /spv → Soul tab")
        }
    }

    private fun printStats(
        label: String,
        stats: MineshaftStats,
    ) {
        soulChat(
            "$label §f${stats.totalVisits}§7 visits, §f${formatDuration(stats.totalDurationSec)}§7 " +
                "total, §f${formatDuration(stats.averageDurationSec.toLong())}§7 average",
        )
        soulChat(
            "§7  §dLittlefoot§7: §f${stats.littlefootFound}§7 " +
                "(§f${percent(stats.littlefootFindRate)}§7 of visits)",
        )

        // Own-odds only: a warped or unclassified visit is someone else's mineshaft, and folding
        // it in is exactly what the three-state `source` exists to stop.
        stats.bySource[VisitSource.DISCOVERED]?.let { own ->
            soulChat("§7  self-discovered: §f${own.visits}§7 of §f${stats.totalVisits}")
        }

        val corpses = stats.byCorpse.entries.sortedByDescending { it.value.found }
        if (corpses.isNotEmpty()) {
            val rendered = corpses.joinToString("§7, ") { (type, c) ->
                "§f$type §a${c.looted}§7/§c${c.found}§8 (${percent(c.spawnRate)} of shafts)"
            }
            soulChat("§7  corpses: $rendered")
        }
    }

    /** `1h 04m` / `12m 30s` / `45s` — never a bare second count, which reads as a timestamp. */
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%dh %02dm", h, m)
        } else if (m > 0) {
            String.format(Locale.ROOT, "%dm %02ds", m, s)
        } else {
            "${s}s"
        }
    }

    private fun percent(rate: Double): String = String.format(Locale.ROOT, "%.1f%%", rate * 100)
}
