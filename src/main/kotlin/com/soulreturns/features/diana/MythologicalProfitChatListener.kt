package com.soulreturns.features.diana

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.items.ItemNameResolver
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.MythologicalMobCatalog
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

/**
 * Chat-driven feeder for [MythologicalProfitTracker]. Listens for:
 *
 *  - **Burrow open** — `"You dug out a Griffin Burrow! (N/M)"`. Every dig fires this
 *    regardless of what was inside (mob vs treasure), so it's the canonical burrows-per-
 *    hour signal.
 *  - **Mob spawn** — `"<flavour>! You dug out a <Mob>!"`. We rely on
 *    [MythologicalMobCatalog.matchDigOut] for the canonical noun, then map mob name →
 *    backend source id via slug (`"Minos Hunter"` → `mythological.minos_hunter`). Calls
 *    [MythologicalProfitTracker.grantKill].
 *  - **Treasure-burrow drops** — `"RARE DROP! You dug out a <Item>!"`. The item is
 *    resolved by display-name lookup against the item catalog, then credited to the
 *    [MythologicalProfitTracker.TREASURE_BURROW_BUCKET]. Coins drops aren't covered yet
 *    (Hypixel's coin chat lines are generic and hard to attribute confidently); future
 *    work can extend the pattern set.
 *
 * **Why a separate object** rather than folding it into [MythologicalMobTracker]? The mob
 * tracker is "what got dug out, with cocoons" — a stable, narrow scope. Profit accounting
 * sits on top of multiple signals (mob kills, burrows, rare drops, future coin lines) and
 * has a different gating + persistence story, so keeping it isolated keeps both clean.
 */
object MythologicalProfitChatListener {
    private val logger = SoulLogger("Soul/Diana")

    private val BURROW_REGEX = Regex("""^You dug out a Griffin Burrow!""")

    // "RARE DROP! You dug out a <Item>!" — captures the item display name. Same trailing-
    // suffix tolerance as the mob catalog (no `$` anchor; third-party mod counters append).
    private val RARE_DROP_REGEX = Regex("""^RARE DROP! You dug out (?:an?|the) (.+?)!""")

    /**
     * "Wow! You dug out 10,000 coins!" — the flavour prefix is optional. `"You dug out"` is
     * a Hypixel-exclusive Diana phrase per user observation, so the regex doesn't need an
     * additional area gate. Number group accepts comma-separated thousands ("1,234,567").
     */
    private val COINS_REGEX = Regex("""^(?:[A-Za-z][A-Za-z !']*?!\s+)?You dug out ([\d,]+) coins!""")

    /**
     * "You charmed a Cretan Bull and captured 2 Shards from it." — `find()` not
     * `matchEntire()` so any leading ability prefix (e.g. `NAGA `, `CHARM `, `SALT `) from
     * a separate Hypixel ability tag is tolerated and ignored. The **mob name** in the
     * middle of the line is what determines the shard: Cretan Bull → `SHARD_CRETAN_BULL`,
     * Sphinx → `SHARD_SPHINX`, Siamese Lynxes → `SHARD_SIAMESE_LYNXES`. Singular `Shard`
     * variant tolerated for level-1 captures.
     */
    private val SHARD_CAPTURE_REGEX =
        Regex("""You charmed (?:an?|the) (.+?) and captured (\d+) Shards? from it\.""")

    fun register() {
        Events.subscribe(this)
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        if (!cfg.dev.trackers.mythologicalTracker()) return
        val stripped = MessageDetector.stripColorCodes(event.raw).trim()

        if (BURROW_REGEX.containsMatchIn(stripped)) {
            MythologicalProfitTracker.grantBurrow()
            return
        }

        // Coin line — credits to treasure_burrow bucket as a COINS drop. Treasure burrows
        // are the documented coin source in Diana (≈25 % of burrows = treasure; the rest
        // = mob spawns). Mob drops are tracked via the inventory watcher into mob_loot.
        val coinsMatch = COINS_REGEX.find(stripped)
        if (coinsMatch != null) {
            val count = coinsMatch.groupValues[1].replace(",", "").toLongOrNull()
            if (count != null && count > 0L) {
                MythologicalProfitTracker.grantDrop(
                    MythologicalProfitTracker.TREASURE_BURROW_BUCKET,
                    "COINS",
                    count,
                )
            }
            return
        }

        // Rare-drop lines are mutually exclusive with mob-dig-out lines, so check them
        // before the mob match (the leading "RARE DROP!" would otherwise be stripped as a
        // flavour prefix and fall through to the mob regex against an item noun).
        val rareMatch = RARE_DROP_REGEX.find(stripped)
        if (rareMatch != null) {
            handleRareDrop(rareMatch.groupValues[1])
            return
        }

        // Attribute-shard capture — chat-only signal (shards go straight to the attribute
        // menu, not inventory or sacks). The shard id is derived from the mob name; the
        // user's setup also receives a leading ability tag (e.g. "NAGA ") before the
        // "You charmed" phrase, which `find()` skips past harmlessly.
        val shardMatch = SHARD_CAPTURE_REGEX.find(stripped)
        if (shardMatch != null) {
            val mobName = shardMatch.groupValues[1]
            val count = shardMatch.groupValues[2].toLongOrNull()
            if (count != null && count > 0L) {
                MythologicalProfitTracker.grantDrop(
                    MythologicalProfitTracker.ATTRIBUTE_SHARD_BUCKET,
                    mobNameToShardId(mobName),
                    count,
                )
            }
            return
        }

        // Mob dig-out → grant kill to the mob's bucket.
        val mob = MythologicalMobCatalog.matchDigOut(stripped) ?: return
        val bucketId = mobIdToSourceId(mob.name)
        MythologicalProfitTracker.grantKill(bucketId)
    }

    /**
     * Resolve a rare-drop chat line's display name to an item id and credit the treasure-
     * burrow bucket. Goes through [ItemNameResolver] so admin-curated aliases (e.g.
     * Hypixel sending a shortened name) win over the catalog's 1:1 lookup. Unknown items
     * log + skip so a Hypixel-side rename surfaces in the log for alias curation.
     */
    private fun handleRareDrop(displayName: String) {
        val itemId = ItemNameResolver.resolve(displayName)
        if (itemId == null) {
            logger.info("Unrecognised rare-drop item: '$displayName' — add an ItemNameAlias")
            return
        }
        MythologicalProfitTracker.grantDrop(
            MythologicalProfitTracker.TREASURE_BURROW_BUCKET,
            itemId,
        )
        // Notify the inventory watcher so the same drop arriving in inventory in the next
        // ~10 s doesn't get credited twice. See [DianaLootWatcher.recordChatCredit].
        DianaLootWatcher.recordChatCredit(itemId, 1L)
    }

    /**
     * Convert a mob display name to its bazaar attribute-shard id: `"Cretan Bull"` →
     * `"SHARD_CRETAN_BULL"`. Uppercase + non-alphanumeric runs collapsed to `_`. Matches
     * the format Hypixel's bazaar uses for `SHARD_*` products + the backend's
     * `ShardItemSeeder` synthetic Item rows.
     */
    internal fun mobNameToShardId(mobName: String): String {
        val slug = mobName.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        return "SHARD_$slug"
    }

    /**
     * Slug a mob display name to its backend source id, matching the backend seed's slug
     * derivation. `"Minos Hunter"` → `"mythological.minos_hunter"`. Lowercase, spaces →
     * underscores, all non-`[a-z0-9_]` stripped. If the backend catalog has the source,
     * return its id verbatim; otherwise return the slug (the source may not be loaded
     * yet — we still want to bucket the kill so it shows up on reconnect).
     */
    internal fun mobIdToSourceId(mobName: String): String {
        val slug =
            "mythological." +
                mobName.lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
        DropCatalogClient.sourceById(slug)?.let { return it.id }
        return slug
    }
}
