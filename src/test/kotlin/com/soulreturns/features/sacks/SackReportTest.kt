package com.soulreturns.features.sacks

import com.soulreturns.features.sacks.SackReport.MenuSlot
import com.soulreturns.features.sacks.SackReport.PageNav
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SackReportTest {
    private fun row(id: String, stored: String = "Stored: 12/22.2k") = MenuSlot(id, "§fItem $id", listOf("§7Mining Sack", "", "§7$stored", "§eRight-Click for stack!"))
    private val border = MenuSlot(null, " ", emptyList())
    private val button = MenuSlot("GO_BACK", "§aGo Back", listOf("§7To Sack of Sacks"))

    @Test
    fun `only rows with a Stored line count, borders and buttons do not`() {
        val ids = SackReport.idsOf(listOf(row("COAL"), border, row("IRON_INGOT", "Stored: 0/22.2k"), button), gemstone = false)
        assertEquals(setOf("COAL", "IRON_INGOT"), ids)
    }

    @Test
    fun `a gemstone row lists one id per tier line`() {
        // Live row (2026-11-23): the rough gem's id, tier lines and a Stored line.
        val jade = MenuSlot("ROUGH_JADE_GEM", "§aJade Gemstones", listOf("§8Gemstones", "§7Rough: §a15,344 §8(15,344)", "§7Flawed: §a0 §8(0)", "§7Fine: §a0 §8(0)", "§7Stored: §a15,344§7/19.7M"))
        assertEquals(setOf("ROUGH_JADE_GEM", "FLAWED_JADE_GEM", "FINE_JADE_GEM"), SackReport.idsOf(jade, gemstone = true))
        assertEquals(setOf("ROUGH_JADE_GEM"), SackReport.idsOf(jade, gemstone = false), "tier lines only count in the Gemstone Sack")
        val direct = MenuSlot("ROUGH_AMBER_GEM", "§6Rough Amber Gemstone", listOf("§7Stored: 5/22.2k"))
        assertEquals(setOf("ROUGH_AMBER_GEM"), SackReport.idsOf(direct, gemstone = true), "a row without tier lines is its own id")
        assertTrue(SackReport.isGemstone("Gemstones Sack"))
        assertTrue(SackReport.isGemstone("Gemstone Sack"))
        assertFalse(SackReport.isGemstone("Mining Sack"))
    }

    @Test
    fun `page arrows are read by name`() {
        val nav = SackReport.pageNav(listOf(row("COAL"), MenuSlot(null, "§aNext Page", emptyList())))
        assertTrue(nav.hasNext)
        assertFalse(nav.hasPrev)
        val both = SackReport.pageNav(listOf(MenuSlot(null, "§aPrevious Page", emptyList()), MenuSlot(null, "§aNext Page", emptyList())))
        assertTrue(both.hasPrev && both.hasNext)
        val none = SackReport.pageNav(listOf(row("COAL")))
        assertFalse(none.hasPrev || none.hasNext)
    }

    @Test
    fun `the report decision compares against the catalog set`() {
        assertTrue(SackReport.differs(null, setOf("COAL")), "no catalog entry yet: report")
        assertTrue(SackReport.differs(setOf("COAL"), setOf("COAL", "IRON_INGOT")))
        assertTrue(SackReport.differs(setOf("COAL", "IRON_INGOT"), setOf("COAL")))
        assertFalse(SackReport.differs(setOf("COAL", "IRON_INGOT"), setOf("IRON_INGOT", "COAL")), "same set, any order: nothing sent")
        assertFalse(SackReport.differs(null, emptySet()), "nothing read, nothing to say")
    }

    @Test
    fun `a single page is complete at once`() {
        val pages = SackReport.Pages()
        assertEquals(setOf("A", "B"), pages.add("Mining Sack", setOf("A", "B"), PageNav(false, false), 0))
    }

    @Test
    fun `a sack with several pages completes after its last page and keeps every page's items`() {
        val pages = SackReport.Pages()
        assertNull(pages.add("Mining Sack", setOf("A", "B"), PageNav(false, true), 0))
        assertNull(pages.add("Mining Sack", setOf("C"), PageNav(true, true), 1_000))
        assertEquals(setOf("A", "B", "C", "D"), pages.add("Mining Sack", setOf("D"), PageNav(true, false), 2_000))
    }

    @Test
    fun `another sack or a long pause starts the visit over`() {
        val pages = SackReport.Pages()
        assertNull(pages.add("Mining Sack", setOf("C"), PageNav(true, false), 0), "the first page was never shown")
        assertNull(pages.add("Combat Sack", setOf("X"), PageNav(false, true), 1_000))
        assertNull(pages.add("Mining Sack", setOf("D"), PageNav(true, false), 2_000), "another sack in between: the Mining visit starts over")
        val later = 2_000 + SackReport.Pages.GAP_MS + 1
        assertNull(pages.add("Mining Sack", setOf("A"), PageNav(false, true), later), "a long pause ended the visit, D is forgotten")
        assertEquals(setOf("A", "B"), pages.add("Mining Sack", setOf("B"), PageNav(true, false), later + 500))
    }
}
