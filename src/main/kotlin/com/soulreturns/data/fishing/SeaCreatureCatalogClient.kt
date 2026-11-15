package com.soulreturns.data.fishing

import com.soulreturns.platform.catalog.BackendCatalog
import com.soulreturns.platform.catalog.CatalogContents
import com.soulreturns.platform.catalog.CatalogRegistry
import com.soulreturns.platform.catalog.CatalogStatus
import kotlinx.serialization.Serializable

/** One row of the `/sea-creatures` response. Additive-only by contract — never reject an extra key. */
@Serializable
data class SeaCreatureEntry(
    val id: String = "",
    val variant: String = "",
    val displayName: String = "",
    val chatMessage: String = "",
    val alternateMessages: List<String> = emptyList(),
    val rarity: String = "",
    val rare: Boolean = false,
    val fishingExperience: Int? = null,
    val sortOrder: Int = 0,
)

/** The whole `/sea-creatures` body, and the exact shape of the on-disk cache. */
@Serializable
data class SeaCreatureCatalogSnapshot(
    val schemaVersion: Int = 1,
    /** Epoch ms of the newest admin edit, or null when the catalog is empty. */
    val updatedAt: Long? = null,
    val creatures: List<SeaCreatureEntry> = emptyList(),
)

/**
 * The client mirror of `GET /sea-creatures` — the sea-creature catalog the fishing tracker matches
 * chat lines against, curated in the backend's admin UI.
 *
 * ### It was written to be extracted, and it has been
 * Doc 06 wrote this as one bespoke client with everything below a `// ---- generic below this line`
 * comment treating the snapshot as an opaque value, and promised the extraction the moment a second
 * consumer landed. Doc 07 added `/drops` and `/items`, so the load / refresh / persist path now lives
 * in [BackendCatalog] and this file is the declaration that was left over: an endpoint, a kind, a
 * cache file name, a serializer, an empty value and a way to count what is inside one.
 *
 * Every behaviour doc 06 established is unchanged and documented on [BackendCatalog] — synchronous
 * disk load, `bypassCache = true`, snapshot preserved on failure, atomic persist, topic-first
 * invalidate filtering, the 1 s debounce and the 2 h staleness backstop.
 *
 * The public surface is deliberately identical to what it was, so [SeaCreatureCatalog] and
 * `FishingDebug` did not have to change.
 */
object SeaCreatureCatalogClient {
    /** The `sync-invalidate` kind the backend publishes for this catalog. */
    const val KIND: String = "sea_creatures"

    private val catalog = BackendCatalog(
        kind = KIND,
        endpoint = "/sea-creatures",
        cacheFile = "sea_creature_catalog.json",
        serializer = SeaCreatureCatalogSnapshot.serializer(),
        empty = SeaCreatureCatalogSnapshot(),
        describe = { CatalogContents(it.creatures.size, it.updatedAt) },
    )

    val snapshot: SeaCreatureCatalogSnapshot get() = catalog.snapshot

    fun addListener(listener: (SeaCreatureCatalogSnapshot) -> Unit) = catalog.addListener(listener)

    /** Declare the catalog. [CatalogRegistry.init] is what actually loads and refreshes it. */
    fun init() = CatalogRegistry.register(catalog)

    suspend fun refresh(): String = catalog.refresh()

    fun status(): CatalogStatus = catalog.status()
}
