package com.soulreturns.features.islandchests

import com.soulreturns.features.inventorybuttons.InventoryButtonIcons.ItemLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IslandChestObservationTest {
    private val context = ChestObservationContext("00000000000040008000000000000001", "00000000000040008000000000000002", 1)
    private val single = ChestTopology("minecraft:chest", listOf(ChestPos(3, 64, -5)))
    private fun vine(count: Int) = StoredChestItem("minecraft:vine", "VINE", "§fVines", count = count)
    private fun contents(size: Int = 27, vararg entries: Pair<Int, StoredChestItem>) = MutableList<StoredChestItem?>(size + 36) { null }.also { slots -> entries.forEach { slots[it.first] = it.second } }
    private fun open(model: IslandChestObservation, topology: ChestTopology = single, id: Int = 1) {
        model.arm(context, topology, 1000)
        model.confirm(topology.menuOrder.first(), 1050)
        assertTrue(model.open(context, id, topology.size, if (topology.size == 54) "Large Chest" else "Chest", 1100))
    }

    @Test
    fun unopenedOrOnlyPartiallyLoadedChestIsNotAnEmptyObservation() {
        val model = IslandChestObservation()
        assertNull(model.full(context, 1, contents(), 1100, "Observer"))
        open(model)
        assertNull(model.slot(context, 1, 0, null, 1200, "Observer"))
        assertNull(model.full(context, 1, emptyList(), 1200, "Observer"))
        assertNull(model.menu(context, 1, List(27) { null }, 1200, "Observer"))
        model.close()
        assertNull(model.full(context, 1, contents(), 1300, "Observer"))
        open(model)
        val empty = assertNotNull(model.full(context, 1, contents(), 1400, "Observer"))
        assertEquals(27, empty.slots.size)
        assertTrue(empty.slots.all { it == null })
    }

    @Test
    fun serverShadowTracksSplitDragQuickMoveDepositAndRemoval() {
        val model = IslandChestObservation()
        open(model)
        val packet = contents(27, 0 to vine(64))
        val initial = assertNotNull(model.full(context, 1, packet, 1200, "Observer"))
        packet[0] = vine(1)
        assertEquals(64, initial.slots[0]!!.count)
        assertEquals(32, model.slot(context, 1, 0, vine(32), 1300, "Observer")!!.slots[0]!!.count)
        assertEquals(48, model.slot(context, 1, 1, vine(16), 1400, "Observer")!!.slots.filterNotNull().sumOf { it.count })
        assertEquals(64, model.slot(context, 1, 2, vine(16), 1500, "Observer")!!.slots.filterNotNull().sumOf { it.count })
        assertEquals(32, model.slot(context, 1, 0, null, 1600, "Observer")!!.slots.filterNotNull().sumOf { it.count })
        assertEquals(96, model.slot(context, 1, 3, vine(64), 1700, "Observer")!!.slots.filterNotNull().sumOf { it.count })
        assertNull(model.slot(context, 1, 27, vine(64), 1800, "Observer"))
        assertNull(model.slot(context, -1, 0, vine(64), 1800, "Observer"))
        val empty = assertNotNull(model.full(context, 1, contents(), 1900, "Observer"))
        assertTrue(empty.slots.all { it == null })
        model.close()
        open(model, id = 2)
        val reopened = assertNotNull(model.full(context, 2, contents(), 2000, "Observer"))
        assertEquals(initial.key, reopened.key)
        assertEquals(2000, reopened.observedAt)
    }

    @Test
    fun doubleChestHalvesHaveOneNumericIdentityAndMoveWithTheirSlots() {
        val right = ChestPos(-9, 64, 10)
        val left = ChestPos(-10, 64, 10)
        val double = ChestTopology("minecraft:chest", listOf(right, left))
        val model = IslandChestObservation()
        open(model, double)
        val result = assertNotNull(model.full(context, 1, contents(54, 0 to vine(2), 27 to vine(7)), 1200, "Observer"))
        assertEquals(listOf(left, right), result.blocks)
        assertEquals("-10,64,10", result.key)
        assertEquals(7, result.slots[0]!!.count)
        assertEquals(2, result.slots[27]!!.count)
        assertEquals(9, result.slots.filterNotNull().sumOf { it.count })
        model.close()
        open(model, double, 2)
        val secondHalfOpen = assertNotNull(model.full(context, 2, contents(54, 0 to vine(2), 27 to vine(7)), 1300, "Observer"))
        assertEquals(result.key, secondHalfOpen.key)
        assertEquals(result.slots, secondHalfOpen.slots)
    }

    @Test
    fun takingLastStackWithoutServerEchoClearsSharedAndFilteredSearchResults() {
        val model = IslandChestObservation()
        val index = IslandChestSearchIndex()
        open(model)
        val initial = assertNotNull(model.full(context, 1, contents(27, 0 to vine(64)), 1200, "Observer"))
        var bucket = IslandChestBucket(revision = 7, shared = listOf(initial.copy(revision = 7)))
        var visible = bucket.visible(true)
        assertEquals(64L, index.search(visible, "vines").single().count)
        assertEquals(64L, index.search(visible, "").single().count)

        // A successful vanilla take can change only the live menu, with no inbound slot packet.
        val removed = assertNotNull(model.menu(context, 1, List(27) { null }, 1300, "Observer"))
        bucket = bucket.observe(LocalChestObservation("last-stack-removed", 7, removed))
        visible = bucket.visible(true)
        assertEquals(1, visible.size)
        assertTrue(visible.single().slots.all { it == null })
        assertTrue(index.search(visible, "vines").isEmpty())
        assertTrue(index.search(visible, "").isEmpty())
        assertNull(model.menu(context, 1, List(27) { null }, 1400, "Observer"))
    }

    @Test
    fun locatingUsesExactIdentityInPhysicalChestSlotsAndFollowsPredictedMoves() {
        val model = IslandChestObservation()
        val selected = vine(64).copy(loreLines = listOf("§7Selected variant"))
        model.select(selected)
        open(model)
        assertNull(model.locatingSlots(context, 1))
        val full = contents(27,
            0 to selected,
            1 to selected.copy(count = 1, look = ItemLook("minecraft:paper")),
            2 to selected.copy(material = "minecraft:paper"),
            3 to selected.copy(skyblockId = "OTHER"),
            4 to selected.copy(displayNameRaw = "§fOther name"),
            5 to selected.copy(loreLines = listOf("§7Other variant")),
            27 to selected,
        )
        assertNotNull(model.full(context, 1, full, 1200, "Observer"))
        val expected = BooleanArray(27) { it == 0 || it == 1 }
        assertContentEquals(expected, assertNotNull(model.locatingSlots(context, 1)))
        assertNull(model.locatingSlots(context, 2))

        val moved = full.take(27).toMutableList()
        moved[0] = null
        moved[1] = selected.copy(count = 32)
        moved[8] = selected.copy(count = 32)
        assertNotNull(model.menu(context, 1, moved, 1300, "Observer"))
        assertContentEquals(BooleanArray(27) { it == 1 || it == 8 }, model.locatingSlots(context, 1))
        model.select(selected.copy(loreLines = listOf("§7Other variant")))
        assertContentEquals(BooleanArray(27) { it == 5 }, model.locatingSlots(context, 1))
        model.select(null)
        assertNull(model.locatingSlots(context, 1))
    }

    @Test
    fun correctionsAndReopeningReplacePredictedSlotsWithoutReplayingOldCoordinates() {
        val model = IslandChestObservation()
        model.select(vine(1))
        open(model)
        assertNotNull(model.full(context, 1, contents(27, 0 to vine(64)), 1200, "Observer"))
        val predicted = contents(27, 4 to vine(64)).take(27)
        assertNotNull(model.menu(context, 1, predicted, 1300, "Observer"))
        assertContentEquals(BooleanArray(27) { it == 4 }, model.locatingSlots(context, 1))
        assertNotNull(model.slot(context, 1, 4, null, 1400, "Observer"))
        assertNotNull(model.slot(context, 1, 0, vine(64), 1400, "Observer"))
        val corrected = contents(27, 0 to vine(64)).take(27)
        assertNull(model.menu(context, 1, corrected, 1500, "Observer"))
        assertContentEquals(BooleanArray(27) { it == 0 }, model.locatingSlots(context, 1))
        assertNotNull(model.full(context, 1, contents(), 1600, "Observer"))
        assertContentEquals(BooleanArray(27), model.locatingSlots(context, 1))

        model.close()
        assertNull(model.locatingSlots(context, 1))
        open(model, id = 2)
        assertNull(model.locatingSlots(context, 2))
        assertNull(model.menu(context, 2, predicted, 1700, "Observer"))
        assertNotNull(model.full(context, 2, contents(27, 8 to vine(32)), 1800, "Observer"))
        assertNull(model.slot(context, 1, 0, vine(64), 1900, "Observer"))
        assertContentEquals(BooleanArray(27) { it == 8 }, model.locatingSlots(context, 2))
        assertNull(model.locatingSlots(context.copy(profile = "other"), 2))
        assertNull(model.locatingSlots(context, 2))
    }

    @Test
    fun doubleChestHighlightsStayInMenuOrderWhileStoredMovesUseCanonicalOrder() {
        val right = ChestPos(-9, 64, 10)
        val left = ChestPos(-10, 64, 10)
        val topology = ChestTopology("minecraft:chest", listOf(right, left))
        val model = IslandChestObservation()
        val other = vine(7).copy(loreLines = listOf("Different variant"))
        model.select(vine(1))
        open(model, topology)
        val full = contents(54, 0 to vine(2), 27 to other)
        val initial = assertNotNull(model.full(context, 1, full, 1200, "Observer"))
        assertEquals(other, initial.slots[0])
        assertEquals(vine(2), initial.slots[27])
        assertContentEquals(BooleanArray(54) { it == 0 }, model.locatingSlots(context, 1))

        val moved = full.take(54).toMutableList()
        moved[0] = null
        moved[30] = vine(2)
        val updated = assertNotNull(model.menu(context, 1, moved, 1300, "Observer"))
        assertNull(updated.slots[27])
        assertEquals(vine(2), updated.slots[3])
        assertContentEquals(BooleanArray(54) { it == 30 }, model.locatingSlots(context, 1))
    }

    @Test
    fun openMenuChangesCannotRebaseOlderContentsAfterNewerCoopEvidenceArrives() {
        val model = IslandChestObservation()
        open(model)
        val initial = assertNotNull(model.full(context, 1, contents(27, 0 to vine(64)), 1200, "Observer"))
        val baseline = 7L
        var bucket = IslandChestBucket(revision = baseline, shared = listOf(initial.copy(revision = baseline)))
            .observe(LocalChestObservation("opened", baseline, initial))
        val newer = initial.copy(slots = List(27) { null }, observerUuid = "coop", revision = 8)
        bucket = bucket.reconcile(8, listOf(newer))

        val moved = assertNotNull(model.menu(context, 1, contents(27, 5 to vine(64)).take(27), 1300, "Observer"))
        bucket = bucket.observe(LocalChestObservation("moved-after-fetch", baseline, moved))
        assertEquals(baseline, bucket.local.single().baseRevision)
        assertEquals(8L, bucket.revision)
        assertTrue(bucket.visible(true).single().slots.all { it == null })
    }

    @Test
    fun titleWithoutPhysicalInteractionOrWrongMenuNeverAssociates() {
        val model = IslandChestObservation()
        assertFalse(model.open(context, 1, 27, "Chest", 1100))
        model.arm(context, single, 1000)
        assertFalse(model.open(context, 1, 27, "Chest", 1100))
        model.arm(context, single, 1000)
        model.confirm(ChestPos(4, 64, -5), 1050)
        assertFalse(model.open(context, 1, 27, "Chest", 1100))
        for (title in listOf("Sack of Sacks", "Ender Chest", "Minion", "Chest Storage")) {
            model.arm(context, single, 1000)
            model.confirm(single.menuOrder.first(), 1050)
            assertFalse(model.open(context, 1, 27, title, 1100))
            assertFalse(model.open(context, 2, 27, "Chest", 1200))
        }
        model.arm(context, single, 1000)
        model.confirm(single.menuOrder.first(), 1050)
        assertFalse(model.open(context, 1, 54, "Large Chest", 1100))
        model.arm(context, single, 1000)
        model.confirm(single.menuOrder.first(), 1050)
        assertFalse(model.open(context, 1, 27, "Chest", 2501))
    }

    @Test
    fun accountProfileTransferAndMenuReplacementInvalidateAssociations() {
        for (next in listOf(context.copy(account = "other"), context.copy(profile = "other"), context.copy(generation = 2))) {
            val model = IslandChestObservation()
            model.arm(context, single, 1000)
            model.confirm(single.menuOrder.first(), 1050)
            assertFalse(model.open(next, 1, 27, "Chest", 1100))
            open(model)
            assertNull(model.full(next, 1, contents(), 1200, "Observer"))
            open(model)
            assertNotNull(model.full(context, 1, contents(27, 0 to vine(64)), 1200, "Observer"))
            assertFalse(model.open(context, 2, 27, "Ender Chest", 1300))
            assertNull(model.slot(context, 1, 0, null, 1400, "Observer"))
        }
    }
}
