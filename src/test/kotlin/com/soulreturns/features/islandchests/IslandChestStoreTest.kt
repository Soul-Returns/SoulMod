package com.soulreturns.features.islandchests

import com.soulreturns.features.inventorybuttons.InventoryButtonIcons.ItemLook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IslandChestStoreTest {
    private val account = "00000000-0000-4000-8000-000000000001"
    private val otherAccount = "00000000-0000-4000-8000-000000000002"
    private val profile = "00000000-0000-4000-8000-000000000003"
    private val otherProfile = "00000000-0000-4000-8000-000000000004"
    private fun observation() = LocalChestObservation("00000000-0000-4000-8000-000000000005", 0,
        StoredIslandChest("1,64,2", listOf(ChestPos(1, 64, 2)), "minecraft:chest", List(27) { slot ->
            if (slot == 0) StoredChestItem("minecraft:player_head", "FISH", "§bFish", listOf("A physical collectible"), 1,
                ItemLook("minecraft:player_head", "{\"name\":\"icon\"}", "minecraft:paper", true)) else null
        }, account, "Observer", 1_700_000_000_000))

    private fun temporary(body: (Path) -> Unit) {
        val directory = Files.createTempDirectory("soul-island-chests-test")
        try { body(directory.resolve("island-chests.json")) } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun accountAndExactProfileSurviveRoundTripWithoutLegacyBuckets() = temporary { file ->
        val store = IslandChestStore(file) { _, _ -> }
        store.load()
        val obs = observation()
        store.update(account, profile) { it.observe(obs) }
        assertTrue(store.read(account, otherProfile).local.isEmpty())
        assertTrue(store.read(otherAccount, profile).local.isEmpty())
        assertNull(store.update(account, null) { it.observe(obs) })
        assertNull(store.update(null, profile) { it.observe(obs) })
        assertNull(store.update(account, "Strawberry") { it.observe(obs) })
        store.flush()
        assertFalse(store.hasChanges())
        val reloaded = IslandChestStore(file) { _, _ -> }.also { it.load() }
        assertEquals(obs, reloaded.read(account.replace("-", ""), profile.replace("-", "")).local.single())
        assertTrue(reloaded.read(account, otherProfile).local.isEmpty())
        assertTrue(reloaded.read(otherAccount, profile).local.isEmpty())
        val root = Json.parseToJsonElement(Files.readString(file)) as JsonObject
        assertEquals(setOf(chestBucketKey(account, profile)), (root["buckets"] as JsonObject).keys)
    }

    @Test
    fun malformedClaimedBucketAndUnknownDataArePreservedWhenAnotherBucketSaves() = temporary { file ->
        val badKey = chestBucketKey(account, profile)!!
        val original = """{"schemaVersion":1,"futureField":{"keep":true},"buckets":{"$badKey":{"local":"unreadable"},"unclaimed":{"anything":[1,2,3]}}}"""
        Files.writeString(file, original)
        val reasons = ArrayList<String>()
        val store = IslandChestStore(file) { _, reason -> reasons += reason }.also { it.load() }
        assertTrue(store.read(account, profile).local.isEmpty())
        store.update(account, profile) { it.observe(observation()) }
        store.update(otherAccount, otherProfile) { it.observe(observation()) }
        store.flush()
        val before = Json.parseToJsonElement(original) as JsonObject
        val after = Json.parseToJsonElement(Files.readString(file)) as JsonObject
        assertEquals(before["futureField"], after["futureField"])
        val beforeBuckets = before["buckets"] as JsonObject
        val afterBuckets = after["buckets"] as JsonObject
        assertEquals(beforeBuckets[badKey], afterBuckets[badKey])
        assertEquals(beforeBuckets["unclaimed"], afterBuckets["unclaimed"])
        assertEquals(listOf("unreadable-bucket"), reasons)
    }

    @Test
    fun unreadableOrNewerFileIsNeverOverwrittenByDefaults() = temporary { file ->
        for (original in listOf("{not valid JSON", """{"schemaVersion":5,"buckets":{"keep":"everything"}}""")) {
            Files.writeString(file, original)
            val reasons = ArrayList<String>()
            val store = IslandChestStore(file) { _, reason -> reasons += reason }.also { it.load() }
            store.update(account, profile) { it.observe(observation()) }
            store.flush()
            assertEquals(original, Files.readString(file))
            assertEquals(1, reasons.size)
            assertFalse(store.hasChanges())
        }
    }
}
