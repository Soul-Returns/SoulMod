package com.soulreturns.data.prices

/**
 * Which value the caller wants for an item. Mirrors SkyHanni's `ItemPriceSource` (LGPL-2.1;
 * attribution surfaced in `/soul config` → About → Used Software), trimmed to what we
 * actually use today.
 *
 * - [BAZAAR_INSTANT_BUY] — what a player pays to insta-buy this item from the bazaar (the
 *   ask side, Hypixel's `quick_status.buyPrice`). This is the **higher** number of the two.
 *   It's the SkyHanni default for valuing profit-tracker drops — treating the drop as worth
 *   what an equivalent stack would cost to acquire reads more naturally as "value" to most
 *   users than the instasell number does.
 * - [BAZAAR_INSTANT_SELL] — what a player receives by insta-selling to the bazaar (the bid
 *   side, Hypixel's `quick_status.sellPrice`). The **lower** number. Use when you want the
 *   pessimistic "if I dump this right now" valuation — useful for cost accounting where
 *   spread matters (a Summoning Eye costs INSTANT_BUY to acquire, even though its
 *   INSTANT_SELL is lower).
 * - [LOWEST_BIN] — minimum auction-house BIN from Elite's mirror. Used for items that
 *   never appear on the bazaar (most pets, weapons, armor).
 *
 * NPC sell prices aren't tracked yet — Hypixel's `/v2/resources/skyblock/items` would be
 * the source if we ever want a fourth tier.
 */
enum class PriceSource {
    BAZAAR_INSTANT_BUY,
    BAZAAR_INSTANT_SELL,
    LOWEST_BIN,
}
