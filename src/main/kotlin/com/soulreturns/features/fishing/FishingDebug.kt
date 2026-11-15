package com.soulreturns.features.fishing

import com.soulreturns.config.cfg
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.fishing.SeaCreatureCatalogClient
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dev.DevOutput
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.stats.PersistentStats
import com.soulreturns.ui.hud.tracker.TrackerHudHost
import com.soulreturns.util.MessageHandler
import com.soulreturns.util.soulChat
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * `/soul fishing` (the player-facing glance) and `/soul dev fishing …` (the diagnostics).
 *
 * The two exist for different questions. The summary answers "how is this session going" and reads
 * like the HUD without needing the HUD. The dev dump answers "why is nothing being counted", which
 * has four unrelated causes — an empty catalog, a visibility gate, an unrecognised chat line, or a
 * stats bucket under the wrong key — and each of them is silent on its own.
 */
object FishingDebug {
    /** `/soul fishing` — the session at a glance, in chat, because it is a mid-grind question. */
    fun summary() {
        val session = FishingTracker.session
        val stats = FishingStatsStore.read()
        soulChat("§bFishing §7— session")
        soulChat("  §7Active time §f${FishingTimer.formatTime()}${if (FishingTimer.isPaused) " §8(paused)" else ""}")
        soulChat("  §7Catches §f${session.catches}§7  Double hooks §f${session.doubleHooks}§7  Cocoons §f${session.cocoons}")
        soulChat("  §7Fishing XP §f${String.format(Locale.ROOT, "%,.0f", session.fishingXp)}")
        FishingTimer.perHour(session.catches)?.let {
            soulChat("  §7Rate §f${String.format(Locale.ROOT, "%.0f", it)}§7/h of active fishing")
        }
        if (FishingFestivalState.active) {
            val remaining = FishingFestivalState.remainingMs() / 1000
            soulChat(
                "  §6Festival §f${stats.festival.catches}§7 catches, " +
                    "§f${String.format(Locale.ROOT, "%dm %02ds", remaining / 60, remaining % 60)}§7 left",
            )
        }
        soulChat("  §7All-time §f${stats.allTime.catches}§7 catches on §f${FishingStatsStore.handle.currentKey()}")
    }

    /** `/soul dev fishing` — everything the feature depends on, in one dump. */
    fun dump() {
        DevOutput.begin("/soul dev fishing")
        catalogBlock()
        DevOutput.line("")
        gateBlock()
        DevOutput.line("")
        countersBlock()
        DevOutput.line("")
        xpBlock()
        DevOutput.line("")
        DevOutput.line("§7Run §f/soul dev fishing unmatched§7 for chat lines the catalog did not recognise,")
        DevOutput.line("§7or §f/soul dev fishing refresh§7 to refetch the catalog now.")
    }

    /**
     * `/soul dev fishing unmatched` — chat lines nothing in the catalog matched, while the player
     * looked like they were fishing.
     *
     * A creature Hypixel adds is missing from the tracker until an admin adds it at
     * `/admin/sea-creatures`. Without this the only symptom is a counter that quietly does not move,
     * which is indistinguishable from not having caught anything. Deliberately over-inclusive — see
     * [FishingTracker]'s capture rules.
     */
    fun unmatched() {
        DevOutput.begin("/soul dev fishing unmatched")
        val lines = FishingTracker.unmatchedLines()
        if (lines.isEmpty()) {
            DevOutput.line("§7No unrecognised catch lines this session.")
            DevOutput.line("§8Kept when a line contains \"CAUGHT!\", when a double hook is pending, or while the fishing")
            DevOutput.line("§8stopwatch is running — the last one is noisy on purpose: most catch lines never say \"CAUGHT!\".")
            return
        }
        DevOutput.line("§7${lines.size} line(s) the sea-creature catalog did not match:")
        DevOutput.line("§8Most are ordinary chat. Paste any that IS a catch line into /admin/sea-creatures.")
        for (line in lines) DevOutput.line("  §f$line")
    }

    /** `/soul dev fishing refresh` — force a catalog refetch and report what came back. */
    fun refresh() {
        DevOutput.begin("/soul dev fishing refresh")
        DevOutput.line("§7Refetching §fGET /sea-creatures§7 …")
        SoulScope.io.launch {
            val outcome = SeaCreatureCatalogClient.refresh()
            DevOutput.line("§7$outcome")
            DevOutput.line("§7Indexed: §f${SeaCreatureCatalog.all().size}§7 creatures")
        }
    }

    /**
     * `/soul dev fishing simulate <line>` — push a line through the chat pump as if Hypixel had sent
     * it.
     *
     * The sticky double-hook flag, the interleaver tolerance and the compactor delta decoding are
     * the three things most likely to break in a rewrite, and all three take a *sequence* of chat
     * lines to exercise. Waiting to catch a real double hook to test that is hours per attempt.
     *
     * It goes through [MessageHandler.simulateMessage], so it is the real path with real side
     * effects — including the `/pc` announcement if that toggle is on. That is the point: it is how
     * you check the announcement fires once and only in a party.
     *
     * Two things to know when using it. The pump drops a line identical to the previous one inside
     * 50 ms, so send repeats with a pause. And the tracker ignores everything while off SkyBlock.
     */
    fun simulate(line: String) {
        DevOutput.begin("/soul dev fishing simulate")
        DevOutput.line("§7Injecting as a server chat line:")
        DevOutput.line("  §f$line")
        if (!SkyblockState.onSkyblock) {
            DevOutput.line("§eNot on SkyBlock — the tracker ignores every line until you are.")
        }
        val before = FishingTracker.session
        MessageHandler.simulateMessage(line)
        val after = FishingTracker.session
        DevOutput.line(
            "§7session: catches §f${before.catches}§8→§f${after.catches}§7  " +
                "double hooks §f${before.doubleHooks}§8→§f${after.doubleHooks}§7  " +
                "cocoons §f${before.cocoons}§8→§f${after.cocoons}",
        )
        DevOutput.line("§8Sequences worth trying, one line per command:")
        DevOutput.line("§8  It's a Double Hook!  /  > Your bottle of thunder has fully charged!  /  A Squid appeared.")
        DevOutput.line("§8  It's a Double Hook! (2)  then  It's a Double Hook! (3)   — each adds the delta, not 1")
        DevOutput.line("§8  CAUGHT! You cocooned a Squid!   — cocoons only; catches and the flag untouched")
    }

    // ------------------------------------------------------------------ blocks

    private fun catalogBlock() {
        val status = SeaCreatureCatalogClient.status()
        DevOutput.line("§bSea-creature catalog §8(GET /sea-creatures)")
        DevOutput.line("  entries=§f${status.entries}§r indexed=§f${SeaCreatureCatalog.all().size}")
        DevOutput.line("  updatedAt=§f${status.updatedAt?.let { Instant.ofEpochMilli(it) } ?: "-"}")
        DevOutput.line("  source=§f${status.source}")
        DevOutput.line("  lastRefresh=§f${ago(status.lastRefreshAt)}§r lastSuccess=§f${ago(status.lastSuccessAt)}")
        DevOutput.line("  lastError=§f${status.lastError ?: "-"}")
        if (status.entries == 0) {
            DevOutput.line("  §8An empty catalog matches nothing — the tracker counts double hooks but no catches.")
        }
    }

    private fun gateBlock() {
        DevOutput.line("§bHUD visibility §8(all three gates must pass)")
        DevOutput.line("  toggle=§f${cfg.fishing.trackerHud}§r onSkyblock=§f${SkyblockState.onSkyblock}")
        DevOutput.line("  area=§f${SkyblockLocation.area ?: "-"}§r sublocation=§f${SkyblockLocation.subLocation ?: "-"}")
        DevOutput.line("  gate=§f${FishingVisibility.gateReason()}")
        DevOutput.line("  visible=§f${FishingVisibility.isVisible}")
        DevOutput.line(
            "  timer=§f${FishingTimer.formatTime()}§r ${if (FishingTimer.isPaused) "§8(paused)" else "§a(running)"}",
        )
        DevOutput.line("  bobbers nearby=§f${BobbinSpotter.nearbyBobbers}§r (counted only while the alert is on)")
        DevOutput.line("  inventory open=§f${TrackerHudHost.inventoryOpen}§r (the tab strip and footer only appear then)")
    }

    /**
     * The action bar, verbatim, plus what the XP regex made of it.
     *
     * Fishing XP is read from the bar rather than from the catalog, because every real account
     * multiplies the base value. That makes the exact wording load-bearing, and Hypixel has reworded
     * skill-XP action bars before — so this prints the raw line, which is the one thing that turns a
     * silent zero into a one-line fix.
     */
    private fun xpBlock() {
        DevOutput.line("§bFishing XP §8(read from the action bar, never from the catalog)")
        DevOutput.line("  last action bar=§f${FishingXpTracker.lastActionBar.ifBlank { "-" }}")
        DevOutput.line("  last match=§f${FishingXpTracker.lastMatch ?: "never matched"}")
        if (FishingXpTracker.lastMatch == null && FishingXpTracker.lastActionBar.isNotBlank()) {
            DevOutput.line("  §8Expected shape: \"+12.5 Fishing (1,234.5/5,000)\" or \"+12.5 Fishing (34.5%)\".")
            DevOutput.line("  §8If the bar above says something else while you fish, paste it — the regex needs updating.")
        }
    }

    private fun countersBlock() {
        val stats = FishingStatsStore.read()
        DevOutput.line("§bCounters")
        DevOutput.line("  stats file=§f${PersistentStats.file()}")
        DevOutput.line("  profile=§f${SkyblockProfileState.current ?: "-"}§r bucket=§f${FishingStatsStore.handle.currentKey()}")
        DevOutput.line("  buckets stored=§f${FishingStatsStore.handle.knownKeys().sorted()}")
        line("session", FishingTracker.session)
        line("festival", stats.festival)
        line("all-time", stats.allTime)
        DevOutput.line(
            "  festival active=§f${FishingFestivalState.active}§r startAt=§f${stats.festivalStartAt}" +
                if (FishingFestivalState.active) "§r remaining=§f${FishingFestivalState.remainingMs() / 1000}s" else "",
        )
    }

    private fun line(
        label: String,
        counts: FishingCounts,
    ) {
        DevOutput.line(
            "  $label: catches=§f${counts.catches}§r dh=§f${counts.doubleHooks}§r " +
                "cocoons=§f${counts.cocoons}§r xp=§f${counts.fishingXp}§r creatures=§f${counts.creatureNames().size}",
        )
    }

    private fun ago(instant: Instant?): String {
        if (instant == null) return "never"
        val seconds = Duration.between(instant, Instant.now()).seconds
        return if (seconds < 60) "${seconds}s ago" else "${seconds / 60}m ${seconds % 60}s ago"
    }
}
