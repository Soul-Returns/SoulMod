package com.soulreturns.features.experiments

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rare set from the recorded RNG Meter pages (research §7) and card matching against real Superpairs cards. */
class RareRewardsTest {
    private fun pages(): Pair<RareRewards.Table, List<RareRewards.Entry>> {
        var t = RareRewards.Table()
        val all = ArrayList<RareRewards.Entry>()
        val flow = ExpFlow(onRngPage = { page, pages, entries ->
            t = RareRewards.withPage(t, page, pages, entries, 1)
            all += entries
        })
        for (e in ExpReplay.events("rng-meter")) {
            if (e.k == "open") flow.open(e.o["cid"]!!.jsonPrimitive.int, ExpReplay.title(e.o), ExpReplay.rows(e.o), 0)
            if (e.k == "content") flow.contents(e.o["cid"]!!.jsonPrimitive.int, ExpReplay.contents(e.o, 54), 0)
        }
        return t to all
    }

    /** A captured Superpairs card (container 36's final board, before the end lore). */
    private fun card(name: String): ExpItem {
        for (e in ExpReplay.events("exp-36")) {
            if (e.k != "slot") continue
            val it = ExpReplay.item(e.o["item"]) ?: continue
            if ((it.plain.contains(name) || it.plainLore.any { l -> l == name }) && it.plainLore.none { l -> l == SuperpairsModel.PROCEED }) return it
        }
        error("no card $name")
    }

    @Test
    fun cutIsTheLargestOddsGap() {
        assertEquals(0.0263, RareRewards.cut(listOf(5.3191, 5.3191, 1.0638, 0.0263, 0.0263, 0.004)))
        assertNull(RareRewards.cut(listOf(5.3191, 5.3191)))
        // The rule follows the menu's numbers: a new band moves the cut.
        assertEquals(1.0, RareRewards.cut(listOf(50.0, 1.0, 0.5)))
    }

    @Test
    fun recordedMeterGivesThirtyNineRareEntries() {
        val (t, all) = pages()
        assertEquals(3, t.pages)
        assertTrue(RareRewards.complete(t))
        assertEquals(60, all.size)
        val c = RareRewards.cut(all.map { it.odds })
        assertEquals(0.0263, c)
        assertEquals(39, all.count { it.odds <= c!! })
        // The selected drop's struck-through base odds are read, not its boosted current odds.
        val looting = all.single { it.name == "Looting V" }
        assertEquals(0.0263, looting.odds)
        assertTrue(looting.selected)
        assertEquals(1, all.count { it.selected })
        assertNotNull(RareRewards.rareKeys(t))
    }

    @Test
    fun incompleteMeterHasNoRareSet() {
        val (t, _) = pages()
        val partial = t.copy(entries = t.entries - "2")
        assertNull(RareRewards.rareKeys(partial))
    }

    @Test
    fun cardsMatchByKeyNeverByName() {
        val (t, _) = pages()
        val keys = RareRewards.rareKeys(t)!!
        val growth6 = card("Growth VI")
        val guardianEpic = card("Guardian")
        val titanic = card("Titanic Experience Bottle")
        // 5.3 % book, a 1.06 % bottle, and an EPIC Guardian (the meter's rare one is LEGENDARY): none warn.
        assertFalse(RareRewards.isRare(growth6, keys))
        assertFalse(RareRewards.isRare(titanic, keys))
        assertEquals("EPIC", guardianEpic.petTier)
        assertFalse(RareRewards.isRare(guardianEpic, keys))
        // The same card one level up (as the meter lists Growth VII: the same leather chestplate) is in the rare set.
        val growth7 = growth6.copy(lore = growth6.lore.map { it.replace("Growth VI", "Growth VII") })
        assertTrue(RareRewards.isRare(growth7, keys))
        assertTrue(RareRewards.isRare(guardianEpic.copy(petTier = "LEGENDARY"), keys))
        // Name alone is never a match: a VII book drawn as another material is not the meter's entry.
        assertFalse(RareRewards.isRare(growth7.copy(id = "enchanted_book"), keys))
    }

    @Test
    fun hypixelsOwnLabelWarnsWithoutTheMeter() {
        val book = card("Growth VI").let { it.copy(lore = listOf("§d§kXX§5 ULTRA-RARE BOOK! §d§kXX") + it.lore.drop(1)) }
        assertTrue(RareRewards.isRare(book, null))
        assertFalse(RareRewards.isRare(card("Growth VI"), null))
    }

    @Test
    fun selectedDropWarnsWhateverItsOdds() {
        val growth6 = card("Growth VI")
        val (t, _) = pages()
        val keys = RareRewards.rareKeys(t)!!
        val entry = RareRewards.Entry("Growth VI", 5.3191, RareRewards.entryKeys(ExpItem("leather_chestplate", 1, "§9Growth VI", dye = growth6.dye)), selected = true)
        val sel = RareRewards.withSelection(null, 1, listOf(entry), 5)
        assertNotNull(sel)
        assertTrue(RareRewards.isRare(growth6, keys, sel.keys))
        // Unselected, the same 5.3 % book does not warn.
        val cleared = RareRewards.withSelection(sel, 1, listOf(entry.copy(selected = false)), 6)
        assertNull(cleared)
        assertFalse(RareRewards.isRare(growth6, keys, cleared?.keys))
        // Another page without a selection keeps it.
        assertEquals(sel, RareRewards.withSelection(sel, 2, emptyList(), 7))
    }

    @Test
    fun superpairsModelWarnsOncePerRareCard() {
        val growth6 = card("Growth VI")
        val flow = ExpFlow(rare = { RareRewards.isRare(it, emptySet(), RareRewards.entryKeys(ExpItem("leather_chestplate", 1, "Growth VI", dye = growth6.dye))) })
        var warned = 0
        ExpReplay.feed(flow, ExpReplay.events("exp-36")) {
            val sp = flow.menu?.model as? SuperpairsModel ?: return@feed
            sp.settle(it.t)
            warned = sp.rareCount
            if (sp.rareSlots.isNotEmpty()) assertTrue(sp.rareSlots.all { s -> sp.mem[s]?.item?.plainLore?.contains("Growth VI") == true })
        }
        assertEquals(1, warned)
    }
}
