package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.data.hypixel.HypixelProfileClient
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The **local** player's active SkyBlock profile, fetched from Hypixel through the backend proxy.
 *
 * This is the self-directed half of the profile data layer: where the Profile Viewer answers "show me
 * that player", this answers "what does Hypixel currently say about *me*", which is the authoritative
 * side of state the client can only estimate by watching GUIs — sack counts, collections, accessory
 * bag. It owns none of those; it holds the snapshot and publishes [SelfProfileLoaded], and the
 * features that want them (later lanes) read [snapshot] or subscribe.
 *
 * ### Trigger model
 * One fetch per [ProfileChanged], and nothing else. No polling: a profile switch is the only signal
 * that authoritative state should have changed, and the response is cached for 60 s at both the
 * backend and [com.soulreturns.platform.http.BackendClient], so `/profile` spam costs one request.
 *
 * ### Matching by id or name, never by `selected`
 * The profile is identified by the exact id from Hypixel's join chat
 * ([SkyblockProfileState.profileId]) when it arrived for this profile, else by matching
 * [ProfileChanged.current] against `cute_name`. The profiles request itself stays: the backend only
 * proxies the by-player endpoint, and the member data is what consumers want. Hypixel's own
 * `selected` flag can lag a switch by up to its cache window, so trusting it would occasionally seed
 * every consumer with the *previous* profile's data — silently, and in the direction that looks
 * plausible. If the profile is not in the response, this loads nothing and says so; stale data
 * that looks current is worse than no data.
 */
object SkyblockSelfProfile {
    private val logger = SoulLogger("Soul/SelfProfile")

    private val registered = AtomicBoolean(false)

    /**
     * Bumped on every load attempt. A response whose generation is no longer current is dropped —
     * two quick `/profile` switches must not let the first response overwrite the second.
     */
    private val generation = AtomicLong()

    @Volatile
    var snapshot: SelfProfileSnapshot? = null
        private set

    /** Why the last attempt ended as it did — `/soul dev profile` prints this. */
    @Volatile
    var lastOutcome: String = "not loaded yet"
        private set

    private val attempts = AtomicLong()

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        Events.subscribe<ProfileChanged> { event ->
            val profile = event.current
            if (profile == null) {
                // Leaving SkyBlock does not invalidate what Hypixel said; a warp clears the reader's
                // *view*, not the data. Keep the snapshot and let the next real switch replace it.
                return@subscribe
            }
            load(profile, reason = "profile changed")
        }
    }

    /** Force a reload of the active profile. Returns what it did, for the dev command. */
    fun reload(): String {
        val profile = SkyblockProfileState.current ?: return "no active SkyBlock profile detected"
        load(profile, reason = "manual reload", bypassCache = true)
        return "reload dispatched for profile '$profile'"
    }

    @Volatile
    private var lastStaleRefreshAt = 0L

    /**
     * Reload when the snapshot is older than [maxAgeMs] or belongs to another profile, at most once
     * per [maxAgeMs]. Goes through the response cache (the backend's own TTL is a minute), so
     * opening the inventory repeatedly costs nothing extra.
     */
    fun refreshIfStale(maxAgeMs: Long) {
        val profile = SkyblockProfileState.current ?: return
        val now = System.currentTimeMillis()
        val snap = snapshot
        val fresh = snap != null && snap.profileName.equals(profile, ignoreCase = true) &&
            now - snap.loadedAt.toEpochMilli() < maxAgeMs
        if (fresh || now - lastStaleRefreshAt < maxAgeMs) return
        lastStaleRefreshAt = now
        load(profile, reason = "stale refresh")
    }

    private fun load(profileName: String, reason: String, bypassCache: Boolean = false) {
        val uuid = selfUuid()
        if (uuid == null) {
            lastOutcome = "no Mojang uuid available"
            logger.debug("Cannot load profile '$profileName' — no uuid")
            return
        }
        val mine = generation.incrementAndGet()
        attempts.incrementAndGet()
        SoulScope.io.launch { fetch(mine, uuid, profileName, reason, bypassCache) }
    }

    private suspend fun fetch(
        mine: Long,
        uuidUndashed: String,
        profileName: String,
        reason: String,
        bypassCache: Boolean,
    ) {
        val result = HypixelProfileClient.profiles(
            uuidUndashed,
            HypixelProfileClient.INTENT_SELF,
            bypassCache = bypassCache,
        )
        if (generation.get() != mine) {
            logger.debug("Dropping stale self-profile response for '$profileName'")
            return
        }
        if (result !is BackendResult.Ok) {
            val failure = result as BackendResult.Failure
            lastOutcome = "fetch failed: HTTP ${failure.status} ${failure.message}"
            logger.debug("Self-profile fetch failed ($reason): ${failure.status} ${failure.message}")
            return
        }

        // The join chat's exact id wins when it is there for this profile (it is read now, not at
        // dispatch: the id line arrives right after the name line that triggered the load).
        val chatId = SkyblockProfileState.profileId
            ?.takeIf { SkyblockProfileState.current.equals(profileName, ignoreCase = true) }
        val profile = if (chatId != null) result.value.byId(chatId) else result.value.byCuteName(profileName)
        if (profile == null) {
            val wanted = if (chatId != null) "id $chatId ('$profileName')" else "'$profileName'"
            lastOutcome = "profile $wanted not in the response " +
                "(has: ${result.value.cuteNames().joinToString()})"
            logger.info("Hypixel response has no profile $wanted")
            return
        }
        val member = profile.member(uuidUndashed)
        if (member == null) {
            lastOutcome = "not a member of '$profileName' in the response"
            logger.warn("members[] has no entry for self on profile ${profile.profileId}")
            return
        }

        val loaded = SelfProfileSnapshot(
            profileName = profile.cuteName,
            profileId = profile.profileId,
            uuidUndashed = uuidUndashed,
            gameMode = profile.gameMode,
            member = member,
            fromCache = result.fromCache,
            loadedAt = Instant.now(),
        )
        snapshot = loaded
        lastOutcome = "loaded '${profile.cuteName}'" +
            " (${member.sacks.size} sack entries, ${member.skills.size} skills)" +
            if (result.fromCache) " from cache" else ""
        logger.info(
            "Self profile loaded ($reason): '${profile.cuteName}' — ${member.sacks.size} sack entries" +
                if (result.fromCache) " (cached)" else "",
        )

        // Publish on the client thread: consumers of this are features that touch Minecraft state,
        // and a coroutine on Dispatchers.IO is not a thread they may do that from (CLAUDE.md — a
        // publisher owes its subscribers a sane thread).
        onClientThread { Events.publish(SelfProfileLoaded(loaded)) }
    }

    /** For `/soul dev profile`. */
    fun status(): String {
        val snap = snapshot
        val head = "attempts=${attempts.get()} · $lastOutcome"
        return if (snap == null) head else "$head\n${snap.describe()}"
    }

    /** Own uuid as Hypixel wants it: 32-hex, undashed. */
    private fun selfUuid(): String? = try {
        MojangUuid.undashed(Minecraft.getInstance().user.profileId)
    } catch (_: Throwable) {
        null
    }

    private fun onClientThread(block: () -> Unit) {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) block() else mc.execute(block)
    }
}

/**
 * What Hypixel says about the local player on their active profile, as of [loadedAt].
 *
 * Holds the [SkyblockProfileMember] view rather than a copy of the fields, so a later lane can read
 * something out of it without this class having to know about it first.
 */
data class SelfProfileSnapshot(
    val profileName: String,
    val profileId: String,
    val uuidUndashed: String,
    val gameMode: String?,
    val member: SkyblockProfileMember,
    /** True when it came from the response cache rather than a fresh request. */
    val fromCache: Boolean,
    val loadedAt: Instant,
) {
    fun describe(): String =
        "profile='$profileName' id=$profileId mode=${gameMode ?: "normal"} " +
            "sacks=${member.sacks.size} skills=${member.skills.size} " +
            "cata=${member.catacombsLevel.level} apiAccessOff=${member.apiAccessDisabled} " +
            "loadedAt=$loadedAt${if (fromCache) " (cached)" else ""}"
}

/** The local player's active profile was (re)loaded from Hypixel. Published on the client thread. */
data class SelfProfileLoaded(val snapshot: SelfProfileSnapshot) : Event
