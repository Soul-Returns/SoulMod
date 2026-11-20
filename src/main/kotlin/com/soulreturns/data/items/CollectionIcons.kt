package com.soulreturns.data.items

import net.minecraft.world.item.ItemStack

/**
 * Icons of collections and minions by their profile-API ids. A collection id is an `/items` id (`INK_SACK:3`,
 * `LOG:1`, `MUSHROOM_COLLECTION`, `GEMSTONE_COLLECTION`); the few that `/items` lacks map through [ALIASES] to the
 * item the collection counts. A minion type (`crafted_generators` `WHEAT_5`) is the item `WHEAT_GENERATOR_5`, a
 * skull with its head skin.
 */
object CollectionIcons {
    /** Collection ids no `/items` row names (checked against the catalog of 2026-11-20), to the item they count. */
    internal val ALIASES = mapOf(
        "UMBER_COLLECTION" to "UMBER",
        "TUNGSTEN_COLLECTION" to "TUNGSTEN",
    )

    /** The item ids to try for collection [id], in order: itself, then its alias. */
    internal fun candidates(id: String): List<String> = listOfNotNull(id, ALIASES[id])

    /** The minion item id for type [type] at [tier] (its highest crafted tier). */
    internal fun minionId(type: String, tier: Int): String = "${type}_GENERATOR_${tier.coerceAtLeast(1)}"

    /** [name] is the collection's display name: the pack names some collection models after it (`collections/gemstone`). */
    fun collection(id: String, name: String): ItemStack? = candidates(id).firstNotNullOfOrNull { ItemIcons.icon(it, if (it == id) name else null) }

    fun minion(type: String, tier: Int): ItemStack? = ItemIcons.icon(minionId(type, tier)) ?: ItemIcons.icon(minionId(type, 1))
}
