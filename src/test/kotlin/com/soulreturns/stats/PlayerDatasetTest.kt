package com.soulreturns.stats

import com.soulreturns.api.HypixelNetwork
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerDatasetTest {
    private lateinit var root: Path

    private val handle by lazy {
        PersistentStats.namespace("dataset_test", JsonObject.serializer(), { JsonObject(emptyMap()) }, { "Banana" })
    }

    private fun count(): Int = handle.read()["n"]?.jsonPrimitive?.int ?: 0

    private fun set(n: Int) = handle.update { JsonObject(mapOf("n" to JsonPrimitive(n))) }

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("soul-dataset")
        PlayerDataset.base = { root }
        // Another test class may have left Alpha open through HypixelNetwork's events.
        PlayerDataset.switchTo(PlayerDataset.PRODUCTION, keepUnsaved = false)
        PersistentStats.open(PlayerDataset.PRODUCTION)
    }

    @AfterTest
    fun tearDown() {
        PlayerDataset.switchTo(PlayerDataset.PRODUCTION, keepUnsaved = false)
        root.toFile().deleteRecursively()
    }

    @Test
    fun alphaDataNeverTouchesTheProductionFile() {
        set(7)
        PersistentStats.flush()
        val production = root.resolve("stats.json")
        val before = production.readBytes()

        PlayerDataset.switchTo(PlayerDataset.ALPHA, keepUnsaved = true)
        assertEquals(0, count(), "the Alpha dataset starts empty, without production numbers")
        set(42)
        PersistentStats.flush()
        PlayerDataset.switchTo(PlayerDataset.PRODUCTION, keepUnsaved = true)

        assertContentEquals(before, production.readBytes())
        assertTrue(Files.isRegularFile(root.resolve("alpha").resolve("stats.json")))
        assertEquals(7, count(), "production state is back after the Alpha session")

        PlayerDataset.switchTo(PlayerDataset.ALPHA, keepUnsaved = true)
        assertEquals(42, count(), "the next Alpha session reloads its own dataset")
    }

    @Test
    fun switchingBackWritesTheAlphaSessionsUnsavedChanges() {
        PlayerDataset.switchTo(PlayerDataset.ALPHA, keepUnsaved = true)
        set(3)
        PlayerDataset.switchTo(PlayerDataset.PRODUCTION, keepUnsaved = true)
        assertEquals(0, count())
        PlayerDataset.switchTo(PlayerDataset.ALPHA, keepUnsaved = true)
        assertEquals(3, count())
    }

    @Test
    fun changesFromBeforeAlphaWasDetectedAreDropped() {
        set(5)
        PersistentStats.flush()
        val production = root.resolve("stats.json")
        val before = production.readBytes()

        // Recorded while the sidebar had not named the network yet, then the footer said Alpha.
        set(99)
        PlayerDataset.switchTo(PlayerDataset.ALPHA, keepUnsaved = false)
        assertEquals(0, count())
        assertContentEquals(before, production.readBytes())

        PlayerDataset.switchTo(PlayerDataset.PRODUCTION, keepUnsaved = true)
        assertEquals(5, count())
        assertContentEquals(before, production.readBytes())
    }

    @Test
    fun anUnreadableAlphaFileIsBackedUpInsideTheAlphaDataset() {
        val alpha = root.resolve("alpha")
        Files.createDirectories(alpha)
        Files.writeString(alpha.resolve("stats.json"), "{ not json")

        PlayerDataset.switchTo(PlayerDataset.ALPHA, keepUnsaved = true)
        assertEquals(0, count())
        val backups = alpha.resolve("backups").listDirectoryEntries("stats-*-unreadable.json")
        assertEquals(1, backups.size)
        assertTrue(Files.notExists(root.resolve("backups")), "production backups are left alone")
    }

    @Test
    fun filesResolveInsideTheOpenDataset() {
        assertEquals(root.resolve("storage.json"), PlayerDataset.file("storage.json"))
        PlayerDataset.switchTo(PlayerDataset.ALPHA, keepUnsaved = true)
        assertEquals(root.resolve("alpha").resolve("island-chests.json"), PlayerDataset.file("island-chests.json"))
        assertEquals(root.resolve("storage.json"), PlayerDataset.file("storage.json", PlayerDataset.PRODUCTION))
    }

    @Test
    fun aServerTransferJoinKeepsTheAlphaDataset() {
        PlayerDataset.onClientThread = { it() }
        PlayerDataset.subscribe()
        HypixelNetwork.reset()
        set(5)
        PersistentStats.flush()
        val production = root.resolve("stats.json")
        val before = production.readBytes()

        // Connect to Alpha: JOIN, then the address latches Alpha.
        PlayerDataset.onJoin()
        HypixelNetwork.onAddress("alpha.hypixel.net")
        assertEquals(PlayerDataset.ALPHA, PlayerDataset.active)
        set(11)

        // Queue limbo -> lobby: Hypixel fires JOIN again without a DISCONNECT.
        PlayerDataset.onJoin()
        HypixelNetwork.onAddress("alpha.hypixel.net")
        assertEquals(PlayerDataset.ALPHA, PlayerDataset.active)
        assertTrue(PlayerDataset.writable())
        assertEquals(11, count())

        // Disconnect: Alpha stays open, not writable, until the next connection joins.
        HypixelNetwork.reset()
        PlayerDataset.onDisconnect()
        assertEquals(PlayerDataset.ALPHA, PlayerDataset.active)
        assertFalse(PlayerDataset.writable())
        assertContentEquals(before, production.readBytes())

        // A production connection: unresolved until the footer, then production.
        PlayerDataset.onJoin()
        HypixelNetwork.onAddress("mc.hypixel.net")
        HypixelNetwork.onEnvironment("PRODUCTION")
        assertEquals(PlayerDataset.PRODUCTION, PlayerDataset.active)
        assertFalse(PlayerDataset.writable())
        HypixelNetwork.onSidebar(listOf("§ewww.hypixel.net"))
        assertTrue(PlayerDataset.writable())
        assertEquals(5, count())
        assertContentEquals(before, production.readBytes())

        PlayerDataset.switchTo(PlayerDataset.ALPHA, keepUnsaved = true)
        assertEquals(11, count(), "the Alpha session's numbers were saved to the Alpha dataset")

        HypixelNetwork.reset()
        PlayerDataset.onDisconnect()
    }
}
