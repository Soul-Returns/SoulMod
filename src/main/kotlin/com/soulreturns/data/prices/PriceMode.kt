package com.soulreturns.data.prices

/**
 * How the player intends to trade — the **only** price choice that is actually theirs to make.
 *
 * ### Why this replaced a venue picker
 * The setting used to be a [PriceSource] picker offering "Lowest BIN" alongside the two bazaar sides,
 * which is nonsense: **an item is sellable on the bazaar, or on the auction house, or neither — never
 * both.** The venue is a property of the item, not a preference. Measured against the live `/prices`
 * payload on 2026-08-23, it is an exact partition:
 *
 * ```
 * bazaar-priced 2124 + BIN-priced 4762 = 6886 total rows
 * in BOTH venues: 0        in NEITHER: 0
 * ```
 *
 * So offering the venue as a choice could only ever mis-price one of the two groups. What is left is
 * a real question with a real answer: on the bazaar you can take the standing offer now or place an
 * order and wait, and those are different prices.
 *
 * ### The buy/sell inversion is the whole subtlety
 * Hypixel's `buyPrice` is the **ask** (what a player pays) and `sellPrice` the **bid** (what a player
 * receives) — which is what [PriceSource.BAZAAR_INSTANT_BUY] / [PriceSource.BAZAAR_INSTANT_SELL]
 * already encode. So each mode means the *opposite* field depending on which way the player is
 * trading, and [sourceFor] is the one place that mapping lives:
 *
 * | Mode | selling a drop | buying (a Summoning Eye) |
 * |---|---|---|
 * | [INSTANT] | `sellPrice` — hit the top buy order, lower | `buyPrice` — hit the lowest sell offer, higher |
 * | [ORDER] | `buyPrice` — list a sell offer and wait, higher | `sellPrice` — place a buy order and wait, lower |
 * | [NPC] | `npcSell` | no NPC sells one — see [PriceCache.buyCost] |
 *
 * Flipping either row makes every profit figure wrong by a spread, which is the classic bazaar
 * confusion and is why the table is written out here rather than re-derived per call site.
 *
 * ### Auction-house items ignore the bazaar half entirely
 * There is no order book to choose a side of — just the lowest listing — so an AH item prices from
 * `lowestBin` under [INSTANT] and [ORDER] alike. That falls out of [ItemPrice.best]'s fallback rather
 * than needing a branch. Under [NPC] an AH-only item genuinely *is* worth nothing to a player who
 * cannot use the auction house, and reading as unpriced is the correct answer, not a gap to paper
 * over — see [PriceCache.sellValue].
 *
 * George the Pet Collector's pet prices need no special case: they are already `npcSellPrice` on
 * `/items`, so they fall out of [NPC] for free.
 */
enum class PriceMode {
    /** Take the standing offer now, at the worse price. The default: it is what most players do. */
    INSTANT,

    /** Place an order and wait for a fill, at the better price. */
    ORDER,

    /** Vendor prices only — the Ironman mode, for a profile that can use neither market. */
    NPC,
    ;

    /** The label the panel's selector shows. */
    val displayName: String
        get() = if (this == INSTANT) {
            "Instant"
        } else if (this == ORDER) {
            "Order"
        } else {
            "NPC"
        }

    /**
     * Which [PriceSource] this mode means, given the direction of the trade. See the class doc's
     * table — this is the only implementation of that inversion.
     *
     * `if/else if` rather than `when (this)`: a subject `when` over an enum makes Kotlin emit a
     * synthetic `$WhenMappings` class that Fabric's KnotClassLoader has been seen to fail to resolve.
     *
     * @param selling true when the player is disposing of the item (loot), false when acquiring it.
     */
    fun sourceFor(selling: Boolean): PriceSource =
        if (this == NPC) {
            PriceSource.NPC
        } else if (this == INSTANT) {
            if (selling) PriceSource.BAZAAR_INSTANT_SELL else PriceSource.BAZAAR_INSTANT_BUY
        } else {
            if (selling) PriceSource.BAZAAR_INSTANT_BUY else PriceSource.BAZAAR_INSTANT_SELL
        }

    companion object {
        /**
         * The stored config value is an `Int` index, so an out-of-range one — a downgrade, a
         * hand-edited file — has to mean something. It means the default, which is the same answer a
         * fresh install gives.
         */
        fun byIndex(index: Int): PriceMode = entries.getOrElse(index) { INSTANT }

        /** The selector's options, in enum order. */
        val labels: List<String> get() = entries.map { it.displayName }
    }
}
