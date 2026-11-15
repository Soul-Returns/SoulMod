package com.soulreturns.features.dungeons.terminals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boards are the first full contents of real Hypixel terminals, Appendix A/B of
 * `docs/research/f7-native-terminals-2026-11-12.md` (row lines start with their first slot).
 */
class TerminalSolversTest {
    // Appendix B: display name -> registry id (only the names these boards use).
    private val ids = mapOf(
        "Acacia Door" to "acacia_door", "Apple" to "apple", "Armor Stand" to "armor_stand", "Arrow" to "arrow",
        "Birch Door" to "birch_door", "Black Stained Clay" to "black_terracotta", "Black Wool" to "black_wool",
        "Bottle o' Enchanting" to "experience_bottle", "Clay" to "clay_ball", "Cooked Fish" to "cooked_cod",
        "Cooked Mutton" to "cooked_mutton", "Cooked Rabbit" to "cooked_rabbit", "Cookie" to "cookie", "Cyan Dye" to "cyan_dye",
        "Cyan Stained Clay" to "cyan_terracotta", "Cyan Wool" to "cyan_wool", "Dark Oak Door" to "dark_oak_door",
        "Diamond Boots" to "diamond_boots", "Diamond Chestplate" to "diamond_chestplate", "Diamond Leggings" to "diamond_leggings",
        "Egg" to "egg", "Empty Map" to "map", "Enchanted Book" to "enchanted_book", "Ender Pearl" to "ender_pearl",
        "Eye of Ender" to "ender_eye", "Fire Charge" to "fire_charge", "Firework Star" to "firework_star",
        "Fishing Rod" to "fishing_rod", "Flower Pot" to "flower_pot", "Glistering Melon" to "glistering_melon_slice",
        "Glowstone Dust" to "glowstone_dust", "Gold Boots" to "golden_boots", "Gold Chestplate" to "golden_chestplate",
        "Gold Helmet" to "golden_helmet", "Gold Horse Armor" to "golden_horse_armor", "Gold Pickaxe" to "golden_pickaxe",
        "Gold Shovel" to "golden_shovel", "Gray Dye" to "gray_dye", "Gray Stained Clay" to "gray_terracotta",
        "Gray Stained Glass" to "gray_stained_glass", "Gray Wool" to "gray_wool", "Gunpowder" to "gunpowder",
        "Ink Sack" to "ink_sac", "Iron Door" to "iron_door", "Iron Helmet" to "iron_helmet", "Iron Horse Armor" to "iron_horse_armor",
        "Iron Ingot" to "iron_ingot", "Lava Bucket" to "lava_bucket", "Leather" to "leather", "Leather Boots" to "leather_boots",
        "Leather Helmet" to "leather_helmet", "Leather Leggings" to "leather_leggings", "Light Blue Stained Clay" to "light_blue_terracotta",
        "Light Blue Stained Glass" to "light_blue_stained_glass", "Light Blue Wool" to "light_blue_wool", "Light Gray Dye" to "light_gray_dye",
        "Lime Dye" to "lime_dye", "Lime Stained Clay" to "lime_terracotta", "Lime Stained Glass" to "lime_stained_glass",
        "Lime Wool" to "lime_wool", "Magma Cream" to "magma_cream", "Melon Seeds" to "melon_seeds", "Milk Bucket" to "milk_bucket",
        "Minecart with Command Block" to "command_block_minecart", "Minecart with Hopper" to "hopper_minecart",
        "Minecart with TNT" to "tnt_minecart", "Nether Quartz" to "quartz", "Pink Dye" to "pink_dye", "Pink Stained Clay" to "pink_terracotta",
        "Pink Stained Glass" to "pink_stained_glass", "Prismarine Shard" to "prismarine_shard", "Purple Dye" to "purple_dye",
        "Purple Stained Clay" to "purple_terracotta", "Purple Stained Glass" to "purple_stained_glass", "Purple Wool" to "purple_wool",
        "Rabbit Foot" to "rabbit_foot", "Rabbit Hide" to "rabbit_hide", "Rabbit Stew" to "rabbit_stew", "Raw Fish" to "cod",
        "Raw Porkchop" to "porkchop", "Redstone" to "redstone", "Redstone Comparator" to "comparator", "Roast Chicken" to "cooked_chicken",
        "Seeds" to "wheat_seeds", "Silver Stained Glass" to "light_gray_stained_glass", "Silver Wool" to "light_gray_wool",
        "Skull Item" to "skeleton_skull", "Slime Ball" to "slime_ball", "Snowball" to "snowball", "Spawn Egg" to "polar_bear_spawn_egg",
        "Spruce Door" to "spruce_door", "Stone Pickaxe" to "stone_pickaxe", "Sugar" to "sugar", "Watch" to "clock",
        "Water Bottle" to "potion", "Wheat" to "wheat",
    )

    private fun cell(text: String): TermItem {
        val t = text.trim()
        t.toIntOrNull()?.let { return TermItem(PANE_RED, "$it", count = it) }
        return when (t) {
            "·" -> TermItem(FILLER, "")
            "OFF" -> TermItem(PANE_RED, "Off")
            "ON" -> TermItem(PANE_LIME, "On")
            "P" -> TermItem(PANE_MAGENTA, "")
            "G" -> TermItem(PANE_LIME, "")
            "R" -> TermItem(PANE_RED, "")
            "W" -> TermItem(PANE_WHITE, "")
            "LOCK" -> TermItem(LOCK_ON, "Lock In Slot")
            "inactive" -> TermItem(LOCK_OFF, "Row Not Active")
            "red", "orange", "yellow", "green", "blue" -> TermItem(t + "_stained_glass_pane", t.replaceFirstChar { it.uppercase() })
            else -> {
                val glint = t.endsWith("[g]")
                val name = t.removeSuffix("[g]").trim()
                TermItem(ids.getValue(name), name, intrinsicGlint = glint)
            }
        }
    }

    private fun board(type: TerminalType, vararg rows: String): MutableList<TermItem?> {
        val b = MutableList<TermItem?>(type.size) { TermItem(FILLER, "") }
        for (row in rows) {
            val start = row.substringBefore(':').trim().toInt()
            row.substringAfter(':').split('|').forEachIndexed { i, c -> b[start + i] = cell(c) }
        }
        return b
    }

    private fun spec(title: String, type: TerminalType): TerminalSpec {
        val s = assertNotNull(TerminalType.recognize(title, type.size), title)
        assertEquals(type, s.type)
        return s
    }

    private fun targets(t: TerminalTracker, now: Long = 0): Set<Int> = t.solution(now).cells.values.filter { it.state == TerminalTracker.TARGET }.map { it.slot }.toSet()

    private fun tracker(spec: TerminalSpec, b: List<TermItem?>): TerminalTracker {
        assertTrue(spec.initialOk(b), "fixture board must validate")
        return TerminalTracker(spec).also { it.update(b, 0) }
    }

    // ---------------------------------------------------------------- boards

    private val boardL = board(
        TerminalType.StartsWith,
        "09: · | Leather | Iron Horse Armor | Snowball | Leather Leggings | Ender Pearl | Cooked Rabbit | Leather | ·",
        "18: · | Slime Ball | Spawn Egg | Roast Chicken | Gold Pickaxe | Glowstone Dust | Leather Helmet | Slime Ball | ·",
        "27: · | Cooked Fish | Leather Helmet | Dark Oak Door | Lava Bucket | Leather Boots | Watch | Slime Ball | ·",
    )
    private val boardE = board(
        TerminalType.StartsWith,
        "09: · | Leather | Egg | Skull Item | Gold Chestplate | Fishing Rod | Minecart with TNT | Eye of Ender | ·",
        "18: · | Leather Helmet | Spruce Door | Empty Map | Fire Charge | Gold Shovel | Enchanted Book [g] | Seeds | ·",
        "27: · | Glistering Melon | Rabbit Stew | Enchanted Book [g] | Cookie | Ender Pearl | Flower Pot | Bottle o' Enchanting [g] | ·",
    )
    private val boardA = board(
        TerminalType.StartsWith,
        "09: · | Armor Stand | Stone Pickaxe | Birch Door | Rabbit Stew | Arrow | Nether Quartz | Armor Stand | ·",
        "18: · | Acacia Door | Dark Oak Door | Armor Stand | Acacia Door | Raw Porkchop | Sugar | Diamond Boots | ·",
        "27: · | Apple | Armor Stand | Prismarine Shard | Glistering Melon | Iron Horse Armor | Diamond Chestplate | Iron Helmet | ·",
    )
    private val boardR = board(
        TerminalType.StartsWith,
        "09: · | Rabbit Foot | Cooked Mutton | Raw Porkchop | Redstone Comparator | Gold Chestplate | Gold Chestplate | Raw Porkchop | ·",
        "18: · | Leather Helmet | Gunpowder | Rabbit Foot | Gold Horse Armor | Leather Helmet | Redstone Comparator | Rabbit Stew | ·",
        "27: · | Rabbit Stew | Redstone | Gold Helmet | Roast Chicken | Rabbit Hide | Iron Door | Rabbit Hide | ·",
    )
    private val boardM = board(
        TerminalType.StartsWith,
        "09: · | Minecart with TNT | Dark Oak Door | Acacia Door | Diamond Leggings | Minecart with Command Block | Minecart with Hopper | Bottle o' Enchanting [g] | ·",
        "18: · | Milk Bucket | Seeds | Firework Star | Clay | Gold Boots | Milk Bucket | Iron Ingot | ·",
        "27: · | Melon Seeds | Magma Cream | Fire Charge | Raw Fish | Minecart with Hopper | Wheat | Water Bottle | ·",
    )
    private val boardLime = board(
        TerminalType.Select,
        "09: · | Purple Wool | Silver Wool | Cyan Stained Clay | Cyan Wool | Lime Stained Glass | Black Stained Clay | Black Wool | ·",
        "18: · | Purple Stained Glass | Lime Dye | Lime Stained Clay | Cyan Dye | Purple Stained Clay | Cyan Dye | Ink Sack | ·",
        "27: · | Light Gray Dye | Light Gray Dye | Silver Wool | Black Wool | Purple Dye | Silver Stained Glass | Lime Wool | ·",
        "36: · | Lime Stained Clay | Cyan Dye | Ink Sack | Purple Stained Glass | Purple Wool | Cyan Dye | Silver Wool | ·",
    )
    private val boardGray = board(
        TerminalType.Select,
        "09: · | Purple Dye | Pink Stained Clay | Purple Wool | Pink Dye | Gray Dye | Lime Stained Clay | Gray Wool | ·",
        "18: · | Pink Stained Glass | Purple Wool | Gray Stained Glass | Gray Wool | Light Blue Stained Glass | Light Blue Stained Clay | Lime Stained Clay | ·",
        "27: · | Gray Stained Glass | Light Blue Stained Glass | Lime Dye | Purple Stained Glass | Lime Wool | Pink Dye | Pink Stained Glass | ·",
        "36: · | Lime Stained Glass | Pink Stained Glass | Purple Stained Glass | Light Blue Wool | Light Blue Stained Clay | Gray Stained Clay | Lime Wool | ·",
    )

    // ---------------------------------------------------------------- recognition

    @Test
    fun recognizesEveryTitleWithItsSize() {
        assertEquals("L", spec("What starts with: 'L'?", TerminalType.StartsWith).param)
        assertEquals("LIME", spec("Select all the LIME items!", TerminalType.Select).param)
        spec("Correct all the panes!", TerminalType.Panes)
        spec("Click in order!", TerminalType.Order)
        spec("Change all to same color!", TerminalType.Rubix)
        spec("Click the button on time!", TerminalType.Melody)
        // Open sets: any letter, any color word.
        assertEquals("Q", spec("What starts with: 'Q'?", TerminalType.StartsWith).param)
        assertEquals("LIGHT BLUE", spec("Select all the LIGHT BLUE items!", TerminalType.Select).param)
    }

    @Test
    fun wrongSizeOrTitleIsNotATerminal() {
        assertNull(TerminalType.recognize("Click in order!", 54))
        assertNull(TerminalType.recognize("Correct all the panes!", 27))
        assertNull(TerminalType.recognize("Click in order", 36))
        assertNull(TerminalType.recognize("Large Chest", 54))
    }

    @Test
    fun aChestWithATerminalTitleButAnotherLayoutIsRejected() {
        val s = spec("Correct all the panes!", TerminalType.Panes)
        val b = board(TerminalType.Panes, "09: · | · | OFF | ON | OFF | ON | OFF | · | ·", "18: · | · | OFF | OFF | OFF | OFF | OFF | · | ·", "27: · | · | OFF | OFF | OFF | OFF | OFF | · | ·")
        assertTrue(s.initialOk(b))
        b[0] = TermItem("chest", "Chest")
        assertFalse(s.slotsOk(b))
        b[0] = TermItem(FILLER, "")
        b[12] = TermItem("diamond", "Diamond")
        assertFalse(s.slotsOk(b))
        // Starts-with with nothing that starts with the letter is not solvable: not a terminal we know.
        assertFalse(spec("What starts with: 'Z'?", TerminalType.StartsWith).initialOk(boardL))
        // Not loaded yet: partial contents are fine, but not "initial".
        val partial = boardL.toMutableList().also { it[10] = null }
        assertTrue(spec("What starts with: 'L'?", TerminalType.StartsWith).slotsOk(partial))
        assertFalse(spec("What starts with: 'L'?", TerminalType.StartsWith).initialOk(partial))
    }

    // ---------------------------------------------------------------- starts with / select

    @Test
    fun startsWithMatchesTheDisplayName() {
        fun t(letter: String, b: List<TermItem?>) = targets(tracker(spec("What starts with: '$letter'?", TerminalType.StartsWith), b))
        assertEquals(setOf(10, 13, 16, 24, 29, 31, 32), t("L", boardL))
        assertEquals(setOf(11, 16, 21, 24, 30, 32), t("E", boardE))
        assertEquals(setOf(10, 14, 16, 19, 21, 22, 28, 29), t("A", boardA))
        assertEquals(setOf(10, 12, 13, 16, 21, 24, 25, 28, 29, 31, 32, 34), t("R", boardR))
        assertEquals(setOf(10, 14, 15, 19, 24, 28, 29, 32), t("M", boardM))
        // Roast Chicken (cooked_chicken) is an R, by its display name.
        assertTrue(31 in t("R", boardR))
    }

    @Test
    fun selectMatchesColorsIncludingLegacyNames() {
        fun t(color: String, b: List<TermItem?>) = targets(tracker(spec("Select all the $color items!", TerminalType.Select), b))
        assertEquals(setOf(14, 20, 21, 34, 37), t("LIME", boardLime))
        assertEquals(setOf(14, 16, 21, 22, 28, 42), t("GRAY", boardGray))
        // Light gray is "Silver" on wool and glass, "Light Gray" on dye.
        assertEquals(setOf(11, 28, 29, 30, 33, 43), t("SILVER", boardLime))
        assertEquals(setOf(11, 28, 29, 30, 33, 43), t("LIGHT GRAY", boardLime))
        assertEquals(setOf(15, 16, 25, 31, 39), t("BLACK", boardLime))
        assertEquals(setOf(23, 24, 29, 40, 41), t("LIGHT BLUE", boardGray))
    }

    @Test
    fun selectionFromGlintPatchAndAcceptedClicks() {
        val s = spec("What starts with: 'E'?", TerminalType.StartsWith)
        val t = tracker(s, boardE)
        // Initially glowing books are not selected: progress starts fresh on every open.
        assertTrue(24 in targets(t) && 30 in targets(t))
        assertEquals(6, t.solution(0).total)

        // An ordinary item: shown done at once, the server's glint patch confirms it.
        assertTrue(t.isCorrect(11, LEFT, 0))
        t.clicked(11, LEFT, 0)
        assertFalse(11 in targets(t, 10))
        assertFalse(t.isCorrect(11, LEFT, 10), "a selected item is never correct again")
        val echoed = boardE.toMutableList().also { it[11] = it[11]!!.copy(glintPatch = true) }
        t.update(echoed, 150)
        assertFalse(11 in targets(t, 5000))

        // A book glows anyway; its click is remembered for this menu, even after the timeout.
        assertTrue(t.isCorrect(24, LEFT, 200))
        t.clicked(24, LEFT, 200)
        t.update(echoed, 350)
        assertFalse(24 in targets(t, 5000))
        assertTrue(30 in targets(t, 5000), "the other book was never clicked")
        assertEquals(4, t.solution(5000).remaining)

        // Wrong letter is never correct.
        assertFalse(t.isCorrect(14, LEFT, 5000))
    }

    @Test
    fun predictionFallsBackToTheServerAfterTheTimeout() {
        val t = tracker(spec("Select all the LIME items!", TerminalType.Select), boardLime)
        t.clicked(14, LEFT, 0)
        assertFalse(14 in targets(t, 100))
        // The server never confirmed: the server wins.
        t.update(boardLime, 900)
        assertFalse(14 in targets(t, 900))
        assertTrue(14 in targets(t, TerminalTracker.PREDICTION_MS + 1))
    }

    @Test
    fun aRegeneratedBoardDropsOldSelections() {
        val s = spec("What starts with: 'E'?", TerminalType.StartsWith)
        val t = tracker(s, boardE)
        t.clicked(24, LEFT, 0)
        assertFalse(24 in targets(t, 10))
        val other = boardE.toMutableList().also { it[24] = TermItem("egg", "Egg") }
        t.update(other, 20)
        assertTrue(24 in targets(t, 20))
    }

    // ---------------------------------------------------------------- panes / order

    @Test
    fun panesHighlightOffPanes() {
        val b = board(TerminalType.Panes, "09: · | · | OFF | ON | OFF | ON | OFF | · | ·", "18: · | · | OFF | OFF | OFF | OFF | OFF | · | ·", "27: · | · | OFF | OFF | OFF | OFF | OFF | · | ·")
        val t = tracker(spec("Correct all the panes!", TerminalType.Panes), b)
        assertEquals(setOf(11, 13, 15, 20, 21, 22, 23, 24, 29, 30, 31, 32, 33), targets(t))
        assertFalse(t.isCorrect(12, LEFT, 0), "an On pane would toggle back Off")
        assertTrue(t.isCorrect(11, LEFT, 0))
        t.clicked(11, LEFT, 0)
        assertFalse(t.isCorrect(11, LEFT, 10))
        assertEquals(12, t.solution(10).remaining)
        b[11] = TermItem(PANE_LIME, "On")
        t.update(b, 150)
        assertEquals(12, t.solution(5000).remaining)
    }

    @Test
    fun orderFollowsTheNumbers() {
        val b = board(TerminalType.Order, "09: · | · | 7 | 1 | 2 | 10 | 8 | · | ·", "18: · | · | 3 | 6 | 5 | 4 | 9 | · | ·")
        val t = tracker(spec("Click in order!", TerminalType.Order), b)
        val expected = listOf(12, 13, 20, 23, 22, 21, 11, 15, 24, 14)
        var now = 0L
        for ((i, slot) in expected.withIndex()) {
            val sol = t.solution(now)
            assertEquals(slot, sol.cells.values.single { it.state == TerminalTracker.TARGET }.slot)
            assertEquals(10 - i, sol.remaining)
            if (i + 1 < expected.size) {
                assertEquals(TerminalTracker.UPCOMING, sol.cells.getValue(expected[i + 1]).state)
                assertEquals(1, sol.cells.getValue(expected[i + 1]).rank)
                assertFalse(t.isCorrect(expected[i + 1], LEFT, now), "the one after next is wrong")
            }
            assertTrue(t.isCorrect(slot, LEFT, now))
            t.clicked(slot, LEFT, now)
            now += 120
            b[slot] = b[slot]!!.copy(id = PANE_LIME)
            t.update(b, now)
        }
        assertEquals(0, t.solution(now).remaining)
    }

    @Test
    fun orderSecondBoard() {
        val b = board(TerminalType.Order, "09: · | · | 4 | 6 | 8 | 10 | 3 | · | ·", "18: · | · | 7 | 9 | 5 | 2 | 1 | · | ·")
        val t = tracker(spec("Click in order!", TerminalType.Order), b)
        assertEquals(setOf(24), targets(t))
    }

    // ---------------------------------------------------------------- rubix

    private fun rubix(vararg rows: String) = board(TerminalType.Rubix, *rows)

    private fun moves(t: TerminalTracker, now: Long = 0) = t.solution(now).cells.values.associate { it.slot to it.clicks }

    @Test
    fun rubixPicksTheCheapestColorWithRightClicks() {
        val b = rubix("09: · | · | · | red | blue | green | · | · | ·", "18: · | · | · | red | red | green | · | · | ·", "27: · | · | · | orange | blue | orange | · | · | ·")
        val t = tracker(spec("Change all to same color!", TerminalType.Rubix), b)
        val sol = t.solution(0)
        assertEquals(0, sol.rubixTarget)
        assertEquals(8, sol.remaining)
        assertEquals(mapOf(12 to 0, 13 to 1, 14 to 2, 21 to 0, 22 to 0, 23 to 2, 30 to -1, 31 to 1, 32 to -1), moves(t))
        assertTrue(t.isCorrect(30, RIGHT, 0))
        assertFalse(t.isCorrect(30, LEFT, 0), "the long way round is blocked")
        assertTrue(t.isCorrect(14, LEFT, 0))
        assertFalse(t.isCorrect(14, RIGHT, 0))
        assertFalse(t.isCorrect(12, LEFT, 0), "already the target color")
    }

    @Test
    fun rubixMinimalCountsOnEveryCapturedBoard() {
        fun cost(vararg rows: String): Int = tracker(spec("Change all to same color!", TerminalType.Rubix), rubix(*rows)).solution(0).remaining
        assertEquals(10, cost("09: · | · | · | orange | green | yellow | · | · | ·", "18: · | · | · | blue | blue | red | · | · | ·", "27: · | · | · | orange | blue | yellow | · | · | ·"))
        assertEquals(7, cost("09: · | · | · | yellow | green | orange | · | · | ·", "18: · | · | · | green | green | red | · | · | ·", "27: · | · | · | yellow | green | yellow | · | · | ·"))
        assertEquals(8, cost("09: · | · | · | green | blue | orange | · | · | ·", "18: · | · | · | yellow | yellow | blue | · | · | ·", "27: · | · | · | yellow | green | blue | · | · | ·"))
        // Brute force: no target color does better.
        val colors = listOf(3, 4, 1, 2, 2, 4, 2, 3, 4)
        assertEquals((0 until 5).minOf { RubixColors.cost(colors, it) }, RubixColors.cost(colors, RubixColors.cheapest(colors)))
    }

    @Test
    fun rubixPredictionAndServerConfirmation() {
        val b = rubix("09: · | · | · | red | blue | green | · | · | ·", "18: · | · | · | red | red | green | · | · | ·", "27: · | · | · | orange | blue | orange | · | · | ·")
        val t = tracker(spec("Change all to same color!", TerminalType.Rubix), b)
        // Two quick left clicks on green: both predicted before the server answers.
        t.clicked(14, LEFT, 0)
        assertEquals(1, moves(t, 10)[14])
        assertTrue(t.isCorrect(14, LEFT, 120))
        t.clicked(14, LEFT, 120)
        assertEquals(0, moves(t, 130)[14])
        assertFalse(t.isCorrect(14, LEFT, 130))
        // The server answers one step at a time; the cell stays done.
        b[14] = TermItem("blue_stained_glass_pane", "Blue")
        t.update(b, 160)
        assertEquals(0, moves(t, 170)[14])
        b[14] = TermItem("red_stained_glass_pane", "Red")
        t.update(b, 280)
        assertEquals(0, moves(t, 5000)[14])
        assertEquals(6, t.solution(5000).remaining)

        // A click the server never applies: shown done, then the server's color wins.
        t.clicked(13, LEFT, 6000)
        assertEquals(0, moves(t, 6100)[13])
        assertEquals(1, moves(t, 6000 + TerminalTracker.PREDICTION_MS + 1)[13])
    }

    // ---------------------------------------------------------------- melody

    @Test
    fun melodyLocksOnlyWhenTheColumnsLineUp() {
        val b = board(
            TerminalType.Melody,
            "00: · | · | P | · | · | · | · | · | ·",
            "09: · | G | R | R | R | R | · | LOCK | ·",
            "18: · | W | W | W | W | W | · | inactive | ·",
            "27: · | W | W | W | W | W | · | inactive | ·",
            "36: · | · | P | · | · | · | · | · | ·",
        )
        val t = tracker(spec("Click the button on time!", TerminalType.Melody), b)
        assertEquals(3, t.solution(0).remaining)
        assertFalse(t.isCorrect(16, LEFT, 0), "green is in column 1, the target is column 2")
        // The green pane steps to column 2 (cid 15 at 156.849).
        b[10] = TermItem(PANE_RED, "")
        b[11] = TermItem(PANE_LIME, "")
        t.update(b, 500)
        assertTrue(t.solution(500).melody!!.aligned)
        assertEquals(TerminalTracker.TARGET, t.solution(500).cells.getValue(16).state)
        assertTrue(t.isCorrect(16, LEFT, 500))
        assertFalse(t.isCorrect(25, LEFT, 500), "an inactive row")
        assertFalse(t.isCorrect(11, LEFT, 500), "a lane pane is not a button")
        t.clicked(16, LEFT, 500)
        assertFalse(t.isCorrect(16, LEFT, 520), "locked, waiting for the server")
        assertEquals(2, t.solution(520).remaining)
        // Server: lane 1 done (white, red button), lane 2 active with a new target (cid 15 at 157.341).
        for (c in 1..5) b[9 + c] = TermItem(PANE_WHITE, "")
        b[16] = TermItem(LOCK_OFF, "Row Not Active")
        b[2] = TermItem(FILLER, "")
        b[3] = TermItem(PANE_MAGENTA, "")
        for (c in 1..5) b[18 + c] = TermItem(if (c == 3) PANE_LIME else PANE_RED, "")
        b[25] = TermItem(LOCK_ON, "Lock In Slot")
        t.update(b, 800)
        val sol = t.solution(800)
        assertEquals(2, sol.remaining)
        assertEquals(TerminalTracker.DONE, sol.cells.getValue(16).state)
        assertTrue(t.isCorrect(25, LEFT, 800))
        assertTrue(spec("Click the button on time!", TerminalType.Melody).slotsOk(b))
    }

    /** A clock whose lane 0 showed [prev] and stepped to [col] at time 0 (step age = now). */
    private fun clock(prev: Int, col: Int): MelodyClock = MelodyClock().also {
        it.observe(0, prev, -500)
        it.observe(0, col, 0)
    }

    @Test
    fun melodyLockIsJudgedOneLatencyAhead() {
        // Accepted on Hypixel (2026-11-12 no-solver run, cid 15 lane 2): sent 346 ms into the aligned step, ~120 ms latency.
        assertEquals(3, clock(2, 3).columnFor(346, 120))
        // Rejected on Hypixel (2026-11-11 Odin run, cid 25 lane 2): column 3 shown, target 4, 316 ms into the step,
        // pling 120 ms later. The server still had column 3 when the click arrived: no NOW cue for it.
        val odin = clock(2, 3)
        assertEquals(3, odin.columnFor(316, 120))
        // It lands 64 ms before the step, more than the jitter even with one tick: blocked (Hypixel rejected it).
        assertFalse(odin.mayAccept(4, 316, 120))
        // A pre-click: column 3 shown 400 ms, 150 ms latency: the click lands after the step to 4. NOW lights.
        assertEquals(4, clock(2, 3).columnFor(400, 150))
        // Late on the aligned step: the server has moved on, no NOW, but the client still shows alignment: allowed.
        val late = clock(3, 4)
        assertEquals(5, late.columnFor(420, 150))
        assertTrue(late.mayAccept(4, 420, 150))
        // Clearly wrong: column 1 shown 100 ms into its step, target 4, three steps away.
        assertFalse(clock(2, 1).mayAccept(4, 100, 150))
        assertFalse(clock(1, 2).mayAccept(4, 100, 150))
    }

    @Test
    fun melodyClockBouncesPausesAndLearnsTheStep() {
        assertEquals(4, MelodyClock.advance(5, 1, 1))
        assertEquals(3, MelodyClock.advance(4, 1, 3))
        assertEquals(2, MelodyClock.advance(1, -1, 1))
        // Step times of cid 21 (no-solver run): 542, 505, 494, 499 ms apart; the first gap starts at the menu's
        // contents, not at a step, so it is not learned.
        val c = MelodyClock()
        var t = 0L
        c.observe(1, 3, t)
        for ((gap, col) in listOf(542L to 4, 505L to 5, 494L to 4, 499L to 3)) {
            t += gap
            c.observe(1, col, t)
        }
        assertEquals(499L, c.period)
        assertEquals(setOf(3, 2), c.columnsBetween(t, 0, 600))
        // After a wrong lock the lane pauses ~1-1.5 s: an overdue step predicts nothing further.
        assertEquals(setOf(3), c.columnsBetween(t + 900, 0, 600))
        // A lane first seen mid-row: direction unknown until its first step.
        val mid = MelodyClock().also { it.observe(0, 3, 0) }
        assertEquals(setOf(2, 4), mid.columnsBetween(0, 500, 500))
        assertNull(mid.columnFor(400, 150))
        // A new lane resets the clock.
        c.observe(2, 1, t + 1000)
        assertEquals(setOf(1), c.columnsBetween(t + 1000, 0, 100))
    }

    @Test
    fun latencyEstimateUsesEchoesThenAPlausiblePing() {
        val l = LatencyEstimate()
        assertEquals(LatencyEstimate.DEFAULT_MS, l.estimate(0, 1), "Hypixel's player list says 1 ms")
        assertEquals(80L, l.estimate(0, 80))
        // Echo latencies of the first solver run's panes terminal.
        for (ms in listOf(157L, 148L, 178L, 160L, 155L, 9L, 3000L)) l.add(ms, 0)
        assertEquals(157L, l.estimate(1000, 80))
        // Ten minutes later they are another server's: back to the fallback.
        assertEquals(80L, l.estimate(10 * 60_000L, 80))
    }

    @Test
    fun melodyNowLeadsByTheLatency() {
        val b = board(
            TerminalType.Melody,
            "00: · | · | · | P | · | · | · | · | ·",
            "09: · | R | G | R | R | R | · | LOCK | ·",
            "18: · | W | W | W | W | W | · | inactive | ·",
            "27: · | W | W | W | W | W | · | inactive | ·",
            "36: · | · | · | P | · | · | · | · | ·",
        )
        val s = spec("Click the button on time!", TerminalType.Melody)
        val t = TerminalTracker(s, latency = { 150 })
        b[10] = TermItem(PANE_LIME, "")
        b[11] = TermItem(PANE_RED, "")
        t.update(b, -500)
        b[10] = TermItem(PANE_RED, "")
        b[11] = TermItem(PANE_LIME, "")
        t.update(b, 0)
        // Column 2 shown, target 3, moving right.
        assertEquals(TerminalTracker.IDLE, t.solution(200).cells.getValue(16).state)
        assertFalse(t.isCorrect(16, LEFT, 100), "two steps of window away")
        assertEquals(TerminalTracker.TARGET, t.solution(400).cells.getValue(16).state, "lands after the step to 3")
        assertTrue(t.isCorrect(16, LEFT, 300), "within latency + jitter of the step")
    }

    @Test
    fun predictionsHoldWhileTheServerIsSilent() {
        // Hypixel run 2026-11-12 #2, order cid 19: no packet at all from +0.35 s to +3.14 s.
        val b = board(TerminalType.Order, "09: · | · | 3 | 2 | 1 | 4 | 8 | · | ·", "18: · | · | 7 | 9 | 6 | 5 | 10 | · | ·")
        fun replay(serverClock: Boolean): TerminalTracker {
            var heard = 209L
            val t = if (serverClock) TerminalTracker(spec("Click in order!", TerminalType.Order), lastInbound = { heard })
            else TerminalTracker(spec("Click in order!", TerminalType.Order))
            t.update(b, 209)
            heard = 350
            for (now in 210L..1500L step 20) {
                t.solution(now)
                when (now) {
                    410L -> t.clicked(13, LEFT, now) // #1
                    610L -> t.clicked(12, LEFT, now) // #2
                    810L -> t.clicked(11, LEFT, now) // #3
                    1350L -> t.clicked(14, LEFT, now) // #4
                }
            }
            return t
        }
        val old = replay(serverClock = false)
        assertFalse(old.isCorrect(23, LEFT, 1522), "the old wall-clock timeout blocked the correct #5 (\"#5, next is #1\")")
        assertEquals(setOf(13), targets(old, 1522))

        val t = replay(serverClock = true)
        assertTrue(t.isCorrect(23, LEFT, 1522), "#5 is next: #1-#4 are still waiting for the silent server")
        t.clicked(23, LEFT, 1522)
        for (now in 1540L..3100L step 20) t.solution(now)
        assertFalse(t.isCorrect(13, LEFT, 2773), "#1 again would be a duplicate")
        assertEquals(setOf(22), targets(t, 2773), "#6 next, no cell came back")
        assertEquals(5, t.solution(3100).remaining)
    }

    @Test
    fun melodyNowStaysLitBrieflyPastTheExpectedStep() {
        // Run 2 cid 15, last lane: column 2 shown and targeted, 361 ms into the step, latency 166: accepted.
        val c = clock(3, 2)
        assertEquals(1, c.columnFor(361, 166))
        assertTrue(c.nowCue(2, 361, 166))
        assertFalse(c.nowCue(2, 400, 166), "more than 50 ms past the expected step")
        assertTrue(c.nowCue(1, 400, 166), "the next column by then")
    }

    @Test
    fun melodyNowNeedsOneTickPastTheStep() {
        // Run 3 cid 16, first lane: column 3 shown, target 4, 399 ms into the step, latency 138: rejected by
        // Hypixel (lands 37 ms after the expected step, inside the step's own tick). NOW must not light.
        assertFalse(clock(2, 3).nowCue(4, 399, 138))
        // Every accepted early lock of runs 2 and 3 (time into the step to latency) still lights NOW.
        for ((age, latency) in listOf(461L to 166L, 443L to 166L, 469L to 184L, 517L to 184L, 464L to 184L, 490L to 150L, 466L to 138L, 492L to 138L, 481L to 157L, 441L to 157L)) {
            assertTrue(clock(2, 3).nowCue(4, age, latency), "$age ms into the step, $latency ms latency")
        }
    }

    @Test
    fun aLateStepIsNotAPause() {
        // Run 3 cid 16 after the wrong lock: the lane resumed at column 4, stepped to 5 469 ms later, and the next
        // step arrived 680 ms after that (a late packet; the following one came 352 ms later). A click 671 ms into
        // the step on target 4 was blocked as "paused"; it would have landed on 4.
        val c = MelodyClock()
        c.observe(0, 3, 0)
        c.observe(0, 4, 1551)
        c.observe(0, 5, 2020)
        assertTrue(c.mayAccept(4, 2020 + 671, 138))
        assertTrue(c.nowCue(4, 2020 + 671, 138))
        // A real pause (wrong lock, 1-1.5 s) is still one: nothing further predicted.
        assertEquals(setOf(5), c.columnsBetween(2020 + 1200, 0, 500))
    }
}
