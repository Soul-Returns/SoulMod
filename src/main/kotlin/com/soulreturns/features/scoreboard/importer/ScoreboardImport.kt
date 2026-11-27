package com.soulreturns.features.scoreboard.importer

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.DataBackups
import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.cfg
import com.soulreturns.util.SoulLogger
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one-time import from Custom Scoreboard. The setup screen offers it once (`importOffered`, set by
 * [markOffered] for "Import" and "Not now" alike) and Advanced offers it any time. Custom Scoreboard's own
 * files are only ever read.
 */
object ScoreboardImport {
    private val logger = SoulLogger("Soul/ScoreboardImport")
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun file(): Path = FabricLoader.getInstance().configDir.resolve("customscoreboard").resolve("config.jsonc")

    private fun read(path: Path): ConvertedSetup? =
        try {
            if (Files.isRegularFile(path)) CustomScoreboardConverter.convert(Files.readString(path), cfg.scoreboard.look) else null
        } catch (t: Throwable) {
            logger.warn("Could not read $path", t)
            null
        }

    /** A readable Custom Scoreboard setup exists. */
    fun available(): Boolean = read(file()) != null

    /** What [apply] would bring over and leave behind; empty when nothing is available. */
    fun preview(): ImportResult = read(file())?.result ?: ImportResult(emptyList(), emptyList())

    /** The offer was shown: the player either imported or said "Not now". */
    fun markOffered() {
        if (!cfg.scoreboard.importOffered) ConfigManager.update { copy(scoreboard = scoreboard.copy(importOffered = true)) }
    }

    /**
     * Replaces the main setup and the Look with the converted ones, after saving the current scoreboard
     * section to `config/soul/backups/`. Returns the result, or null when nothing was imported.
     */
    fun apply(): ImportResult? {
        val c = read(file()) ?: return null
        backup()
        ConfigManager.update {
            copy(scoreboard = scoreboard.copy(main = c.setup, look = c.look, keepVanilla = c.keepVanilla, importOffered = true))
        }
        return c.result
    }

    private fun backup() {
        try {
            val tmp = Files.createTempDirectory("soul-sb").resolve("scoreboard-config.json")
            Files.writeString(tmp, json.encodeToString(ScoreboardConfig.serializer(), cfg.scoreboard))
            DataBackups.backup(tmp, "before-import")
            Files.deleteIfExists(tmp)
            Files.deleteIfExists(tmp.parent)
        } catch (t: Throwable) {
            logger.warn("Could not back up the scoreboard section", t)
        }
    }
}
