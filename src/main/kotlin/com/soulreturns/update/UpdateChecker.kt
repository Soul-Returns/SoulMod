package com.soulreturns.update

import com.google.gson.JsonParser
import com.soulreturns.Soul
import com.soulreturns.api.SoulHttp
import com.soulreturns.config.cfg
import net.minecraft.SharedConstants
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.TitleScreen
import com.soulreturns.util.SoulLogger
import java.util.concurrent.Executors

object UpdateChecker {

    private val logger = SoulLogger("Soul/Update")
    private const val RELEASES_URL = "https://api.github.com/repos/Soul-Returns/SoulMod/releases/latest"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soul-update-check").also { it.isDaemon = true }
    }

    @Volatile
    var latestUpdate: UpdateInfo? = null
        private set

    /** Called on startup; respects the config toggle. */
    fun checkAsync() {
        if (!cfg.updates.checkForUpdates()) {
            logger.debug("Update check skipped (disabled in config)")
            return
        }
        executor.submit {
            try {
                val info = doCheck()
                if (info != null) {
                    latestUpdate = info
                    val mc = MinecraftClient.getInstance()
                    mc?.execute {
                        if (!UpdateModal.dismissed &&
                            (mc.world != null || mc.currentScreen is TitleScreen)
                        ) {
                            mc.setScreen(UpdateModal(info))
                        }
                        // Otherwise ScreenEvents.AFTER_INIT on TitleScreen will pick it up.
                    }
                }
            } catch (e: Exception) {
                logger.warn("Update check failed: ${e.message}")
            }
        }
    }

    /** Called from /soul checkForUpdates — bypasses config toggle, invokes callback with result. */
    fun checkNow(callback: (UpdateInfo?) -> Unit) {
        executor.submit {
            try {
                val info = doCheck()
                if (info != null) latestUpdate = info
                callback(info)
            } catch (e: Exception) {
                logger.warn("Manual update check failed: ${e.message}")
                callback(null)
            }
        }
    }

    /** Performs the GitHub API request and returns [UpdateInfo] if a newer release exists, else null. */
    private fun doCheck(): UpdateInfo? {
        val currentVersion = Soul.version.substringBefore("+")
        val mcVersion = SharedConstants.getGameVersion().name()
        logger.info("Checking for updates... (current: $currentVersion, mc: $mcVersion)")

        val response = SoulHttp.get(
            RELEASES_URL,
            mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
            )
        )
        logger.debug("GitHub API response: HTTP ${response.statusCode()}")

        if (response.statusCode() !in 200..299) {
            logger.warn("Update check failed — HTTP ${response.statusCode()}")
            return null
        }

        val json = JsonParser.parseString(response.body()).asJsonObject
        val tagName = json.get("tag_name")?.asString ?: run {
            logger.warn("Response missing tag_name")
            return null
        }
        val releaseUrl = json.get("html_url")?.asString ?: run {
            logger.warn("Response missing html_url")
            return null
        }

        val newVersion = tagName.removePrefix("v")
        logger.info("Latest release: $newVersion  |  current: $currentVersion")

        if (!isNewer(newVersion, currentVersion)) {
            logger.info("Up to date (current=$currentVersion, latest=$newVersion)")
            return null
        }

        val expectedAssetName = "soul-$newVersion+$mcVersion.jar"
        logger.info("Update found: $currentVersion -> $newVersion  |  looking for asset '$expectedAssetName'")

        val assets = json.getAsJsonArray("assets") ?: run {
            logger.warn("Release $newVersion has no assets array")
            return null
        }
        val assetNames = assets.map { it.asJsonObject.get("name")?.asString }
        logger.debug("Assets in release: $assetNames")

        val assetUrl = assets.asSequence()
            .map { it.asJsonObject }
            .firstOrNull { it.get("name")?.asString == expectedAssetName }
            ?.get("browser_download_url")?.asString
            ?: run {
                logger.warn("No matching asset '$expectedAssetName' in release $newVersion (available: $assetNames)")
                return null
            }

        logger.info("Asset found: $assetUrl")
        return UpdateInfo(newVersion, releaseUrl, assetUrl)
    }

    /** Returns true if [candidate] is strictly newer than [current] (both in "x.y.z" form). */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai > bi
        }
        return false
    }
}
