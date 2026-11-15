package com.soulreturns.features.pets

import com.soulreturns.data.skyblock.SkyblockRarity.COMMON
import com.soulreturns.data.skyblock.SkyblockRarity.LEGENDARY
import com.soulreturns.data.skyblock.SkyblockRarity.RARE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Expected values from the mock kit (`tools/mockkit/data/skyblock.js`, run in node) and research §5. */
class PetLevelsTest {
    @Test
    fun tableTotals() {
        assertEquals(25_353_230, PetLevels.levelXp(100, LEGENDARY, "Mole"))
        assertEquals(5_624_785, PetLevels.levelXp(100, COMMON, "Rabbit"))
        assertEquals(210_255_385, PetLevels.levelXp(200, LEGENDARY, "Golden Dragon"))
    }

    @Test
    fun needPerLevel() {
        assertEquals(726_700, PetLevels.need(87, LEGENDARY, "Griffin"))
        assertEquals(272_700, PetLevels.need(74, LEGENDARY, "Scatha"))
        assertEquals(17_800, PetLevels.need(37, LEGENDARY, "Rock"))
        assertEquals(48_400, PetLevels.need(58, RARE, "Silverfish"))
        assertEquals(0, PetLevels.need(100, LEGENDARY, "Mole"))
    }

    @Test
    fun goldenDragonRunsTo200() {
        assertEquals(200, PetLevels.maxLevel("Golden Dragon"))
        assertEquals(200, PetLevels.maxLevel("GOLDEN_DRAGON"))
        assertEquals(100, PetLevels.maxLevel("Ender Dragon"))
        assertEquals(0, PetLevels.need(100, LEGENDARY, "Golden Dragon"))
        assertEquals(5_555, PetLevels.need(101, LEGENDARY, "Golden Dragon"))
        assertEquals(1_886_700, PetLevels.need(102, LEGENDARY, "Golden Dragon"))
        assertEquals(0, PetLevels.need(200, LEGENDARY, "Golden Dragon"))
        assertTrue(PetLevels.isEgg("Golden Dragon", 99))
        assertFalse(PetLevels.isEgg("Golden Dragon", 100))
        assertFalse(PetLevels.isEgg("Ender Dragon", 50))
        // The mock's Golden Dragon start: Lvl 102 at 41 %.
        val p = PetLevels.levelOf(26_132_332.0, LEGENDARY, "Golden Dragon")
        assertEquals(102, p.level)
        assertEquals(773_547.0, p.into)
        assertEquals(1_886_700, p.need)
    }

    @Test
    fun levelOfMaxedGivesOverflow() {
        // Captured petInfo.exp values (research §5 cross-checks).
        val mole = PetLevels.levelOf(25_799_754.0, LEGENDARY, "Mole")
        assertTrue(mole.maxed)
        assertEquals(100, mole.level)
        assertEquals(446_524.0, mole.overflow)
        assertEquals(9_726_032.0, PetLevels.levelOf(35_079_262.0, LEGENDARY, "Ender Dragon").overflow)
    }

    @Test
    fun levelOfMidLevel() {
        // The mock's Griffin: Lvl 87, 27 % → 196,209 / 726,700, total 9,502,339.
        val p = PetLevels.levelOf(9_502_339.0, LEGENDARY, "Griffin")
        assertEquals(87, p.level)
        assertEquals(196_209.0, p.into)
        assertEquals(726_700, p.need)
        assertFalse(p.maxed)
    }

    @Test
    fun formats() {
        assertEquals("196.2k", PetFormat.short(196_209.0))
        assertEquals("1.9M", PetFormat.short(1_886_700.0))
        assertEquals("2M", PetFormat.short(2_000_000.0))
        assertEquals("1.2M", PetFormat.short(1_200_000.0))
        assertEquals("999", PetFormat.short(999.0))
        assertEquals("460.7k", PetFormat.short(460_686.3))
        assertEquals("196,209", PetFormat.full(196_209.0))
        assertEquals("365,447,410", PetFormat.full(365_447_410.0))
        assertEquals("27.0%", PetFormat.percent(196_209.0 / 726_700.0))
        assertEquals("18.9%", PetFormat.percent(0.189))
        assertEquals("99.9%", PetFormat.percent(0.9999))
        assertEquals("45s", PetFormat.duration(45 / 3600.0))
        assertEquals("26m", PetFormat.duration(26 / 60.0))
        assertEquals("1h 17m", PetFormat.duration(77 / 60.0))
        assertEquals("3d 4h", PetFormat.duration(76.0))
        assertEquals("27m", PetFormat.duration((726_700.0 - 196_209.0) / 1_200_000.0))
    }
}
