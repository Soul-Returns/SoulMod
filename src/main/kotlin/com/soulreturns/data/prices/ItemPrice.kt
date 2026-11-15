package com.soulreturns.data.prices

/**
 * Everything known about what one item is worth, and **where it can actually be sold**.
 *
 * ### Why capability is part of the answer
 * [PriceCache.price] returns a single `Long` and uses 0 for "no price", which collapses two very
 * different facts: *nobody buys this here* and *we have not fetched a price yet*. A Hyperion reporting
 * `NPC sell: 0` is the first; the same 0 before the first poll is the second. A caller cannot tell
 * them apart, so it renders `—` for both and the player learns nothing.
 *
 * This type keeps them apart. **A null price means "not available", and the venue's own field says
 * whether the venue applies at all**:
 *
 * | State | Reads as |
 * |---|---|
 * | `bazaar == null` | not a bazaar product — it will never have one |
 * | `bazaar != null` | the live quote, both sides |
 * | `auctionable == false` | Hypixel forbids trading it at all (soulbound, untradeable) |
 * | [auctionSellable] `&& lowestBin == null` | allowed on the AH, but nothing is listed (or the feed has not loaded) |
 * | `npcSell == null` | no NPC will buy it |
 *
 * ### `auctionable` is NOT "sells on the auction house" — use [auctionSellable]
 * `auctionable` is `/items`' permission bit: *Hypixel does not forbid trading this*. A bazaar product
 * satisfies it too, so reading it as an AH venue claimed that a Summoning Eye could be auctioned and
 * merely had no listings — which is wrong twice over, and contradicted the partition this whole layer
 * is built on. The two venues do not overlap; see [PriceMode] for the measured numbers.
 *
 * ### Where each field comes from
 * - **bazaar** — membership of Hypixel's live bazaar feed, which is authoritative: a product that
 *   exists is in the feed, and one that does not never appears. 2124 products, of which only ~1000
 *   are in the item catalog (the rest are `ENCHANTMENT_*` / `SHARD_*` synthetics).
 * - **lowestBin** — Elite's mirror. Note this is *currently listed*, not *permitted*: an AH item with
 *   nothing on the AH right now still has `auctionable = true`.
 * - **auctionable** — the backend's `/items`, derived at ingest from Hypixel's `can_auction`,
 *   `can_trade` and `soulbound`. The mod keeps no copy of that rule, deliberately — the same
 *   reasoning as the skill-caps endpoint: a rule about Hypixel's data should be correctable without
 *   a client release. It is a **permission**, not a venue — combine it with [bazaarSellable] to get
 *   one, which is what [auctionSellable] does.
 * - **npcSell** — the item catalog's `npcSellPrice`. For **pets** that is what George the Pet
 *   Collector pays, curated on the backend because it exists in no API. Remember George caps out at
 *   50M coins per day, so a pet's NPC value is not always realisable in one sitting.
 */
data class ItemPrice(
    val itemId: String,
    /** The live bazaar quote, or null when this id is not a bazaar product at all. */
    val bazaar: PriceCache.BazaarPrices?,
    /** Lowest BIN currently listed, or null when nothing is. See [auctionSellable] for the venue. */
    val lowestBin: Long?,
    /**
     * Whether Hypixel permits **trading** this item at all — not whether it is an auction-house item.
     * A bazaar product is auctionable in this sense too. For the venue, use [auctionSellable].
     */
    val auctionable: Boolean,
    /** What an NPC pays, or null when none will buy it. */
    val npcSell: Long?,
) {
    val bazaarSellable: Boolean get() = bazaar != null

    val npcSellable: Boolean get() = npcSell != null

    /**
     * Whether this item actually trades on the **auction house**: Hypixel permits it *and* it is not
     * a bazaar product.
     *
     * The `!bazaarSellable` half is the whole point and is not defensive coding. An item is sellable
     * on the bazaar, or on the auction house, or neither — never both — so bazaar membership *decides*
     * this. Without it, [auctionable] alone reported a Summoning Eye as auctionable-with-no-listings,
     * because Hypixel does not forbid trading eyes; the panel then offered a third venue for a
     * two-venue item and blamed an empty order book for it. See [PriceMode] for the measured
     * partition.
     *
     * An item in **neither** feed still reads true, which is right: nothing is listed at the moment,
     * but it is permitted, and those are exactly the ids (pets, rune tiers, `+100` variants) that
     * trade on the AH.
     */
    val auctionSellable: Boolean get() = auctionable && !bazaarSellable

    /**
     * Whether [source] is a venue this item can be sold at. Distinct from having a price — an AH item
     * with nothing currently listed is sellable there and simply has no quote.
     *
     * `if/else if` over the enum rather than `when (source)`, per the codebase rule.
     */
    fun sellableAt(source: PriceSource): Boolean =
        if (source == PriceSource.LOWEST_BIN) {
            auctionSellable
        } else if (source == PriceSource.NPC) {
            npcSellable
        } else {
            bazaarSellable
        }

    /** The price at [source], or null when the venue does not apply **or** has no quote yet. */
    fun at(source: PriceSource): Long? =
        if (source == PriceSource.BAZAAR_INSTANT_BUY) {
            bazaar?.instantBuy?.takeIf { it > 0L }
        } else if (source == PriceSource.BAZAAR_INSTANT_SELL) {
            bazaar?.instantSell?.takeIf { it > 0L }
        } else if (source == PriceSource.LOWEST_BIN) {
            lowestBin?.takeIf { it > 0L }
        } else {
            npcSell?.takeIf { it > 0L }
        }

    /**
     * A number to value this drop at: [preferred] if it has one, else whatever venue does.
     *
     * This is the *valuation* question — "roughly what is this worth" — and is deliberately separate
     * from [at], which answers "what would this venue pay". A tracker wants the former, so that
     * choosing a price source never silently zeroes half a loot table; a display of capabilities
     * wants the latter, so it never claims a venue pays something it does not.
     */
    fun best(preferred: PriceSource): Long? =
        at(preferred)
            ?: at(PriceSource.BAZAAR_INSTANT_BUY)
            // Guarded on the venue rather than just on the number. For a bazaar product `lowestBin`
            // is always null, so today this changes nothing — but "accidentally right because the
            // other field happens to be empty" is how the sellableAt bug above got in, and a
            // contract violation on the wire should not be able to price an eye off a stray BIN.
            ?: lowestBin?.takeIf { it > 0L && auctionSellable }
            ?: npcSell?.takeIf { it > 0L }

    /** Every venue this item can be sold at, in [PriceSource] order. For diagnostics. */
    fun venues(): List<PriceSource> = PriceSource.entries.filter { sellableAt(it) }
}
