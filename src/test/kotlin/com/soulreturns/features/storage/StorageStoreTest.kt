package com.soulreturns.features.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageStoreTest {
    private val account = "00000000-0000-4000-8000-000000000001"
    private val profile = "00000000-0000-4000-8000-000000000002"
    private val other = "00000000-0000-4000-8000-000000000003"
    private val key = storageScope(account, profile)!!
    private val page = StoredStoragePage(StoragePageId("ender", 1), "Ender Chest 1", 5,
        items = List(45) { index -> if (index == 0) """{id:"minecraft:player_head",count:1,components:{"minecraft:custom_name":{text:"Remember me"},"minecraft:profile":{name:"icon"},"minecraft:lore":[{text:"Searchable lore"}],"minecraft:item_model":"minecraft:paper"}}""" else null })

    private fun temporary(body: (Path) -> Unit) {
        val directory = Files.createTempDirectory("soul-storage-test")
        try { body(directory.resolve("storage.json")) } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun fullComponentPayloadAndOtherProfilesSurviveAtomicRoundTrip() = temporary { file ->
        val store = StorageStore(file) { _, _ -> }.also { it.load() }
        store.update(key) { it.observe(page) }
        store.update(storageScope(account, other)!!) { it.observe(page.copy(label = "Other profile")) }
        store.update(storageScope(other, profile)!!) { it.observe(page.copy(label = "Other account")) }
        store.flush()
        assertFalse(store.hasChanges())
        assertFalse(Files.exists(file.resolveSibling("storage.json.tmp")))
        val reloaded = StorageStore(file) { _, _ -> }.also { it.load() }
        assertEquals(page, reloaded.read(key).pages.single())
        assertEquals("Other profile", reloaded.read(storageScope(account, other)!!).pages.single().label)
        assertEquals("Other account", reloaded.read(storageScope(other, profile)!!).pages.single().label)
    }

    @Test
    fun lastPageSurvivesRestartWithoutLeakingAcrossProfilesOrAccounts() = temporary { file ->
        val store = StorageStore(file) { _, _ -> }.also { it.load() }
        val otherProfile = storageScope(account, other)!!
        val otherAccount = storageScope(other, profile)!!
        store.update(key) { it.observe(page.copy(id = StoragePageId("ender", 5))) }
        store.update(otherProfile) { it.observe(page.copy(id = StoragePageId("backpack", 2))) }
        store.update(otherAccount) { it.observe(page.copy(id = StoragePageId("ender", 9))) }
        store.flush()
        val reloaded = StorageStore(file) { _, _ -> }.also { it.load() }
        assertEquals("enderchest 5", reloaded.read(key).openingCommand("storage"))
        assertEquals("backpack 2", reloaded.read(otherProfile).openingCommand("storage"))
        assertEquals("enderchest 9", reloaded.read(otherAccount).openingCommand("storage"))
        assertEquals("storage", reloaded.read(storageScope(other, other)!!).openingCommand("storage"))
    }

    @Test
    fun existingStoreWithoutLastPageKeepsItsPagesAndUsesTheOverview() = temporary { file ->
        Files.writeString(file, """{"schemaVersion":1,"buckets":{"$key":{"pages":[{"id":{"kind":"ender","index":1},"label":"Ender Chest 1","rows":5}],"selectors":[]}}}""")
        val store = StorageStore(file) { _, _ -> }.also { it.load() }
        assertEquals(listOf(page.copy(items = null)), store.read(key).pages)
        assertEquals("storage", store.read(key).openingCommand("storage"))
        store.update(key) { it.observe(page.copy(id = StoragePageId("ender", 5))) }
        store.flush()
        val reloaded = StorageStore(file) { _, _ -> }.also { it.load() }
        assertEquals(2, reloaded.read(key).pages.size)
        assertEquals("enderchest 5", reloaded.read(key).openingCommand("storage"))
    }

    @Test
    fun corruptOrNewerRootCannotBeOverwrittenByFreshObservations() = temporary { file ->
        for (original in listOf("not json", """{"schemaVersion":3,"buckets":{}}""", """{"schemaVersion":0,"buckets":{}}""")) {
            Files.writeString(file, original)
            val reasons = mutableListOf<String>()
            val store = StorageStore(file) { _, reason -> reasons += reason }.also { it.load() }
            store.update(key) { it.observe(page) }
            store.flush()
            assertEquals(original, Files.readString(file))
            assertTrue(reasons.isNotEmpty())
            assertEquals(page, store.read(key).pages.single())
        }
    }

    @Test
    fun v1BackpackPagesSavedWithoutUuidTakeTheirSlotsLastSeenBackpack() = temporary { file ->
        fun backpack(index: Int, identity: String?, items: Boolean) =
            """{"id":{"kind":"backpack","index":$index},"label":"Greater Backpack $index","rows":4,"identity":${identity?.let { "\"$it\"" } ?: "null"},"items":${if (items) "[\"payload\"" + ",null".repeat(35) + "]" else "null"}}"""
        fun selector(index: Int, state: String, identity: String?) =
            """{"id":{"kind":"backpack","index":$index},"state":"$state","identity":${identity?.let { "\"$it\"" } ?: "null"},"rows":4}"""
        val pages = listOf(backpack(2, null, true), backpack(3, null, false), backpack(4, "bag-d", true), backpack(5, null, true), backpack(6, null, true))
        val selectors = listOf(selector(2, "installed", "bag-b"), selector(3, "installed", "bag-c"), selector(4, "installed", "bag-d"), selector(6, "installed", null))
        val otherProfile = storageScope(account, other)!!
        Files.writeString(file, """{"schemaVersion":1,"futureField":true,"buckets":{"$key":{"pages":[${pages.joinToString(",")}],"selectors":[${selectors.joinToString(",")}]},"$otherProfile":{"pages":"bad"}}}""")
        val reasons = mutableListOf<String>()
        val store = StorageStore(file) { _, reason -> reasons += reason }.also { it.load() }
        assertEquals(listOf("migrate-v1"), reasons)
        val bucket = store.read(key)
        assertEquals(listOf("bag-b", null, "bag-d", null, null), bucket.pages.map { it.identity })
        assertEquals("payload", bucket.remembered(StoragePageId("backpack", 2))!!.first())
        assertEquals(bucket.pages.first(), bucket.reconcile(bucket.selectors).pages.first())
        assertTrue(store.hasChanges())
        store.flush()
        val saved = Json.parseToJsonElement(Files.readString(file)) as JsonObject
        assertEquals("2", saved["schemaVersion"].toString())
        assertEquals("true", saved["futureField"].toString())
        assertEquals("""{"pages":"bad"}""", (saved["buckets"] as JsonObject)[otherProfile].toString())
        val again = mutableListOf<String>()
        val reloaded = StorageStore(file) { _, reason -> again += reason }.also { it.load() }
        assertEquals(bucket, reloaded.read(key))
        assertTrue(again.isEmpty())
        assertFalse(reloaded.hasChanges())
    }

    @Test
    fun malformedBucketAndUnclaimedDataStayVerbatimWhenHealthyProfileSaves() = temporary { file ->
        val original = Json.parseToJsonElement("""{"schemaVersion":1,"futureField":{"keep":true},"buckets":{"$key":{"pages":"bad"},"unclaimed":{"future":"verbatim"}}}""") as JsonObject
        Files.writeString(file, original.toString())
        val reasons = mutableListOf<String>()
        val store = StorageStore(file) { _, reason -> reasons += reason }.also { it.load() }
        assertTrue(store.read(key).pages.isEmpty())
        store.update(key) { it.observe(page) }
        store.update(storageScope(account, other)!!) { it.observe(page) }
        store.flush()
        val saved = Json.parseToJsonElement(Files.readString(file)) as JsonObject
        assertEquals(original["futureField"], saved["futureField"])
        assertEquals((original["buckets"] as JsonObject)[key], (saved["buckets"] as JsonObject)[key])
        assertEquals((original["buckets"] as JsonObject)["unclaimed"], (saved["buckets"] as JsonObject)["unclaimed"])
        assertEquals(listOf("unreadable-bucket"), reasons)
    }

    @Test
    fun unreadableComponentBucketIsBackedUpOnceAndNotRewritten() = temporary { file ->
        val initial = StorageStore(file) { _, _ -> }.also { it.load() }
        initial.update(key) { it.observe(page) }
        initial.flush()
        val original = Json.parseToJsonElement(Files.readString(file)) as JsonObject
        val reasons = mutableListOf<String>()
        val store = StorageStore(file) { _, reason -> reasons += reason }.also { it.load() }
        store.read(key)
        store.unreadableItems(key)
        store.unreadableItems(key)
        store.update(key) { it.observe(page.copy(label = "New in-memory observation")) }
        store.update(storageScope(account, other)!!) { it.observe(page) }
        store.flush()
        val saved = Json.parseToJsonElement(Files.readString(file)) as JsonObject
        assertEquals((original["buckets"] as JsonObject)[key], (saved["buckets"] as JsonObject)[key])
        assertEquals(listOf("unreadable-items"), reasons)
    }
}
