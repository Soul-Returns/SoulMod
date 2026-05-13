package com.soulreturns.platform.http

import com.soulreturns.Soul
import com.soulreturns.config.cfg
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object SoulHttp {
    const val BACKEND_URL = "https://sky.soulreturns.com"
    const val MOJANG_API = "https://api.mojang.com"

    fun userAgent(): String {
        val mc =
            try {
                net.minecraft.SharedConstants.getCurrentVersion().name()
            } catch (_: Throwable) {
                "unknown"
            }
        return "SoulMod/${Soul.version}/$mc"
    }

    fun backendBaseUrl(): String {
        val sysProp = System.getProperty("soul.backendUrl")
        if (!sysProp.isNullOrBlank()) return sysProp.trimEnd('/')
        val override =
            try {
                cfg.dev.backend.backendUrlOverride()
            } catch (_: Throwable) {
                ""
            }
        if (override.isNotBlank()) return override.trimEnd('/')
        return BACKEND_URL
    }

    /**
     * Shared HTTP client. **Do not bind this to [SoulExecutor]** — `HttpClient.send()`
     * dispatches its internal completion callbacks through the configured executor, and
     * with `SoulExecutor`'s 2 threads, two concurrent blocking `send()` calls (one from
     * `BackendClient`'s wrapper task, one from `RealtimeClient`'s token fetch, …) saturate
     * the pool while both wait for callbacks that need the same pool to run. Letting
     * `HttpClient` use its default internal executor keeps `SoulExecutor` free for the
     * `CompletableFuture` wrappers and avoids that deadlock entirely.
     */
    val client: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", userAgent())
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", userAgent())
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
