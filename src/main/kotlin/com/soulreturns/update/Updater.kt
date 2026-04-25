package com.soulreturns.update

import com.soulreturns.api.SoulHttp
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.MinecraftClient
import com.soulreturns.util.SoulLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.Executors

object Updater {

    private val logger = SoulLogger("Soul/Updater")

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soul-updater").also { it.isDaemon = true }
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun downloadAndSchedule(
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        executor.submit {
            try {
                val mcVersion = SharedConstants.getGameVersion().name()
                val newJarName = "soul-${info.version}+$mcVersion.jar"
                val gameDir = FabricLoader.getInstance().gameDir
                val modsDir = gameDir.resolve("mods")
                val stagingDir = gameDir.resolve(".soul-update")
                Files.createDirectories(stagingDir)

                val partFile = stagingDir.resolve("$newJarName.part")
                val finalFile = modsDir.resolve(newJarName)

                // Ask FabricLoader where it actually loaded the mod JAR from.
                val oldJarPath: java.nio.file.Path? = FabricLoader.getInstance()
                    .getModContainer("soul")
                    .map { it.origin.paths.firstOrNull()?.takeIf { p -> p.toString().endsWith(".jar") } }
                    .orElse(null)

                mc().execute { onProgress(0.05f) }

                logger.info("Downloading $newJarName from ${info.assetDownloadUrl}")
                val request = HttpRequest.newBuilder()
                    .GET()
                    .uri(URI.create(info.assetDownloadUrl))
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", SoulHttp.userAgent())
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() !in 200..299) {
                    mc().execute { onError("Download failed (HTTP ${response.statusCode()})") }
                    return@submit
                }

                mc().execute { onProgress(0.1f) }

                // Stream body to .part file in the staging directory.
                response.body().use { input ->
                    Files.copy(input, partFile, StandardCopyOption.REPLACE_EXISTING)
                }

                mc().execute { onProgress(0.9f) }

                // Validate the downloaded file is a well-formed ZIP/JAR.
                try {
                    java.util.zip.ZipFile(partFile.toFile()).close()
                } catch (e: Exception) {
                    Files.deleteIfExists(partFile)
                    mc().execute { onError("Downloaded file is corrupt — please try again") }
                    return@submit
                }

                // Move from staging into mods/.
                Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING)

                // Schedule deletion of the old JAR on JVM shutdown (after game fully exits).
                if (oldJarPath != null && Files.exists(oldJarPath)) {
                    logger.info("Will delete old JAR on shutdown: $oldJarPath")
                    Runtime.getRuntime().addShutdownHook(Thread({
                        try {
                            Files.deleteIfExists(oldJarPath)
                            stagingDir.toFile().delete()
                        } catch (e: Exception) {
                            logger.warn("Failed to remove old JAR on shutdown: ${e.message}")
                        }
                    }, "soul-update-cleanup"))
                } else {
                    logger.warn("Old JAR not found (running from classes/dev?) — skipping deletion")
                }

                mc().execute {
                    onProgress(1.0f)
                    onDone()
                }
            } catch (e: Exception) {
                logger.warn("Update download failed", e)
                mc().execute { onError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun mc(): MinecraftClient = MinecraftClient.getInstance()
}
