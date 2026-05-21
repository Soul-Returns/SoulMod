package com.soulreturns.data.skyblock

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.MayorChanged
import com.soulreturns.platform.http.SoulHttp
import com.soulreturns.util.SoulLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only access to the current Hypixel SkyBlock mayor + minister + their elected perks.
 *
 * Fed by a background daemon polling `https://api.hypixel.net/v2/resources/skyblock/election`
 * every [POLL_INTERVAL_MS]. The endpoint is public (no key) and tiny (~3 KB), so the cost is
 * negligible. We deliberately do **not** gate the poll on [SkyblockApi.isOnSkyblock] — the
 * Mythological Event tab is meaningful even when the player is idle on the title screen,
 * provided the mayor lookup has succeeded at least once this session.
 *
 * **Term key.** Two mayor terms can share the same name (Diana cycles every few years) but
 * are distinguished by their election year. The canonical key returned by [currentMayorKey]
 * is `"${name}_${election.year}"` — stable for the lifetime of a single mayor term, even
 * across mayor → mayor → same-name-mayor sequences. Trackers persist this string as their
 * event-bucket key.
 *
 * Owns its own daemon thread (`soul-mayor`) — never run on `SoulExecutor`, same lesson as
 * [com.soulreturns.platform.sync.SyncEngine] / `RealtimeClient` / `PriceCache`.
 */
object MayorState {
    private val logger = SoulLogger("Soul/Mayor")

    private const val ELECTION_URL = "https://api.hypixel.net/v2/resources/skyblock/election"

    // 15 minutes — mayors only cycle every ~5 days SkyBlock time (~5 hours real time per
    // SkyBlock year), so the data effectively never changes between polls. Long enough that
    // a quiet user costs nothing; short enough that a fresh login lands a real value within
    // one tick of the daemon waking up.
    private const val POLL_INTERVAL_MS = 15L * 60L * 1000L

    data class MayorInfo(
        val key: String,
        val name: String,
        val electionYear: Int,
        val perks: List<String>,
    ) {
        /** Stable identifier for this mayor term across the codebase. */
        val termKey: String get() = "${name}_$electionYear"
    }

    data class MinisterInfo(
        val key: String,
        val name: String,
        val perk: String?,
    )

    @Volatile
    var currentMayor: MayorInfo? = null
        private set

    @Volatile
    var currentMinister: MinisterInfo? = null
        private set

    private val lastRefresh = AtomicLong(0)

    @Volatile private var thread: Thread? = null

    @Volatile private var running: Boolean = false

    /** Canonical term key for the current mayor — null if the API hasn't responded yet. */
    fun currentMayorKey(): String? = currentMayor?.termKey

    /**
     * Convenience predicate — does the current mayor's perk list contain [perkName]
     * (case-insensitive contains-match against any perk title)? Used by the Mythological
     * tracker to detect whether Diana's Mythological Ritual perk is the active one (Diana
     * has two perks each term — the rotation cycles which mob set spawns).
     */
    fun mayorHasPerk(perkName: String): Boolean = currentMayor?.perks?.any { it.contains(perkName, ignoreCase = true) } == true

    fun start() {
        if (running) return
        running = true
        val t =
            Thread({ runLoop() }, "soul-mayor").apply {
                isDaemon = true
            }
        thread = t
        t.start()
    }

    /** Force an immediate synchronous fetch. Used by `/soul dev refreshMayor`. */
    fun refreshNow(): String {
        lastRefresh.set(0)
        return fetchElection()
    }

    private fun runLoop() {
        while (running) {
            try {
                fetchElection()
                Thread.sleep(SLEEP_TICK_MS)
            } catch (_: InterruptedException) {
                // Either shutdown or refreshNow() woke us — re-enter the loop; the rate
                // limiter decides whether real work fires.
            } catch (t: Throwable) {
                logger.warn("Mayor poll loop error: ${t.message}", t)
                try {
                    Thread.sleep(SLEEP_TICK_MS)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun fetchElection(): String {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastRefresh.get()
        if (sinceLast < POLL_INTERVAL_MS) return "skipped (${sinceLast / 1000}s ago)"
        return try {
            val response = SoulHttp.get(ELECTION_URL)
            if (response.statusCode() / 100 != 2) {
                logger.warn("Election fetch failed: HTTP ${response.statusCode()}")
                return "HTTP ${response.statusCode()}"
            }
            val parsed = parseElection(response.body())
            lastRefresh.set(now)
            applyMayor(parsed.first, parsed.second)
            "mayor=${parsed.first?.name ?: "?"} minister=${parsed.second?.name ?: "?"}"
        } catch (t: Throwable) {
            logger.warn("Election fetch error: ${t.message}", t)
            "error: ${t.message}"
        }
    }

    private fun applyMayor(
        mayor: MayorInfo?,
        minister: MinisterInfo?,
    ) {
        val previous = currentMayor
        currentMayor = mayor
        currentMinister = minister
        if (mayor?.termKey != previous?.termKey) {
            logger.info(
                "Mayor changed: ${previous?.name ?: "?"} (${previous?.termKey ?: "?"}) → " +
                    "${mayor?.name ?: "?"} (${mayor?.termKey ?: "?"})",
            )
            Events.publish(
                MayorChanged(
                    previousKey = previous?.termKey,
                    previousName = previous?.name,
                    currentKey = mayor?.termKey,
                    currentName = mayor?.name,
                ),
            )
        }
    }

    private fun parseElection(body: String): Pair<MayorInfo?, MinisterInfo?> {
        val root = JsonParser.parseString(body).asJsonObject
        if (!root.has("success") || !root.get("success").asBoolean) {
            logger.warn("Election response success=false")
            return null to null
        }
        val mayorJson = root.getAsJsonObject("mayor") ?: return null to null
        val mayor = parseMayor(mayorJson)
        val minister =
            mayorJson.getAsJsonObject("minister")?.let { parseMinister(it) }
        return mayor to minister
    }

    private fun parseMayor(obj: JsonObject): MayorInfo? {
        val name = obj.get("name")?.asString ?: return null
        val key = obj.get("key")?.asString ?: return null
        val year =
            obj.getAsJsonObject("election")?.get("year")?.asInt
                ?: return null
        val perks =
            obj.getAsJsonArray("perks")?.mapNotNull { p ->
                p.asJsonObject.get("name")?.asString
            } ?: emptyList()
        return MayorInfo(key = key, name = name, electionYear = year, perks = perks)
    }

    private fun parseMinister(obj: JsonObject): MinisterInfo? {
        val name = obj.get("name")?.asString ?: return null
        val key = obj.get("key")?.asString ?: return null
        val perkName = obj.getAsJsonObject("perk")?.get("name")?.asString
        return MinisterInfo(key = key, name = name, perk = perkName)
    }

    private const val SLEEP_TICK_MS = 60L * 1000L
}
