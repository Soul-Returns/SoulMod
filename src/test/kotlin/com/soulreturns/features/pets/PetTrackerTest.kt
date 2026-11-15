package com.soulreturns.features.pets

import com.soulreturns.data.skyblock.SkyblockRarity.LEGENDARY
import com.soulreturns.data.skyblock.SkyblockRarity.MYTHIC
import com.soulreturns.data.skyblock.TabPet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetTrackerTest {
    private var data = PetProfileData()
    private val fx = ArrayList<PetFx>()
    private val tracker = PetTracker({ data }, { data = it }).also { t -> t.fx = { fx += it } }

    private fun tab(name: String, level: Int, xp: Double?, need: Double? = null, maxed: Boolean = false, rarity: com.soulreturns.data.skyblock.SkyblockRarity = LEGENDARY) =
        TabPet(level, name, rarity, false, null, xp, need, null, maxed)

    private fun autopet(name: String, level: Int, held: PetHeld? = null, rarity: com.soulreturns.data.skyblock.SkyblockRarity = MYTHIC) =
        PetChat.Autopet(name, level, rarity, false, held)

    @Test
    fun unknownUntilAnySource() {
        tracker.restore()
        assertEquals(PetTracker.UNKNOWN, tracker.state)
        assertNull(tracker.view(0))
        tracker.onTab(null, 0)
        assertEquals(PetTracker.NONE, tracker.state)
    }

    @Test
    fun autopetParksDisagreeingTab() {
        tracker.restore()
        tracker.onTab(tab("Mole", 100, 460_686.3, maxed = true), 1_000)
        tracker.onChat(autopet("Armadillo", 100), 10_000)
        assertEquals("Armadillo", tracker.view(10_000)!!.name)
        assertEquals(PetFx.Swap("AUTOPET"), fx.last())
        // The tab still shows the old pet a moment later (0.7–2.5 s lag): parked.
        tracker.onTab(tab("Mole", 100, 460_700.0, maxed = true), 11_000)
        assertEquals("Armadillo", tracker.view(11_000)!!.name)
        // The tab catches up and agrees: applies at once.
        tracker.onTab(tab("Armadillo", 100, 1_466_603.0, maxed = true, rarity = MYTHIC), 12_500)
        assertEquals(1_466_603.0, tracker.view(12_500)!!.overflow)
        tracker.tick(16_000)
        assertEquals("Armadillo", tracker.view(16_000)!!.name)
    }

    @Test
    fun tabThatMissedTheSwapNeverOverridesChat() {
        tracker.restore()
        tracker.onTab(tab("Mole", 100, 460_686.3, maxed = true), 1_000)
        tracker.onChat(autopet("Armadillo", 100), 10_000)
        tracker.tick(20_000)
        assertEquals("Armadillo", tracker.view(20_000)!!.name)
    }

    @Test
    fun tabChangedDuringParkWinsAfterIt() {
        tracker.restore()
        tracker.onTab(tab("Mole", 100, 1.0, maxed = true), 1_000)
        tracker.onChat(autopet("Armadillo", 100), 10_000)
        // A later swap the chat never reported (e.g. a summon from the menu): the tab says Griffin.
        tracker.onTab(tab("Griffin", 87, 196_209.0, 726_700.0), 12_000)
        assertEquals("Armadillo", tracker.view(12_000)!!.name)
        tracker.tick(15_000)
        assertEquals("Griffin", tracker.view(15_000)!!.name)
    }

    @Test
    fun levelUpOnlyForTheActivePet() {
        tracker.restore()
        tracker.onTab(tab("Black Cat", 90, 1_000.0, 100_000.0, rarity = MYTHIC), 1_000)
        fx.clear()
        // Exp Share pet levelled: ignored.
        tracker.onChat(PetChat.LevelUp("Orchid Mantis", 95), 2_000)
        assertEquals(90, tracker.view(2_000)!!.level)
        assertTrue(fx.isEmpty())
        tracker.onChat(PetChat.LevelUp("Black Cat", 91), 3_000)
        assertEquals(91, tracker.view(3_000)!!.level)
        assertEquals(PetFx.LevelUp(90, 91, false), fx.single())
        // A tab refresh still showing the old level (in flight) does not take the level back down…
        tracker.onTab(tab("Black Cat", 90, 99_000.0, 100_000.0, rarity = MYTHIC), 3_500)
        assertEquals(91, tracker.view(3_500)!!.level)
        // …and the tab confirming the new level is not a second level-up.
        tracker.onTab(tab("Black Cat", 91, 50.0, 100_000.0, rarity = MYTHIC), 4_000)
        assertEquals(1, fx.size)
    }

    @Test
    fun apiNeverOverridesNewerLiveHeldItem() {
        tracker.restore()
        tracker.onTab(tab("Golden Dragon", 200, 1.0, maxed = true), 1_000)
        tracker.onChat(PetChat.Holding(PetHeld("Hephaestus Relic", '6')), 600_000)
        // API snapshot from 5 min later still says Tier Boost: minutes stale, the live read wins.
        tracker.onApi(listOf(ApiPet("gd-uuid", "GOLDEN_DRAGON", 550_000_000.0, "LEGENDARY", "PET_ITEM_TIER_BOOST", "GOLDEN_DRAGON_ANUBIS", true)), 900_000)
        assertEquals(PetHeld("Hephaestus Relic", '6'), tracker.view(900_000)!!.held)
        // The chat-only record was folded into the uuid record.
        assertEquals(setOf("gd-uuid"), data.pets.keys)
        assertEquals("gd-uuid", data.active)
    }

    @Test
    fun apiSeedsWhatNothingLiveKnows() {
        tracker.restore()
        tracker.onApi(
            listOf(
                ApiPet("a", "MOLE", 25_799_754.0, "LEGENDARY", "PET_ITEM_QUICK_CLAW", null, false),
                ApiPet("b", "GRIFFIN", 9_502_339.0, "LEGENDARY", "ANTIQUE_REMEDIES", null, true),
            ),
            5_000,
        )
        // Nothing live yet: the API's active pet shows, with its held item and exact progress.
        val v = tracker.view(5_000)!!
        assertEquals("Griffin", v.name)
        assertEquals(87, v.level)
        assertEquals(196_209.0, v.into)
        assertEquals(726_700.0, v.need)
        assertEquals("Antique Remedies", v.held?.name)
        // A live read later wins over the API's active pet.
        tracker.onTab(tab("Mole", 100, 446_524.0, maxed = true), 6_000)
        assertEquals("Mole", tracker.view(6_000)!!.name)
        assertEquals("Quick Claw", tracker.view(6_000)!!.held?.name)
    }

    @Test
    fun apiOverridesLiveValueOlderThanItsStaleness() {
        tracker.restore()
        tracker.onTab(tab("Mole", 100, 1.0, maxed = true), 0)
        tracker.onChat(PetChat.Holding(PetHeld("Quick Claw", '9')), 1_000)
        tracker.onApi(listOf(ApiPet("a", "MOLE", 25_799_754.0, "LEGENDARY", "DWARF_TURTLE_SHELMET", null, true)), 1_000 + PetTracker.API_STALE_MS + 1)
        assertEquals("Dwarf Turtle Shelmet", tracker.view(PetTracker.API_STALE_MS + 2_000)!!.held?.name)
    }

    @Test
    fun menuPetBeatsApiAndNamesTheActivePet() {
        tracker.restore()
        tracker.onApi(listOf(ApiPet("gd", "GOLDEN_DRAGON", 1.0, "LEGENDARY", null, null, false)), 1_000)
        val item = PetItem("gd", "GOLDEN_DRAGON", "Golden Dragon", 200, LEGENDARY, true, 550_815_480.0, "HEPHAESTUS_RELIC", PetHeld("Hephaestus Relic", '6'), active = true)
        tracker.onMenuPet(item, PetIconLook("minecraft:player_head", "{skin}"), equipped = false, now = 2_000)
        val v = tracker.view(2_000)!!
        assertEquals("Golden Dragon", v.name)
        assertEquals(200, v.level)
        assertTrue(v.maxed)
        assertEquals(PetHeld("Hephaestus Relic", '6'), v.held)
        assertEquals("{skin}", v.icon?.skin)
        assertEquals(550_815_480.0 - 210_255_385.0, v.overflow)
    }

    @Test
    fun summonTakesLevelFromCacheAndDespawnShowsNone() {
        tracker.restore()
        tracker.onTab(tab("Mole", 100, 1.0, maxed = true), 0)
        tracker.onTab(tab("Griffin", 87, 196_209.0, 726_700.0), 1_000)
        tracker.onChat(PetChat.Summon("Mole", LEGENDARY, false), 100_000)
        assertEquals(100, tracker.view(100_000)!!.level)
        assertEquals(PetFx.Swap("SUMMONED"), fx.last())
        tracker.onChat(PetChat.Despawn("Mole"), 200_000)
        assertEquals(PetTracker.NONE, tracker.state)
        assertTrue(data.none)
    }

    @Test
    fun restoreShowsTheStoredPetBeforeTheTab() {
        tracker.restore()
        tracker.onTab(tab("Griffin", 87, 196_209.0, 726_700.0), 0)
        // Relog: memory gone, the record (exp saved from the tab) still draws the bar.
        val fresh = PetTracker({ data }, { data = it })
        fresh.restore()
        val v = fresh.view(1_000)!!
        assertEquals("Griffin", v.name)
        assertEquals(87, v.level)
        assertEquals(196_209.0, v.into!!, 0.5)
    }

    @Test
    fun xpRateFromTabDeltas() {
        tracker.restore()
        val need = 726_700.0
        var t = 0L
        var into = 100_000.0
        repeat(20) {
            tracker.onTab(tab("Griffin", 87, into, need), t)
            into += 1_000.0
            t += 3_000L
        }
        // 1,000 XP per 3 s = 1.2M XP/h.
        assertEquals(1_200_000.0, tracker.view(t)!!.rate!!, 1.0)
        // Nothing for more than 90 s: not levelling.
        assertNull(tracker.view(t + 95_000)!!.rate)
    }

    @Test
    fun xpRateFromTotals() {
        val rate = PetXpRate()
        rate.add(0, 1_000.0)
        rate.add(3_000, 2_000.0)
        rate.add(6_000, 3_000.0)
        rate.add(9_000, 4_000.0)
        assertEquals(1_200_000.0, rate.perHour(9_000)!!, 1.0)
    }

    // Slot 21 of "(1/3) Loadouts" as captured 2026-11-11 (Hypixel M4 session): the equipped pet, reopened with the new one after a click.
    private fun loadoutPet(name: String, lore: String, petInfo: String) = PetItems.parse(name, listOf(lore), petInfo)!!

    private val goldenDragon = loadoutPet(
        "§r§7[Lvl 200] §r§6Golden Dragon§r§d ✦",
        "§r§6Held Item: §r§6Hephaestus Remedies",
        """{"type":"GOLDEN_DRAGON","active":true,"exp":5.993862468210523E8,"tier":"LEGENDARY","hideInfo":false,"heldItem":"HEPHAESTUS_REMEDIES","candyUsed":0,"skin":"GOLDEN_DRAGON_ANUBIS","uuid":"10c88cdc-513c-49bc-bda5-e1e3780fd0b8"}""",
    )
    private val jellyfish = loadoutPet(
        "§r§7[Lvl 100] §r§6Jellyfish",
        "§r§6Held Item: §r§6Hephaestus Relic",
        """{"type":"JELLYFISH","active":true,"exp":5.995642571534876E7,"tier":"LEGENDARY","hideInfo":false,"heldItem":"HEPHAESTUS_RELIC","candyUsed":0,"uuid":"3433d276-7e4d-47f9-8cc4-d3971a2500f2"}""",
    )
    private val blackCat = loadoutPet(
        "§r§7[Lvl 100] §r§dBlack Cat§r§d ✦",
        "§r§6Held Item: §r§5Unalloyed Speed",
        """{"type":"BLACK_CAT","active":true,"exp":9.281359303109491E7,"tier":"MYTHIC","hideInfo":false,"heldItem":"UNALLOYED_SPEED","candyUsed":0,"skin":"BLACK_CAT_PURRANORMAL","uuid":"08120eb4-62a6-4e98-892d-15a8f99c763d"}""",
    )

    @Test
    fun loadoutMenuSwitchesThePetWhereTheTabIsAbsent() {
        tracker.restore()
        // 13:24:14 Autopet on entering the dungeon; 13:24:18 "You equipped Loadout 2!" brings the Golden Dragon back (no chat naming it, no Pet tab widget in the Catacombs).
        val line = PetChat.parse(
            "§r§cAutopet §eequipped your §7[Lvl 100] §6Jellyfish§e! §a§lVIEW RULE",
            listOf("§r§cAutopet Rule\n\n§r§aWhen:\n§r§7You enter §r§aDungeon§r§7.\n\n§r§aEquip: §r§7[Lvl 100] §r§6Jellyfish\n§r§aHeld Item: §r§6Hephaestus Relic"),
        )!!
        tracker.onChat(line, 0)
        tracker.onMenuPet(jellyfish, null, equipped = false, now = 2_000)
        assertEquals("Jellyfish", tracker.view(2_000)!!.name)
        fx.clear()
        tracker.onMenuPet(goldenDragon, null, equipped = false, now = 4_000)
        val v = tracker.view(4_000)!!
        assertEquals("Golden Dragon", v.name)
        assertEquals(200, v.level)
        assertTrue(v.skinned)
        assertEquals("Hephaestus Remedies", v.held?.name)
        assertEquals(PetFx.Swap(null), fx.single())
    }

    @Test
    fun loadoutMenuSwitchParksTheLaggingTab() {
        tracker.restore()
        // 13:21:36 Golden Dragon (tab); click Loadout 7 -> Black Cat at 13:21:38.683; the tab still shows the dragon until 13:21:39.66.
        tracker.onTab(tab("Golden Dragon", 200, 388_000_000.0, maxed = true), 0)
        tracker.onMenuPet(blackCat, null, equipped = false, now = 1_700)
        assertEquals("Black Cat", tracker.view(1_700)!!.name)
        tracker.onTab(tab("Golden Dragon", 200, 388_000_100.0, maxed = true), 2_000)
        assertEquals("Black Cat", tracker.view(2_000)!!.name)
        tracker.onTab(tab("Black Cat", 100, 67_460_363.0, maxed = true, rarity = MYTHIC), 2_700)
        assertEquals("Black Cat", tracker.view(2_700)!!.name)
        // Loadout 4 switches back; the dragon's menu read wins and the tab agrees.
        tracker.onMenuPet(goldenDragon, null, equipped = false, now = 4_700)
        tracker.onTab(tab("Golden Dragon", 200, 388_000_200.0, maxed = true), 5_700)
        tracker.tick(20_000)
        assertEquals("Golden Dragon", tracker.view(20_000)!!.name)
    }

    @Test
    fun petsMenuReadOfAnInactivePetDoesNotSwitch() {
        tracker.restore()
        tracker.onMenuPet(goldenDragon, null, equipped = false, now = 0)
        // The Pets menu lists other pets with petInfo.active false.
        val hedgehog = PetItems.parse(
            "§r§e⭐ §r§7[Lvl 100] §r§6Hedgehog",
            emptyList(),
            """{"type":"HEDGEHOG","active":false,"exp":2.0E7,"tier":"LEGENDARY","uuid":"hh"}""",
        )!!
        tracker.onMenuPet(hedgehog, null, equipped = false, now = 1_000)
        assertEquals("Golden Dragon", tracker.view(1_000)!!.name)
    }
}
