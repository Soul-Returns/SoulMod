package com.soulreturns.profileviewer.api

import com.google.gson.JsonParser
import com.soulreturns.profileviewer.SpvExecutor
import java.util.UUID
import java.util.concurrent.CompletableFuture

object MojangApi {
    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, UUID>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UUID>?): Boolean = size > 64
        }
    )

    fun resolveUuid(name: String): CompletableFuture<UUID?> =
        CompletableFuture.supplyAsync({
            val key = name.lowercase()
            cache[key]?.let { return@supplyAsync it }
            try {
                val response = SpvHttp.get("${SpvHttp.MOJANG_API}/users/profiles/minecraft/$name")
                if (response.statusCode() == 200 && response.body().isNotBlank()) {
                    val obj = JsonParser.parseString(response.body()).asJsonObject
                    val raw = obj["id"].asString
                    val uuid = parseUndashedUuid(raw)
                    cache[key] = uuid
                    uuid
                } else {
                    SpvExecutor.log("Mojang lookup '$name' -> ${response.statusCode()}")
                    null
                }
            } catch (e: Exception) {
                SpvExecutor.warn("Mojang lookup failed for $name", e)
                null
            }
        }, SpvExecutor.executor)

    private fun parseUndashedUuid(raw: String): UUID {
        val s = if (raw.contains('-')) raw else
            "${raw.substring(0, 8)}-${raw.substring(8, 12)}-${raw.substring(12, 16)}-${raw.substring(16, 20)}-${raw.substring(20)}"
        return UUID.fromString(s)
    }

    fun toUndashed(uuid: UUID): String = uuid.toString().replace("-", "")
}
