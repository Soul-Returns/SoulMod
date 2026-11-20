package com.soulreturns.features.profileviewer

/**
 * The Overview's **Market value** (DATA.md "Prices"): purse + bank + decoded items × price + sacks ×
 * price + pets (`TYPE;tier` keys) + essence × price. Items are priced at their base price; enchantments,
 * stars, reforges, gemstones and pet levels beyond the `+100` key are not priced. Pure: plain inputs in,
 * total and breakdown out, so it is tested without prices or decoded items.
 */
object MarketValue {
    /** One stack: its market key (null = the stack has none) and its count. */
    class Stack(val key: String?, val count: Int)

    /** A group of items under one breakdown label ("Inventory", "Ender Chest"). */
    class Group(val label: String, val stacks: List<Stack>)

    /**
     * @property items the decoded item groups in breakdown order; null when the Inventory API is off.
     * @property bank null when the Banking API is off (co-op and personal bank both absent).
     * @property pets the pets' market keys, null for a pet without one.
     * @property hidden parts that could not be counted, in words ("Personal Vault: API off").
     */
    class Inputs(
        val purse: Double?,
        val bank: Double?,
        val items: List<Group>?,
        val sacks: Map<String, Long>,
        val pets: List<String?>,
        val essence: Map<String, Long>,
        val hidden: List<String> = emptyList(),
    )

    class Part(val label: String, val coins: Double)

    /**
     * @property parts every part with a value, largest first.
     * @property unpriced items, pets, sack and essence types with no price (counted once per stack or type).
     * @property hidden parts that could not be counted, including the items when the Inventory API is off.
     */
    class Estimate(val total: Double, val parts: List<Part>, val unpriced: Int, val hidden: List<String>) {
        /** True when the decoded items are missing (Inventory API off): the mock's "coins and pets only". */
        val itemsHidden: Boolean get() = hidden.any { it.startsWith(ITEMS_HIDDEN) }
    }

    const val ITEMS_HIDDEN = "Items: Inventory API off"

    /** [price] answers coins per unit for a market key, 0 or less = no price. */
    fun estimate(inputs: Inputs, price: (String) -> Long): Estimate {
        var unpriced = 0
        val parts = ArrayList<Part>()
        fun priced(key: String?, count: Long): Double {
            if (count <= 0) return 0.0
            val p = key?.let(price) ?: 0L
            if (p <= 0) {
                unpriced++
                return 0.0
            }
            return p.toDouble() * count
        }
        inputs.purse?.let { parts += Part("Purse", it) }
        inputs.bank?.let { parts += Part("Bank", it) }
        for (group in inputs.items.orEmpty()) {
            parts += Part(group.label, group.stacks.sumOf { priced(it.key, it.count.toLong()) })
        }
        parts += Part("Pets", inputs.pets.sumOf { priced(it, 1) })
        parts += Part("Sacks", inputs.sacks.entries.sumOf { (id, n) -> priced(id, n) })
        parts += Part("Essence", inputs.essence.entries.sumOf { (type, n) -> priced("ESSENCE_$type", n) })
        val hidden = if (inputs.items == null) listOf(ITEMS_HIDDEN) + inputs.hidden else inputs.hidden
        return Estimate(parts.sumOf { it.coins }, parts.filter { it.coins > 0 }.sortedByDescending { it.coins }, unpriced, hidden)
    }
}
