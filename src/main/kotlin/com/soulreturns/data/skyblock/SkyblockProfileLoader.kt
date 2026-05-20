package com.soulreturns.data.skyblock

import com.google.gson.JsonObject
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ProfileChanged
import com.soulreturns.features.sacks.SackState
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.util.SoulLogger
import net.minecraft.client.Minecraft
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pulls the active SkyBlock profile's authoritative state from the backend's
 * `/skyblock/profiles?uuid=<mojang-uuid>` proxy (which forwards to Hypixel's
 * `api.hypixel.net/v2/skyblock/profiles` and caches the result for 60s).
 *
 * **What we seed today.** Sack counts — `members[<my-uuid>].inventory.sacks_counts` is a
 * flat `Map<skyblockId, Long>` that maps cleanly onto [SackState]. Sample sizes seen
 * in the wild: ~100-600 entries per profile. The reader REPLACES the active profile's
 * sack state (not merges) because Hypixel's number is the source of truth at API tick
 * time — any local data is either older or a partial GUI scan that this overrides.
 *
 * **Trigger model.** Subscribes to [ProfileChanged]; every profile transition (initial
 * detection at session start, manual `/profile` switch, ...) kicks off a fresh fetch.
 * [SackState]'s own [ProfileChanged] handler runs first (legacy-bucket promotion),
 * then the async fetch completes and replaces the now-active profile's map. No
 * polling — the backend's 60s cache shields Hypixel from rate-limit hits and the
 * profile-change event is the only signal that authoritative state should change.
 *
 * **Why through the backend, not Hypixel directly.** `api.hypixel.net/v2/skyblock/profiles`
 * requires an `API-Key` header (each user would need their own dev key — bad UX). The
 * backend holds a single key, authenticates the mod via the existing bearer-token, and
 * caches per-UUID. The mod stays unauthenticated for the user-facing path.
 *
 * **Future scope hooks.** Once the backend prompt for inventory display lands, the
 * same fetch will feed other features (accessory bag, equipment, collections). For
 * now, this reader's only side-effect is updating [SackState].
 */
object SkyblockProfileLoader {
    private val logger = SoulLogger("Soul/SkyblockProfile")
    private val registered = AtomicBoolean(false)

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        Events.subscribe<ProfileChanged> { event ->
            val newProfile = event.to ?: return@subscribe
            loadFor(newProfile)
        }
    }

    /** Manual trigger — used by `/soul dev loadProfile`. */
    fun loadActiveNow(): String {
        val profile =
            com.soulreturns.data.profile.ProfileApi.currentProfile
                ?: return "no active SkyBlock profile detected"
        loadFor(profile)
        return "fetch dispatched for profile '$profile'"
    }

    private fun loadFor(profileName: String) {
        val uuid = clientUuid()
        if (uuid == null) {
            logger.info("Cannot load profile '$profileName' — no Mojang UUID available")
            return
        }
        BackendClient.get("/skyblock/profiles?uuid=$uuid", intent = "skyblock-profile-load")
            .whenComplete { result, throwable ->
                if (throwable != null) {
                    logger.warn("Profile fetch threw: ${throwable.message}", throwable)
                    return@whenComplete
                }
                // `if (result is …)` chain rather than `when (result)` — sealed-class subjects
                // trigger the $WhenMappings KnotClassLoader trap (CLAUDE.md).
                if (result is BackendClient.Result.Ok) {
                    val obj =
                        try {
                            result.json.asJsonObject
                        } catch (e: Exception) {
                            logger.warn("Profile response not a JSON object: ${e.message}")
                            return@whenComplete
                        }
                    processResponse(obj, uuid, profileName)
                } else if (result is BackendClient.Result.Error) {
                    logger.info("Profile fetch failed: HTTP ${result.statusCode} ${result.message}")
                }
            }
    }

    private fun processResponse(
        root: JsonObject,
        queriedUuid: String,
        profileName: String,
    ) {
        if (!root.has("success") || !root.get("success").asBoolean) {
            logger.warn("Profile fetch response success=false")
            return
        }
        val profiles = root.getAsJsonArray("profiles") ?: return
        // Match by cute_name — Hypixel returns every profile the queried UUID is a member
        // of; we want the one whose name corresponds to the current ProfileChanged event.
        var target: JsonObject? = null
        for (p in profiles) {
            val obj = p.asJsonObject
            if (obj.get("cute_name")?.asString == profileName) {
                target = obj
                break
            }
        }
        if (target == null) {
            logger.info("Profile '$profileName' not found in Hypixel response")
            return
        }
        val members = target.getAsJsonObject("members")
        if (members == null) {
            logger.info("Profile '$profileName' has no members object")
            return
        }
        val member = members.getAsJsonObject(queriedUuid)
        if (member == null) {
            logger.info("Player '$queriedUuid' not a member of profile '$profileName'")
            return
        }
        val inventory = member.getAsJsonObject("inventory")
        if (inventory == null) {
            logger.info("Profile '$profileName' has no inventory key (API access disabled?)")
            return
        }
        val sacks = inventory.getAsJsonObject("sacks_counts")
        if (sacks == null) {
            logger.info("Profile '$profileName' has no inventory.sacks_counts")
            return
        }
        val snap = HashMap<String, Long>(sacks.size())
        for ((k, v) in sacks.entrySet()) {
            try {
                snap[k] = v.asLong
            } catch (_: Throwable) {
                // Non-numeric value (shouldn't happen for sacks_counts but defensive)
            }
        }
        SackState.replaceActiveProfile(snap)
        logger.info("Hypixel sack snapshot applied: profile='$profileName', ${snap.size} entries")
    }

    /** Mojang UUID as 32-char lowercase hex (no dashes — Hypixel's required format). */
    private fun clientUuid(): String? {
        val user = Minecraft.getInstance()?.user ?: return null
        val uuid = user.profileId ?: return null
        return uuid.toString().replace("-", "")
    }
}
