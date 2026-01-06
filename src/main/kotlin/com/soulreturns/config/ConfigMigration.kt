package com.soulreturns.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.soulreturns.Soul
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Handles migration of config.json between schema versions.
 *
 * This first migration upgrades legacy configs that did not have a
 * configVersion field into the new v1 layout used by MainConfig.
 */
object ConfigMigration {
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    fun migrateIfNeeded(configFile: File) {
        if (!configFile.exists()) return

        try {
            FileReader(configFile).use { reader ->
                val element = JsonParser.parseReader(reader)
                if (!element.isJsonObject) return

                val root = element.asJsonObject

                // Already versioned – nothing to do for now (only v1 exists).
                if (root.has("configVersion")) {
                    val existingVersion = root.get("configVersion").asInt
                    if (existingVersion >= CURRENT_CONFIG_VERSION) {
                        return
                    }
                    // Placeholder for future incremental migrations when
                    // CURRENT_CONFIG_VERSION is bumped beyond 1.
                    // For now there is no newer schema than v1.
                    return
                }

                // Legacy config without version – treat as v0 and migrate.
                migrateFromV0(root, configFile)
            }
        } catch (e: Exception) {
            Soul.getLogger()?.error("Failed to inspect existing config for migration: ${e.message}")
        }
    }

    private fun migrateFromV0(root: JsonObject, configFile: File) {
        Soul.getLogger()?.info("Detected legacy config without version; migrating to v$CURRENT_CONFIG_VERSION")

        val newConfig = MainConfig()

        // Helper to safely traverse nested objects
        fun JsonObject.obj(name: String): JsonObject? =
            if (this.has(name) && this.get(name).isJsonObject) this.getAsJsonObject(name) else null

        // Render category
        root.obj("renderCategory")?.let { renderObj ->
            renderObj.obj("highlightSubCategory")?.let { highlightObj ->
                highlightObj.get("itemHighlightingEnabled")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    newConfig.renderCategory.highlightSubCategory.itemHighlightingEnabled = it
                }
                highlightObj.get("highlightPestEquipment")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    newConfig.renderCategory.highlightSubCategory.highlightPestEquipment = it
                }
                highlightObj.get("usePestVest")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    newConfig.renderCategory.highlightSubCategory.usePestVest = it
                }
                highlightObj.get("highlightFarmingEquipment")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    newConfig.renderCategory.highlightSubCategory.highlightFarmingEquipment = it
                }
                highlightObj.get("highlightCustomItems")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    newConfig.renderCategory.highlightSubCategory.highlightCustomItems = it
                }
            }

            renderObj.get("hideHeldItemTooltip")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                newConfig.renderCategory.hideHeldItemTooltip = it
            }
            renderObj.get("showSkyblockIdInTooltip")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                newConfig.renderCategory.showSkyblockIdInTooltip = it
            }
            renderObj.get("oldSneakHeight")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                newConfig.renderCategory.oldSneakHeight = it
            }
        }

        // Fishing category – old layout had an extra "doubleHookMessage" object
        root.obj("fishingCategory")?.obj("chatSubCategory")?.let { chatObj ->
            chatObj.obj("doubleHookMessage")?.let { doubleObj ->
                doubleObj.get("doubleHookMessageToggle")?.takeIf { it.isJsonPrimitive }?.asBoolean?.let {
                    newConfig.fishingCategory.chatSubCategory.doubleHookMessageToggle = it
                }
                doubleObj.get("doubleHookMessageText")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                    newConfig.fishingCategory.chatSubCategory.doubleHookMessageText = it
                }
            }
        }

        // Mining category
        root.obj("miningCategory")
            ?.obj("dwarvenMinesSubCategory")
            ?.get("donExpressoAlert")
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean
            ?.let {
                newConfig.miningCategory.dwarvenMinesSubCategory.donExpressoAlert = it
            }

        // Fixes category
        root.obj("fixesCategory")
            ?.get("fixDoubleSneak")
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean
            ?.let {
                newConfig.fixesCategory.fixDoubleSneak = it
            }

        // Ensure version is set to the current schema
        newConfig.configVersion = CURRENT_CONFIG_VERSION

        try {
            configFile.parentFile?.mkdirs()
            FileWriter(configFile).use { writer ->
                gson.toJson(newConfig, writer)
            }
            Soul.getLogger()?.info("Migration to v$CURRENT_CONFIG_VERSION completed; config written to ${configFile.path}")
        } catch (e: Exception) {
            Soul.getLogger()?.error("Failed to write migrated config: ${e.message}")
        }
    }
}