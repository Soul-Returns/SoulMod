package com.soulreturns.devbridge

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Recompresses a finished recording (`.rec.ndjson.gz`, sync-flushed so it was readable while it
 * grew) to zstd level 12 (~5x smaller: 595 MB raw was 65 MB gz, 13 MB zstd), on its own low
 * priority thread with the external `zstd` binary at `nice 19`, `-T2`. No binary on the machine:
 * the gzip stays and nothing else changes. The gzip is replaced only after zstd exited 0 and the
 * output was moved into place; `latest.rec.ndjson.gz` follows. The tools read both formats.
 */
object Compress {
    private val logger = LoggerFactory.getLogger("Soul/DevBridge")

    private val executor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "Soul devbridge compress").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

    private val zstd: String? by lazy { find("zstd") }
    private val nice: String? by lazy { find("nice") }

    fun submit(gz: Path) {
        executor.execute {
            try {
                run(gz)
            } catch (t: Throwable) {
                logger.warn("[Soul/DevBridge] recompressing $gz failed, keeping the gzip: $t")
            }
        }
    }

    private fun run(gz: Path) {
        val exe = zstd ?: return
        if (!Files.isRegularFile(gz) || !gz.fileName.toString().endsWith(".gz")) return
        val dst = gz.resolveSibling(gz.fileName.toString().removeSuffix(".gz") + ".zst")
        val part = dst.resolveSibling(dst.fileName.toString() + ".part")
        val cmd = (nice?.let { listOf(it, "-n", "19") } ?: emptyList()) + listOf(exe, "-q", "-T2", "-12", "-f", "-o", part.toString())
        val process = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            GZIPInputStream(Files.newInputStream(gz), 1 shl 16).use { input -> process.outputStream.use { input.copyTo(it, 1 shl 16) } }
        } catch (t: Throwable) {
            process.destroy()
            Files.deleteIfExists(part)
            throw t
        }
        if (process.waitFor() != 0 || Files.size(part) == 0L) {
            Files.deleteIfExists(part)
            logger.warn("[Soul/DevBridge] zstd exited ${process.exitValue()} for $gz, keeping the gzip")
            return
        }
        Files.move(part, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val dir = gz.parent
        val oldLatest = dir.resolve("latest.rec.ndjson.gz")
        if (Files.isSymbolicLink(oldLatest) && Files.readSymbolicLink(oldLatest) == gz.fileName) {
            val newLatest = dir.resolve("latest.rec.ndjson.zst")
            Files.deleteIfExists(newLatest)
            Files.createSymbolicLink(newLatest, dst.fileName)
            Files.delete(oldLatest)
        }
        val before = Files.size(gz)
        Files.delete(gz)
        logger.info("[Soul/DevBridge] recompressed ${dst.fileName}: $before -> ${Files.size(dst)} bytes")
    }

    /** First executable named [name] on PATH or in the usual install dirs. */
    private fun find(name: String): String? {
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator) + listOf("/usr/bin", "/usr/local/bin", "/bin", "/opt/homebrew/bin")
        return dirs.filter { it.isNotEmpty() }.map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.path
    }
}
