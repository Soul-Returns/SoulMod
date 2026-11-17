package com.soulreturns.config

import com.soulreturns.stats.PlayerDataset
import com.soulreturns.util.SoulLogger
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Copies of player data taken before Soul rewrites it: before a migration, before a file that
 * could not be fully read is overwritten, before an older Soul saves over a newer Soul's file.
 * Lives in `config/soul/backups/`, newest [KEEP] per file; files of the Alpha dataset
 * ([PlayerDataset]) back up into `config/soul/alpha/backups/`, so they never rotate production copies out.
 */
object DataBackups {
    private const val KEEP = 10
    private val logger = SoulLogger("Soul/Backups")
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun dir(): Path = FabricLoader.getInstance().configDir.resolve("soul").resolve("backups")

    /** Copies [file] to `backups/<name>-<time>-<reason>.<ext>`; returns the copy, or null when it failed. */
    fun backup(file: Path, reason: String): Path? {
        if (!Files.isRegularFile(file)) return null
        return try {
            val dir = if (file.parent == PlayerDataset.dir(PlayerDataset.ALPHA)) file.parent.resolve("backups") else dir()
            Files.createDirectories(dir)
            val name = file.fileName.toString()
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            val target = dir.resolve("$stem-${LocalDateTime.now().format(stamp)}-$reason${if (ext.isEmpty()) "" else ".$ext"}")
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            prune(dir, "$stem-")
            logger.info("Backed up $name to $target ($reason)")
            target
        } catch (t: Throwable) {
            logger.warn("Could not back up $file", t)
            null
        }
    }

    private fun prune(dir: Path, prefix: String) {
        Files.list(dir).use { files ->
            files.filter { it.fileName.toString().startsWith(prefix) }
                .sorted(Comparator.comparing<Path, String> { it.fileName.toString() }.reversed())
                .skip(KEEP.toLong())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}
