package com.soulreturns.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import com.soulreturns.util.SoulLogger
import java.io.File
import java.io.FileReader

/**
 * One-shot migrator from the pre-owo `config/soul/config.json` layout to the
 * new owo-config file at `config/soul.json5`. Reads the legacy file, builds a
 * nested JsonObject matching the owo-config file structure, and writes it so
 * that [SoulConfig.createAndLoad] picks it up on first run.
 */
object LegacyConfigMigrator {
    private val logger = SoulLogger("Soul/Config")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun runIfPresent() {
        val configDir = FabricLoader.getInstance().configDir.toFile()
        val legacy = File(configDir, "soul/config.json")
        val newFile = File(configDir, "soul/config.json5")

        // Secondary migration: someone already running the owo-config build
        // before this path change would have soul.json5 at the config root.
        val rootFile = File(configDir, "soul.json5")
        if (rootFile.exists() && !newFile.exists()) {
            newFile.parentFile?.mkdirs()
            if (rootFile.renameTo(newFile)) {
                logger.info("Moved soul.json5 → soul/config.json5")
            } else {
                logger.warn("Could not move soul.json5 to soul/config.json5")
            }
        }

        if (!legacy.exists() || newFile.exists()) {
            // If the existing json5 was written with the old flat dotted-key
            // format (keys like "render.hideHeldItemTooltip"), owo-config can't
            // read it and falls back to defaults. Detect this and re-migrate
            // from the .legacy backup if present.
            if (newFile.exists()) {
                val hasFlatKeys = try {
                    val json = FileReader(newFile).use { JsonParser.parseReader(it) }
                    json.isJsonObject && json.asJsonObject.keySet().any { it.contains('.') }
                } catch (_: Exception) { false }

                if (hasFlatKeys) {
                    logger.warn("Existing soul/config.json5 uses flat dotted keys (old format); re-migrating.")
                    newFile.delete()
                    val backup = File(configDir, "soul/config.json.legacy")
                    if (backup.exists() && backup.renameTo(legacy)) {
                        logger.info("Restored config.json.legacy → config.json for re-migration")
                        // Fall through to run migration below
                    } else {
                        logger.warn("No .legacy backup found; cannot re-migrate — using defaults")
                        return
                    }
                } else {
                    return
                }
            } else {
                return
            }
        }

        val root: JsonObject = try {
            FileReader(legacy).use { JsonParser.parseReader(it) }.asJsonObject
        } catch (e: Exception) {
            logger.warn("Failed to parse legacy config; skipping migration", e)
            return
        }

        // Build a nested JsonObject that matches the owo-config file layout.
        val out = JsonObject()

        fun JsonObject.obj(name: String): JsonObject? =
            takeIf { has(name) && get(name).isJsonObject }?.getAsJsonObject(name)

        fun JsonObject.bool(key: String): Boolean? =
            takeIf { has(key) && get(key).isJsonPrimitive }?.get(key)?.asBoolean

        fun JsonObject.str(key: String): String? =
            takeIf { has(key) && get(key).isJsonPrimitive }?.get(key)?.asString

        fun JsonObject.flt(key: String): Float? =
            takeIf { has(key) && get(key).isJsonPrimitive }?.get(key)?.asFloat

        fun JsonObject.int_(key: String): Int? =
            takeIf { has(key) && get(key).isJsonPrimitive }?.get(key)?.asInt

        fun JsonObject.getOrCreate(key: String): JsonObject {
            if (!has(key)) add(key, JsonObject())
            return getAsJsonObject(key)
        }

        root.obj("renderCategory")?.let { r ->
            val render = out.getOrCreate("render")
            r.bool("hideHeldItemTooltip")?.let { render.addProperty("hideHeldItemTooltip", it) }
            r.bool("showSkyblockIdInTooltip")?.let { render.addProperty("showSkyblockIdInTooltip", it) }
            r.bool("oldSneakHeight")?.let { render.addProperty("oldSneakHeight", it) }

            r.obj("hudScaleSubCategory")?.let { hs ->
                val hudScale = render.getOrCreate("hudScale")
                hs.flt("tabListScale")?.let { hudScale.addProperty("tabListScale", it) }
                hs.flt("hotbarScale")?.let { hudScale.addProperty("hotbarScale", it) }
                hs.flt("bossBarScale")?.let { hudScale.addProperty("bossBarScale", it) }
                hs.flt("chatScale")?.let { hudScale.addProperty("chatScale", it) }
                hs.flt("actionBarScale")?.let { hudScale.addProperty("actionBarScale", it) }
                hs.flt("scoreboardScale")?.let { hudScale.addProperty("scoreboardScale", it) }
            }
            r.obj("highlightSubCategory")?.let { h ->
                val highlights = render.getOrCreate("highlights")
                h.bool("itemHighlightingEnabled")?.let { highlights.addProperty("itemHighlightingEnabled", it) }
                h.bool("highlightPestEquipment")?.let { highlights.addProperty("highlightPestEquipment", it) }
                h.bool("usePestVest")?.let { highlights.addProperty("usePestVest", it) }
                h.bool("highlightFarmingEquipment")?.let { highlights.addProperty("highlightFarmingEquipment", it) }
                h.bool("highlightCustomItems")?.let { highlights.addProperty("highlightCustomItems", it) }
            }
            r.obj("overlaysSubCategory")?.let { o ->
                val overlays = render.getOrCreate("overlays")
                o.bool("enableLegionCounter")?.let { overlays.addProperty("enableLegionCounter", it) }
                o.bool("enablePartyOverlay")?.let { overlays.addProperty("enablePartyOverlay", it) }
            }
        }

        root.obj("fishingCategory")?.let { f ->
            val fishing = out.getOrCreate("fishing")
            f.obj("chatSubCategory")?.let { c ->
                val chat = fishing.getOrCreate("chat")
                c.bool("doubleHookMessageToggle")?.let { chat.addProperty("doubleHookMessageToggle", it) }
                c.str("doubleHookMessageText")?.let { chat.addProperty("doubleHookMessageText", it) }
            }
            f.obj("bobbinTimeSubCategory")?.let { b ->
                val bobbinTime = fishing.getOrCreate("bobbinTime")
                b.bool("enableBobbinTimeCounter")?.let { bobbinTime.addProperty("enableBobbinTimeCounter", it) }
                b.bool("enableBobbinTimeAlert")?.let { bobbinTime.addProperty("enableBobbinTimeAlert", it) }
                b.int_("alertBobberCount")?.let { bobbinTime.addProperty("alertBobberCount", it) }
                b.bool("syncBobbinAlertWithParty")?.let { bobbinTime.addProperty("syncBobbinAlertWithParty", it) }
                b.str("alertItemNameFilter")?.let { bobbinTime.addProperty("alertItemNameFilter", it) }
            }
        }

        root.obj("miningCategory")?.obj("dwarvenMinesSubCategory")?.let { d ->
            val dwarvenMines = out.getOrCreate("mining").getOrCreate("dwarvenMines")
            d.bool("donExpressoAlert")?.let { dwarvenMines.addProperty("donExpressoAlert", it) }
        }

        root.obj("fixesCategory")?.let { fx ->
            val fixes = out.getOrCreate("fixes")
            fx.bool("fixDoubleSneak")?.let { fixes.addProperty("fixDoubleSneak", it) }
        }

        root.obj("profileViewerCategory")?.let { p ->
            val pv = out.getOrCreate("profileViewer")
            p.bool("enabled")?.let { pv.addProperty("enabled", it) }
            p.str("backendUrlOverride")?.let { pv.addProperty("backendUrlOverride", it) }
        }

        root.obj("debugCategory")?.let { d ->
            val debug = out.getOrCreate("debug")
            d.bool("debugMode")?.let { debug.addProperty("debugMode", it) }
            d.obj("loggingSubCategory")?.let { l ->
                val logging = debug.getOrCreate("logging")
                l.bool("logConfigChanges")?.let { logging.addProperty("logConfigChanges", it) }
                l.bool("logGuiLayout")?.let { logging.addProperty("logGuiLayout", it) }
                l.bool("logWidgetInteractions")?.let { logging.addProperty("logWidgetInteractions", it) }
                l.bool("logMessageHandler")?.let { logging.addProperty("logMessageHandler", it) }
                l.bool("logFeatureEvents")?.let { logging.addProperty("logFeatureEvents", it) }
                l.bool("logCommandsAndMessages")?.let { logging.addProperty("logCommandsAndMessages", it) }
            }
        }

        if (out.size() == 0) {
            logger.info("Legacy config existed but contained no recognised keys; skipping migration")
            return
        }

        try {
            newFile.parentFile?.mkdirs()
            newFile.writeText(gson.toJson(out))
        } catch (e: Exception) {
            logger.warn("Failed to write migrated owo config", e)
            return
        }

        val backup = File(legacy.parentFile, "config.json.legacy")
        if (backup.exists()) backup.delete()
        if (legacy.renameTo(backup)) {
            logger.info("Migrated legacy config to soul/config.json5 (backed up as {})", backup.name)
        } else {
            logger.warn("Could not rename legacy config; future runs may re-migrate")
        }
    }
}
