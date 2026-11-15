package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.features.profit.dragon.gui.DragonProfitHudSettings
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import com.soulreturns.util.withOutgoingPrefix
import net.minecraft.client.Minecraft
import java.util.Locale

/**
 * The per-kill profit line, fired once per scan window five seconds after the first loot stand.
 *
 * ### The announced number is net of what this kill cost you
 * `gross = Σ(price × count)` over the drops credited so far; `eyeCost = ownEyes × price(SUMMONING_EYE)`
 * where `ownEyes` is what [EyePlacementTracker] attributed to *this* spawn and [DragonLootScanner]
 * froze at scan-begin. The announced figure is `gross − eyeCost`. That subtraction is the reason the
 * feature is worth having: a Superior that dropped 14M off eight eyes you paid 9M for is not a 14M
 * kill, and the gross number quietly flatters every summoner.
 *
 * **The breakdown is omitted when you placed no eyes**, which is every lootshare kill — the common
 * case — so the usual line stays short and only says something extra when there is something to say.
 *
 * ### Routing
 * - master off → nothing at all
 * - master on, party sub-toggle on, in a party → `/pc`
 * - master on, party sub-toggle on, solo → local `[Soul]` chat
 * - master on, party sub-toggle off → local `[Soul]` chat always
 *
 * The solo fallback is not politeness: **Hypixel silently drops `/pc` when you are not in a party**,
 * so a blind send is an invisible failure that looks like a broken feature.
 *
 * ### This is a conversational message, so it takes the `[Soul]` prefix
 * Unlike `!ptme` and waypoint shares, which are protocol other mods parse and must stay raw. Do not
 * "fix" this to match them.
 *
 * ### There is no `/ac` path
 * An early prototype had one and hit Hypixel's *"You cannot say the same message twice!"* anti-spam.
 * Party chat and local-only are the supported set.
 */
object DragonProfitAnnouncer {
    private val logger = SoulLogger("Soul/DragonProfit")

    /**
     * Called directly by [DragonLootScanner] rather than through the event bus — the scanner owns the
     * window's lifecycle and this is a one-shot callback inside the same package, so an event type
     * would be ceremony with one publisher and one subscriber.
     *
     * @param drops a copy of the granted ledger at trigger time; the window keeps mutating its own.
     */
    fun onDragonLootSummary(
        dragonType: DragonType,
        killSource: KillSource,
        drops: Map<String, Long>,
        ownEyes: Long,
    ) {
        if (!cfg.combat.dragons.enabled || !cfg.combat.dragons.sendDragonProfit) return
        val player = Minecraft.getInstance().player ?: return

        val mode = DragonProfitHudSettings.priceMode
        val useNpcFloor = cfg.combat.dragons.useNpcPriceIfHigher
        val gross = drops.entries.sumOf { (itemId, count) ->
            PriceCache.sellValue(DropResolver.priceLookupId(itemId), mode, useNpcFloor) * count
        }
        // `value`, not `price`: an eye has no NPC buyer, so under an NPC price source the honest
        // per-venue answer is 0 — and a zero eye cost would silently overstate every summoned kill's
        // profit by the whole cost of the eyes. What an eye cost you is a valuation question.
        //
        // Still no NPC floor: that toggle values things you SELL, and an eye is something you bought,
        // so flooring it could only ever inflate the subtraction.
        val eyeCost = ownEyes * PriceCache.buyCost(DragonProfitTracker.SUMMONING_EYE_ID, mode)
        val net = gross - eyeCost

        // `displayName` is the bare type ("Old"); chat wants "Old Dragon" so the line reads as a
        // sentence. It stays bare on the panel, where "Dragon" is already the title.
        val message = if (ownEyes > 0L && eyeCost > 0L) {
            String.format(
                Locale.ROOT,
                "%s Dragon profit: %s coins (gross %s - %s for %d eye%s)",
                dragonType.displayName,
                formatCoins(net),
                formatCoins(gross),
                formatCoins(eyeCost),
                ownEyes,
                if (ownEyes == 1L) "" else "s",
            )
        } else {
            String.format(Locale.ROOT, "%s Dragon profit: %s coins", dragonType.displayName, formatCoins(gross))
        }

        if (cfg.combat.dragons.sendDragonProfitToPartyChat && PartyManager.isInParty()) {
            player.connection.sendCommand("pc ${withOutgoingPrefix(message)}")
            logger.info("Sent /pc profit summary ($killSource, $ownEyes eyes): $message")
        } else {
            soulChat(message)
            logger.info("Sent local profit summary ($killSource, $ownEyes eyes): $message")
        }
    }

    /**
     * `1.2k` / `15.4M` / `-3.7M`. Compact because these run to ten figures and a raw
     * `1,234,567,890` in a chat line is unreadable; `Locale.ROOT` because a comma decimal separator
     * would make `15,4M` mean something else to the party member reading it.
     */
    fun formatCoins(value: Long): String {
        val abs = kotlin.math.abs(value)
        val sign = if (value < 0) "-" else ""
        return if (abs < 1_000L) {
            String.format(Locale.ROOT, "%s%d", sign, abs)
        } else if (abs < 1_000_000L) {
            String.format(Locale.ROOT, "%s%.1fk", sign, abs / 1_000.0)
        } else if (abs < 1_000_000_000L) {
            String.format(Locale.ROOT, "%s%.1fM", sign, abs / 1_000_000.0)
        } else {
            String.format(Locale.ROOT, "%s%.1fB", sign, abs / 1_000_000_000.0)
        }
    }
}
