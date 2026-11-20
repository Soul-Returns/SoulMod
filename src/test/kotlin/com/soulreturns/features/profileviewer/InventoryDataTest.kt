package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.ItemBlobs
import com.soulreturns.data.hypixel.MagicalPower
import com.soulreturns.data.hypixel.abiphoneContacts
import com.soulreturns.data.hypixel.accessoryBagBlob
import com.soulreturns.data.hypixel.activeTuning
import com.soulreturns.data.hypixel.backpackBlobs
import com.soulreturns.data.hypixel.bagUpgradesPurchased
import com.soulreturns.data.hypixel.enderChestBlob
import com.soulreturns.data.hypixel.enderChestPages
import com.soulreturns.data.hypixel.fishingBagBlob
import com.soulreturns.data.hypixel.hasPersonalVaultApi
import com.soulreturns.data.hypixel.humanizeItemId
import com.soulreturns.data.hypixel.inventoryBlob
import com.soulreturns.data.hypixel.inventoryDisplayOrder
import com.soulreturns.data.hypixel.mergeSacks
import com.soulreturns.data.hypixel.potionBagBlob
import com.soulreturns.data.hypixel.powerDisplayName
import com.soulreturns.data.hypixel.quiverBlob
import com.soulreturns.data.hypixel.riftPrismConsumed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Inventories and Accessories accessors against the `inventories-*` / `accessories-*` fixture fragments. */
class InventoryDataTest {
    private fun sam() = InvHarness.member("sam", "inventories-sam", "accessories-sam")

    @Test
    fun `sam has every container and the vault toggle`() {
        val m = sam()
        assertNotNull(m.inventoryBlob)
        assertNotNull(m.enderChestBlob)
        assertNotNull(m.potionBagBlob)
        assertNotNull(m.fishingBagBlob)
        assertNotNull(m.quiverBlob)
        assertNotNull(m.accessoryBagBlob)
        assertTrue(m.hasPersonalVaultApi)
        assertFalse(m.apiAccessDisabled)
    }

    @Test
    fun `backpack keys are zero based and become slots 1 to 18`() {
        val bps = sam().backpackBlobs
        assertEquals(listOf(1, 2, 4), bps.map { it.slot })
        assertTrue(bps.all { it.iconBlob != null })
        assertEquals(27, ItemBlobs.parse(bps[0].data)!!.size)
        assertEquals(45, ItemBlobs.parse(bps[2].data)!!.size)
        val icon = ItemBlobs.parse(bps[0].iconBlob!!)!!.single()
        assertEquals("§aGreater Backpack", icon!!.name)
    }

    @Test
    fun `the inventory is listed hotbar first and shown rows first`() {
        val items = ItemBlobs.parse(sam().inventoryBlob!!)!!
        assertEquals(36, items.size)
        assertEquals("HYPERION", items[8]!!.id)
        val shown = inventoryDisplayOrder(items)
        assertEquals("HYPERION", shown[35]!!.id)
        assertEquals("REFINED_MITHRIL", shown[0]!!.id)
        assertEquals(listOf(1, 2, 3), inventoryDisplayOrder(listOf(1, 2, 3)))
    }

    @Test
    fun `the ender chest blob is three pages of 45`() {
        val pages = enderChestPages(ItemBlobs.parse(sam().enderChestBlob!!)!!)
        assertEquals(listOf(45, 45, 45), pages.map { it.size })
        assertEquals("TERMINATOR", pages[0][7]!!.id)
        assertNull(pages[0][1])
    }

    @Test
    fun `joe has no vault and no backpacks`() {
        val m = InvHarness.member("joe", "inventories-joe", "accessories-joe")
        assertFalse(m.hasPersonalVaultApi)
        assertTrue(m.backpackBlobs.isEmpty())
        assertEquals(1, enderChestPages(ItemBlobs.parse(m.enderChestBlob!!)!!).size)
    }

    @Test
    fun `builder has the Inventory API off but still has sacks`() {
        val m = InvHarness.member("builder", "inventories-builder", "accessories-builder")
        assertTrue(m.apiAccessDisabled)
        assertNull(m.inventoryBlob)
        assertNull(m.accessoryBagBlob)
        assertEquals(9120L, m.sacks["COBBLESTONE"])
    }

    @Test
    fun `item text carries id, rarity, recombobulator and search`() {
        val hyperion = ItemBlobs.parse(sam().inventoryBlob!!)!![8]!!
        assertEquals("LEGENDARY", hyperion.rarity)
        assertTrue(hyperion.recombobulated)
        assertEquals(1, hyperion.count)
        assertEquals("Hyperion", hyperion.plainName)
        assertTrue(hyperion.matches("hyperion"))
        assertTrue(hyperion.matches("damage: +260"))
        assertTrue(hyperion.matches(""))
        assertFalse(hyperion.matches("terminator"))
        val coal = ItemBlobs.parse(sam().enderChestBlob!!)!![0]!!
        assertEquals(40, coal.count)
        assertEquals("UNCOMMON", coal.rarity)
    }

    @Test
    fun `an unreadable blob is null, not an empty container`() {
        assertNull(ItemBlobs.parse("this is not base64!"))
        assertNull(ItemBlobs.parse("AAAA"))
    }

    @Test
    fun `sacks sort by count and Soul counts replace Hypixel's`() {
        val api = mapOf("COBBLESTONE" to 50L, "WHEAT" to 0L, "RAW_FISH" to 10L)
        val plain = mergeSacks(api)
        assertEquals(listOf("COBBLESTONE", "RAW_FISH", "WHEAT"), plain.lines.map { it.id })
        assertNull(plain.newestSoulAt)

        val merged = mergeSacks(api, mapOf("RAW_FISH" to (99L to 1_000L), "ENDER_PEARL" to (4L to 2_000L), "COBBLESTONE" to (50L to 3_000L)))
        assertEquals(listOf("RAW_FISH", "COBBLESTONE", "ENDER_PEARL", "WHEAT"), merged.lines.map { it.id })
        assertTrue(merged.lines.first { it.id == "RAW_FISH" }.fromSoul)
        assertTrue(merged.lines.first { it.id == "ENDER_PEARL" }.fromSoul)
        // an equal count is not "newer than Hypixel's"
        assertFalse(merged.lines.first { it.id == "COBBLESTONE" }.fromSoul)
        assertEquals(2_000L, merged.newestSoulAt)
    }

    @Test
    fun `ids without a catalog name get readable ones`() {
        assertEquals("Enchanted Raw Fish", humanizeItemId("ENCHANTED_RAW_FISH"))
        assertEquals("Raw Fish 3", humanizeItemId("RAW_FISH:3"))
    }

    @Test
    fun `accessory storage fields`() {
        val m = sam()
        assertEquals(12, m.bagUpgradesPurchased)
        assertTrue(m.riftPrismConsumed)
        assertEquals(12, m.abiphoneContacts)
        assertEquals(listOf("Strength" to 12, "Crit Damage" to 20, "Intelligence" to 8), m.activeTuning.map { it.stat to it.points })
        val joe = InvHarness.member("joe", "inventories-joe", "accessories-joe")
        assertEquals(0, joe.bagUpgradesPurchased)
        assertFalse(joe.riftPrismConsumed)
        assertNull(joe.abiphoneContacts)
        assertTrue(joe.activeTuning.isEmpty())
        assertNull(InvHarness.member("builder", "inventories-builder", "accessories-builder").bagUpgradesPurchased)
    }

    @Test
    fun `power names are title cased`() {
        assertEquals("Bubbly", powerDisplayName("bubbly"))
        assertEquals("Very Special", powerDisplayName("VERY SPECIAL"))
    }

    @Test
    fun `magical power adds up the way the wiki says`() {
        val m = sam()
        val items = ItemBlobs.parse(m.accessoryBagBlob!!)!!.filterNotNull()
        assertEquals(36, items.size)
        val b = MagicalPower.breakdown(items, prism = true, abiphoneContacts = 12)
        val byLabel = b.rows.associateBy { it.label }
        assertEquals(9 * 16, byLabel.getValue("Legendary").power)
        assertEquals(6 * 12, byLabel.getValue("Epic").power)
        assertEquals(22, byLabel.getValue("Mythic").power)
        assertEquals(12 * 8, byLabel.getValue("Rare").power)
        assertEquals(4 * 5, byLabel.getValue("Uncommon").power)
        assertEquals(4 * 3, byLabel.getValue("Common").power)
        assertEquals(16, byLabel.getValue("Hegemony Artifact").power)
        assertEquals(11, byLabel.getValue("Rift Prism").power)
        assertEquals(6, byLabel.getValue("Abiphone contacts").power)
        assertEquals(399, b.total)
        assertEquals(0, b.duplicates)
        assertEquals(9, byLabel.getValue("Legendary").count)
        assertEquals(16, byLabel.getValue("Legendary").each)
        assertNull(byLabel.getValue("Rift Prism").each)
    }

    @Test
    fun `an accessory with the same id counts once, at its best rarity`() {
        val items = ItemBlobs.parse(InvHarness.member("joe", "inventories-joe", "accessories-joe").accessoryBagBlob!!)!!.filterNotNull()
        assertEquals(5, items.size)
        val b = MagicalPower.breakdown(items, prism = false, abiphoneContacts = 0)
        assertEquals(1, b.duplicates)
        assertEquals(5 + 8 + 3 + 12, b.total)
        assertEquals(1, b.rows.first { it.label == "Uncommon" }.count)
        assertTrue(b.rows.none { it.label == "Common" && it.count > 2 })
    }
}
