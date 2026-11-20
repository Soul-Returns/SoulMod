package com.soulreturns.features.pets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetHeadsTest {
    private val base = "1".repeat(64)
    private val mythic = "2".repeat(64)
    private val skinItem = "3".repeat(64)
    private val skinNeu = "4".repeat(64)
    private val heads = mapOf("FLYING_FISH" to base, "FLYING_FISH;5" to mythic, "PET_SKIN_NEU_ONLY" to skinNeu, "BROKEN" to "zz")
    private val catalog = mapOf("PET_SKIN_PHOENIX_ICE" to skinItem)

    private fun texture(type: String, tier: String?, skin: String?) = PetHeads.texture(type, tier, skin, catalog::get, heads)

    @Test
    fun aKnownSkinWinsCatalogFirstThenNeu() {
        assertEquals(skinItem, texture("FLYING_FISH", "LEGENDARY", "PHOENIX_ICE"))
        assertEquals(skinItem, texture("FLYING_FISH", "LEGENDARY", "PET_SKIN_PHOENIX_ICE"))
        assertEquals(skinNeu, texture("FLYING_FISH", "LEGENDARY", "NEU_ONLY"))
    }

    @Test
    fun anUnknownSkinFallsBackToTheBasePet() {
        assertEquals(base, texture("FLYING_FISH", "LEGENDARY", "NOT_IN_ANY_CATALOG"))
    }

    @Test
    fun aTierWithItsOwnHeadUsesIt() {
        assertEquals(mythic, texture("FLYING_FISH", "MYTHIC", null))
        assertEquals(base, texture("FLYING_FISH", "rare", null))
        assertEquals(base, texture("FLYING_FISH", null, null))
    }

    @Test
    fun anUnknownPetOrABrokenHashHasNoHead() {
        assertNull(texture("SOME_NEW_PET", "EPIC", null))
        assertNull(texture("BROKEN", "EPIC", null))
    }

    @Test
    fun aMenuIconCountsOnlyWhenItIsATexturedHeadOrALoadedModel() {
        val model = "hypixel_skyblock:item/pets/x"
        assertTrue(PetHeads.usable(PetIconLook("minecraft:player_head", skin = "{}"), { false }))
        assertFalse(PetHeads.usable(PetIconLook("minecraft:player_head"), { false }), "a bare player head is Steve")
        assertFalse(PetHeads.usable(PetIconLook("minecraft:paper", model = model), { false }))
        assertTrue(PetHeads.usable(PetIconLook("minecraft:paper", model = model), { it == model }))
        assertFalse(PetHeads.usable(null, { true }))
    }
}
