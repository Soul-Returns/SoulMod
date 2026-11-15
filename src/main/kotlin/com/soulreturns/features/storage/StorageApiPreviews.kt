package com.soulreturns.features.storage

internal fun storageApiPreviews(
    bucket: StorageBucket,
    api: List<StoredStoragePage>,
    invalidatedBackpacks: Set<StoragePageId>,
): Map<StoragePageId, StoredStoragePage> {
    val observed = bucket.pages.associateBy { it.id }
    val selectors = bucket.selectors.associateBy { it.id }
    return api.filter { page ->
        if (page.items == null || observed.containsKey(page.id)) false
        else if (page.id.kind != "backpack") true
        else {
            val selector = selectors[page.id]
            page.id !in invalidatedBackpacks && (selector == null ||
                (selector.state == "installed" && selector.identity != null && selector.identity == page.identity))
        }
    }.associateBy { it.id }
}
