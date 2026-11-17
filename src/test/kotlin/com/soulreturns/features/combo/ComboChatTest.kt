package com.soulreturns.features.combo

import com.soulreturns.util.MessageDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComboChatTest {
    // Captured on the main network, 2026-11-17 (SkyBlock 0.27.3).
    private val raw =
        listOf(
            "§r§a§l+5 Kill Combo §r§3+4☯ Combat Wisdom",
            "§r§a§l+10 Kill Combo §r§8+§r§68 §r§7coins per kill",
            "§r§9§l+15 Kill Combo §r§3+4☯ Combat Wisdom",
            "§r§5§l+20 Kill Combo §r§8+§r§68 §r§7coins per kill",
            "§r§5§l+25 Kill Combo §r§3+4☯ Combat Wisdom",
        )
    private val expiry = "§r§cYour Kill Combo has expired! You reached a 25 Kill Combo!"

    private fun parse(line: String) = ComboChat.parse(MessageDetector.stripColorCodes(line).trim())

    private val wisdom = ComboBonus.Kind.COMBAT_WISDOM
    private val coins = ComboBonus.Kind.COINS_PER_KILL

    @Test
    fun eachMilestoneCarriesItsBonusKindAndAmount() {
        val expected =
            listOf(
                ComboChat.Milestone(5, listOf(ComboBonus(wisdom, 4.0))),
                ComboChat.Milestone(10, listOf(ComboBonus(coins, 8.0))),
                ComboChat.Milestone(15, listOf(ComboBonus(wisdom, 4.0))),
                ComboChat.Milestone(20, listOf(ComboBonus(coins, 8.0))),
                ComboChat.Milestone(25, listOf(ComboBonus(wisdom, 4.0))),
            )
        assertEquals(expected, raw.map { parse(it) })
    }

    @Test
    fun expiryLineIsRecognised() {
        assertEquals(ComboChat.Expired(25), parse(expiry))
    }

    @Test
    fun bonusesStackOverTheCombo() {
        val run = ComboRun()
        for (line in raw) {
            val m = parse(line) as ComboChat.Milestone
            run.onMilestone(m.count, m.bonuses)
        }
        assertEquals(12.0, run.combatWisdom)
        assertEquals(16.0, run.coinsPerKill)
    }

    @Test
    fun expiryResetsTheTotals() {
        val run = ComboRun()
        run.onMilestone(5, listOf(ComboBonus(wisdom, 4.0)))
        run.onMilestone(10, listOf(ComboBonus(coins, 8.0)))
        run.reset()
        assertEquals(0.0, run.combatWisdom)
        assertEquals(0.0, run.coinsPerKill)
        run.onMilestone(15, listOf(ComboBonus(wisdom, 4.0))) // a combo may resume past 5 on the next line we see
        assertEquals(4.0, run.combatWisdom)
    }

    @Test
    fun aLowerMilestoneStartsANewComboEvenWithoutAnExpiryLine() {
        val run = ComboRun()
        run.onMilestone(5, listOf(ComboBonus(wisdom, 4.0)))
        run.onMilestone(10, listOf(ComboBonus(coins, 8.0)))
        run.onMilestone(5, listOf(ComboBonus(wisdom, 6.0)))
        assertEquals(6.0, run.combatWisdom)
        assertEquals(0.0, run.coinsPerKill)
    }

    @Test
    fun unknownBonusTextStillAdvancesTheCombo() {
        for (line in listOf("§a§l+30 Kill Combo §r§b+9✦ Magic Find", "+35 Kill Combo +7 Wolf Power", "+30 Kill Combo", "+30 Kill Combo +x☯ Combat Wisdom")) {
            val m = parse(line) as ComboChat.Milestone
            assertEquals(if (line.startsWith("+35")) 35 else 30, m.count, line)
            assertTrue(m.bonuses.isEmpty(), line)
        }
        val run = ComboRun()
        run.onMilestone(5, listOf(ComboBonus(wisdom, 4.0)))
        run.onMilestone(10, emptyList())
        run.onMilestone(15, listOf(ComboBonus(wisdom, 4.0)))
        assertEquals(8.0, run.combatWisdom)
    }

    @Test
    fun bonusesAreReadWhateverTheirSizeOrOrder() {
        assertEquals(
            ComboChat.Milestone(25, listOf(ComboBonus(wisdom, 12.5))),
            ComboChat.parse("+25 Kill Combo +12.5☯ Combat Wisdom"),
        )
        assertEquals(
            ComboChat.Milestone(10, listOf(ComboBonus(coins, 1200.0))),
            ComboChat.parse("+10 Kill Combo +1,200 coins per kill"),
        )
        assertEquals(
            ComboChat.Milestone(10, listOf(ComboBonus(wisdom, 3.0), ComboBonus(coins, 8.0))),
            ComboChat.parse("+10 Kill Combo +3 Combat Wisdom +8 coins per kill"),
        )
    }

    @Test
    fun otherChatIsNotACombo() {
        assertNull(ComboChat.parse("You reached a 30 Kill Combo"))
        assertNull(ComboChat.parse("[MVP+] Someone: +5 Kill Combo"))
        assertTrue(ComboChat.isMilestone("+5 Kill Combo +4☯ Combat Wisdom"))
        assertTrue(ComboChat.isExpiry("Your Kill Combo has expired! You reached a 5 Kill Combo!"))
    }

    @Test
    fun durationsComeFromThePetLore() {
        val lore =
            listOf(
                "Kill Combo", "Gain buffs for combo kills. Effects", "stack as you increase your combo.",
                "5 Combo (lasts 9.5s)", "+4☯ Combat Wisdom", "10 Combo (lasts 7.5s)", "+8 coins per kill",
                "15 Combo (lasts 5.5s)", "+4☯ Combat Wisdom", "20 Combo (lasts 4.5s)", "+8 coins per kill",
                "25 Combo (lasts 3.7s)", "+4☯ Combat Wisdom", "30 Combo (lasts 2.7s)", "+8 coins per kill",
                "This pet's perks are active even", "when the pet is not summoned!",
            )
        assertEquals(mapOf(5 to 9.5f, 10 to 7.5f, 15 to 5.5f, 20 to 4.5f, 25 to 3.7f, 30 to 2.7f), ComboDurations.parse(lore))
        assertEquals(emptyMap(), ComboDurations.parse(listOf("Held Item: none", "0 Combo (lasts 0s)")))
    }

    @Test
    fun milestoneTierClampsToTheTable() {
        assertEquals(5, ComboData.milestoneOf(7))
        assertEquals(30, ComboData.milestoneOf(31))
        assertEquals(30, ComboData.milestoneOf(120))
        assertEquals(ComboData.GOLD, ComboData.colorFor(44))
    }
}
