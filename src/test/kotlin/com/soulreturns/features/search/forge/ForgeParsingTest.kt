package com.soulreturns.features.search.forge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lines as captured on Hypixel (`docs/research/forge-2026-11-16.md` §1, §4a), colors stripped. */
class ForgeParsingTest {
    @Test
    fun durations() {
        assertEquals(21, ForgeParsing.seconds("21s"))
        assertEquals(5 * 3600L + 36 * 60, ForgeParsing.seconds("5h 36m"))
        assertEquals(4 * 86400L + 21 * 3600 + 36 * 60, ForgeParsing.seconds("4d 21h 36m"))
        assertEquals(5 * 3600L + 35 * 60 + 57, ForgeParsing.seconds("5h 35m 57s"))
        assertNull(ForgeParsing.seconds("soon"))
    }

    @Test
    fun forgeSlots() {
        val idle = ForgeParsing.forgeSlot(null, "Slot #1", listOf("View and start forge processes", "using the materials that you have.", "", "Click to select a process!"))
        assertEquals(ForgeParsing.Idle, idle)
        val busy = ForgeParsing.forgeSlot("REFINED_DIAMOND", "Refined Diamond", listOf("Currently making: Refined Diamond", "Time Remaining: 5h 36m"))
        assertEquals(ForgeParsing.Busy("REFINED_DIAMOND", "Refined Diamond", 5 * 3600L + 36 * 60), busy)
        // A finished or locked slot was never captured: an item without the two lines is unknown, not guessed.
        assertEquals(ForgeParsing.Unknown, ForgeParsing.forgeSlot("GEMSTONE_CHAMBER", "Gemstone Chamber", listOf("Click to claim!")))
        assertEquals(ForgeParsing.Unknown, ForgeParsing.forgeSlot(null, "Slot #6", listOf("Requires HotM 6")))
    }

    @Test
    fun recipeEntries() {
        val lore = listOf(
            "Grants +X Mining Speed.", "", "EPIC DRILL", "", "Items Required", "Titanium Drill DR-X555",
            "\uE010 Flawless Ruby Gemstone", "Gemstone Mixture x16", "Mithril Plate x10", "", "Duration: 21s", "", "Click to select!",
        )
        val e = ForgeParsing.entry("TITANIUM_DRILL_4", "Titanium Drill DR-X655", lore) as ForgeParsing.Recipe
        assertEquals(listOf("Titanium Drill DR-X555", "Flawless Ruby Gemstone", "Gemstone Mixture", "Mithril Plate"), e.ingredients)
        assertEquals(21, e.shownSeconds)
        assertEquals(0, e.coins)
        val pet = ForgeParsing.entry("PET", "[Lvl 1] T-Rex", listOf("Items Required", "T-Rex", "300,000 Coins", "", "Duration: 2d 19h 12m"))
            as ForgeParsing.Recipe
        assertEquals(300_000, pet.coins)
        assertEquals(listOf("T-Rex"), pet.ingredients)
        val locked = ForgeParsing.entry(null, "Frigid Husk", listOf("Requires Glacite Collection V"))
        assertEquals(ForgeParsing.Locked("Frigid Husk", "Requires Glacite Collection V"), locked)
        assertNull(ForgeParsing.entry(null, "Go Back", listOf("To Select Process (Slot #1)")))
    }

    @Test
    fun titles() {
        val gear = ForgeParsing.pageTitle("(1/2) Gear")
        assertEquals(Triple("Gear", 1, 2), Triple(gear.category, gear.page, gear.pages))
        assertEquals("Refining", ForgeParsing.pageTitle("Refining").category)
        assertTrue(ForgeParsing.isPicker("Select Process (Slot #1)"))
    }

    @Test
    fun quickForgeReadsThePercentNotTheLevel() {
        val maxed = ForgeParsing.quickForge("minecraft:diamond", listOf("Level 20", "Decreases the time it takes to forge", "by 30%.", "ENABLED"))!!
        assertEquals(20 to 30.0, maxed.level to maxed.percent)
        val levelling = ForgeParsing.quickForge("minecraft:emerald", listOf("Level 1/20", "Decreases the time it takes to forge", "by 10.5%.",
            "=====[ UPGRADE ]=====", "Level 2/20", "Decreases the time it takes to forge", "by 11%.", "Cost", "33 Mithril Powder"))!!
        assertEquals(1 to 10.5, levelling.level to levelling.percent)
        // Strawberry's boosted level: the percent already includes it.
        assertEquals(11.0, ForgeParsing.quickForge("minecraft:emerald", listOf("Level 2/20", "by 11%."))!!.percent)
        assertEquals(0.0, ForgeParsing.quickForge("minecraft:coal", listOf("Level 1/20", "by 10.5%.", "Requires Pickobulus"))!!.percent)
    }

    @Test
    fun quickForgeFromARecipePage() {
        // Blueberry (30 %): Refined Titanium 12 h shows 8h 24m; short recipes are ignored (rounding).
        assertEquals(30.0, ForgeParsing.quickForgeFromPage(listOf(21L to 30L, (8 * 3600L + 24 * 60) to 12 * 3600L)))
        // Kiwi (no Quick Forge): durations equal the base.
        assertEquals(0.0, ForgeParsing.quickForgeFromPage(listOf(12 * 3600L to 12 * 3600L)))
        // 11 %: 12 h -> 10h 40m (whole minutes).
        assertEquals(11.0, ForgeParsing.quickForgeFromPage(listOf((10 * 3600L + 40 * 60) to 12 * 3600L)))
        assertNull(ForgeParsing.quickForgeFromPage(listOf(21L to 30L)))
    }
}
