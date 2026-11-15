package com.soulreturns.features.mining.commissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Strings from the Dwarven Mines capture of 2026-11-15 (`docs/research/commissions-2026-11-15.md`). */
class CommissionParsingTest {
    private val description = listOf(
        "§r§7Commissions are tasks given directly",
        "§r§7by the king which give bountiful",
        "§r§7rewards.",
    )
    private val rewards = listOf(
        "§r§9Rewards",
        "§r§7- §r§5+410 HOTM Exp",
        "§r§7- §r§2500 Mithril Powder",
        "§r§7- §r§3+7.5k Mining Exp",
        "§r§7- §r§b10 Bits",
    )
    private val progress = listOf("§r§9Progress", "§r§f§l§m                    §r§9 0%")

    @Test
    fun menuInProgress() {
        val lore = description + listOf(
            "§r§9Goblin Raid Slayer",
            "§r§7Slay §r§a20 §r§cGoblins §r§7during the §r§cGoblin",
            "§r§cRaid §r§7Event.",
        ) + rewards + progress
        assertEquals(Commission("Goblin Raid Slayer", "0%", false), CommissionParsing.parseMenuItem("§r§6Commission #1", lore))
    }

    @Test
    fun menuPartialPercent() {
        val lore = description + listOf("§r§9Royal Mines Mithril", "§r§7Mine §r§a250 §r§7Mithril Ore in §r§bRoyal Mines§r§7.") +
            rewards + listOf("§r§9Progress", "§r§a§l§m          §r§f§l§m          §r§9 47.5%")
        assertEquals(Commission("Royal Mines Mithril", "47.5%", false), CommissionParsing.parseMenuItem("§r§6Commission #3", lore))
    }

    @Test
    fun menuCompleted() {
        val lore = description + listOf("§r§9Upper Mines Titanium", "§r§7Mine §r§a10 §r§7Titanium Ore in §r§bUpper Mines§r§7.") +
            rewards + listOf("§r§a§lCOMPLETED", "§r§eClick to claim rewards!")
        assertEquals(
            Commission("Upper Mines Titanium", CommissionParsing.DONE, true),
            CommissionParsing.parseMenuItem("§r§6Commission #4", lore),
        )
    }

    @Test
    fun menuCompletedWithNewSuffix() {
        val lore = description + listOf("§r§9Royal Mines Mithril", "§r§7Mine §r§a250 §r§7Mithril Ore in §r§bRoyal Mines§r§7.") +
            rewards + listOf("§r§a§lCOMPLETED", "§r§eClick to claim rewards!")
        assertEquals(
            Commission("Royal Mines Mithril", CommissionParsing.DONE, true),
            CommissionParsing.parseMenuItem("§6Commission #4 §r§a§lNEW", lore),
        )
    }

    @Test
    fun otherMenuItemsAreNotCommissions() {
        assertNull(CommissionParsing.parseMenuItem("§r§eCommission Milestones", listOf("§r§7View milestone progress and rewards!")))
        assertNull(CommissionParsing.parseMenuItem("§r§aFilter", listOf("§r§a▶ §r§2Dwarven Mines", "§r§7  §r§5Crystal Hollows")))
        assertNull(CommissionParsing.parseMenuItem("§r§cClose", emptyList()))
    }

    @Test
    fun menuTitle() {
        assertEquals(true, CommissionParsing.isMenuTitle("§rCommissions"))
        assertEquals(false, CommissionParsing.isMenuTitle("Commission Milestones"))
    }

    @Test
    fun tabWidget() {
        val lines = listOf(
            "§r 6) §r§9Gemstone Mixture§r§7: §r§b2h",
            "§r 7) §r§cLOCKED",
            "",
            "§r§9§lCommissions:",
            "§r §r§fGoblin Raid Slayer: §r§c0%",
            "§r §r§fRaffle: §r§c0%",
            "§r §r§fRoyal Mines Mithril: §r§c0%",
            "§r §r§fUpper Mines Titanium: §r§aDONE",
            "",
            "§r§9§lPowders:",
            "§r Mithril: §r§266,017",
            "§r               §r§3§lInfo",
        )
        assertEquals(
            listOf(
                Commission("Goblin Raid Slayer", "0%", false),
                Commission("Raffle", "0%", false),
                Commission("Royal Mines Mithril", "0%", false),
                Commission("Upper Mines Titanium", "DONE", true),
            ),
            CommissionParsing.parseTab(lines),
        )
    }

    @Test
    fun tabWidgetEndsAtNextHeaderWithoutBlank() {
        val lines = listOf("§r§9§lCommissions:", "§r §r§fRaffle: §r§e50%", "§r§9§lPowders:", "§r Mithril: §r§266,017")
        assertEquals(listOf(Commission("Raffle", "50%", false)), CommissionParsing.parseTab(lines))
    }

    @Test
    fun tabWithoutWidget() {
        assertNull(CommissionParsing.parseTab(listOf("§r§9§lPowders:", "§r Mithril: §r§266,017")))
    }
}
