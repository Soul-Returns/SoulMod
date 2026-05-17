package com.soulreturns.features.profit.dragon

import com.soulreturns.data.skyblock.SkyblockRarity

/**
 * Hardcoded catalog of every drop the dragons can produce, sourced from the Hypixel
 * SkyBlock wiki. Each entry carries the NEU/Hypixel item id (`itemId`) used by
 * [com.soulreturns.data.prices.PriceCache] for value lookups, plus the rarity tier used
 * to tint the row label inside [com.soulreturns.ui.hud.DragonProfitHud].
 *
 * Drops are scoped to one or more [DragonType]s via [dragonTypes]. The same physical item
 * can appear in multiple dragons' tables (e.g. `<TYPE>_FRAGMENT` is type-specific, but
 * `ASPECT_OF_THE_DRAGON` and the Ender Dragon pet are global drops from any dragon).
 *
 * **Naming conventions Hypixel uses (worth recording):**
 * - **Fragments** are `<TYPE>_FRAGMENT` (no `_DRAGON` in between) — `OLD_FRAGMENT`,
 *   `UNSTABLE_FRAGMENT`, etc. Don't write `OLD_DRAGON_FRAGMENT` — that key doesn't exist
 *   in the bazaar / lowest-BIN feeds and prices won't resolve.
 * - **Aspect of the Dragon** is singular — `ASPECT_OF_THE_DRAGON`. (`ASPECT_OF_THE_DRAGONS`
 *   plural is the in-game display name but not the id.)
 * - **Pets** are all `ENDER_DRAGON` regardless of dragon type — only the **rarity tier**
 *   (numeric suffix `;0`…`;4`) distinguishes them. NEU uses `ENDER_DRAGON;<tier>` and Elite's
 *   `/resources/auctions/neu` mirror keys by that exact id; that's what we send to
 *   [com.soulreturns.data.prices.PriceCache.price]. Dragon-specific pet ids (`WISE_DRAGON`,
 *   `STRONG_DRAGON`, etc.) are NOT a thing — they're a SkyHanni / NEU misnomer for older
 *   pets that don't exist in modern SkyBlock.
 * - **Aspect of the Dragon does NOT drop from Superior** — it drops from the other six
 *   dragons. Superior's signature weapon-tier drops are Dragon Horn + Pearlescent Dye.
 * - **Superior-only**: `DRAGON_HORN`, `PEARLESCENT_DYE`.
 * - **Young-only**: `DRAGON_SCALE`.
 * - **Unstable-only**: `DRAGON_NEST_TRAVEL_SCROLL`.
 * - **Universal materials** (every dragon): `ENDER_PEARL`, `ENCHANTED_ENDER_PEARL`,
 *   `DRAGON_CLAW`, **Draconic Shard**. The shard's bazaar product id is `SHARD_DRACONIC`
 *   — NOT `ATTRIBUTE_SHARD_DRAGON_ESSENCE` (NEU's internal name) and NOT the bare
 *   `ATTRIBUTE_SHARD` from item NBT. Hypixel's bazaar uses `SHARD_<NAME>` for every
 *   attribute shard; verify any new shard id with `/soul dev getPrice SHARD_<NAME>` rather
 *   than guessing from NEU.
 *
 * **This catalog is the placeholder for the future repo-backed item list.** When we wire
 * the repo (per the user's plan), this enum is replaced by a JSON-backed list with the
 * same shape so tweaks can ship without a mod release.
 */
enum class DragonDrop(
    val itemId: String,
    val displayName: String,
    val rarity: SkyblockRarity,
    val dragonTypes: Set<DragonType>,
) {
    // ─── Fragments (one per dragon type) ───
    // displayName matches Hypixel's in-game name on the loot armor-stand customName
    // (`"Protector Dragon Fragment x8"`) so DragonLootScanner can reverse-lookup directly
    // via `DragonDrop.byDisplayName`. The bazaar id (no `_DRAGON_` infix) stays as-is.
    PROTECTOR_FRAGMENT(
        "PROTECTOR_FRAGMENT",
        "Protector Dragon Fragment",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.PROTECTOR),
    ),
    OLD_FRAGMENT(
        "OLD_FRAGMENT",
        "Old Dragon Fragment",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.OLD),
    ),
    WISE_FRAGMENT(
        "WISE_FRAGMENT",
        "Wise Dragon Fragment",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.WISE),
    ),
    UNSTABLE_FRAGMENT(
        "UNSTABLE_FRAGMENT",
        "Unstable Dragon Fragment",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.UNSTABLE),
    ),
    YOUNG_FRAGMENT(
        "YOUNG_FRAGMENT",
        "Young Dragon Fragment",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.YOUNG),
    ),
    STRONG_FRAGMENT(
        "STRONG_FRAGMENT",
        "Strong Dragon Fragment",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.STRONG),
    ),
    SUPERIOR_FRAGMENT(
        "SUPERIOR_FRAGMENT",
        "Superior Dragon Fragment",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.SUPERIOR),
    ),

    // ─── Universal material drops (every dragon) ───
    ENDER_PEARL(
        "ENDER_PEARL",
        "Ender Pearl",
        SkyblockRarity.COMMON,
        DragonType.entries.toSet(),
    ),
    ENCHANTED_ENDER_PEARL(
        "ENCHANTED_ENDER_PEARL",
        "Enchanted Ender Pearl",
        SkyblockRarity.COMMON,
        DragonType.entries.toSet(),
    ),
    DRAGON_CLAW(
        "DRAGON_CLAW",
        "Dragon Claw",
        SkyblockRarity.EPIC,
        DragonType.entries.toSet(),
    ),

    // Hypixel attribute shard. The item's NBT carries `skyblockId: "ATTRIBUTE_SHARD"`
    // with `custom_data.attributes.dragon_essence:1`, but the **bazaar product id** is the
    // entirely different form `SHARD_<NAME>` — verified live against
    // `https://api.hypixel.net/v2/skyblock/bazaar`. NEU's internal ids
    // (`ATTRIBUTE_SHARD_DRAGON_ESSENCE`) and Elite's mirror use yet another convention; the
    // bazaar one is what `PriceCache.parseBazaar` keys against, so that's what goes here.
    DRACONIC_SHARD(
        "SHARD_DRACONIC",
        "Draconic Shard",
        SkyblockRarity.RARE,
        DragonType.entries.toSet(),
    ),

    // ─── Non-superior weapon drop ───
    // Aspect of the Dragon does NOT drop from Superior — Superior's signature weapon-tier
    // drops are the Dragon Horn / Pearlescent Dye in the per-dragon block below.
    ASPECT_OF_THE_DRAGON(
        // Bazaar id is singular; the loot stand label is plural ("Aspect of the Dragons").
        // DragonLootScanner uses the plural via `DragonDrop.byDisplayName`.
        "ASPECT_OF_THE_DRAGON",
        "Aspect of the Dragons",
        SkyblockRarity.LEGENDARY,
        setOf(
            DragonType.PROTECTOR,
            DragonType.OLD,
            DragonType.WISE,
            DragonType.UNSTABLE,
            DragonType.YOUNG,
            DragonType.STRONG,
        ),
    ),

    // ─── Dragon armor sets (4 pieces per type, scoped to that dragon) ───
    // Ids follow `<TYPE>_DRAGON_<SLOT>` — note the `_DRAGON_` infix here (unlike fragments
    // which drop it). Every set is LEGENDARY. Each piece drops only from its own dragon;
    // mixing pieces grants the partial-set bonus described in lore but doesn't affect drop
    // sourcing.
    PROTECTOR_DRAGON_HELMET(
        "PROTECTOR_DRAGON_HELMET",
        "Protector Dragon Helmet",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.PROTECTOR),
    ),
    PROTECTOR_DRAGON_CHESTPLATE(
        "PROTECTOR_DRAGON_CHESTPLATE",
        "Protector Dragon Chestplate",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.PROTECTOR),
    ),
    PROTECTOR_DRAGON_LEGGINGS(
        "PROTECTOR_DRAGON_LEGGINGS",
        "Protector Dragon Leggings",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.PROTECTOR),
    ),
    PROTECTOR_DRAGON_BOOTS(
        "PROTECTOR_DRAGON_BOOTS",
        "Protector Dragon Boots",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.PROTECTOR),
    ),

    OLD_DRAGON_HELMET(
        "OLD_DRAGON_HELMET",
        "Old Dragon Helmet",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.OLD),
    ),
    OLD_DRAGON_CHESTPLATE(
        "OLD_DRAGON_CHESTPLATE",
        "Old Dragon Chestplate",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.OLD),
    ),
    OLD_DRAGON_LEGGINGS(
        "OLD_DRAGON_LEGGINGS",
        "Old Dragon Leggings",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.OLD),
    ),
    OLD_DRAGON_BOOTS(
        "OLD_DRAGON_BOOTS",
        "Old Dragon Boots",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.OLD),
    ),

    WISE_DRAGON_HELMET(
        "WISE_DRAGON_HELMET",
        "Wise Dragon Helmet",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.WISE),
    ),
    WISE_DRAGON_CHESTPLATE(
        "WISE_DRAGON_CHESTPLATE",
        "Wise Dragon Chestplate",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.WISE),
    ),
    WISE_DRAGON_LEGGINGS(
        "WISE_DRAGON_LEGGINGS",
        "Wise Dragon Leggings",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.WISE),
    ),
    WISE_DRAGON_BOOTS(
        "WISE_DRAGON_BOOTS",
        "Wise Dragon Boots",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.WISE),
    ),

    UNSTABLE_DRAGON_HELMET(
        "UNSTABLE_DRAGON_HELMET",
        "Unstable Dragon Helmet",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.UNSTABLE),
    ),
    UNSTABLE_DRAGON_CHESTPLATE(
        "UNSTABLE_DRAGON_CHESTPLATE",
        "Unstable Dragon Chestplate",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.UNSTABLE),
    ),
    UNSTABLE_DRAGON_LEGGINGS(
        "UNSTABLE_DRAGON_LEGGINGS",
        "Unstable Dragon Leggings",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.UNSTABLE),
    ),
    UNSTABLE_DRAGON_BOOTS(
        "UNSTABLE_DRAGON_BOOTS",
        "Unstable Dragon Boots",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.UNSTABLE),
    ),

    YOUNG_DRAGON_HELMET(
        "YOUNG_DRAGON_HELMET",
        "Young Dragon Helmet",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.YOUNG),
    ),
    YOUNG_DRAGON_CHESTPLATE(
        "YOUNG_DRAGON_CHESTPLATE",
        "Young Dragon Chestplate",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.YOUNG),
    ),
    YOUNG_DRAGON_LEGGINGS(
        "YOUNG_DRAGON_LEGGINGS",
        "Young Dragon Leggings",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.YOUNG),
    ),
    YOUNG_DRAGON_BOOTS(
        "YOUNG_DRAGON_BOOTS",
        "Young Dragon Boots",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.YOUNG),
    ),

    STRONG_DRAGON_HELMET(
        "STRONG_DRAGON_HELMET",
        "Strong Dragon Helmet",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.STRONG),
    ),
    STRONG_DRAGON_CHESTPLATE(
        "STRONG_DRAGON_CHESTPLATE",
        "Strong Dragon Chestplate",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.STRONG),
    ),
    STRONG_DRAGON_LEGGINGS(
        "STRONG_DRAGON_LEGGINGS",
        "Strong Dragon Leggings",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.STRONG),
    ),
    STRONG_DRAGON_BOOTS(
        "STRONG_DRAGON_BOOTS",
        "Strong Dragon Boots",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.STRONG),
    ),

    SUPERIOR_DRAGON_HELMET(
        "SUPERIOR_DRAGON_HELMET",
        "Superior Dragon Helmet",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.SUPERIOR),
    ),
    SUPERIOR_DRAGON_CHESTPLATE(
        "SUPERIOR_DRAGON_CHESTPLATE",
        "Superior Dragon Chestplate",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.SUPERIOR),
    ),
    SUPERIOR_DRAGON_LEGGINGS(
        "SUPERIOR_DRAGON_LEGGINGS",
        "Superior Dragon Leggings",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.SUPERIOR),
    ),
    SUPERIOR_DRAGON_BOOTS(
        "SUPERIOR_DRAGON_BOOTS",
        "Superior Dragon Boots",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.SUPERIOR),
    ),

    // ─── Per-dragon exclusive drops ───
    DRAGON_NEST_TRAVEL_SCROLL(
        "DRAGON_NEST_TRAVEL_SCROLL",
        "Dragon Nest Travel Scroll",
        SkyblockRarity.EPIC,
        setOf(DragonType.UNSTABLE),
    ),
    DRAGON_SCALE(
        "DRAGON_SCALE",
        "Dragon Scale",
        SkyblockRarity.COMMON,
        setOf(DragonType.YOUNG),
    ),
    DRAGON_HORN(
        "DRAGON_HORN",
        "Dragon Horn",
        SkyblockRarity.EPIC,
        setOf(DragonType.SUPERIOR),
    ),
    PEARLESCENT_DYE(
        "PEARLESCENT_DYE",
        "Pearlescent Dye",
        SkyblockRarity.LEGENDARY,
        setOf(DragonType.SUPERIOR),
    ),

    // ─── Ender Dragon pet (any dragon; only Epic & Legendary exist) ───
    // NEU/Elite key format is `ENDER_DRAGON;<tier>` where tier 3 = Epic, 4 = Legendary.
    // The petInfo NBT also carries `"tier":"LEGENDARY"` for display purposes; the id with
    // the numeric suffix is what the bazaar / lowest-BIN feeds use. Lower tiers (Common /
    // Uncommon / Rare) are not obtainable for the Ender Dragon pet at all.
    ENDER_DRAGON_PET_EPIC(
        "ENDER_DRAGON;3",
        "Ender Dragon Pet (Epic)",
        SkyblockRarity.EPIC,
        DragonType.entries.toSet(),
    ),
    ENDER_DRAGON_PET_LEGENDARY(
        "ENDER_DRAGON;4",
        "Ender Dragon Pet (Legendary)",
        SkyblockRarity.LEGENDARY,
        DragonType.entries.toSet(),
    ),
    ;

    companion object {
        fun byId(id: String): DragonDrop? = entries.firstOrNull { it.name == id }

        fun byItemId(itemId: String): DragonDrop? = entries.firstOrNull { it.itemId == itemId }

        /**
         * Reverse-lookup by the in-game [displayName]. Used by `DragonLootScanner` to map a
         * loot armor-stand's `customName` (e.g. `"Protector Dragon Fragment"`) back to its
         * catalog entry. Case-sensitive; in-game names match the catalog exactly.
         */
        fun byDisplayName(name: String): DragonDrop? = entries.firstOrNull { it.displayName == name }
    }
}
