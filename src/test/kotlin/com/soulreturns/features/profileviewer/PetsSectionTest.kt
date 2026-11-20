package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.autopetRuleCount
import com.soulreturns.data.hypixel.katTypesSacrificed
import com.soulreturns.data.hypixel.petMarketKey
import com.soulreturns.data.hypixel.petScore
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.pets.ApiPet
import com.soulreturns.features.pets.PetItems
import com.soulreturns.features.pets.PetLevels
import com.soulreturns.features.pets.PetState
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.profileviewer.gui.sections.PetsSection
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetsSectionTest {
    private fun rows(base: String, fragment: String, live: (ApiPet) -> Double? = { null }): List<PetsSection.PetRow> {
        val member = SpvGearFixtures.context(base, fragment).member
        return PetsSection.rows(PetState.apiPets(member), live) { PetItems.titleOf(it) }
    }

    // ------------------------------------------------------------------ data

    @Test
    fun `pet score, Kat and autopet read the fixtures`() {
        val sam = SpvGearFixtures.context("sam", "pets-sam").member
        assertEquals(1534L, sam.petScore)
        assertEquals(7, sam.katTypesSacrificed)
        assertEquals(3, sam.autopetRuleCount)
        val joe = SpvGearFixtures.context("joe", "pets-joe").member
        assertEquals(210L, joe.petScore)
        assertEquals(0, joe.katTypesSacrificed)
        assertEquals(0, joe.autopetRuleCount)
        val empty = SkyblockProfileMember(JsonObject(emptyMap()))
        assertNull(empty.petScore)
        assertEquals(0, empty.katTypesSacrificed)
    }

    @Test
    fun `market keys use the base tier and the level band`() {
        assertEquals("GOLDEN_DRAGON;4+200", petMarketKey("GOLDEN_DRAGON", "LEGENDARY", 200))
        assertEquals("ENDER_DRAGON;4+100", petMarketKey("ENDER_DRAGON", "LEGENDARY", 100))
        assertEquals("RABBIT;0", petMarketKey("RABBIT", "COMMON", 12))
        assertEquals("GRIFFIN;3", petMarketKey("GRIFFIN", "EPIC", 80))
        assertNull(petMarketKey("RABBIT", "NOPE", 1))
        assertNull(petMarketKey("RABBIT", null, 1))
    }

    // ------------------------------------------------------------------ rows

    @Test
    fun `levels come from the Pet HUD's XP math`() {
        val r = rows("sam", "pets-sam").associateBy { it.name }
        assertEquals(6, r.size)
        val golden = r.getValue("Golden Dragon")
        assertEquals(200, golden.level)
        assertTrue(golden.maxed)
        assertEquals(200, golden.maxLevel)
        assertTrue(golden.active)
        assertEquals("Minos Relic", golden.held)
        assertEquals("Dragon Golden Neon", golden.skin)
        val ender = r.getValue("Ender Dragon")
        assertTrue(ender.maxed)
        assertEquals(646_770.0, ender.overflow, 0.5)
        assertEquals("Lucky Clover", ender.held)
        val black = r.getValue("Black Cat")
        assertTrue(black.maxed && black.level == 100)
        assertNull(black.held)
        val rabbit = r.getValue("Rabbit")
        val expected = PetLevels.levelOf(1200.0, SkyblockRarity.COMMON, "Rabbit")
        assertEquals(expected.level, rabbit.level)
        assertEquals(expected.into, rabbit.into, 0.001)
        assertFalse(rabbit.maxed)
    }

    @Test
    fun `a Tier Boost shows one rarity up and levels against it`() {
        val griffin = rows("sam", "pets-sam").first { it.name == "Griffin" }
        assertEquals(SkyblockRarity.LEGENDARY, griffin.rarity)
        assertEquals(PetLevels.levelOf(3_000_000.0, SkyblockRarity.LEGENDARY, "Griffin").level, griffin.level)
        assertEquals("Tier Boost", griffin.held)
        assertEquals("GRIFFIN;3", griffin.priceKey)
    }

    @Test
    fun `sorting follows the mock`() {
        val all = rows("sam", "pets-sam")
        assertEquals(listOf("Golden Dragon", "Ender Dragon", "Black Cat"), PetsSection.sorted(all, "level", "").take(3).map { it.name })
        assertEquals("Griffin", PetsSection.sorted(all, "level", "")[3].name)
        val byRarity = PetsSection.sorted(all, "rarity", "").map { it.name }
        assertEquals(listOf("Golden Dragon", "Ender Dragon", "Black Cat", "Griffin"), byRarity.take(4))
        assertEquals("Silverfish", byRarity[4])
        assertEquals("Rabbit", byRarity[5])
        assertEquals(listOf("Black Cat", "Ender Dragon", "Golden Dragon", "Griffin", "Rabbit", "Silverfish"), PetsSection.sorted(all, "name", "").map { it.name })
        assertEquals(listOf("Golden Dragon", "Ender Dragon"), PetsSection.sorted(all, "level", "dragon").map { it.name })
        assertTrue(PetsSection.sorted(all, "level", "zzz").isEmpty())
    }

    @Test
    fun `live XP only counts when it is ahead of the API`() {
        val ahead = rows("joe", "pets-joe") { if (it.type == "ENDER_DRAGON") 9_500_000.0 else null }
        val dragon = ahead.first { it.name == "Ender Dragon" }
        assertTrue(dragon.live)
        assertEquals(9_500_000.0, dragon.xp, 0.0)
        assertFalse(ahead.first { it.name == "Rabbit" }.live)
        val behind = rows("joe", "pets-joe") { 1.0 }
        assertTrue(behind.none { it.live })
        assertEquals(9_000_000.0, behind.first { it.name == "Ender Dragon" }.xp, 0.0)
    }

    // ------------------------------------------------------------------ section

    private fun render(base: String, fragment: String, w: Int = 969, rows: List<PetsSection.PetRow>? = null, sort: String = "level", query: String = ""): Pair<SpvGearFixtures.Recorder, Int> {
        val ctx = SpvGearFixtures.context(base, fragment)
        val ui = Ui(PetsSection.id, ViewState())
        PetsSection.render(ctx, ui, rows ?: rows(base, fragment), sort, query)
        return SpvGearFixtures.paint(ui, w)
    }

    @Test
    fun `sam's pets page shows the hero, the facts and every pet`() {
        val text = render("sam", "pets-sam").first.all()
        assertTrue("Active pet" in text && "Pet facts" in text, text)
        assertTrue("Golden Dragon" in text && "Lv 200" in text, text)
        assertTrue("Max level" in text, text)
        assertTrue("Holding Minos Relic" in text && "Dragon Golden Neon skin" in text, text)
        assertTrue("6 · 3 max level" in text, text)
        assertTrue("1,534" in text, text)
        assertTrue("7 of 10 types" in text, text)
        assertTrue("Autopet rules" in text, text)
        assertTrue("ACTIVE" in text, text)
        for (name in listOf("Ender Dragon", "Black Cat", "Griffin", "Rabbit", "Silverfish")) assertTrue(name in text, name)
    }

    @Test
    fun `a pet without an item says so, and an unfinished one shows its progress`() {
        val text = render("joe", "pets-joe").first.all()
        assertTrue("Ender Dragon" in text && "No pet item" in text, text)
        assertTrue(Regex("Lv \\d+ of 100").containsMatchIn(text), text)
        assertTrue("XP to level" in text, text)
        assertTrue("210" in text, text)
    }

    @Test
    fun `no active pet`() {
        val member = SpvGearFixtures.context("joe", "pets-joe").member
        val quiet = PetState.apiPets(member).map { ApiPet(it.uuid, it.type, it.exp, it.tier, it.heldItem, it.skin, active = false) }
        val text = render("joe", "pets-joe", rows = PetsSection.rows(quiet, { null }) { PetItems.titleOf(it) }).first.all()
        assertTrue("No active pet" in text, text)
        assertFalse("ACTIVE" in text)
    }

    @Test
    fun `the filter field shows its match count inside, only while filtering`() {
        val text = render("sam", "pets-sam", query = "dragon").first.all()
        assertTrue("Level Rarity Name 2 " in text, text)
        assertFalse("Level Rarity Name 2 " in render("sam", "pets-sam").first.all())
    }

    @Test
    fun `the live chip appears on the hero only for a live reading`() {
        val member = SpvGearFixtures.context("joe", "pets-joe").member
        val live = PetsSection.rows(PetState.apiPets(member), { 9_500_000.0 }) { PetItems.titleOf(it) }
        assertTrue("SOUL · LIVE XP" in render("joe", "pets-joe", rows = live).first.all())
        assertFalse("SOUL · LIVE XP" in render("joe", "pets-joe").first.all())
    }

    @Test
    fun `builder keeps its pets with the inventory api off`() {
        val text = render("builder", "pets-builder").first.all()
        assertTrue("Rabbit" in text && "8" in text, text)
        assertTrue("0 of 10 types" in text, text)
    }

    @Test
    fun `the section reads the fixture end to end and narrower means taller`() {
        val ctx = SpvGearFixtures.context("sam", "pets-sam")
        assertEquals("6", PetsSection.badge(ctx))
        val ui = Ui(PetsSection.id, ViewState())
        PetsSection.build(ctx, ui)
        val (canvas, wide) = SpvGearFixtures.paint(ui, 969)
        assertTrue("Golden Dragon" in canvas.all())
        val ui2 = Ui(PetsSection.id, ViewState())
        PetsSection.build(ctx, ui2)
        val narrow = SpvGearFixtures.paint(ui2, 476).second
        assertTrue(narrow > wide, "narrow $narrow wide $wide")
    }
}
