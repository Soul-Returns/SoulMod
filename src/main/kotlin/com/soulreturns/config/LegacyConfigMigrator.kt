package com.soulreturns.config

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.wispforest.owo.config.ConfigWrapper
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader

/**
 * One-shot migrator from the pre-owo `config/soul/config.json` layout to the
 * new owo-config file at `config/soul.json5`. Reads the legacy file, writes a
 * stub owo-config file (so [ConfigWrapper.load] picks it up) and renames the
 * old file out of the way.
 *
 * Because we run before [SoulConfig.createAndLoad], we can't use the
 * generated wrapper's typed setters. Instead we materialise a raw json5 file
 * with the new key layout (`<section>.<sub>.<field>`) — owo's Jankson loader
 * accepts plain JSON keyed that way.
 */
object LegacyConfigMigrator {
    private val logger = LoggerFactory.getLogger("SoulMod/LegacyMigrator")

    fun runIfPresent() {
        val configDir = File(FabricLoader.getInstance().configDir.toFile(), ".")
        val legacy = File(configDir, "soul/config.json")
        val newFile = File(configDir, "soul.json5")
        if (!legacy.exists() || newFile.exists()) return

        val root: JsonObject = try {
            FileReader(legacy).use { JsonParser.parseReader(it) }.asJsonObject
        } catch (e: Exception) {
            logger.warn("Failed to parse legacy config; skipping migration", e)
            return
        }

        // Build a flat map of `dotted.path -> value`. Each entry mirrors the
        // structure of [SoulConfigModel].
        val out = LinkedHashMap<String, Any>()

        fun JsonObject.obj(name: String): JsonObject? =
            takeIf { has(name) && get(name).isJsonObject }?.getAsJsonObject(name)

        fun JsonObject.copyBool(key: String, target: String) {
            if (has(key) && get(key).isJsonPrimitive) out[target] = get(key).asBoolean
        }
        fun JsonObject.copyStr(key: String, target: String) {
            if (has(key) && get(key).isJsonPrimitive) out[target] = get(key).asString
        }
        fun JsonObject.copyFlt(key: String, target: String) {
            if (has(key) && get(key).isJsonPrimitive) out[target] = get(key).asFloat
        }
        fun JsonObject.copyInt(key: String, target: String) {
            if (has(key) && get(key).isJsonPrimitive) out[target] = get(key).asInt
        }

        root.obj("renderCategory")?.let { r ->
            r.copyBool("hideHeldItemTooltip", "render.hideHeldItemTooltip")
            r.copyBool("showSkyblockIdInTooltip", "render.showSkyblockIdInTooltip")
            r.copyBool("oldSneakHeight", "render.oldSneakHeight")
            r.obj("hudScaleSubCategory")?.let { hs ->
                hs.copyFlt("tabListScale", "render.hudScale.tabListScale")
                hs.copyFlt("hotbarScale", "render.hudScale.hotbarScale")
                hs.copyFlt("bossBarScale", "render.hudScale.bossBarScale")
                hs.copyFlt("chatScale", "render.hudScale.chatScale")
                hs.copyFlt("actionBarScale", "render.hudScale.actionBarScale")
                hs.copyFlt("scoreboardScale", "render.hudScale.scoreboardScale")
            }
            r.obj("highlightSubCategory")?.let { h ->
                h.copyBool("itemHighlightingEnabled", "render.highlights.itemHighlightingEnabled")
                h.copyBool("highlightPestEquipment", "render.highlights.highlightPestEquipment")
                h.copyBool("usePestVest", "render.highlights.usePestVest")
                h.copyBool("highlightFarmingEquipment", "render.highlights.highlightFarmingEquipment")
                h.copyBool("highlightCustomItems", "render.highlights.highlightCustomItems")
            }
            r.obj("overlaysSubCategory")?.let { o ->
                o.copyBool("enableLegionCounter", "render.overlays.enableLegionCounter")
                o.copyBool("enablePartyOverlay", "render.overlays.enablePartyOverlay")
            }
        }

        root.obj("fishingCategory")?.let { f ->
            f.obj("chatSubCategory")?.let { c ->
                c.copyBool("doubleHookMessageToggle", "fishing.chat.doubleHookMessageToggle")
                c.copyStr("doubleHookMessageText", "fishing.chat.doubleHookMessageText")
            }
            f.obj("bobbinTimeSubCategory")?.let { b ->
                b.copyBool("enableBobbinTimeCounter", "fishing.bobbinTime.enableBobbinTimeCounter")
                b.copyBool("enableBobbinTimeAlert", "fishing.bobbinTime.enableBobbinTimeAlert")
                b.copyInt("alertBobberCount", "fishing.bobbinTime.alertBobberCount")
                b.copyBool("syncBobbinAlertWithParty", "fishing.bobbinTime.syncBobbinAlertWithParty")
                b.copyStr("alertItemNameFilter", "fishing.bobbinTime.alertItemNameFilter")
            }
        }

        root.obj("miningCategory")?.obj("dwarvenMinesSubCategory")?.let { d ->
            d.copyBool("donExpressoAlert", "mining.dwarvenMines.donExpressoAlert")
        }

        root.obj("fixesCategory")?.let { fx ->
            fx.copyBool("fixDoubleSneak", "fixes.fixDoubleSneak")
        }

        root.obj("profileViewerCategory")?.let { p ->
            p.copyBool("enabled", "profileViewer.enabled")
            p.copyStr("backendUrlOverride", "profileViewer.backendUrlOverride")
        }

        root.obj("debugCategory")?.let { d ->
            d.copyBool("debugMode", "debug.debugMode")
            d.obj("loggingSubCategory")?.let { l ->
                l.copyBool("logConfigChanges", "debug.logging.logConfigChanges")
                l.copyBool("logGuiLayout", "debug.logging.logGuiLayout")
                l.copyBool("logWidgetInteractions", "debug.logging.logWidgetInteractions")
                l.copyBool("logMessageHandler", "debug.logging.logMessageHandler")
                l.copyBool("logFeatureEvents", "debug.logging.logFeatureEvents")
                l.copyBool("logCommandsAndMessages", "debug.logging.logCommandsAndMessages")
            }
        }

        if (out.isEmpty()) {
            logger.info("Legacy config existed but contained no recognised keys; skipping migration")
            return
        }

        try {
            val sb = StringBuilder()
            sb.append("// Migrated from legacy config/soul/config.json\n")
            sb.append("{\n")
            val it = out.entries.iterator()
            while (it.hasNext()) {
                val (k, v) = it.next()
                sb.append("    \"").append(k).append("\": ")
                when (v) {
                    is Boolean -> sb.append(v.toString())
                    is Int -> sb.append(v.toString())
                    is Float -> sb.append(v.toString())
                    is String -> sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                    else -> sb.append(v.toString())
                }
                if (it.hasNext()) sb.append(',')
                sb.append('\n')
            }
            sb.append("}\n")
            newFile.parentFile?.mkdirs()
            newFile.writeText(sb.toString())
        } catch (e: Exception) {
            logger.warn("Failed to write migrated owo config", e)
            return
        }

        val backup = File(legacy.parentFile, "config.json.legacy")
        if (backup.exists()) backup.delete()
        if (legacy.renameTo(backup)) {
            logger.info("Migrated legacy config to soul.json5 (backed up as {})", backup.name)
        } else {
            logger.warn("Could not rename legacy config; future runs may re-migrate")
        }
    }
}
