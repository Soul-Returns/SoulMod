package com.soulreturns.features.mining.nucleus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Raw lines from capture `2026-11-07_17-13-51` (50 nucleus runs, Hypixel, 26.2 client). */
class NucleusChatParserTest {
    private val purpleBar = "§r§5§l" + "▬".repeat(64)
    private val aquaBar = "§r§3§l" + "▬".repeat(64)

    private fun feedAll(lines: List<String>): List<NucleusEvent> {
        val parser = NucleusChatParser()
        return lines.mapNotNull { parser.feed(it) }
    }

    @Test
    fun foundBlockFirstCrystal() {
        val events = feedAll(
            listOf(
                purpleBar,
                "§r§f                       §r§5§l✦ CRYSTAL FOUND §r§7(1§r§7/5§r§7)",
                "§r§f                              §r§5Amethyst Crystal",
                "",
                "§r§f            §r§7Place this Crystal in the §r§5Crystal Nucleus §r§7and",
                "§r§f             §r§7find all §r§a5 §r§7Crystals in the §r§5Crystal Hollows §r§7to",
                "§r§f                               §r§7earn §r§6rare loot§r§7!",
                purpleBar,
            ),
        )
        assertEquals(listOf<NucleusEvent>(NucleusEvent.CrystalFound("Amethyst", 1, 5)), events)
    }

    @Test
    fun foundBlockLastCrystal() {
        val events = feedAll(
            listOf(
                purpleBar,
                "§r§f                       §r§5§l✦ CRYSTAL FOUND §r§7(5§r§7/5§r§7)",
                "§r§f                                §r§aJade Crystal",
                "",
                "§r§f           §r§7You have found all of the Crystals! Good stuff!",
                purpleBar,
            ),
        )
        assertEquals(listOf<NucleusEvent>(NucleusEvent.CrystalFound("Jade", 5, 5)), events)
    }

    @Test
    fun placedLines() {
        val events = feedAll(
            listOf(
                "§r§5§l✦ §r§dYou placed the §r§bSapphire Crystal§r§d!",
                "§r  §r§dKeep exploring the §r§5Crystal Hollows §r§dto find the rest!",
                "§r§5§l✦ §r§dYou placed the §r§6Amber Crystal§r§d!",
            ),
        )
        assertEquals(listOf<NucleusEvent>(NucleusEvent.CrystalPlaced("Sapphire"), NucleusEvent.CrystalPlaced("Amber")), events)
    }

    @Test
    fun lootBundleWithRareDrops() {
        val events = feedAll(
            listOf(
                "§r§5§l✦ §r§dYou placed the §r§6Amber Crystal§r§d!",
                aquaBar,
                "§r  §r§5§lCRYSTAL NUCLEUS LOOT BUNDLE ",
                "",
                "§r  §r§a§lREWARDS",
                "§r    §r§6Helix Fossil",
                "§r    §r§5Divan Fragment",
                "§r    §r§5\uE010 Flawless Ruby Gemstone",
                "§r    §r§5Jaderald",
                "§r    §r§9\uE015 Fine Amber Gemstone",
                "§r    §r§9\uE008 Fine Amethyst Gemstone",
                "§r    §r§9\uE053 Fine Jade Gemstone",
                "§r    §r§9\uE00D Fine Jasper Gemstone",
                "§r    §r§9\uE010 Fine Ruby Gemstone",
                "§r    §r§9\uE003 Fine Sapphire Gemstone",
                "§r    §r§9\uE01C Fine Topaz Gemstone",
                "§r    §r§a\uE015 Flawed Amber Gemstone §r§8x24",
                "§r    §r§a\uE008 Flawed Amethyst Gemstone §r§8x24",
                "§r    §r§a\uE053 Flawed Jade Gemstone §r§8x24",
                "§r    §r§a\uE010 Flawed Ruby Gemstone §r§8x52",
                "§r    §r§a\uE003 Flawed Sapphire Gemstone §r§8x24",
                "§r    §r§a\uE01C Flawed Topaz Gemstone §r§8x42",
                "§r    §r§dGold Essence §r§8x10",
                "§r    §r§2Mithril Powder §r§8x6,327",
                aquaBar,
                "§r§7Pick it up near the §r§5Nucleus Vault§r§7!",
            ),
        )
        assertEquals(2, events.size)
        val bundle = events[1] as NucleusEvent.LootBundle
        assertEquals(19, bundle.items.size)
        assertEquals(NucleusLoot("Helix Fossil", 1), bundle.items[0])
        assertEquals(NucleusLoot("Flawless Ruby Gemstone", 1), bundle.items[2])
        assertEquals(NucleusLoot("Flawed Ruby Gemstone", 52), bundle.items[14])
        assertEquals(NucleusLoot("Gold Essence", 10), bundle.items[17])
        assertEquals(NucleusLoot("Mithril Powder", 6327), bundle.items[18])
    }

    @Test
    fun lootLineShapes() {
        assertEquals(NucleusLoot("Enchanted Book (Lapidary I)", 1), NucleusLines.lootLine(NucleusLines.clean("§r    §r§fEnchanted Book (Lapidary I§r§f)")))
        assertEquals(NucleusLoot("Bob-omb", 20), NucleusLines.lootLine(NucleusLines.clean("§r    §r§9Bob-omb §r§8x20")))
        assertEquals(NucleusLoot("Dwarven O's Gemstone Grahams", 1), NucleusLines.lootLine(NucleusLines.clean("§r    §r§5Dwarven O's Gemstone Grahams")))
        assertEquals(NucleusLoot("Gemstone Powder", 4761), NucleusLines.lootLine(NucleusLines.clean("§r    §r§dGemstone Powder §r§8x4,761")))
        assertNull(NucleusLines.lootLine(NucleusLines.clean("§r  §r§a§lREWARDS")))
    }

    @Test
    fun kitLines() {
        val events = feedAll(
            listOf(
                "§r§e[NPC] Professor Robot§r§f: §rThat's not one of the compon-",
                "§r§e[NPC] Professor Robot§r§f: §rWait a minute. This will work just fine.",
                "§r§e[NPC] §r§bKalhuiki Door Guardian§r§f: §rA Jungle Key! I will open the door for you.",
                "§r§e[NPC] §r§bKalhuiki Door Guardian§r§f: §rThe door is open, go in!",
                "§r§e[NPC] §r§6King Yolkar§r§f: §rYou need my §afoul stench §fagain? Bring me a §9Goblin Egg §fof any type!",
                "§r§e[NPC] §r§6King Yolkar§r§f: §rI'm covering you in my §afoul stench §ras we speak. It won't last long before it dissipates!",
                "§r§e[NPC] §r§6Keeper of Diamond§r§f: §rExcellent! You have returned the §cScavenged Diamond Axe §rto its rightful place!",
                "§r§e[NPC] §r§6Keeper of Lapis§r§f: §rYou have already restored this Dwarf's §cScavenged Lapis Sword§r!",
                "§r§e[NPC] §r§6Keeper of Diamond§r§f: §rYou found all of the items! Behold... the §aJade Crystal§r!",
            ),
        )
        // Keeper lines (scavenged tools) are free and not tracked.
        assertEquals(
            listOf<NucleusEvent>(
                NucleusEvent.KitUsed("Precursor Apparatus"),
                NucleusEvent.KitUsed("Jungle Key"),
                NucleusEvent.KitUsed("Goblin Egg"),
            ),
            events,
        )
    }

    @Test
    fun robotPartsOneByOne() {
        val events = feedAll(
            listOf(
                "§r§e[NPC] Professor Robot§r§f: Thanks for bringing me the §9Synthetic Heart§r! Bring me 5 more components to fix the giant!",
                "Thanks for bringing me the §9Robotron Reflector§r! Bring me 4 more components to fix the giant!",
                "Thanks for bringing me the §9Superlite Motor§r! Bring me 3 more components to fix the giant!",
                "Thanks for bringing me the §9FTX 3070§r! Bring me 2 more components to fix the giant!",
                "Thanks for bringing me the §9Electron Transmitter§r! Bring me one more component to fix the giant!",
                "§rYou've brought me all of the components!",
                "§rYou've brought me all of the components!",
            ),
        )
        // The closing line names no part: it is the one missing from the hand-in; a stray repeat adds nothing.
        assertEquals(
            listOf<NucleusEvent>(
                NucleusEvent.KitUsed("Synthetic Heart"),
                NucleusEvent.KitUsed("Robotron Reflector"),
                NucleusEvent.KitUsed("Superlite Motor"),
                NucleusEvent.KitUsed("FTX 3070"),
                NucleusEvent.KitUsed("Electron Transmitter"),
                NucleusEvent.KitUsed("Control Switch"),
            ),
            events,
        )
    }

    @Test
    fun toolLinesAreNotKit() {
        assertEquals(
            emptyList<NucleusEvent>(),
            feedAll(
                listOf(
                    "§r§e[NPC] §r§6Keeper of Diamond§r§f: §rExcellent! You have returned the §cScavenged Diamond Axe §rto its rightful place!",
                    "§r§e[NPC] §r§6Keeper of Gold§r§f: §rYou found all of the items! Behold... the §aJade Crystal§r!",
                ),
            ),
        )
    }

    @Test
    fun eggKindIsTheCountThatDropped() {
        val before = mapOf("GOBLIN_EGG" to 10, "GOBLIN_EGG_RED" to 2)
        assertEquals("Red Goblin Egg", GoblinEggs.kindSpent(before, mapOf("GOBLIN_EGG" to 10, "GOBLIN_EGG_RED" to 1)))
        assertEquals("Goblin Egg", GoblinEggs.kindSpent(before, mapOf("GOBLIN_EGG" to 9, "GOBLIN_EGG_RED" to 2)))
        assertEquals("Blue Goblin Egg", GoblinEggs.kindSpent(mapOf("GOBLIN_EGG_BLUE" to 1), emptyMap()))
        assertNull(GoblinEggs.kindSpent(before, before))
    }

    @Test
    fun kitNamesMapToIds() {
        assertEquals("GOBLIN_EGG_YELLOW", NucleusItems.kitId("Yellow Goblin Egg"))
        assertEquals("FTX_3070", NucleusItems.kitId("FTX 3070"))
        assertEquals("SYNTHETIC_HEART", NucleusItems.kitId("Synthetic Heart"))
        assertEquals(NucleusLines.ROBOT_PARTS.size, NucleusLines.ROBOT_PARTS.mapNotNull(NucleusItems::kitId).size)
    }

    @Test
    fun migrationDropsToolKitOnly() {
        val bucket = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"session":{"runs":2,"kit":{"Jungle Key":2,"Scavenged Diamond Axe":2}},"total":{"runs":5,"kit":{"Goblin Egg":5,"Scavenged Lapis Sword":3,"Scavenged Golden Hammer":1}},"sessionStarted":true}""",
        )
        val out = com.soulreturns.stats.StatsMigrations.dropNucleusTools(bucket).toString()
        assertEquals(
            """{"session":{"runs":2,"kit":{"Jungle Key":2}},"total":{"runs":5,"kit":{"Goblin Egg":5}},"sessionStarted":true}""",
            out,
        )
    }

    @Test
    fun balLines() {
        val events = feedAll(
            listOf(
                "§r§c§oThe ground starts to shake and the lava bubbles...",
                "§r§c[BOSS] Bal§r§f: Bah! Come forth my minions! Fly above, take them out!",
                "§r§c[BOSS] Bal§r§f: AGGHHHHHHHHHHH!",
                "§r§c§oThe boss looks weak and tired and retreats into the lava...",
                "§r§cYou haven't earned this Crystal! Come back when you have! >:-)",
            ),
        )
        assertEquals(
            listOf(NucleusEvent.BalSpawning, NucleusEvent.BalActive, NucleusEvent.BalDown, NucleusEvent.CrystalLocked),
            events,
        )
    }

    @Test
    fun syntheticIds() {
        assertEquals("ENCHANTMENT_LAPIDARY_1", NucleusItems.syntheticId("Enchanted Book (Lapidary I)"))
        assertEquals("ENCHANTMENT_FORTUNE_4", NucleusItems.syntheticId("Enchanted Book (Fortune IV)"))
        assertEquals("ESSENCE_DIAMOND", NucleusItems.syntheticId("Diamond Essence"))
        assertNull(NucleusItems.syntheticId("Divan Fragment"))
    }
}
