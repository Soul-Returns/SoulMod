package com.soulreturns.features.inventorybuttons

import com.soulreturns.config.ButtonProfile
import com.soulreturns.config.InventoryButtonsConfig
import com.soulreturns.config.InventoryButtonsConfig.Companion.DEFAULT_ID
import com.soulreturns.config.InventoryButtonsConfig.Companion.RUN_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InventoryButtonProfilesTest {
    private val config = InventoryButtonsConfig(
        profiles = listOf(ButtonProfile("m", "Mining"), ButtonProfile("d", "Dungeons"), ButtonProfile("h", "Hub set")),
    )

    private fun resolve(c: InventoryButtonsConfig, area: String?, inDungeon: Boolean = false) =
        InventoryButtonProfiles.resolve(c, area, inDungeon)

    @Test
    fun nothingSetIsDefault() {
        assertEquals(DEFAULT_ID, resolve(config, "Hub"))
        assertEquals(DEFAULT_ID, resolve(config, null))
        assertEquals(DEFAULT_ID, resolve(InventoryButtonsConfig(), "Hub", inDungeon = true))
    }

    @Test
    fun everywhereAppliesWhereNothingMoreSpecificIsSet() {
        val c = config.copy(activeEverywhere = "m")
        assertEquals("m", resolve(c, "Hub"))
        assertEquals("m", resolve(c, null))
        assertEquals("m", resolve(c, "Dungeon", inDungeon = true))
    }

    @Test
    fun areaBeatsEverywhere() {
        val c = config.copy(activeEverywhere = "m", activeByArea = mapOf("Hub" to "h"))
        assertEquals("h", resolve(c, "Hub"))
        assertEquals("m", resolve(c, "Mineshaft"))
        // An area may also hand a place back to Default explicitly.
        assertEquals(DEFAULT_ID, resolve(c.copy(activeByArea = mapOf("Hub" to DEFAULT_ID)), "Hub"))
    }

    @Test
    fun dungeonRunBeatsAreaAndEverywhere() {
        val c = config.copy(activeEverywhere = "m", activeByArea = mapOf(RUN_KEY to "d", "Dungeon" to "h", "Dungeon Hub" to "h"))
        assertEquals("d", resolve(c, "Dungeon", inDungeon = true))
        assertEquals("h", resolve(c, "Dungeon Hub")) // the hub is not a run
        assertEquals("h", resolve(c.copy(activeByArea = c.activeByArea - RUN_KEY), "Dungeon", inDungeon = true))
        assertEquals("m", resolve(c, "Hub", inDungeon = false)) // the run slot never applies outside a run
    }

    @Test
    fun deletedProfilesFallBack() {
        val c = config.copy(activeEverywhere = "gone", activeByArea = mapOf("Hub" to "gone", RUN_KEY to "gone", "Mineshaft" to "m"))
        assertEquals(DEFAULT_ID, resolve(c, "Hub"))
        assertEquals(DEFAULT_ID, resolve(c, "Dungeon", inDungeon = true))
        assertEquals("m", resolve(c, "Mineshaft"))
        // Slots below a dangling one still apply.
        assertEquals("d", resolve(c.copy(activeEverywhere = "d"), "Hub"))
    }

    @Test
    fun withoutProfileRemovesItsSlots() {
        val c = config.copy(activeEverywhere = "m", activeByArea = mapOf("Hub" to "m", "Mineshaft" to "h", RUN_KEY to "m"))
        val after = InventoryButtonProfiles.withoutProfile(c, "m")
        assertEquals(listOf("d", "h"), after.profiles.map { it.id })
        assertEquals(DEFAULT_ID, after.activeEverywhere)
        assertEquals(mapOf("Mineshaft" to "h"), after.activeByArea)
    }

    @Test
    fun assigningTakesTheSlotFromItsOwner() {
        var c = InventoryButtonProfiles.useIn(config, "Hub", "m")
        assertEquals("m", InventoryButtonProfiles.owner(c, "Hub"))
        c = InventoryButtonProfiles.useIn(c, "Hub", "h")
        assertEquals("h", InventoryButtonProfiles.owner(c, "Hub"))
        assertEquals(emptyList(), InventoryButtonProfiles.slotsOf(c, "m"))
        assertEquals(listOf("Hub"), InventoryButtonProfiles.slotsOf(c, "h"))
        c = InventoryButtonProfiles.release(c, "Hub")
        assertNull(InventoryButtonProfiles.owner(c, "Hub"))
        assertEquals(config, InventoryButtonProfiles.useIn(config, "Hub", "unknown"))
        assertEquals("d", InventoryButtonProfiles.everywhere(InventoryButtonProfiles.useEverywhere(config, "d")))
    }

    @Test
    fun slotsListTheRunFirstThenAreasAlphabetically() {
        val c = config.copy(activeByArea = mapOf("Mineshaft" to "m", RUN_KEY to "m", "Dwarven Mines" to "m", "Hub" to "d"))
        assertEquals(listOf(RUN_KEY, "Dwarven Mines", "Mineshaft"), InventoryButtonProfiles.slotsOf(c, "m"))
        assertEquals(InventoryButtonProfiles.DUNGEON_RUN, InventoryButtonProfiles.slotLabel(RUN_KEY))
        assertEquals(RUN_KEY, InventoryButtonProfiles.slotKey(InventoryButtonProfiles.DUNGEON_RUN))
    }

    @Test
    fun pickerListsSeenAreasOnce() {
        val groups = InventoryButtonProfiles.pickerGroups(listOf("Hub", "Winter Island", "Winter Island", "Abiphone Lab", RUN_KEY))
        val other = groups.first { it.first == "Other" }.second
        assertEquals(listOf("Abiphone Lab", "Winter Island"), other.takeLast(2))
        assertEquals(1, groups.sumOf { g -> g.second.count { it == "Hub" } })
        assertEquals(0, groups.sumOf { g -> g.second.count { it == RUN_KEY } })
    }
}
