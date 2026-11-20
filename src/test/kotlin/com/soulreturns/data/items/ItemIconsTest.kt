package com.soulreturns.data.items

import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons.ItemLook
import net.minecraft.SharedConstants
import net.minecraft.core.component.DataComponents
import net.minecraft.server.Bootstrap
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemIconsTest {
    private val packModel = "hypixel_skyblock:item/uncategorized/refined_diamond"
    private val loaded: (String) -> Boolean = { it == packModel }
    private val noPack: (String) -> Boolean = { false }
    private val skin = "{\"id\":[1,2,3,4],\"properties\":[]}"

    @BeforeTest
    fun boot() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        com.soulreturns.features.profileviewer.SectionBuildTest.bindItemComponents
    }

    @Test
    fun legacyDataValuesPickTheModernVariant() {
        assertEquals("minecraft:spruce_log", LegacyMaterials.itemId("LOG", 1))
        assertEquals("minecraft:dark_oak_log", LegacyMaterials.itemId("LOG_2", 1))
        assertEquals("minecraft:oak_log", LegacyMaterials.itemId("LOG", 9))
        assertEquals("minecraft:red_sand", LegacyMaterials.itemId("SAND", 1))
        assertEquals("minecraft:blue_orchid", LegacyMaterials.itemId("RED_ROSE", 1))
        assertEquals("minecraft:rose_bush", LegacyMaterials.itemId("DOUBLE_PLANT", 4))
        assertEquals("minecraft:cocoa_beans", LegacyMaterials.itemId("INK_SACK", 3))
        assertEquals("minecraft:mycelium", LegacyMaterials.itemId("MYCEL"))
        assertEquals("minecraft:player_head", LegacyMaterials.itemId("SKULL_ITEM", 3))
    }

    @Test
    fun aSeenLookWinsOverEverything() {
        val seen = ItemLook("minecraft:paper", model = packModel)
        assertEquals(seen, ItemIcons.choose(seen, loaded, "hypixel_skyblock:item/other", "minecraft:diamond", null, false))
        val vanilla = ItemLook("minecraft:diamond_pickaxe", model = "minecraft:stick")
        assertEquals(vanilla, ItemIcons.choose(vanilla, noPack, null, null, null, false))
    }

    @Test
    fun aSeenLookWhosePackModelIsNotLoadedFallsThrough() {
        val seen = ItemLook("minecraft:paper", model = packModel)
        assertEquals(ItemLook("minecraft:diamond"), ItemIcons.choose(seen, noPack, null, "minecraft:diamond", null, false))
        assertNull(ItemIcons.choose(seen, noPack, null, null, null, false))
    }

    @Test
    fun thePackModelGoesOnTheCatalogMaterialOrPaper() {
        assertEquals(ItemLook("minecraft:diamond", model = packModel, glint = true), ItemIcons.choose(null, loaded, packModel, "minecraft:diamond", null, true))
        assertEquals(ItemLook("minecraft:paper", model = packModel), ItemIcons.choose(null, loaded, packModel, null, null, false))
    }

    @Test
    fun theCatalogMaterialComesLastAndAHeadNeedsItsSkin() {
        assertEquals(ItemLook("minecraft:cocoa_beans"), ItemIcons.choose(null, noPack, null, "minecraft:cocoa_beans", null, false))
        assertEquals(ItemLook("minecraft:player_head", skin = skin), ItemIcons.choose(null, noPack, null, "minecraft:player_head", skin, false))
        assertNull(ItemIcons.choose(null, noPack, null, "minecraft:player_head", null, false), "a bare player head would draw Steve")
        assertNull(ItemIcons.choose(null, noPack, null, null, null, false))
    }

    @Test
    fun aTextureHashBecomesATexturedHead() {
        val hash = "a074a7bd976fe6aba1624161793be547d54c835cf422243a851ba09d1e650553"
        val json = assertNotNull(HeadSkins.profileJson(hash.uppercase()))
        val stack = InventoryButtonIcons.build(ItemLook("minecraft:player_head", skin = json))
        val profile = assertNotNull(stack.get(DataComponents.PROFILE), "the profile JSON must parse back")
        val value = profile.partialProfile().properties().get("textures").single().value()
        assertTrue(String(Base64.getDecoder().decode(value)).contains("textures.minecraft.net/texture/$hash"))
        assertEquals(json, HeadSkins.profileJson(hash), "one id per texture")
    }

    @Test
    fun malformedHashesMakeNoHead() {
        for (bad in listOf("", "abc", "not-a-hash-at-all-but-long-enough", "g".repeat(64), "a".repeat(129))) {
            assertNull(HeadSkins.profileJson(bad), bad)
        }
    }
}
