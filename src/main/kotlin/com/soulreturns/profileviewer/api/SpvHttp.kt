package com.soulreturns.profileviewer.api

import com.soulreturns.Soul
import com.soulreturns.profileviewer.SpvExecutor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object SpvHttp {
    const val PROD_BACKEND_URL = "https://spv.soulreturns.dev"
    const val MOJANG_API = "https://api.mojang.com"

    fun userAgent(): String {
        val mc = try {
            net.minecraft.SharedConstants.getGameVersion().name()
        } catch (_: Throwable) {
            "unknown"
        }
        return "SoulMod/${Soul.version}/$mc"
    }

    fun backendBaseUrl(): String {
        val sysProp = System.getProperty("soul.spv.backendUrl")
        if (!sysProp.isNullOrBlank()) return sysProp.trimEnd('/')
        val override = try {
            Soul.configManager.config.instance.profileViewerCategory.backendUrlOverride
        } catch (_: Throwable) {
            ""
        }
        if (override.isNotBlank()) return override.trimEnd('/')
        return PROD_BACKEND_URL
    }

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .executor(SpvExecutor.executor)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .GET()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", userAgent())
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
