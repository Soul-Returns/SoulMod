package com.soulreturns.data.hypixel

import com.soulreturns.util.MojangUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A parsed `GET /skyblock/profiles?uuid=<32-hex>` body.
 *
 * The backend is a **pure proxy** for this endpoint — the body is Hypixel's, verbatim, including its
 * own `success` flag. That flag can be `false` on an HTTP **200**, so [success] must be checked
 * rather than inferred from the status code (see [HypixelProfileClient], which does).
 *
 * @property raw the whole body, so a later lane can read a field this one never modelled.
 */
class SkyblockProfiles internal constructor(
    val raw: JsonObject,
    val success: Boolean,
    /** Hypixel's own error text when [success] is false. */
    val cause: String?,
    val profiles: List<SkyblockProfile>,
) {
    val isEmpty: Boolean get() = profiles.isEmpty()

    /** The profile the player is currently playing, if Hypixel flagged one. */
    fun selected(): SkyblockProfile? = profiles.firstOrNull { it.selected }

    fun byCuteName(name: String): SkyblockProfile? =
        profiles.firstOrNull { it.cuteName.equals(name, ignoreCase = true) }

    /** Exact lookup by `profile_id`, dashed or not. */
    fun byId(id: String): SkyblockProfile? {
        val wanted = MojangUuid.parse(id) ?: return null
        return profiles.firstOrNull { MojangUuid.parse(it.profileId) == wanted }
    }

    /** Every profile's display name, for the "did you mean" list on a bad override. */
    fun cuteNames(): List<String> = profiles.map { it.cuteName }

    /**
     * Pick the profile to show: an explicit `cute_name` override, else the one Hypixel flags
     * `selected`, else the first.
     *
     * A **non-matching override is an error, not a fallback** — silently showing a different
     * profile than the one asked for is the kind of thing a user never notices and then reports as
     * wrong numbers. Returns null in that case; the caller lists [cuteNames] instead.
     */
    fun pick(override: String?): SkyblockProfile? {
        if (override != null) return byCuteName(override)
        return selected() ?: profiles.firstOrNull()
    }

    companion object {
        /** Parse a raw Hypixel body. Never throws: a malformed profile entry is skipped, not fatal. */
        fun from(raw: JsonObject): SkyblockProfiles {
            val profiles = (raw["profiles"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { element -> (element as? JsonObject)?.let(SkyblockProfile::from) }
                ?: emptyList()
            return SkyblockProfiles(
                raw = raw,
                // Absent `success` on a 2xx body means Hypixel changed something; treat the body we
                // could parse as usable rather than discarding profiles we are holding.
                success = raw.boolean("success", fallback = profiles.isNotEmpty()),
                cause = raw.string("cause"),
                profiles = profiles,
            )
        }
    }
}

/**
 * One SkyBlock profile ("Banana", "Coconut", …).
 *
 * @property raw the profile object as Hypixel sent it — `banking`, `community_upgrades` and
 *   everything else this lane does not model stays reachable here.
 */
class SkyblockProfile internal constructor(
    val raw: JsonObject,
    val profileId: String,
    val cuteName: String,
    /** `ironman`, `island` (stranded), `bingo`, … or null for a normal profile. */
    val gameMode: String?,
    val selected: Boolean,
) {
    /** True for any profile that is not a normal one — worth flagging, the numbers differ. */
    val isSpecialGameMode: Boolean get() = !gameMode.isNullOrBlank()

    /**
     * A member's data, by **undashed** 32-hex uuid — the form Hypixel keys `members` with. Pass
     * anything through [com.soulreturns.util.MojangUuid.undashed] first; a dashed key never matches.
     */
    fun member(uuidUndashed: String): SkyblockProfileMember? =
        raw.path("members")?.obj(uuidUndashed)?.let { SkyblockProfileMember(it) }

    /** Every member uuid on this profile (co-op included), undashed. */
    fun memberUuids(): Set<String> = raw.path("members")?.keys ?: emptySet()

    override fun toString(): String = "SkyblockProfile($cuteName, id=$profileId, selected=$selected)"

    companion object {
        internal fun from(raw: JsonObject): SkyblockProfile? {
            val header = try {
                hypixelJson.decodeFromJsonElement(ProfileHeader.serializer(), raw)
            } catch (_: Throwable) {
                // One unreadable entry must not cost the whole list — a co-op profile with an odd
                // field should not hide the four good profiles next to it.
                null
            } ?: return null
            val id = header.profileId ?: return null
            return SkyblockProfile(
                raw = raw,
                profileId = id,
                // Hypixel has been seen omitting cute_name on freshly created profiles; the id's
                // first block is at least stable and recognisable in a list.
                cuteName = header.cuteName?.takeIf { it.isNotBlank() } ?: id.take(8),
                gameMode = header.gameMode?.takeIf { it.isNotBlank() },
                selected = header.selected,
            )
        }
    }
}

/**
 * The typed part of a profile object. Declared with defaults everywhere and decoded with
 * `ignoreUnknownKeys`, so Hypixel adding or dropping a key is a non-event.
 */
@Serializable
private data class ProfileHeader(
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("cute_name") val cuteName: String? = null,
    @SerialName("game_mode") val gameMode: String? = null,
    val selected: Boolean = false,
)
