package com.soulreturns.features.pets

import com.soulreturns.config.PetHudConfig
import com.soulreturns.data.skyblock.SkyblockRarity.LEGENDARY
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The card / line metrics of `mocks/pet-hud/mock.js` (PET: PAD 5, GAP 3, name line 11, small line 9,
 * bar 4, BAR_MIN_W 90, NAME_LV_GAP 14, ICON 16 + 5, row gap 8; line: PAD 3 × 5, icon 10 + 4, bar 40).
 * Text widths use a stand-in (half the size per character), so only the layout math is under test.
 */
class PetHudModelTest {
    private val m = PetHudModel.Measure { runs, size -> (runs.sumOf { it.text.length } * size / 2f).roundToInt() }

    private val griffin = PetView(
        "Griffin", LEGENDARY, false, 87, 100, false, 196_209.0, 726_700.0, null, 9_502_339.0,
        PetHeld("Antique Remedies", '5'), null, 1_200_000.0,
    )

    @Test
    fun cardWithEveryDefaultElement() {
        val card = PetHudModel.card(griffin, PetTracker.ACTIVE, PetHudConfig())
        assertEquals(listOf("196.2k / 726.7k"), card.xpRow!!.left.map { it.text })
        assertEquals(listOf("27.0%"), card.xpRow!!.right.map { it.text })
        assertEquals(listOf("held", "rate"), card.rows.map { it.id })
        assertEquals("+1.2M XP/h · Lvl 88 in 27m", card.rows[1].left.joinToString("") { it.text })
        val box = PetHudModel.measureCard(card, m)
        // Column: the rate row (26 chars × 4 = 104) beats the 90 px bar minimum.
        assertEquals(104, box.columnW)
        assertEquals(5 + 16 + 5 + 104 + 5, box.width)
        // 5 + name 11 + (3 + bar 4) + (3 + 9) xp + (3 + 9) held + (3 + 9) rate + 5.
        assertEquals(64, box.height)
        // The icon centers on name + bar + xp row: round((11 + 7 + 12 - 16) / 2) = 7.
        assertEquals(7, box.iconTop)
    }

    @Test
    fun barMinimumWidth() {
        val c = PetHudConfig(heldItem = false, xpPerHour = false, timeToLevel = false)
        val box = PetHudModel.measureCard(PetHudModel.card(griffin, PetTracker.ACTIVE, c), m)
        assertEquals(90, box.columnW)
        assertEquals(121, box.width)
        assertEquals(40, box.height)
        // Without the bar the minimum goes too: the xp row (60 + 8 + 20) is the widest.
        val noBar = PetHudModel.measureCard(PetHudModel.card(griffin, PetTracker.ACTIVE, c.copy(xpBar = false)), m)
        assertEquals(88, noBar.columnW)
    }

    @Test
    fun noPetCard() {
        val card = PetHudModel.card(null, PetTracker.NONE, PetHudConfig())
        assertEquals("No pet", card.emptyTitle!!.text)
        assertEquals("Summon one in /pets", card.emptyHint!!.text)
        val box = PetHudModel.measureCard(card, m)
        assertEquals(10 + 21 + 76, box.width)
        assertEquals(5 + 23 + 5, box.height)
        assertEquals(4, box.iconTop)
        assertEquals("Pet unknown", PetHudModel.card(null, PetTracker.UNKNOWN, PetHudConfig()).emptyTitle!!.text)
    }

    @Test
    fun maxLevelCard() {
        val gd = PetView("Golden Dragon", LEGENDARY, true, 200, 200, true, null, null, 155_191_949.0, null, PetHeld("Hephaestus Relic", '6'), null, null)
        val card = PetHudModel.card(gd, PetTracker.ACTIVE, PetHudConfig())
        assertEquals("MAX LEVEL", card.xpRow!!.left.single().text)
        assertEquals("+155.2M XP", card.xpRow!!.right.single().text)
        assertEquals(listOf("Golden Dragon", " ✦"), card.name!!.map { it.text })
        // No XP/h at max, ever.
        assertEquals(listOf("held"), card.rows.map { it.id })
        assertEquals("+155,191,949 XP", PetHudModel.card(gd, PetTracker.ACTIVE, PetHudConfig(numberFormat = PetHudFormats.FULL)).xpRow!!.right.single().text)
        assertNull(PetHudModel.card(gd, PetTracker.ACTIVE, PetHudConfig(percent = false, xpNumbers = false, overflow = false)).xpRow)
    }

    @Test
    fun eggAndProgressToMax() {
        val egg = PetView("Golden Dragon", LEGENDARY, false, 64, 200, false, 100.0, 1_000.0, null, 5_000_000.0, null, null, null)
        val card = PetHudModel.card(egg, PetTracker.ACTIVE, PetHudConfig(progressToMax = true))
        assertEquals(listOf("egg", "tomax"), card.rows.map { it.id })
        // 5,000,000 / 210,255,385 = 2.37… → floored 2.3 %.
        assertEquals("To Lvl 200 2.3%", card.rows[1].left.joinToString("") { it.text })
    }

    @Test
    fun compactLine() {
        val line = PetHudModel.line(griffin, PetTracker.ACTIVE, PetHudConfig(style = PetHudFormats.COMPACT))
        assertEquals("27.0% · 27m · +1.2M/h", line.tail.joinToString("") { it.text })
        val (w, h) = PetHudModel.measureLine(line, m)
        assertEquals(17, h)
        // 10 pad + (10 + 4) icon + (24 + 3) level + 35 name + (5 + 40) bar + (4 + 84) tail.
        assertEquals(219, w)
        val none = PetHudModel.measureLine(PetHudModel.line(null, PetTracker.NONE, PetHudConfig()), m)
        assertEquals(10 + 14 + 30 to 17, none)
    }
}
