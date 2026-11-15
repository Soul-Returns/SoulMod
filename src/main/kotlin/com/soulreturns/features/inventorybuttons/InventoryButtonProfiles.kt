package com.soulreturns.features.inventorybuttons

import com.soulreturns.config.InventoryButtonsConfig
import com.soulreturns.config.InventoryButtonsConfig.Companion.DEFAULT_ID
import com.soulreturns.config.InventoryButtonsConfig.Companion.RUN_KEY

/**
 * Which button profile is in force where. Pure, so it is unit-tested.
 *
 * Profiles are plain named button sets; where one is used is a mapping on the config: one
 * **Everywhere** slot plus optional per-area slots (and the dungeon-run slot), each holding exactly
 * one profile id, so two profiles can never claim the same place. A slot whose profile no longer
 * exists counts as unset.
 *
 * Resolution, first hit wins: the dungeon-run slot while in a run, the current area's slot,
 * Everywhere, then Default.
 */
object InventoryButtonProfiles {
    /** The dungeon-run slot as the editor lists it next to the areas. */
    const val DUNGEON_RUN = "Dungeon run"

    /**
     * Areas the picker always offers, grouped. Hypixel names as the tab list reports them. Only a
     * starting list: any other area the client has seen or a slot holds joins "Other", nothing
     * is ever checked against it.
     */
    val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Mining" to listOf("Dwarven Mines", "Crystal Hollows", "Mineshaft", "Gold Mine", "Deep Caverns"),
        "Dungeons" to listOf("Dungeon Hub"),
        "Combat" to listOf("Crimson Isle", "Kuudra", "The End", "Spider's Den"),
        "Farming & Foraging" to listOf("Garden", "The Farming Islands", "The Park", "Galatea"),
        "Other" to listOf("Hub", "Private Island", "The Rift", "Backwater Bayou", "Jerry's Workshop"),
    )

    private fun exists(config: InventoryButtonsConfig, id: String?): Boolean =
        id != null && (id == DEFAULT_ID || config.profiles.any { it.id == id })

    /** The id of the profile in force here ([DEFAULT_ID] for Default). */
    fun resolve(config: InventoryButtonsConfig, area: String?, inDungeon: Boolean): String =
        (if (inDungeon) owner(config, RUN_KEY) else null)
            ?: area?.let { owner(config, it) }
            ?: everywhere(config)

    /** The profile holding [slot] (an area name or [RUN_KEY]), or null when the slot is unset. */
    fun owner(config: InventoryButtonsConfig, slot: String): String? =
        config.activeByArea[slot]?.takeIf { exists(config, it) }

    /** The Everywhere profile: the stored one while it exists, else Default. */
    fun everywhere(config: InventoryButtonsConfig): String =
        config.activeEverywhere.takeIf { exists(config, it) } ?: DEFAULT_ID

    /** The slot as the editor shows it: [DUNGEON_RUN] for the run key, the area name otherwise. */
    fun slotLabel(slot: String): String = if (slot == RUN_KEY) DUNGEON_RUN else slot

    fun slotKey(label: String): String = if (label == DUNGEON_RUN) RUN_KEY else label

    /** Per-area slots [id] holds, the dungeon run first, then areas alphabetically. Everywhere is separate ([everywhere]). */
    fun slotsOf(config: InventoryButtonsConfig, id: String): List<String> {
        val held = config.activeByArea.keys.filter { owner(config, it) == id }
        return held.filter { it == RUN_KEY } + held.filter { it != RUN_KEY }.sortedBy { it.lowercase() }
    }

    /** Make [id] the Everywhere profile. */
    fun useEverywhere(config: InventoryButtonsConfig, id: String): InventoryButtonsConfig =
        if (exists(config, id)) config.copy(activeEverywhere = id) else config

    /** Give [slot] to [id], taking it from whichever profile held it. */
    fun useIn(config: InventoryButtonsConfig, slot: String, id: String): InventoryButtonsConfig =
        if (exists(config, id)) config.copy(activeByArea = config.activeByArea + (slot to id)) else config

    /** Unset [slot]: that place follows Everywhere again. */
    fun release(config: InventoryButtonsConfig, slot: String): InventoryButtonsConfig =
        config.copy(activeByArea = config.activeByArea - slot)

    /** Remove profile [id] and every slot that held it (they fall back, Everywhere to Default). */
    fun withoutProfile(config: InventoryButtonsConfig, id: String): InventoryButtonsConfig =
        config.copy(
            profiles = config.profiles.filter { it.id != id },
            activeEverywhere = if (config.activeEverywhere == id) DEFAULT_ID else config.activeEverywhere,
            activeByArea = config.activeByArea.filterValues { it != id },
        )

    /**
     * The picker's groups: [GROUPS] plus every area in [extra] (seen, or already held by a slot)
     * that none of them lists, sorted into "Other".
     */
    fun pickerGroups(extra: Collection<String>): List<Pair<String, List<String>>> {
        val listed = GROUPS.flatMapTo(HashSet()) { it.second }
        val more = extra.filter { it.isNotBlank() && it != RUN_KEY && it !in listed }.distinct().sorted()
        if (more.isEmpty()) return GROUPS
        return GROUPS.map { (name, areas) -> if (name == "Other") name to areas + more else name to areas }
    }
}
