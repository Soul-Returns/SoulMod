package com.soulreturns.platform.http

import com.soulreturns.Soul
import com.soulreturns.config.cfg
import net.minecraft.SharedConstants
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * Low-level HTTP to the Soul backend (`sky.soulreturns.com`). Bearer auth + the realtime
 * subscription both build on this.
 *
 * The base URL can be overridden for local backend dev with `-Dsoul.backendUrl=http://localhost:29500`
 * (set it in the client run config's VM options).
 *
 * [get] and [post] negotiate **gzip** and decompress transparently — see [stringBody], and note that
 * the JDK's `HttpClient` does neither on its own. Every caller gets it for free; the SSE stream in
 * `RealtimeClient` builds its own request against [client] and is deliberately not affected.
 */
object SoulHttp {
    const val BACKEND_URL = "https://sky.soulreturns.com"

    /** `Soul/<modVersion>/<mcVersion>` — the backend's ClientStateTracker parses this for telemetry. */
    fun userAgent(): String {
        val mc = try {
            SharedConstants.getCurrentVersion().name()
        } catch (_: Throwable) {
            "unknown"
        }
        return "Soul/${Soul.version}/$mc"
    }

    /**
     * When true, a non-blank `dev.backendUrl` config field wins over the `-Dsoul.backendUrl` property
     * and `SOUL_BACKEND_URL` env var (which normally take precedence). Set by the config screen's
     * "Apply & reconnect" button so an in-game URL change can override a launch-time env/VM value.
     * Session-scoped (in-memory) — a relaunch reverts to the env/property taking precedence.
     */
    @Volatile
    var overrideEnvWithConfig: Boolean = false

    /**
     * Resolved backend base URL. Precedence:
     *  1. `dev.backendUrl` config field — only when [overrideEnvWithConfig] (Apply button) is set
     *  2. `-Dsoul.backendUrl` system property (IntelliJ VM option)
     *  3. `SOUL_BACKEND_URL` env var (IntelliJ run-config env var)
     *  4. `dev.backendUrl` config field (in-game `/soul` → Dev)
     *  5. prod default [BACKEND_URL]
     */
    fun backendBaseUrl(): String {
        if (overrideEnvWithConfig) {
            try {
                cfg.dev.backendUrl.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
            } catch (_: Throwable) {
                // Config not ready — fall through to the normal precedence.
            }
        }
        System.getProperty("soul.backendUrl")?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        System.getenv("SOUL_BACKEND_URL")?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        try {
            cfg.dev.backendUrl.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        } catch (_: Throwable) {
            // Config not ready yet — fall through to the default.
        }
        return BACKEND_URL
    }

    /**
     * Shared client. Deliberately uses the JDK's own internal executor (not a Soul thread pool):
     * blocking `send()` calls would otherwise starve a small pool waiting on completion callbacks
     * that need the same pool.
     */
    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .GET()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", userAgent())
            .header("Accept-Encoding", "gzip")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), stringBody())
    }

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", userAgent())
            .header("Accept-Encoding", "gzip")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), stringBody())
    }

    /**
     * `GET` with the body as a stream, for large binary downloads (`BackendClient.download`). No
     * `Accept-Encoding`: the body is hashed and written as received. [timeout] covers the wait for
     * the response headers; the caller bounds the body read.
     */
    fun stream(url: String, headers: Map<String, String> = emptyMap(), timeout: Duration = Duration.ofSeconds(30)): HttpResponse<java.io.InputStream> {
        val builder = HttpRequest.newBuilder()
            .GET()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", userAgent())
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    /**
     * A `String` body handler that gunzips when the response says it is gzipped.
     *
     * ### The JDK does neither half of this for you
     * `HttpClient` **never sends `Accept-Encoding` itself and never transparently decompresses** —
     * unlike curl, every browser, and most HTTP libraries. So an unmodified request gets an identity
     * response however well the server compresses, and there is no warning anywhere: it simply works,
     * slowly and expensively.
     *
     * That was not academic. `GET /prices` measures **~570 KB** as identity and **~72 KB** gzipped
     * (2026-08-23) — so the bandwidth argument that moved prices onto the backend was ~8× off until
     * this existed,
     * and `/items` and `/drops` are large enough to care too. Asking is one header; the whole cost of
     * honouring the answer is this handler.
     *
     * ### It degrades to the raw bytes rather than throwing
     * A `Content-Encoding: gzip` on a body that is not actually gzip — a proxy that rewrote the body
     * and kept the header — would otherwise surface as a `ZipException` from deep inside the client,
     * which reads as "the backend is down" rather than "something in the middle is broken". Falling
     * back to interpreting the bytes as UTF-8 means the worst case is the pre-existing behaviour, and
     * the parse error that follows names the real payload.
     */
    private fun stringBody(): HttpResponse.BodyHandler<String> =
        HttpResponse.BodyHandler { info ->
            val gzipped = info.headers()
                .firstValue("Content-Encoding")
                .map { it.trim().equals("gzip", ignoreCase = true) }
                .orElse(false)
            if (!gzipped) {
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
            } else {
                // Mapping over a byte array rather than streaming: these bodies are hundreds of KB at
                // most, already fully buffered by `ofString` before, and an in-memory gunzip does not
                // block on I/O — which is what the mapping finisher is required not to do.
                HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray()) { bytes ->
                    if (bytes.isEmpty()) {
                        ""
                    } else {
                        try {
                            GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(StandardCharsets.UTF_8) }
                        } catch (_: Throwable) {
                            bytes.toString(StandardCharsets.UTF_8)
                        }
                    }
                }
            }
        }
}
