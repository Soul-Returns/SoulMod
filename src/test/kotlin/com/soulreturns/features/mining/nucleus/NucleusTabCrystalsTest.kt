package com.soulreturns.features.mining.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Raw tab lines from capture `2026-11-08_04-25-39` (Hypixel, 26.2 client). */
class NucleusTabCrystalsTest {
    private val before = listOf(
        "§r               §r§3§lInfo",
        "§r§b§lCrystals:",
        "§r §r§aJade§r§f: §r§e✖ Not Placed",
        "§r §r§6Amber§r§f: §r§c✖ Not Found",
        "§r §r§5Amethyst§r§f: §r§c✖ Not Found",
        "§r §r§bSapphire§r§f: §r§e✖ Not Placed",
        "§r §r§eTopaz§r§f: §r§c✖ Not Found",
        "",
        "§r§9§lCommissions:",
    )

    @Test
    fun notPlacedIsFoundNotPlaced() {
        assertEquals(
            mapOf(
                "Jade" to CrystalState.FOUND,
                "Amber" to CrystalState.MISSING,
                "Amethyst" to CrystalState.MISSING,
                "Sapphire" to CrystalState.FOUND,
                "Topaz" to CrystalState.MISSING,
            ),
            NucleusTabCrystals.parse(before),
        )
    }

    @Test
    fun placedAfterThePlacedLine() {
        val after = before.map { if ("Jade" in it) "§r §r§aJade§r§f: §r§a✔ Placed" else it }
        assertEquals(CrystalState.PLACED, NucleusTabCrystals.parse(after)?.get("Jade"))
        assertEquals(CrystalState.FOUND, NucleusTabCrystals.parse(after)?.get("Sapphire"))
    }

    @Test
    fun noWidget() {
        assertNull(NucleusTabCrystals.parse(listOf("§r§9§lCommissions:", "§r §r§fGoblin Slayer: §r§c0%")))
    }
}
