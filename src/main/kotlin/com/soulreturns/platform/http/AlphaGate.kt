package com.soulreturns.platform.http

import com.soulreturns.api.HypixelNetwork
import com.soulreturns.util.SoulLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * Refuses backend requests while the connection is on the Hypixel Alpha network (rule://backend,
 * "Alpha gate"). Default deny: an endpoint missing from [allows] stays closed there until someone
 * has checked that nothing it sends comes from the game.
 */
internal object AlphaGate {
    private val logger = SoulLogger("Soul/Backend")

    /** Catalog, price, update and dev-access reads, and lookups keyed only on a Mojang name or uuid. */
    private val GET_PATHS = setOf(
        "/items", "/prices", "/drops", "/sea-creatures", "/recipes", "/sacks", "/market/values",
        "/mod/update", "/dev/access", "/player/resolve", "/skyblock/profiles",
    )
    private val GET_PATTERNS = listOf(
        Regex("/mod/update/[^/]+/(jar|manifest)"),
        Regex("/player/[0-9a-fA-F]{32}/(skyblock-caps|soul-stats)"),
    )

    /** `/presence/state` is masked by `PresenceService` (no SkyBlock fields on Alpha). */
    private val POST_PATHS = setOf("/dev/unlock", "/presence/state")

    private val refusedCount = AtomicLong()
    private val loggedConnection = AtomicLong(-1)

    val refused: Long get() = refusedCount.get()

    /** Whether [method] [path] may go out from the Alpha network; [path] may carry a query string. */
    fun allows(method: String, path: String): Boolean {
        val bare = path.substringBefore('?')
        if (method == "GET") return bare in GET_PATHS || GET_PATTERNS.any { it.matches(bare) }
        return method == "POST" && bare in POST_PATHS
    }

    /** Null when the request may be sent now, else the failure to return without sending it. */
    fun refusal(method: String, path: String): BackendResult.Failure? {
        if (!HypixelNetwork.isAlpha || allows(method, path)) return null
        refusedCount.incrementAndGet()
        val endpoint = "$method ${path.substringBefore('?')}"
        val connection = HypixelNetwork.alphaConnection
        if (loggedConnection.getAndSet(connection) != connection) {
            logger.warn("Hypixel Alpha network: refusing backend requests with game data until disconnect (first: $endpoint)")
        }
        return BackendResult.Failure(BackendResult.Failure.ALPHA_BLOCKED, "not sent from the Hypixel Alpha network: $endpoint")
    }
}
