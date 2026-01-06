package com.soulreturns.features.itemhighlight

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.soulreturns.Soul
import com.soulreturns.config.config
import com.soulreturns.util.DebugLogger
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.nio.file.Files

/**
 * Represents a group of items to highlight with a specific color
 */
data class HighlightGroup(
    @SerializedName("name")
    val name: String,

    @SerializedName("color")
    val color: String,  // Format: "a:r:g:b:chroma"

    @SerializedName("items")
    val items: List<String>
)

/**
 * Manages loading and accessing item highlight groups from JSON files
 */
object HighlightManager {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val highlightGroups = mutableListOf<HighlightGroup>()
    private val itemToColorMap = mutableMapOf<String, Int>()
    private val customItems = mutableSetOf<String>()

    private val configDir: File by lazy {
        val baseDir = FabricLoader.getInstance().configDir.toFile()
        File(baseDir, "soul/highlight")
    }

    private val builtinDir: File by lazy {
        File(configDir, "builtin").apply {
            if (!exists()) mkdirs()
        }
    }

    private val customDir: File by lazy {
        File(configDir, "custom").apply {
            if (!exists()) {
                mkdirs()
                createExampleCustomFiles()
            }
        }
    }

    /**
     * Loads all highlight group JSON files from the config directory
     */
    fun loadGroups() {
        highlightGroups.clear()
        itemToColorMap.clear()
        customItems.clear()

        // Always ensure builtin files exist
        createBuiltinFiles()

        // Load builtin highlights
        builtinDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            loadGroupFromFile(file, isCustom = false)
        }

        // Load custom highlights
        customDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            loadGroupFromFile(file, isCustom = true)
        }

        DebugLogger.logFeatureEvent("Loaded ${highlightGroups.size} highlight groups with ${itemToColorMap.size} total items")
        Soul.getLogger()?.info("Loaded ${highlightGroups.size} highlight groups with ${itemToColorMap.size} total items")
    }

    private fun loadGroupFromFile(file: File, isCustom: Boolean) {
        try {
            val json = Files.readString(file.toPath())
            val group = gson.fromJson(json, HighlightGroup::class.java)

            if (group != null && group.items.isNotEmpty()) {
                highlightGroups.add(group)
                DebugLogger.logFeatureEvent("Loaded ${if (isCustom) "custom" else "builtin"} highlight group: ${group.name} with ${group.items.size} items")

                // Parse color and map each item to it
                val color = parseColor(group.color)
                group.items.forEach { itemId ->
                    itemToColorMap[itemId] = color
                    if (isCustom) {
                        customItems.add(itemId)
                    }
                }
            }
        } catch (e: Exception) {
            Soul.getLogger()?.warn("Failed to load highlight group from ${file.name}: ${e.message}")
        }
    }

    // Pest equipment items
    private val pestEquipment = setOf(
        "PESTHUNTERS_NECKLACE",
        "PESTHUNTERS_GLOVES",
        "PESTHUNTERS_BELT",
        "PESTHUNTERS_CLOAK"
    )

    // Farming equipment items
    private val farmingEquipment = setOf(
        "LOTUS_BRACELET",
        "LOTUS_NECKLACE",
        "LOTUS_BELT",
        "LOTUS_CLOAK",
        "ZORROS_CAPE",
        "BLOSSOM_BRACELET",
        "BLOSSOM_NECKLACE",
        "BLOSSOM_BELT",
        "BLOSSOM_CLOAK"
    )

    /**
     * Gets the color for a specific Skyblock item ID
     * @return ARGB color integer, or null if item is not in any group
     */
    fun getColorForItem(skyblockId: String): Int? {
        val renderConfig = config.renderCategory

        // Check if item is in pest equipment group
        if (skyblockId in pestEquipment) {
            if (!renderConfig.highlightSubCategory.highlightPestEquipment) return null
            // If using pest vest, don't highlight the cloak
            if (skyblockId == "PESTHUNTERS_CLOAK" && renderConfig.highlightSubCategory.usePestVest) return null
        }

        // Check if item is pest vest (only highlight if toggle is on and usePestVest is true)
        if (skyblockId == "PEST_VEST") {
            if (!renderConfig.highlightSubCategory.highlightPestEquipment || !renderConfig.highlightSubCategory.usePestVest) return null
        }

        // Check if item is in farming equipment group
        if (skyblockId in farmingEquipment) {
            if (!renderConfig.highlightSubCategory.highlightFarmingEquipment) return null
        }

        // Check if item is a custom item
        if (skyblockId in customItems) {
            if (!renderConfig.highlightSubCategory.highlightCustomItems) return null
        }

        return itemToColorMap[skyblockId]
    }

    /**
     * Parses MoulConfig color format to ARGB integer
     * Format: "a:r:g:b:chroma" where each component is 0-255
     */
    private fun parseColor(colorString: String): Int {
        return try {
            val parts = colorString.split(":")
            if (parts.size >= 4) {
                val a = parts[0].toInt()
                val r = parts[1].toInt()
                val g = parts[2].toInt()
                val b = parts[3].toInt()
                (a shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                0xFFFF0000.toInt() // Default: opaque red
            }
        } catch (e: Exception) {
            0xFFFF0000.toInt() // Default: opaque red
        }
    }

    /**
     * Creates builtin highlight group files (always persisted)
     */
    private fun createBuiltinFiles() {
        // Pest Equipment (orange)
        val pestGroup = HighlightGroup(
            name = "Pest Equipment",
            color = "255:255:165:0:0", // Orange
            items = listOf(
                "PESTHUNTERS_NECKLACE",
                "PESTHUNTERS_GLOVES",
                "PESTHUNTERS_BELT",
                "PESTHUNTERS_CLOAK",
                "PEST_VEST"
            )
        )

        // Farming Equipment (green)
        val farmingGroup = HighlightGroup(
            name = "Farming Equipment",
            color = "255:0:255:0:0", // Green
            items = listOf(
                "LOTUS_BRACELET",
                "LOTUS_NECKLACE",
                "LOTUS_BELT",
                "LOTUS_CLOAK",
                "ZORROS_CAPE",
                "BLOSSOM_BRACELET",
                "BLOSSOM_NECKLACE",
                "BLOSSOM_BELT",
                "BLOSSOM_CLOAK"
            )
        )

        try {
            File(builtinDir, "pest_equipment.json").writeText(gson.toJson(pestGroup))
            File(builtinDir, "farming_equipment.json").writeText(gson.toJson(farmingGroup))
            Soul.getLogger()?.info("Created/updated builtin highlight files")
        } catch (e: Exception) {
            Soul.getLogger()?.info("Failed to create builtin files: ${e.message}")
        }
    }

    /**
     * Creates example custom highlight group files for the user
     */
    private fun createExampleCustomFiles() {
        // Example: Combat items (red)
        val combatGroup = HighlightGroup(
            name = "Combat Items",
            color = "255:255:0:0:0", // Red
            items = listOf("ASPECT_OF_THE_END", "ASPECT_OF_THE_DRAGONS", "HYPERION")
        )

        try {
            File(customDir, "combat.json").writeText(gson.toJson(combatGroup))
            Soul.getLogger()?.info("Created example custom highlight files")
        } catch (e: Exception) {
            Soul.getLogger()?.info("Failed to create example custom files: ${e.message}")
        }
    }
}
