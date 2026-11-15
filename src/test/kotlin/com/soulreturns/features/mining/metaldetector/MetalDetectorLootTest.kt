package com.soulreturns.features.mining.metaldetector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Chat lines as captured on Hypixel (2026-11-07); the powder line's amount format is a guess. */
class MetalDetectorLootTest {
    private fun parse(raw: String) = MetalDetectorLoot.parse(MetalDetectorLoot.clean(raw))

    @Test
    fun gemstoneWithAmount() {
        assertEquals(
            "Flawed Ruby Gemstone" to 2L,
            parse("§r§aYou found §r§a\uE010 Flawed Ruby Gemstone §r§8x2 §r§awith your §r§cMetal Detector§r§a!"),
        )
    }

    @Test
    fun singleGemstone() {
        assertEquals(
            "Flawed Sapphire Gemstone" to 1L,
            parse("§r§aYou found §r§a\uE003 Flawed Sapphire Gemstone §r§awith your §r§cMetal Detector§r§a!"),
        )
    }

    @Test
    fun tool() {
        val (name, qty) = parse("§r§aYou found §r§cScavenged Emerald Hammer §r§awith your §r§cMetal Detector§r§a!")!!
        assertEquals("Scavenged Emerald Hammer" to 1L, name to qty)
        assertEquals(MetalDetectorLoot.GROUP_TOOLS, MetalDetectorLoot.group(name))
    }

    @Test
    fun powder() {
        val (name, qty) = parse("§r§aYou found §r§d+1,250 Gemstone Powder §r§awith your §r§cMetal Detector§r§a!")!!
        assertEquals("Gemstone Powder" to 1250L, name to qty)
        assertEquals(MetalDetectorLoot.GROUP_POWDER, MetalDetectorLoot.group(name))
    }

    @Test
    fun otherLine() {
        assertNull(parse("§aYou found a Gemstone in the chest!"))
    }
}
