package com.soulreturns.features.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageModelTest {
    private val backpack = StoragePageId("backpack", 1)
    private val second = StoragePageId("backpack", 2)
    private val ender = StoragePageId("ender", 1)
    private fun page(id: StoragePageId = backpack, identity: String? = "bag-a") =
        StoredStoragePage(id, "Remembered", 4, identity, List(36) { if (it == 0) "real-component-payload" else null })

    @Test
    fun onlyExactSupportedTitlesAndSizesCanBind() {
        assertNotNull(StorageMenus.parse("Storage", 6))
        assertNotNull(StorageMenus.parse("Ender Chest (9/9)", 6))
        assertEquals(backpack, StorageMenus.parse("Greater Backpack (Slot #1)", 5)?.page)
        assertEquals(ender, StorageMenus.parse("ender chest (1/9)", 6)?.page)
        assertNotNull(StorageMenus.parse("storage", 6))
        assertNotNull(StorageMenus.parse("greater backpack (slot #1)", 5))
        assertNotNull(StorageMenus.parse("Massive Backpack (Slot #18)", 6))
        for ((title, rows) in listOf("Storage" to 5, "Storage Settings" to 6, "Ender Chest (0/9)" to 6,
            "Ender Chest (1/9)" to 5, "Greater Backpack (Slot #19)" to 5, "Greater Backpack (Slot #1)" to 1,
            "Other Greater Backpack (Slot #1)" to 5)) assertNull(StorageMenus.parse(title, rows), title)
    }

    @Test
    fun lastPageRewritesOnlyTheBareStorageCommand() {
        val lastEnder = StorageBucket().observe(page(StoragePageId("ender", 5), null))
        assertEquals("enderchest 5", lastEnder.openingCommand("storage"))
        assertEquals("enderchest 5", lastEnder.openingCommand(" STORAGE "))
        val lastBackpack = lastEnder.observe(page(second))
        assertEquals("backpack 2", lastBackpack.openingCommand("storage"))
        for (command in listOf("storage 3", "enderchest 1", "backpack 7", "storages", "soul storage")) {
            assertEquals(command, lastBackpack.openingCommand(command))
        }
        assertEquals("storage", StorageBucket().openingCommand("storage"))
        assertEquals("storage", lastBackpack.reconcile(listOf(StorageSelector(second, "empty"))).openingCommand("storage"))
        for (id in listOf(StoragePageId("ender", 10), StoragePageId("backpack", 0), StoragePageId("other", 1))) {
            assertEquals("storage", StorageBucket(lastPage = id).openingCommand("storage"))
        }
    }

    @Test
    fun initialFullPacketIsRequiredBeforePredictionsOrSlotCorrections() {
        val observation = StorageObservation<String?>()
        observation.open(7, StorageMenus.parse("Greater Backpack (Slot #1)", 5)!!)
        assertFalse(observation.slot(7, 9, "item"))
        assertFalse(observation.predicted(7, List(45) { null }))
        assertFalse(observation.full(6, List(81) { null }))
        assertFalse(observation.full(7, List(80) { null }))
        assertNull(observation.contents)
        val initial = MutableList<String?>(81) { null }.also { it[9] = "64 items" }
        assertTrue(observation.full(7, initial))
        initial[9] = "mutated source"
        assertEquals("64 items", observation.contents!![9])
        assertTrue(observation.predicted(7, List(45) { null }))
        assertNull(observation.contents!![9])
        assertTrue(observation.slot(7, 9, "server correction"))
        assertEquals("server correction", observation.contents!![9])
        observation.open(8, StorageMenus.parse("Ender Chest (1/9)", 6)!!)
        assertNull(observation.contents)
        assertFalse(observation.slot(7, 9, "late old-menu packet"))
        assertFalse(observation.predicted(8, List(54) { null }))
        observation.clear()
        assertFalse(observation.full(8, List(90) { null }))
    }

    @Test
    fun removalThenReinstallationElsewhereNeverReusesOldSlotContents() {
        val original = StorageBucket(listOf(page()), listOf(StorageSelector(backpack, "installed", "bag-a")))
        val removed = original.reconcile(listOf(StorageSelector(backpack, "empty"), StorageSelector(second, "empty")))
        assertTrue(removed.pages.isEmpty())
        val moved = removed.reconcile(listOf(StorageSelector(backpack, "empty"), StorageSelector(second, "installed", "bag-a")))
        assertTrue(moved.pages.isEmpty())
        assertEquals("installed", moved.selectors.last().state)
    }

    @Test
    fun replacedOrUnidentifiableBackpackIsUnknownNotEmpty() {
        val original = StorageBucket(listOf(page()), listOf(StorageSelector(backpack, "installed", "bag-a")))
        assertNotNull(original.reconcile(listOf(StorageSelector(backpack, "installed", "bag-a"))).pages.single().items)
        assertNull(original.reconcile(listOf(StorageSelector(backpack, "installed", "bag-b"))).pages.single().items)
        assertNull(original.reconcile(listOf(StorageSelector(backpack, "installed"))).pages.single().items)
        assertNull(original.reconcile(listOf(StorageSelector(backpack, "unknown"))).pages.singleOrNull())
    }

    @Test
    fun nativeMutationInvalidatesOnlyTheAffectedBackpack() {
        val bucket = StorageBucket(listOf(page(), page(second, "bag-b"), page(ender, null)))
        val changed = bucket.invalidateBackpacks(setOf(backpack))
        assertNull(changed.pages.first { it.id == backpack }.items)
        assertNotNull(changed.pages.first { it.id == second }.items)
        assertNotNull(changed.pages.first { it.id == ender }.items)
        assertNotNull(bucket.pages.first().items)
    }

    @Test
    fun scopeRequiresExactAccountAndProfileUuids() {
        val account = "00000000-0000-4000-8000-000000000001"
        val profile = "00000000-0000-4000-8000-000000000002"
        assertEquals("$account/$profile", storageScope(account.replace("-", ""), profile.uppercase()))
        assertNull(storageScope(account, "Strawberry"))
        assertNull(storageScope(null, profile))
        assertNull(storageScope(account, null))
        assertNull(storageScope(account, "1-1-1-1-1"))
        assertFalse(storageScope(account, profile) == storageScope(profile, account))
    }
}
