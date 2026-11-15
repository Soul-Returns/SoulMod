package com.soulreturns.config

import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads, holds, and persists [SoulConfig] at `config/soul/config.json`.
 *
 * The held instance is immutable; mutate with [update] (replace-and-save). Call [init] once
 * during client startup before any feature reads [cfg].
 *
 * Players keep their settings across updates: decoding, migrations and partial recovery live in
 * [ConfigCodec]; this class backs the file up ([DataBackups]) before it rewrites anything the codec
 * changed or could not read, and tells the player in chat when settings were reset.
 */
object ConfigManager {
    private val logger = SoulLogger("Soul/Config")

    private lateinit var file: File

    @Volatile
    private var loaded = false

    @Volatile
    var config: SoulConfig = SoulConfig()
        private set

    private var noticeHooked = false

    /** Shown once in chat on the first join after a load that reset settings. */
    @Volatile
    private var loadNotice: String? = null

    fun init() {
        val dir = FabricLoader.getInstance().configDir.resolve("soul").toFile()
        if (!dir.exists()) dir.mkdirs()
        file = File(dir, "config.json")
        config = load()
        loaded = true
        logger.info("Config loaded from ${file.path}")
        if (noticeHooked) return
        noticeHooked = true
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            loadNotice?.let {
                loadNotice = null
                soulChat(it)
            }
        }
    }

    /** True once [init] has run — used by the logger to know whether the debug toggle is readable. */
    fun isReady(): Boolean = loaded

    private fun load(): SoulConfig {
        if (!file.exists()) {
            val defaults = SoulConfig()
            save(defaults)
            return defaults
        }
        val text =
            try {
                file.readText()
            } catch (t: Throwable) {
                logger.warn("Could not read config.json; using defaults without overwriting it", t)
                return SoulConfig()
            }
        val decoded = ConfigCodec.decode(text)
        val path = file.toPath()
        if (decoded.notes.isNotEmpty()) {
            val copy = DataBackups.backup(path, "unreadable")
            decoded.notes.forEach { logger.warn("config.json: $it") }
            loadNotice = "§eSome settings could not be read and were reset (${decoded.notes.size}). " +
                "Your old file is saved in §fconfig/soul/backups/${copy?.fileName ?: ""}"
        } else if (decoded.migrated) {
            DataBackups.backup(path, "v${decoded.fileVersion}")
            logger.info("Migrated config.json from version ${decoded.fileVersion}")
        } else if (decoded.fromNewer) {
            // An older Soul drops settings it does not know on its next save; keep the newer file.
            DataBackups.backup(path, "newer-v${decoded.fileVersion}")
            logger.warn("config.json was written by a newer Soul (version ${decoded.fileVersion}); a backup was kept")
        }
        if (decoded.migrated || decoded.notes.isNotEmpty()) save(decoded.config)
        return decoded.config
    }

    fun save(toSave: SoulConfig = config) {
        if (!::file.isInitialized) return
        try {
            val target = file.toPath()
            val tmp = target.resolveSibling("config.json.tmp")
            Files.writeString(tmp, ConfigCodec.encode(toSave))
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            logger.warn("Failed to save config", t)
        }
    }

    /** Replace the held config via [block] and persist. */
    fun update(block: SoulConfig.() -> SoulConfig) {
        config = config.block()
        save()
    }
}

/** Point-of-use config accessor. Read fresh every time; never cache. */
val cfg: SoulConfig get() = ConfigManager.config
