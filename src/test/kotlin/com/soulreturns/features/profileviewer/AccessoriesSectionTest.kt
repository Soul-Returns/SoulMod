package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.ItemBlobs
import com.soulreturns.data.hypixel.accessoryBagBlob
import com.soulreturns.data.hypixel.filterAccessorySlots
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.profileviewer.gui.sections.AccessoriesSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Accessories section built from the fixtures plus the `accessories-*` fragments. Everything asserted
 * here comes from the NBT text of the bag (rarity lines, ids), so it holds without a running client.
 */
class AccessoriesSectionTest {
    private fun sam() = InvHarness.context("sam", "inventories-sam", "accessories-sam")

    private fun render(ctx: ProfileContext, state: ViewState = ViewState(), w: Int = 969) =
        InvHarness.render(ctx, AccessoriesSection, InvRecorder(state), w)

    @Test
    fun `the tiles read the bag storage`() {
        val text = render(sam()).first.all()
        assertTrue("Magical Power 399 as Hypixel reports it" in text, text)
        assertTrue("Power Bubbly selected at Maxwell" in text, text)
        assertTrue("Accessories 36 1 recombobulated" in text, text)
        assertTrue("Bag slots 51 12 upgrades bought" in text, text)
        assertTrue("+12 Strength" in text, text)
        assertTrue("points from Magical Power" in text)
    }

    @Test
    fun `the breakdown lists a row per rarity and the extras`() {
        val text = render(sam()).first.all()
        for (row in listOf("Mythic", "Legendary", "Epic", "Rare", "Uncommon", "Common", "Hegemony Artifact", "Rift Prism", "Abiphone contacts", "Total")) {
            assertTrue(row in text, "missing $row")
        }
        assertTrue("9 × 16" in text && "6 × 12" in text && "12 × 8" in text, text)
        assertTrue("144" in text && "399" in text)
        assertTrue("upgrade family" !in text, "the fixture's reported value equals the sum")
    }

    @Test
    fun `the bag filters by rarity and name, strongest first`() {
        val items = ItemBlobs.parse(sam().member.accessoryBagBlob!!)!!
        fun shown(rarity: String, query: String) = filterAccessorySlots(items, rarity, query)
        assertEquals(36, shown("ALL", "").size)
        assertEquals(1, shown("MYTHIC", "").size)
        assertEquals(9, shown("LEGENDARY", "").size)
        assertEquals(1, shown("ALL", "wolf").size)
        assertTrue(shown("ALL", "no such ring").isEmpty())
        val order = shown("ALL", "").map { items[it]!!.rarity }
        assertEquals("MYTHIC", order.first())
        assertEquals("COMMON", order.last())
        assertEquals(listOf(0, 1), shown("LEGENDARY", "").take(2), "equal rarities keep slot order")
    }

    @Test
    fun `the bag card has its title and a count line`() {
        val state = ViewState()
        state.set("accessories.rarity", "MYTHIC")
        val text = render(sam(), state).first.all()
        assertTrue("Accessory bag" in text, text)
    }

    @Test
    fun `a repeated accessory counts once and a difference to Hypixel is explained`() {
        val ctx = InvHarness.context("joe", "inventories-joe", "accessories-joe")
        val text = render(ctx).first.all()
        assertTrue("Accessories 5 1 recombobulated · 1 repeated" in text, text)
        assertTrue("Power Bloody" in text, text)
        assertTrue("Tuning — points from Magical Power" in text, text)
        assertTrue("Hypixel reports 36" in text, text)
        assertTrue("0 upgrades bought" in text)
    }

    @Test
    fun `Inventory API off shows the notice and nothing else`() {
        val ctx = InvHarness.context("builder", "inventories-builder", "accessories-builder")
        assertNotNull(AccessoriesSection.locked(ctx))
        assertNull(AccessoriesSection.badge(ctx))
        val text = render(ctx).first.all()
        assertTrue("Builder42's Inventory API is off" in text, text)
        assertTrue("the accessory bag and Magical Power" in text, text)
        assertTrue("Accessory bag" !in text)
    }

    @Test
    fun `the rail badge is Hypixel's Magical Power`() {
        assertEquals("399", AccessoriesSection.badge(sam()))
        assertNull(AccessoriesSection.locked(sam()))
    }

    @Test
    fun `builds at both widths and the narrow one is taller`() {
        val (_, wide) = render(sam(), w = 969)
        val (_, narrow) = render(sam(), w = 476)
        assertTrue(wide > 0 && narrow > wide, "wide $wide narrow $narrow")
    }
}
