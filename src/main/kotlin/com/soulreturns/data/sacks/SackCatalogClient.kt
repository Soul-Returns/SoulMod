package com.soulreturns.data.sacks

import com.soulreturns.platform.catalog.BackendCatalog
import com.soulreturns.platform.catalog.CatalogContents
import com.soulreturns.platform.catalog.CatalogRegistry
import com.soulreturns.platform.catalog.CatalogStatus
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One sack of the catalog: [key] is the Sack of Sacks item id without its size prefix (`MINING_SACK`),
 * [name] the in-game menu title (`Mining Sack`), [items] the SkyBlock ids the sack's menu lists.
 */
@Serializable
data class SackCatalogEntry(
    val key: String = "",
    val name: String = "",
    val items: List<String> = emptyList(),
)

/** `GET /sacks`. An empty list is valid: the backend learns the table from client reports. */
@Serializable
data class SackCatalogSnapshot(
    val schemaVersion: Int = 1,
    val sacks: List<SackCatalogEntry> = emptyList(),
)

/** Lookups over one snapshot, built once per swap. */
class SackCatalogIndex private constructor(val sacks: List<SackCatalogEntry>) {
    private val byKey = HashMap<String, SackCatalogEntry>(sacks.size * 2)
    private val byName = HashMap<String, SackCatalogEntry>(sacks.size * 2)

    init {
        for (sack in sacks) {
            if (sack.key.isNotEmpty()) byKey[sack.key] = sack
            if (sack.name.isNotEmpty()) byName[normalize(sack.name)] = sack
        }
    }

    val isEmpty: Boolean get() = sacks.isEmpty()

    fun byKey(key: String): SackCatalogEntry? = byKey[key]

    /** The entry whose menu title is [title] (case and spacing do not matter). */
    fun byName(title: String): SackCatalogEntry? = byName[normalize(title)]

    companion object {
        val EMPTY = SackCatalogIndex(emptyList())

        fun of(snapshot: SackCatalogSnapshot): SackCatalogIndex = if (snapshot.sacks.isEmpty()) EMPTY else SackCatalogIndex(snapshot.sacks)

        private fun normalize(name: String): String = name.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)
    }
}

/**
 * The client mirror of `GET /sacks` (rule://catalogs-prices, rule://sacks): which item ids each sack's menu
 * holds, learned by Soul clients themselves ([com.soulreturns.features.sacks.SackReporter]) because Hypixel
 * publishes no item-to-sack mapping. Kind `sacks`, disk cache `sack_catalog.json`, 24 h staleness (it only
 * changes when reports are applied, and every change publishes the invalidate).
 * No bundled fallback: with an empty catalog the Profile Viewer lists sack items flat.
 */
object SackCatalogClient {
    const val KIND: String = "sacks"

    private val catalog = BackendCatalog(
        kind = KIND,
        endpoint = "/sacks",
        cacheFile = "sack_catalog.json",
        serializer = SackCatalogSnapshot.serializer(),
        empty = SackCatalogSnapshot(),
        describe = { CatalogContents(it.sacks.size, null) },
        staleAfterMs = 24L * 60 * 60 * 1000,
    )

    /** Rebuilt on every snapshot swap and published by one volatile assignment. */
    @Volatile var index: SackCatalogIndex = SackCatalogIndex.of(catalog.snapshot)
        private set

    fun init() {
        index = SackCatalogIndex.of(catalog.snapshot)
        catalog.addListener { index = SackCatalogIndex.of(it) }
        CatalogRegistry.register(catalog)
    }

    suspend fun refresh(): String = catalog.refresh()

    /**
     * Dev fixtures only (`SpvFixtures`, key `sackCatalog`): show [snapshot] without a backend. The next catalog
     * swap replaces it.
     */
    fun preview(snapshot: SackCatalogSnapshot) {
        index = SackCatalogIndex.of(snapshot)
    }

    fun status(): CatalogStatus = catalog.status()
}
