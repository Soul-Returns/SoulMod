package com.soulreturns.api

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object BackendClient {
    private const val DEFAULT_EXPIRE_MS = 60_000L

    sealed class Result {
        data class Ok(val json: JsonElement, val expiresAt: Instant) : Result()
        data class Error(val statusCode: Int, val message: String) : Result()
    }

    private data class CacheEntry(val json: JsonElement, val expiresAt: Instant)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun get(endpoint: String, intent: String? = null): CompletableFuture<Result> =
        CompletableFuture.supplyAsync({
            cache[endpoint]?.let { entry ->
                if (entry.expiresAt.isAfter(Instant.now())) {
                    SoulExecutor.log("Cache hit for $endpoint")
                    return@supplyAsync Result.Ok(entry.json, entry.expiresAt)
                }
                cache.remove(endpoint)
            }

            val token = BackendAuth.ensureAuthenticated()
                ?: return@supplyAsync Result.Error(401, "Not authenticated with backend")

            val first = call(endpoint, token, intent)
            if (first is Result.Error && first.statusCode == 401) {
                BackendAuth.clear()
                val refreshed = BackendAuth.ensureAuthenticated(forceRefresh = true)
                    ?: return@supplyAsync Result.Error(401, "Re-auth failed")
                call(endpoint, refreshed, intent)
            } else {
                first
            }
        }, SoulExecutor.executor)

    private fun call(endpoint: String, token: String, intent: String?): Result {
        val url = SoulHttp.backendBaseUrl() + endpoint
        SoulExecutor.log("GET $url")
        val response = try {
            SoulHttp.get(
                url,
                headers = buildMap {
                    put("Authorization", token)
                    if (intent != null) put("X-Intent", intent)
                }
            )
        } catch (e: Exception) {
            SoulExecutor.warn("Backend GET threw for $endpoint", e)
            return Result.Error(-1, e.message ?: "network error")
        }

        if (response.statusCode() in 200..299) {
            val expireMs = response.headers().firstValue("X-Backend-Expire-In")
                .map { it.toLongOrNull() ?: DEFAULT_EXPIRE_MS }
                .orElse(DEFAULT_EXPIRE_MS)
            val expiresAt = Instant.now().plusMillis(expireMs)
            return try {
                val json = JsonParser.parseString(response.body())
                if (expireMs > 0) {
                    cache[endpoint] = CacheEntry(json, expiresAt)
                }
                Result.Ok(json, expiresAt)
            } catch (e: Exception) {
                Result.Error(-2, "Invalid JSON: ${e.message}")
            }
        }
        return Result.Error(response.statusCode(), response.body().take(200))
    }
}
