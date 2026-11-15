package com.soulreturns.data.prices

/**
 * Which value the caller wants for an item. Mirrors SkyHanni's `ItemPriceSource` (LGPL-2.1;
 * attribution surfaced in `/soul` → About → Used Software), trimmed to what we actually use.
 *
 * ### This is internal plumbing, not a user setting
 * It used to back a "Price source" dropdown, which was wrong: **an item is sellable on the bazaar, or
 * on the auction house, or neither — never both**, so the venue is decided by the item and there is
 * nothing for the player to pick. What the player actually chooses is [PriceMode] — instant, order or
 * NPC — and [PriceMode.sourceFor] maps that onto the entries here.
 *
 * So [LOWEST_BIN] is still needed and still correct; it is simply never *selected*, only reached for
 * an item the bazaar does not carry. Do not put it back in front of the user.
 *
 * - [BAZAAR_INSTANT_BUY] — what a player pays to insta-buy this item from the bazaar (the ask side,
 *   Hypixel's `quick_status.buyPrice`). The **higher** of the two. It is SkyHanni's default for
 *   valuing profit-tracker drops — treating a drop as worth what an equivalent stack would cost to
 *   acquire reads more naturally as "value" to most users than the instasell number does.
 * - [BAZAAR_INSTANT_SELL] — what a player receives by insta-selling to the bazaar (the bid side,
 *   Hypixel's `quick_status.sellPrice`). The **lower** number. Use it when you want the pessimistic
 *   "if I dump this right now" valuation — useful for cost accounting where the spread matters (a
 *   Summoning Eye costs INSTANT_BUY to acquire, even though its INSTANT_SELL is lower).
 * - [LOWEST_BIN] — minimum auction-house BIN from Elite's mirror. Reached for exactly the items the
 *   bazaar never carries (most pets, weapons, armor) — 4762 of the 6886 priced ids, against the
 *   bazaar's 2124, with **no overlap and no gap** (measured 2026-08-23; see [PriceMode]). There is no
 *   order book here, so instant and order modes both read this number.
 * - [NPC] — NPC vendor sell price, from the backend's item catalog. The primary value for Ironman
 *   players, who can use neither the bazaar nor the AH. 0 for items without an NPC sell value (most
 *   loot drops have one; pets, shards and synthetics do not).
 */
enum class PriceSource {
    BAZAAR_INSTANT_BUY,
    BAZAAR_INSTANT_SELL,
    LOWEST_BIN,
    NPC,
    ;

    /**
     * The label the config dropdown and the dev dump show.
     *
     * An `if/else if` chain rather than `when (this)`: a subject `when` over an enum makes Kotlin emit
     * a synthetic `PriceSource$WhenMappings` class, which Fabric's KnotClassLoader has been seen to
     * fail to resolve at runtime. The same rule applies everywhere in this codebase.
     */
    val displayName: String
        get() = if (this == BAZAAR_INSTANT_BUY) {
            "Bazaar instant buy"
        } else if (this == BAZAAR_INSTANT_SELL) {
            "Bazaar instant sell"
        } else if (this == LOWEST_BIN) {
            "Lowest BIN"
        } else {
            "NPC sell"
        }

    companion object {
        /**
         * The config field is an `Int` index, so an out-of-range value — a downgrade, a hand-edited
         * file — has to mean something. It means the default, which is the same answer a fresh
         * install gives.
         */
        fun byIndex(index: Int): PriceSource = entries.getOrElse(index) { BAZAAR_INSTANT_BUY }
    }
}
