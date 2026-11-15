package com.soulreturns.data.items

import com.soulreturns.platform.catalog.BackendCatalog
import com.soulreturns.platform.catalog.CatalogContents
import com.soulreturns.platform.catalog.CatalogRegistry
import com.soulreturns.platform.catalog.CatalogStatus
import com.soulreturns.util.SoulLogger
import java.util.Locale

/**
 * The client mirror of `GET /items` — canonical display name, rarity, NPC sell price and the optional
 * bazaar-id override for every SkyBlock item.
 *
 * Two consumers: [com.soulreturns.data.drops.DropResolver] (name + rarity + price-lookup id) and
 * [com.soulreturns.data.prices.PriceCache] (`npcSellPrice`, which is the whole of `PriceSource.NPC`).
 *
 * ### Display names collide, in bulk
 * Hypixel's catalog carries several hundred rows that share a display name — cosmetic variants,
 * builder duplicates, tiered accessories that never renamed. So [byDisplayName] cannot be a plain
 * `associateBy`; something has to pick.
 *
 * **The policy is: shortest id wins, ties broken lexicographically.** A base item's id is a prefix of
 * its variants' (`HYPERION` before `HYPERION_STARRED`, `ROCK` before `ROCK_GEMSTONE`), so the
 * shortest is the one a player naming the item out loud almost always means. The tie-break matters
 * more than the rule does: it makes the chosen entry a pure function of the *set* of rows rather than
 * of the order the backend happened to serialise them in, so the same name resolves to the same id
 * across refreshes and across machines. A nondeterministic reverse lookup is the kind of bug that
 * reproduces for one person and nobody else.
 *
 * The collision **total** is logged once per refresh. Logging each one would be several hundred lines
 * on every startup for a condition that is normal and permanent; the count is what tells you whether
 * it changed.
 *
 * ### This index is a diagnostic aid, not the resolution path
 * Turning a loot stand's text into an item id goes through
 * [com.soulreturns.data.drops.DropResolver.findItemIdByDisplayName], which is scoped to one drop
 * source and therefore cannot collide at all. [byDisplayName] exists so `/soul dev dragons` can say
 * "that unresolved stand names a real item — the drop graph is missing a row" rather than leaving an
 * admin to guess.
 */
object ItemCatalogClient {
    private val logger = SoulLogger("Soul/Catalog")

    /** The `sync-invalidate` kind the backend publishes for this catalog. */
    const val KIND: String = "items"

    private val catalog = BackendCatalog(
        kind = KIND,
        endpoint = "/items",
        cacheFile = "item_catalog.json",
        serializer = ItemCatalogSnapshot.serializer(),
        empty = ItemCatalogSnapshot(),
        describe = { CatalogContents(it.items.size, it.updatedAt) },
    )

    @Volatile private var byId: Map<String, ItemCatalogEntry> = emptyMap()

    /** Lower-cased display name → the canonical entry for it. See the class doc for "canonical". */
    @Volatile private var byDisplayName: Map<String, ItemCatalogEntry> = emptyMap()

    @Volatile private var collisions: Int = 0

    val snapshot: ItemCatalogSnapshot get() = catalog.snapshot

    /** How many display names had more than one row at the last index build. Diagnostics only. */
    val displayNameCollisions: Int get() = collisions

    /** Declare the catalog. [CatalogRegistry.init] is what loads and refreshes it. */
    fun init() {
        rebuild(catalog.snapshot)
        catalog.addListener(::rebuild)
        CatalogRegistry.register(catalog)
    }

    suspend fun refresh(): String = catalog.refresh()

    fun status(): CatalogStatus = catalog.status()

    fun byId(id: String): ItemCatalogEntry? = byId[id]

    /** Case-insensitive lookup by display name. See the class doc before using it for resolution. */
    fun byDisplayName(displayName: String): ItemCatalogEntry? = byDisplayName[displayName.lowercase(Locale.ROOT)]

    fun isEmpty(): Boolean = byId.isEmpty()

    private fun rebuild(fresh: ItemCatalogSnapshot) {
        val ids = HashMap<String, ItemCatalogEntry>(fresh.items.size)
        val names = HashMap<String, ItemCatalogEntry>(fresh.items.size)
        var collided = 0
        for (item in fresh.items) {
            if (item.id.isEmpty()) continue
            ids[item.id] = item
            if (item.displayName.isEmpty()) continue
            val nameKey = item.displayName.lowercase(Locale.ROOT)
            val existing = names[nameKey]
            if (existing == null) {
                names[nameKey] = item
                continue
            }
            collided++
            if (preferred(item, existing)) names[nameKey] = item
        }
        byId = ids
        byDisplayName = names
        collisions = collided
        logger.info(
            "Indexed ${ids.size} items (${names.size} distinct display names, $collided collisions resolved by shortest id)",
        )
    }

    /** Shortest id wins; equal lengths are settled lexicographically so the choice is order-free. */
    private fun preferred(
        candidate: ItemCatalogEntry,
        incumbent: ItemCatalogEntry,
    ): Boolean {
        if (candidate.id.length != incumbent.id.length) return candidate.id.length < incumbent.id.length
        return candidate.id < incumbent.id
    }
}
