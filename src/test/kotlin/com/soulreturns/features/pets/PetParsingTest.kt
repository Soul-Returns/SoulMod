package com.soulreturns.features.pets

import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.data.skyblock.TabWidgetParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Strings captured with the dev bridge (2026-11-07/08, `run/soul-dev/captures/`), research §1–§3. */
class PetParsingTest {
    @Test
    fun autopetWithHeldItemHover() {
        val line = PetChat.parse(
            "§r§cAutopet §eequipped your §7[Lvl 100] §dFlying Fish§e! §a§lVIEW RULE",
            listOf("§r§cAutopet Rule\n\n§r§aWhen:\n§r§7You throw a fishing hook.\n\n§r§aEquip: §r§e⭐ §r§7[Lvl 100] §r§dFlying Fish\n§r§aHeld Item: §r§6Burnt Texts\n\n§r§eExcept if:\n§r§7You are in §r§aDungeon§r§7."),
        )
        assertEquals(PetChat.Autopet("Flying Fish", 100, SkyblockRarity.MYTHIC, false, PetHeld("Burnt Texts", '6')), line)
    }

    @Test
    fun autopetSkinStarWithoutReset() {
        val line = PetChat.parse("§r§cAutopet §eequipped your §7[Lvl 200] §6Golden Dragon§d ✦§e! §a§lVIEW RULE") as PetChat.Autopet
        assertEquals("Golden Dragon", line.name)
        assertEquals(200, line.level)
        assertEquals(SkyblockRarity.LEGENDARY, line.rarity)
        assertTrue(line.skinned)
        assertNull(line.held)
    }

    @Test
    fun autopetRuleWithoutHeldItem() {
        val line = PetChat.parse(
            "§r§cAutopet §eequipped your §7[Lvl 100] §6Mole§e! §a§lVIEW RULE",
            listOf("§r§cAutopet Rule\n\n§r§aWhen:\n§r§7You throw a fishing hook.\n\n§r§aEquip: §r§7[Lvl 100] §r§6Mole\n\n§r§eExcept if:\n§r§7You have a §r§aMole §r§7pet equipped.\n§r§7You're NOT in §r§aCrystal Hollows§r§7."),
        ) as PetChat.Autopet
        assertEquals("Mole", line.name)
        assertNull(line.held)
    }

    @Test
    fun summonLevelUpHolding() {
        assertEquals(PetChat.Summon("Mole", SkyblockRarity.LEGENDARY, false), PetChat.parse("§r§aYou summoned your §r§6Mole§r§a!"))
        assertEquals(PetChat.Summon("Rabbit", SkyblockRarity.MYTHIC, true), PetChat.parse("§aYou summoned your §r§dRabbit§r§9 ✦§r§a!"))
        assertEquals(PetChat.LevelUp("Orchid Mantis", 95), PetChat.parse("§r§aYour §r§6Orchid Mantis §r§aleveled up to level §r§995§r§a!"))
        assertEquals(PetChat.Holding(PetHeld("Hephaestus Relic", '6')), PetChat.parse("§r§aYour pet is now holding §r§6Hephaestus Relic§r§a."))
        assertEquals(PetChat.Despawn("Mole"), PetChat.parse("§r§aYou despawned your §r§6Mole§r§a!"))
    }

    @Test
    fun noiseIsIgnored() {
        assertNull(PetChat.parse("§r§cYou have despawned your monsters!"))
        assertNull(PetChat.parse("§r§cYour Plasmaflux Power Orb despawned."))
        assertNull(PetChat.parse("§aYour Phoenix Pet saved you from certain death!"))
    }

    @Test
    fun tabPetMaxedOverflow() {
        val read = TabWidgetParser.parse(
            listOf("§r§e§lPet:", "§r §r§7[Lvl 200] §r§6Golden Dragon§r§d ✦", "§r §r§6+§r§e365,447,410 XP"),
        )
        assertTrue(read.petSeen)
        val pet = read.pet!!
        assertEquals("Golden Dragon", pet.name)
        assertEquals(200, pet.level)
        assertEquals(SkyblockRarity.LEGENDARY, pet.rarity)
        assertTrue(pet.skinned)
        assertTrue(pet.maxed)
        assertEquals(365_447_410.0, pet.xp)

        val mole = TabWidgetParser.parse(listOf("§r§e§lPet:", "§r §r§7[Lvl 100] §r§6Mole", "§r §r§6+§r§e460,686.3 XP")).pet!!
        assertEquals(460_686.3, mole.xp)
        assertFalse(mole.skinned)
    }

    @Test
    fun tabPetProgressAndNone() {
        // Non-max form: only SkyHanni's test strings show it (not captured yet).
        val pet = TabWidgetParser.parse(listOf("§e§lPet:", " §7[Lvl 87] §6Griffin", " §e33,915§6/§e179.7k XP §6(18.9%)")).pet!!
        assertFalse(pet.maxed)
        assertEquals(33_915.0, pet.xp)
        assertEquals(179_700.0, pet.xpForNext!!, 0.01)
        assertEquals(18.9, pet.percent)

        val none = TabWidgetParser.parse(listOf("§e§lPet:", " §7No pet selected"))
        assertTrue(none.petSeen)
        assertNull(none.pet)
    }

    @Test
    fun menuPetItem() {
        val item = PetItems.parse(
            "§r§e⭐ §r§7[Lvl 200] §r§6Golden Dragon§r§d ✦",
            listOf("§r§8Combat Pet, Anubis Skin", "§r§6Held Item: §r§6Hephaestus Relic", "§r§7Increases all pet stats §r§7by §r§a50%§r§7.", "", "§r§b§lMAX LEVEL", "§r§8▸ 550,815,480 XP"),
            """{"type":"GOLDEN_DRAGON","active":true,"exp":5.508154800877969E8,"tier":"LEGENDARY","heldItem":"HEPHAESTUS_RELIC","candyUsed":0,"skin":"GOLDEN_DRAGON_ANUBIS","uuid":"0f3a","uniqueId":"x"}""",
        )!!
        assertEquals("Golden Dragon", item.name)
        assertEquals(200, item.level)
        assertEquals(SkyblockRarity.LEGENDARY, item.rarity)
        assertTrue(item.skinned)
        assertTrue(item.active)
        assertEquals("0f3a", item.uuid)
        assertEquals("HEPHAESTUS_RELIC", item.heldId)
        assertEquals(PetHeld("Hephaestus Relic", '6'), item.held)
        assertEquals(550_815_480.0877969, item.exp!!, 0.001)
    }

    @Test
    fun titles() {
        assertEquals("Hephaestus Relic", PetItems.titleOf("HEPHAESTUS_RELIC"))
        assertEquals("Tier Boost", PetItems.titleOf("PET_ITEM_TIER_BOOST"))
        assertEquals("Golden Dragon", PetItems.titleOf("GOLDEN_DRAGON"))
    }
}
