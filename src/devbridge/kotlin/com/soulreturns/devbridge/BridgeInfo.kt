package com.soulreturns.devbridge

import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonObject
import net.fabricmc.loader.api.FabricLoader
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.appender.RandomAccessFileAppender
import org.apache.logging.log4j.core.appender.RollingFileAppender
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Versions, compatibility and the files agents find the bridge by. Touches Soul only through the
 * reflective [check] (and the inlined [BUILT_API] constant), so it is safe while disabled.
 */
object BridgeInfo {
    /** The bridge API this jar was compiled against (inlined from Soul at build time). */
    const val BUILT_API = DevBridgeApi.VERSION

    val gameDir: Path = FabricLoader.getInstance().gameDir.toAbsolutePath().normalize()
    val dir: Path = gameDir.resolve("soul-dev")
    val started = System.currentTimeMillis()

    fun version(mod: String): String? =
        FabricLoader.getInstance().getModContainer(mod).map { it.metadata.version.friendlyString }.orElse(null)

    val soulVersion: String? get() = version("soul")
    val bridgeVersion: String? get() = version("soul-devbridge")

    /** The installed Soul's bridge API, null when it has none (older than the bridge API). */
    val soulApi: Int? by lazy {
        try {
            Class.forName("com.soulreturns.features.dev.DevBridgeApi").getField("VERSION").getInt(null)
        } catch (_: Throwable) {
            null
        }
    }

    /** Null when compatible, else the reason including the fix. */
    fun check(): String? {
        val api = soulApi
        if (api == BUILT_API) return null
        val have = if (api == null) "has no bridge API" else "speaks bridge API $api"
        return "Soul ${soulVersion ?: "?"} $have; this bridge (built with Soul ${bridgeVersion ?: "?"}) needs API $BUILT_API. " +
            "Fix: close the game, run tools/devbridge/install --<instance> (rebuilds the bridge from the checkout), restart."
    }

    /** latest.log of this process: log4j's file appender (relative to the working dir, not the game dir). */
    val logFile: Path? by lazy {
        val fromLog4j =
            runCatching {
                val ctx = LogManager.getContext(false) as LoggerContext
                ctx.configuration.appenders.values.firstNotNullOfOrNull { a ->
                    when (a) {
                        is RollingRandomAccessFileAppender -> a.fileName
                        is RollingFileAppender -> a.fileName
                        is RandomAccessFileAppender -> a.fileName
                        is FileAppender -> a.fileName
                        else -> null
                    }?.takeIf { it.endsWith("latest.log") }
                }
            }.getOrNull()
        (fromLog4j?.let { Path.of(it) } ?: Path.of("logs/latest.log")).toAbsolutePath().normalize()
    }

    /** Prism instance name when the game dir is `<instances>/<name>/minecraft`, else the dir name. */
    val instance: String
        get() = if (gameDir.fileName?.toString() in setOf("minecraft", ".minecraft")) gameDir.parent.fileName.toString() else gameDir.fileName.toString()

    fun describe(port: Int, disabled: String?): Map<String, Any?> =
        linkedMapOf(
            "port" to port,
            "pid" to ProcessHandle.current().pid(),
            "instance" to instance,
            "game_dir" to gameDir.toString(),
            "log" to logFile?.toString(),
            "soul_version" to soulVersion,
            "bridge_version" to bridgeVersion,
            "bridge_api" to BUILT_API,
            "soul_api" to soulApi,
            "disabled" to disabled,
            "started" to started,
        )

    /** `<gameDir>/soul-dev/bridge.json` (owner and owner's group only: the token is the only access control) plus a discovery pointer. */
    fun write(port: Int, token: String, disabled: String?): Path {
        Files.createDirectories(dir)
        val target = dir.resolve("bridge.json")
        val content = JsonObject((describe(port, disabled) + ("token" to token)).mapValues { json(it.value) }).toString() + "\n"
        writePrivate(target, content)
        val pointerDir = Path.of(System.getProperty("user.home"), ".cache", "soul-devbridge", "clients")
        val pointer = pointerDir.resolve("${ProcessHandle.current().pid()}.json")
        runCatching {
            Files.createDirectories(pointerDir)
            writePrivate(pointer, JsonObject(mapOf("pid" to json(ProcessHandle.current().pid()), "game_dir" to json(gameDir.toString()))).toString() + "\n")
        }
        // A shutdown hook, not CLIENT_STOPPING: the test client is stopped with SIGTERM.
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { Files.deleteIfExists(target); Files.deleteIfExists(pointer) } })
        return target
    }

    // rw-rw----, not rw-------: on a dir with a default ACL the group bits become the ACL mask, and the desk sandbox
    // (granted soul-dev/ by ACL) must read the token. On a user-private group this stays owner-only.
    private fun writePrivate(target: Path, content: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.deleteIfExists(tmp)
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw----")))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(tmp)
        }
        Files.writeString(tmp, content)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
