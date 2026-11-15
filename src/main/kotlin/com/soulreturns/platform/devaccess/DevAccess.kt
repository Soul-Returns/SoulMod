package com.soulreturns.platform.devaccess

import com.soulreturns.core.events.Events
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendAuthenticated
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.io.File

/**
 * Whether this client shows developer surfaces: the Dev config category, `/soul dev …`, `/soul
 * dungeon`, the dungeon debug overlay and the dev keybinds.
 *
 * A UX barrier, not security (the client is open source): it keeps the mod uncluttered for players
 * who are not developing it. Gating is **visibility only**: backend infrastructure configured under
 * Dev (presence, backend URL, logging) behaves the same whether or not it is unlocked.
 *
 * The backend owns the entitlement (`POST /dev/unlock`, `GET /dev/access`); this caches the answer
 * in `config/soul/dev_access.json`, so it survives restarts and works offline. It is refreshed after
 * every successful backend handshake, and a `developer: false` relocks. Development runs start
 * unlocked so working on the mod never needs the password, until "Lock developer mode" stores an
 * explicit lock; from then on a dev run behaves like a release (handy for testing the unlock).
 */
object DevAccess {
    private val logger = SoulLogger("Soul/DevAccess")
    private val json = Json { ignoreUnknownKeys = true }
    private val retryAfterField = Regex("\"retryAfter\"\\s*:\\s*(\\d+)")

    @Serializable
    private data class Stored(val unlocked: Boolean)

    @Serializable
    private data class UnlockRequest(val password: String)

    @Serializable
    private data class AccessResponse(val developer: Boolean)

    private val devEnvironment: Boolean = FabricLoader.getInstance().isDevelopmentEnvironment

    /** The stored decision; null = none yet, which means unlocked in a dev run, locked otherwise. */
    @Volatile
    private var cached: Boolean? = null

    /**
     * Bumped by every local unlock/lock. A refresh whose request started before one of those drops
     * its answer: the handshake inside `POST /dev/unlock` fires a refresh whose `developer: false`
     * can land after the unlock succeeded.
     */
    @Volatile
    private var generation = 0

    private val file: File
        get() = FabricLoader.getInstance().configDir.resolve("soul/dev_access.json").toFile()

    /** True when developer surfaces should exist. Cheap; read it at point of use. */
    val unlocked: Boolean get() = cached ?: devEnvironment

    fun register() {
        load()
        Events.subscribe<BackendAuthenticated> { refresh() }
    }

    /**
     * Ask the backend to unlock with [password]. Returns null on success, else the message to show
     * under the password field.
     */
    suspend fun unlock(password: String): String? {
        val result = BackendClient.post("/dev/unlock", UnlockRequest(password), UnlockRequest.serializer(), intent = "dev-unlock")
        if (result is BackendResult.Ok) {
            val developer = result.value["developer"]?.jsonPrimitive?.booleanOrNull
            if (developer != true) return "The backend did not grant access."
            setLocal(true)
            logger.info("Developer access unlocked")
            return null
        }
        return unlockError(result as BackendResult.Failure)
    }

    /**
     * Relock locally and remember it, also in a dev run. Only a password unlock lifts it: the
     * refresh never grants, so the lock sticks even while the backend entitlement remains.
     */
    fun lock() {
        setLocal(false)
        logger.info("Developer access locked locally")
    }

    private fun refresh() {
        val startedAt = generation
        SoulScope.io.launch {
            val result = BackendClient.get("/dev/access", AccessResponse.serializer(), intent = "dev-access", bypassCache = true)
            if (result !is BackendResult.Ok || generation != startedAt) return@launch
            // Revocation only: a stored grant falls to an explicit `developer: false`. A dev run's
            // implicit unlock and a local lock are not the backend's to change.
            if (cached == true && !result.value.developer) {
                set(false)
                logger.info("Developer access revoked by the backend")
            }
        }
    }

    private fun unlockError(failure: BackendResult.Failure): String {
        val status = failure.status
        if (status == 403 && failure.message.contains("not_configured")) return "Developer access is not available."
        if (status == 403) return "Wrong password."
        if (status == 429) {
            // Once the failed-attempt budget is spent even the right password gets 429.
            val seconds = failure.retryAfter?.seconds
                ?: retryAfterField.find(failure.message)?.groupValues?.get(1)?.toLongOrNull()
            if (seconds == null || seconds <= 0) return "Too many attempts. Try again later."
            if (seconds < 120) return "Too many attempts. Try again in ${seconds}s."
            return "Too many attempts. Try again in ${(seconds + 59) / 60} min."
        }
        if (status == BackendResult.Failure.NO_AUTH || status == BackendResult.Failure.TRANSPORT ||
            status == BackendResult.Failure.TIMEOUT
        ) {
            return "Could not reach the Soul backend. Are you offline?"
        }
        return "Developer access is not available (HTTP $status)."
    }

    private fun setLocal(value: Boolean) {
        generation++
        set(value)
    }

    private fun set(value: Boolean) {
        val before = unlocked
        cached = value
        // Fabric copies only usable client commands into the chat tree, once per commands packet.
        if (before != value) {
            val mc = Minecraft.getInstance()
            mc.execute { if (mc.connection != null) ClientCommands.refreshCommandCompletions() }
        }
        try {
            val f = file
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(Stored.serializer(), Stored(value)))
        } catch (e: Exception) {
            logger.warn("Could not persist developer access", e)
        }
    }

    private fun load() {
        cached = try {
            val f = file
            if (f.exists()) json.decodeFromString(Stored.serializer(), f.readText()).unlocked else null
        } catch (e: Exception) {
            logger.warn("Could not read developer access", e)
            null
        }
    }
}
