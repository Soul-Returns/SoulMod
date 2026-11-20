package com.soulreturns.data.hypixel

import com.soulreturns.platform.catalog.BackendCatalog
import com.soulreturns.platform.catalog.CatalogContents
import com.soulreturns.platform.catalog.CatalogRegistry
import com.soulreturns.platform.catalog.CatalogStatus

/**
 * The client mirror of `GET /hotm/tree`: the Heart of the Mountain tree (layout, maxima, costs, effects,
 * tier XP, token and Core rewards) the Profile Viewer lays a member's `skill_tree` over. The backend serves
 * it from NotEnoughUpdates-REPO's tables corrected by Soul's captures; the mod never bundles it
 * (rule://catalogs-prices). Kind `hotm`, disk cache `hotm_tree.json`, default staleness.
 * Until the first download [tree] is empty and the Mining section shows the plain list.
 */
object HotmTreeClient {
    const val KIND: String = "hotm"

    private val catalog = BackendCatalog(
        kind = KIND,
        endpoint = "/hotm/tree",
        cacheFile = "hotm_tree.json",
        serializer = HotmTreeSnapshot.serializer(),
        empty = HotmTreeSnapshot(),
        describe = { CatalogContents(it.nodes.size, it.updatedAt) },
    )

    /** Rebuilt on every snapshot swap and published by one volatile assignment. */
    @Volatile var tree: HotmTree = HotmTree(catalog.snapshot)
        private set

    /** Declare the catalog; [CatalogRegistry.init] loads and refreshes it. */
    fun init() {
        tree = HotmTree(catalog.snapshot)
        catalog.addListener { tree = HotmTree(it) }
        CatalogRegistry.register(catalog)
    }

    suspend fun refresh(): String = catalog.refresh()

    /**
     * Dev fixtures only (`SpvFixtures`, key `hotmTree`): show [snapshot] without a backend. The next catalog
     * swap replaces it.
     */
    fun preview(snapshot: HotmTreeSnapshot) {
        tree = HotmTree(snapshot)
    }

    fun status(): CatalogStatus = catalog.status()
}
