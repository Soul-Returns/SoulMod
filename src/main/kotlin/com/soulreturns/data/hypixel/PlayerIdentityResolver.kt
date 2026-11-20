package com.soulreturns.data.hypixel

import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.platform.http.SoulHttp
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Collections

/** A resolved player: their 32-hex undashed uuid and Mojang's canonical casing of their name. */
data class PlayerIdentity(val uuidUndashed: String, val name: String)

/**
 * Username → uuid.
 *
 * **Primary path is the backend** (`GET /player/resolve?name=<name>`), not Mojang directly. The
 * legacy mod hit `api.mojang.com` from every client: Mojang rate-limits that per IP and returns no
 * useful error body, so N users meant N caches and N callers. Going through the backend gives one
 * shared 6-hour cache, one well-behaved caller, and an instant answer for anyone who has ever
 * authenticated with Soul (the backend checks its own `Player` table first, and
 * `TokenManager::issueForPlayer` keeps that name current).
 *
 * **Mojang stays as a fallback**, for exactly one situation: the backend is unreachable, broken, rate
 * limited, or told us it could not reach Mojang itself (`502 upstream_unavailable`). Then `/sp`
 * still works.
 *
 * ### Deliberate deviation from the port document
 * The document said to fall back to Mojang "if it 404s **or** the backend is unreachable". We do
 * **not** fall back on a 404. The backend consults Mojang itself before answering 404, so a fallback
 * there is a second request for an answer we already have — and the lane's own acceptance criterion
 * requires `/sp <nonexistent>` to cost exactly **one** request. A 404 is therefore final.
 */
object PlayerIdentityResolver {
    private val logger = SoulLogger("Soul/Identity")

    const val INTENT = "spv-resolve"

    /** Mojang's name→profile endpoint. Only used when the backend cannot answer. */
    private const val MOJANG_PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/"

    /** Hypixel/Minecraft usernames. Check before spending a request on an impossible name. */
    val NAME_PATTERN = Regex("[A-Za-z0-9_]{1,16}")

    private const val CACHE_SIZE = 64

    /**
     * Access-ordered LRU, keyed by lowercased name — a user paging through a party roster
     * re-resolves the same handful of names, and a name→uuid mapping is stable for at least the
     * 30-day rename cooldown. Only *successful* resolutions are cached: caching a miss would hide a
     * name that gets registered a minute later.
     */
    private val cache: MutableMap<String, PlayerIdentity> = Collections.synchronizedMap(
        object : LinkedHashMap<String, PlayerIdentity>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlayerIdentity>?): Boolean =
                size > CACHE_SIZE
        },
    )

    fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

    /** Already-known identity for [name], without touching the network. */
    fun cached(name: String): PlayerIdentity? = cache[name.lowercase()]

    fun clearCache() = cache.clear()

    /**
     * Canonical names resolved this session, most recently used first — the tab-completion source
     * for `/sp`, so a name looked up once can be typed with two keystrokes afterwards.
     */
    fun cachedNames(): List<String> = synchronized(cache) { cache.values.map { it.name }.reversed() }

    /** For `/soul dev` — how many names are memoized. */
    fun cacheSize(): Int = cache.size

    /**
     * Resolve [name] to a [PlayerIdentity], or null when the player does not exist.
     *
     * @return null for "no such player" **and** for "could not find out" — [ResolveOutcome] is the
     *   version that distinguishes them. Callers that talk to the user should use [resolveDetailed]
     *   so they can say "unknown player" and "backend unreachable" differently.
     */
    suspend fun resolve(name: String): PlayerIdentity? = resolveDetailed(name).identity

    /** [resolve] plus why it failed, so the chat line can be specific. */
    suspend fun resolveDetailed(name: String): ResolveOutcome {
        val trimmed = name.trim()
        if (!isValidName(trimmed)) return ResolveOutcome(null, ResolveOutcome.INVALID_NAME, "invalid username")

        cache[trimmed.lowercase()]?.let { return ResolveOutcome(it, ResolveOutcome.CACHED, "cached") }

        val backend = BackendClient.get(
            endpoint = "/player/resolve?name=$trimmed",
            serializer = ResolveResponse.serializer(),
            intent = INTENT,
        )
        if (backend is BackendResult.Ok) {
            val identity = backend.value.toIdentity()
            if (identity != null) {
                remember(identity)
                return ResolveOutcome(identity, ResolveOutcome.BACKEND, "backend")
            }
            // A 200 whose body we cannot make a uuid out of is a contract break, not a miss. Say so
            // loudly — silently falling through to Mojang would hide it forever.
            logger.warn("/player/resolve returned 200 with an unusable body for '$trimmed'")
        } else {
            val failure = backend as BackendResult.Failure
            // 404 is Mojang's own answer, relayed. Final — do not ask Mojang the same question.
            if (failure.status == 404) {
                return ResolveOutcome(null, ResolveOutcome.UNKNOWN_PLAYER, "no such player")
            }
            logger.debug("/player/resolve failed for '$trimmed' (${failure.status}) — trying Mojang directly")
        }

        val direct = resolveViaMojang(trimmed)
        if (direct != null) {
            remember(direct)
            return ResolveOutcome(direct, ResolveOutcome.MOJANG_FALLBACK, "Mojang (backend unavailable)")
        }
        return ResolveOutcome(null, ResolveOutcome.UNAVAILABLE, "could not reach the backend or Mojang")
    }

    private fun remember(identity: PlayerIdentity) {
        cache[identity.name.lowercase()] = identity
    }

    /**
     * The legacy direct-Mojang path, kept as the fallback. A `204`/`404` there means the name does
     * not exist — but we only get here when the backend already failed, so we cannot tell that apart
     * from a broken lookup with any confidence, and both return null.
     */
    private suspend fun resolveViaMojang(name: String): PlayerIdentity? = withContext(Dispatchers.IO) {
        try {
            val response = SoulHttp.get(MOJANG_PROFILE_URL + name)
            if (response.statusCode() != 200 || response.body().isNullOrBlank()) {
                logger.debug("Mojang lookup '$name' -> HTTP ${response.statusCode()}")
                return@withContext null
            }
            hypixelJson.decodeFromString(MojangProfile.serializer(), response.body()).toIdentity(name)
        } catch (t: Throwable) {
            logger.debug("Mojang lookup '$name' failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** The result of a resolve attempt: the identity if there is one, plus why not if there is not. */
    data class ResolveOutcome(val identity: PlayerIdentity?, val source: String, val detail: String) {
        val resolved: Boolean get() = identity != null

        companion object {
            const val CACHED = "cache"
            const val BACKEND = "backend"
            const val MOJANG_FALLBACK = "mojang"
            const val INVALID_NAME = "invalid"
            const val UNKNOWN_PLAYER = "unknown"
            const val UNAVAILABLE = "unavailable"
        }
    }
}

/** `GET /player/resolve` → `{"uuid": "<32-hex undashed>", "name": "<canonical casing>"}`. */
@Serializable
private data class ResolveResponse(val uuid: String? = null, val name: String? = null) {
    fun toIdentity(): PlayerIdentity? {
        // Normalise through the one uuid helper rather than trusting the field's form: this also
        // covers the backend answering from its own `Player` row, where the stored value could be
        // dashed or upper-case without ever being wrong.
        val undashed = MojangUuid.undashedOrNull(uuid)?.lowercase() ?: return null
        val canonical = name?.takeIf { it.isNotBlank() } ?: return null
        return PlayerIdentity(undashed, canonical)
    }
}

/** Mojang's `users/profiles/minecraft/<name>` → `{"id": "<32-hex>", "name": "<canonical>"}`. */
@Serializable
private data class MojangProfile(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
) {
    fun toIdentity(requested: String): PlayerIdentity? {
        val undashed = MojangUuid.undashedOrNull(id)?.lowercase() ?: return null
        return PlayerIdentity(undashed, name?.takeIf { it.isNotBlank() } ?: requested)
    }
}
