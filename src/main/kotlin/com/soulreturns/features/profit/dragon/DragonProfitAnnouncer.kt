package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import net.minecraft.client.Minecraft
import java.util.Locale

/**
 * Per-kill dragon profit announcer. Invoked by [DragonLootScanner] exactly once per
 * scan window, 5 seconds after the first loot stand is parsed for the kill.
 *
 * **Profit math is net of the local player's own eye cost for *this* kill.**
 * `gross = sum(price × count)` over the drop snapshot at instant-buy prices; `eyeCost =
 * ownEyes × price(SUMMONING_EYE)` where `ownEyes` is captured per-spawn by
 * [EyePlacementTracker] (the "attributing N own eye(s)" log line) and threaded through
 * [DragonLootScanner.ActiveScan]. The announced number is `gross − eyeCost`. Lootshare
 * kills always have `ownEyes = 0`, so no subtraction.
 *
 * Routing follows the [cfg.combat.dragons.sendDragonProfit] master toggle and the
 * [cfg.combat.dragons.sendDragonProfitToPartyChat] sub-toggle:
 *  - master OFF → no announcement at all
 *  - master ON, sub ON → `/pc <Type> profit: <net> coins (gross <gross> − <eyeCost> eyes)` (skipped when solo)
 *  - master ON, sub OFF → local `[Soul] <Type> profit: <net> coins (…)` only
 *
 * The breakdown is omitted when `ownEyes == 0` (lootshare kills, or summoned-but-no-own-eyes
 * edge case) — just `"<Type> profit: <gross> coins"`. Keeps the line short for the common
 * lootshare path where there's nothing to subtract.
 *
 * Driven by direct call rather than the [com.soulreturns.core.events.Events] bus to avoid
 * adding a one-shot event type. The scanner already owns the lifecycle of the scan
 * window; piping the summary back through it stays in the same package.
 */
object DragonProfitAnnouncer {
    private val logger = SoulLogger("Soul/DragonProfit")

    fun register() {
        // No subscriptions — fired by direct call from [DragonLootScanner.tick]. Method
        // kept for symmetry with the other `*.register()` entries in [Soul.registerFeatures].
    }

    /**
     * Invoked by [DragonLootScanner] when the per-scan delayed summary timer expires.
     * The [drops] map is a defensive copy of `grantedSoFar` at trigger time; safe to
     * iterate without locking even if the scan window keeps mutating the original.
     * [ownEyes] is the local player's eye contribution for this specific kill, snapshotted
     * at scan-begin time so back-to-back spawns can't poison it.
     */
    fun onDragonLootSummary(
        dragonType: DragonType,
        killSource: KillSource,
        drops: Map<DragonDrop, Long>,
        ownEyes: Long,
    ) {
        if (!cfg.combat.dragons.sendDragonProfit()) return
        val player = Minecraft.getInstance().player ?: return
        val toParty = cfg.combat.dragons.sendDragonProfitToPartyChat()
        if (toParty && !PartyManager.isInParty()) {
            DebugLogger.logFeatureEvent("DragonProfitAnnouncer: ${dragonType.displayName} kill but not in a party — skipping /pc")
            return
        }
        val gross =
            drops.entries.sumOf { (drop, count) ->
                PriceCache.price(drop.itemId, PriceSource.BAZAAR_INSTANT_BUY) * count
            }
        val eyeUnitPrice = PriceCache.price("SUMMONING_EYE", PriceSource.BAZAAR_INSTANT_BUY)
        val eyeCost = ownEyes * eyeUnitPrice
        val net = gross - eyeCost
        val msg =
            if (ownEyes > 0L && eyeCost > 0L) {
                String.format(
                    Locale.ROOT,
                    "%s profit: %s coins (gross %s − %s for %d eye%s)",
                    dragonType.displayName,
                    formatCoins(net),
                    formatCoins(gross),
                    formatCoins(eyeCost),
                    ownEyes,
                    if (ownEyes == 1L) "" else "s",
                )
            } else {
                // Lootshare or summoned-with-no-own-eyes — no deduction segment, just gross.
                String.format(
                    Locale.ROOT,
                    "%s profit: %s coins",
                    dragonType.displayName,
                    formatCoins(gross),
                )
            }
        if (toParty) {
            player.connection.sendCommand("pc $msg")
            logger.info("Sent /pc dragon-profit summary ($killSource, $ownEyes eyes): $msg")
        } else {
            soulChat(msg)
            logger.info("Sent local dragon-profit summary ($killSource, $ownEyes eyes, party-chat sub-toggle off): $msg")
        }
    }

    /**
     * Short coin formatter consistent with [com.soulreturns.ui.hud.DragonProfitHud]'s chip
     * formatter: `"1.2k"`, `"15.4M"`, `"-3.7M"`. Negative values shouldn't appear here
     * since we're summing gross drops, but the sign branch is kept for safety.
     */
    private fun formatCoins(v: Long): String {
        val abs = kotlin.math.abs(v)
        val sign = if (v < 0) "-" else ""
        return when {
            abs < 1_000L -> String.format(Locale.ROOT, "%s%d", sign, abs)
            abs < 1_000_000L -> String.format(Locale.ROOT, "%s%.1fk", sign, abs / 1_000.0)
            abs < 1_000_000_000L -> String.format(Locale.ROOT, "%s%.1fM", sign, abs / 1_000_000.0)
            else -> String.format(Locale.ROOT, "%s%.1fB", sign, abs / 1_000_000_000.0)
        }
    }
}
