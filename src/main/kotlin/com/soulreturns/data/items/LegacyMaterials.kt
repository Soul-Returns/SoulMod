package com.soulreturns.data.items

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import java.util.Locale

/**
 * The modern item for a `/items` `material` (Hypixel's API still names Bukkit 1.8 materials: `SKULL_ITEM`,
 * `INK_SACK`, `GOLD_SWORD`). Only vanilla's own renames live here; a name that maps to nothing resolves to null
 * and callers keep their placeholder. [damage] is the legacy data value where an id carries one (`INK_SACK:4`).
 */
object LegacyMaterials {
    private val RENAMED = mapOf(
        "SKULL_ITEM" to "player_head", "SKULL" to "skeleton_skull", "STAINED_CLAY" to "white_terracotta", "HARD_CLAY" to "terracotta",
        "WOOL" to "white_wool", "CARPET" to "white_carpet", "STAINED_GLASS" to "white_stained_glass", "STAINED_GLASS_PANE" to "white_stained_glass_pane",
        "THIN_GLASS" to "glass_pane", "RED_ROSE" to "poppy", "YELLOW_FLOWER" to "dandelion", "EMPTY_MAP" to "map", "SULPHUR" to "gunpowder",
        "NETHER_STALK" to "nether_wart", "SEEDS" to "wheat_seeds", "LOG" to "oak_log", "LOG_2" to "acacia_log", "LEAVES" to "oak_leaves",
        "LEAVES_2" to "acacia_leaves", "SAPLING" to "oak_sapling", "WOOD" to "oak_planks", "WATCH" to "clock", "CLAY_BRICK" to "brick",
        "NETHER_BRICK_ITEM" to "nether_brick", "FIREWORK" to "firework_rocket", "FIREWORK_CHARGE" to "firework_star", "EXP_BOTTLE" to "experience_bottle",
        "BOOK_AND_QUILL" to "writable_book", "CARROT_ITEM" to "carrot", "POTATO_ITEM" to "potato", "RAW_BEEF" to "beef", "RAW_CHICKEN" to "chicken",
        "PORK" to "porkchop", "GRILLED_PORK" to "cooked_porkchop", "COOKED_FISH" to "cooked_cod", "SNOW_BALL" to "snowball", "ENDER_STONE" to "end_stone",
        "HUGE_MUSHROOM_1" to "brown_mushroom_block", "HUGE_MUSHROOM_2" to "red_mushroom_block", "MELON" to "melon_slice", "MELON_BLOCK" to "melon",
        "IRON_BARDING" to "iron_horse_armor", "GOLD_BARDING" to "golden_horse_armor", "DIAMOND_BARDING" to "diamond_horse_armor", "IRON_FENCE" to "iron_bars",
        "WEB" to "cobweb", "WORKBENCH" to "crafting_table", "ENCHANTMENT_TABLE" to "enchanting_table", "MOB_SPAWNER" to "spawner", "LONG_GRASS" to "short_grass",
        "DOUBLE_PLANT" to "sunflower", "BED" to "red_bed", "SIGN" to "oak_sign", "BOAT" to "oak_boat", "SMOOTH_BRICK" to "stone_bricks", "FENCE" to "oak_fence",
        "WOOD_STEP" to "oak_slab", "STEP" to "stone_slab", "LEASH" to "lead", "COMMAND" to "command_block", "EYE_OF_ENDER" to "ender_eye",
        "ENDER_PORTAL_FRAME" to "end_portal_frame", "SPECKLED_MELON" to "glistering_melon_slice", "MUSHROOM_SOUP" to "mushroom_stew",
        "IRON_PLATE" to "heavy_weighted_pressure_plate", "GOLD_PLATE" to "light_weighted_pressure_plate", "STONE_PLATE" to "stone_pressure_plate",
        "WOOD_PLATE" to "oak_pressure_plate", "WOOD_DOOR" to "oak_door", "TRAP_DOOR" to "oak_trapdoor", "PISTON_BASE" to "piston",
        "PISTON_STICKY_BASE" to "sticky_piston", "REDSTONE_TORCH_ON" to "redstone_torch", "DIODE" to "repeater", "REDSTONE_COMPARATOR" to "comparator",
        "RAILS" to "rail", "CAULDRON_ITEM" to "cauldron", "BREWING_STAND_ITEM" to "brewing_stand", "FLOWER_POT_ITEM" to "flower_pot", "COCOA" to "cocoa_beans",
        "GOLD_RECORD" to "music_disc_13", "GREEN_RECORD" to "music_disc_cat", "QUARTZ_ORE" to "nether_quartz_ore", "MONSTER_EGG" to "pig_spawn_egg",
        "STORAGE_MINECART" to "chest_minecart", "POWERED_MINECART" to "furnace_minecart", "NOTE_BLOCK" to "note_block", "JUKEBOX" to "jukebox",
    )
    private val DYES = listOf("ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye", "cyan_dye", "light_gray_dye",
        "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal")
    private val FISH = listOf("cod", "salmon", "tropical_fish", "pufferfish")

    /** `minecraft:…` registry id for [material], or null when it names no item. */
    fun itemId(material: String?, damage: Int = 0): String? {
        if (material.isNullOrEmpty()) return null
        val upper = material.uppercase(Locale.ROOT)
        val name = if (upper == "INK_SACK") DYES.getOrNull(damage) ?: DYES[0]
            else if (upper == "RAW_FISH") FISH.getOrNull(damage) ?: FISH[0]
            else RENAMED[upper] ?: legacyTool(upper) ?: upper.lowercase(Locale.ROOT)
        val key = Identifier.tryParse("minecraft:$name") ?: return null
        return if (BuiltInRegistries.ITEM.containsKey(key)) key.toString() else null
    }

    /** `WOOD_SWORD` → `wooden_sword`, `GOLD_HELMET` → `golden_helmet`, `IRON_SPADE` → `iron_shovel`. */
    private fun legacyTool(name: String): String? {
        val parts = name.split('_', limit = 2)
        if (parts.size != 2) return null
        val material = if (parts[0] == "WOOD") "wooden" else if (parts[0] == "GOLD" && parts[1] !in GOLD_KEEP) "golden" else return spade(name)
        return material + "_" + (if (parts[1] == "SPADE") "shovel" else parts[1].lowercase(Locale.ROOT))
    }

    private val GOLD_KEEP = setOf("INGOT", "NUGGET", "BLOCK", "ORE", "RECORD", "PLATE", "BARDING")

    private fun spade(name: String): String? = if (name.endsWith("_SPADE")) name.removeSuffix("_SPADE").lowercase(Locale.ROOT) + "_shovel" else null
}
