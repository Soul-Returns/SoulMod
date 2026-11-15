package com.soulreturns.features.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageApiPreviewsTest {
    private val ender = StoragePageId("ender", 1)
    private val backpack = StoragePageId("backpack", 2)
    private fun page(id: StoragePageId, identity: String? = null) =
        StoredStoragePage(id, "Preview", 1, identity, listOf("item", null, null, null, null, null, null, null, null))

    @Test
    fun observedEmptyContentsAreNotRepopulatedFromApi() {
        val api = page(ender)
        val emptied = api.copy(items = List(9) { null })
        assertTrue(storageApiPreviews(StorageBucket(pages = listOf(api.copy(items = null))), listOf(api), emptySet()).isEmpty())
        assertTrue(storageApiPreviews(StorageBucket(pages = listOf(emptied)), listOf(api), emptySet()).isEmpty())
        assertEquals(api, storageApiPreviews(StorageBucket(), listOf(api), emptySet())[ender])
    }

    @Test
    fun removedReplacedAndUnidentifiedBackpacksCannotInheritApiContents() {
        val api = page(backpack, "old-bag")
        for (selector in listOf(StorageSelector(backpack, "empty"), StorageSelector(backpack, "locked"),
            StorageSelector(backpack, "installed", "replacement"), StorageSelector(backpack, "installed"))) {
            assertTrue(storageApiPreviews(StorageBucket(selectors = listOf(selector)), listOf(api), emptySet()).isEmpty())
        }
        val same = StorageBucket(selectors = listOf(StorageSelector(backpack, "installed", "old-bag")))
        assertEquals(api, storageApiPreviews(same, listOf(api), emptySet())[backpack])
    }

    @Test
    fun nativeMutationCannotReseedAStaleApiPreview() {
        val api = page(backpack, "bag")
        val known = StorageBucket(selectors = listOf(StorageSelector(backpack, "installed", "bag")))
        assertTrue(storageApiPreviews(known, listOf(api), setOf(backpack)).isEmpty())
    }
}
