package com.soulreturns.features.islandchests

import com.soulreturns.data.skyblock.SkyblockRarity
import kotlin.test.Test
import kotlin.test.assertEquals

class IslandChestItemColorTest {
    @Test
    fun petLevelPrefixDoesNotHideRarity() {
        val epic = StoredChestItem("minecraft:player_head", "PET", "§7[Lvl 1] §5Bal", count = 1)
        val legendary = epic.copy(displayNameRaw = "§7[Lvl 1] §6Bal")
        assertEquals(SkyblockRarity.EPIC.color, epic.searchColor())
        assertEquals(SkyblockRarity.LEGENDARY.color, legendary.searchColor())
    }

    @Test
    fun petRarityFooterWorksWhenCachedNameHasNoColor() {
        val pet = StoredChestItem("minecraft:player_head", "PET", "[Lvl 1] Bal", listOf("§5§lEPIC PET"), 1)
        assertEquals(SkyblockRarity.EPIC.color, pet.searchColor())
        assertEquals(SkyblockRarity.LEGENDARY.color, pet.copy(loreLines = listOf("§6§lLEGENDARY PET")).searchColor())
    }

    @Test
    fun descriptionRarityDoesNotOverrideNameColor() {
        val item = StoredChestItem("minecraft:stone", null, "§9Stone", listOf("§7Summons a LEGENDARY companion"), 1)
        assertEquals(SkyblockRarity.RARE.color, item.searchColor())
    }
}
