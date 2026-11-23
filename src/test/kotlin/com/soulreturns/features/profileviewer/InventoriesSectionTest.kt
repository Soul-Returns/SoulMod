package com.soulreturns.features.profileviewer

import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.profileviewer.gui.sections.InventoriesSection
import com.soulreturns.features.profileviewer.gui.sections.InventoryLookups
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Inventories section built from the fixtures plus the `inventories-*` fragments. Item stacks cannot be
 * decoded in a headless JVM (the datafixer needs a running client), so these assert on what does not depend
 * on them: the container list, page titles, search counts (read from the NBT text), sacks and the API-off states.
 */
class InventoriesSectionTest {
    private val price = InventoryLookups.price
    private val itemName = InventoryLookups.itemName
    private val sackCatalog = InventoryLookups.sackCatalog

    @BeforeTest
    fun seams() {
        InventoryLookups.price = { 100L }
        InventoryLookups.itemName = { null }
        InventoryLookups.sackCatalog = { com.soulreturns.data.sacks.SackCatalogIndex.EMPTY }
    }

    @AfterTest
    fun restore() {
        InventoryLookups.price = price
        InventoryLookups.itemName = itemName
        InventoryLookups.sackCatalog = sackCatalog
    }

    private fun sam() = InvHarness.context("sam", "inventories-sam", "accessories-sam")

    private fun render(ctx: com.soulreturns.features.profileviewer.gui.ProfileContext, state: ViewState = ViewState(), w: Int = 969) =
        InvHarness.render(ctx, InventoriesSection, InvRecorder(state), w)

    @Test
    fun `the container list and every page title`() {
        val text = render(sam()).first.all()
        for (label in listOf("Everything", "Inventory", "Ender Chest", "Backpacks", "Personal Vault", "Potion Bag", "Fishing Bag", "Quiver", "Sacks")) {
            assertTrue(label in text, "missing nav entry $label in $text")
        }
        for (title in listOf("Ender Chest 1", "Ender Chest 2", "Ender Chest 3", "Backpack 1", "Backpack 2", "Backpack 4", "Personal Vault", "Potion Bag", "Fishing Bag", "Quiver")) {
            assertTrue(title in text, "missing page $title")
        }
        assertTrue("Backpack 3" !in text, "backpack slot 3 is empty in the fixture")
        assertTrue("API is off" !in text)
    }

    @Test
    fun `a page shows its worth from the price seam`() {
        // every priced stack is worth 100 coins per item, so a page's worth is above 10,000 and abbreviated
        val text = render(sam()).first.all()
        assertTrue(Regex("""\d+(\.\d)?k""").containsMatchIn(text), text)
    }

    @Test
    fun `search keeps only pages with a match and counts them`() {
        val state = ViewState()
        state.field("inventories.q").set("hyperion")
        val text = render(sam(), state).first.all()
        assertTrue("1 match" in text, text)
        assertTrue("1 matches" !in text)
        assertTrue("Ender Chest 2" !in text, "a page without a match is not listed")
        assertTrue("Backpack 1" !in text)
    }

    @Test
    fun `search reads lore as well as names`() {
        val state = ViewState()
        state.field("inventories.q").set("damage: +310")
        val text = render(sam(), state).first.all()
        assertTrue("Ender Chest 1" in text && "1 match" in text, text)
    }

    @Test
    fun `search with no hit says so`() {
        val state = ViewState()
        state.field("inventories.q").set("zzzz")
        val text = render(sam(), state).first.all()
        assertTrue("Nothing matches \"zzzz\"" in text, text)
        assertTrue("Searched names and lore of every item." in text)
    }

    @Test
    fun `search counts sack rows too`() {
        val state = ViewState()
        state.set("inventories.container", "sacks")
        state.set("inventories.sackSel", "OTHER")
        state.field("inventories.q").set("cobble")
        val text = render(sam(), state).first.all()
        assertTrue("Cobblestone" in text && "Mithril Ore" !in text, text)
    }

    @Test
    fun `sacks list names, counts and the hide-empty switch`() {
        val state = ViewState()
        state.set("inventories.container", "sacks")
        state.set("inventories.sackSel", "OTHER")
        val all = render(sam(), state).first.all()
        assertTrue("Other items" in all && "Cobblestone" in all && "55,020" in all, all)
        assertTrue("Wheat" in all, "empty sacks rows are dimmed, not hidden")
        assertTrue("Hide empty" in all)

        state.set("inventories.hideEmpty", "1")
        val hidden = render(sam(), state).first.all()
        assertTrue("Wheat" !in hidden, hidden)
        assertTrue("Cobblestone" in hidden)
    }

    @Test
    fun `Inventory API off shows the notice, sacks and the locked list`() {
        val ctx = InvHarness.context("builder", "inventories-builder", "accessories-builder")
        assertNotNull(InventoriesSection.locked(ctx))
        val state = ViewState()
        state.set("inventories.sackSel", "OTHER")
        val text = render(ctx, state).first.all()
        assertTrue("Builder42's Inventory API is off" in text, text)
        assertTrue("Sacks still show: Hypixel sends sack counts even with the Inventory API off." in text, text)
        assertTrue("Other items" in text && "Cobblestone" in text)
        assertTrue("Ender Chest 1" !in text)
        assertTrue("Personal Vault" in text, "locked entries stay in the list")
    }

    @Test
    fun `a container that is not there falls back to everything`() {
        val state = ViewState()
        state.set("inventories.container", "vault")
        val text = render(InvHarness.context("joe", "inventories-joe", "accessories-joe"), state).first.all()
        assertTrue("Ender Chest 1" in text, text)
        assertTrue("Fishing Bag" in text)
    }

    @Test
    fun `the section is only partly locked without the API`() {
        assertNull(InventoriesSection.locked(sam()))
        assertEquals("Inventory API off: only sacks", InventoriesSection.locked(InvHarness.context("builder", "inventories-builder")))
    }

    @Test
    fun `builds at both widths and the narrow one is taller`() {
        val (_, wide) = render(sam(), w = 969)
        val (_, narrow) = render(sam(), w = 476)
        assertTrue(wide > 0 && narrow > wide, "wide $wide narrow $narrow")
        for (fixture in listOf("joe", "builder")) {
            val ctx = InvHarness.context(fixture, "inventories-$fixture", "accessories-$fixture")
            for (w in listOf(969, 476)) assertTrue(render(ctx, w = w).second > 0, "$fixture at $w")
        }
    }
}
