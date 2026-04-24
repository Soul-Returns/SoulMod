package com.soulreturns.profileviewer.model

import com.google.gson.JsonObject

/**
 * Lightweight wrappers around the raw Hypixel JSON. We expose typed getters
 * for fields we know we use; everything else stays as JsonObject so unknown
 * data is still reachable later.
 */
data class ProfileSummary(
    val profileId: String,
    val cuteName: String,
    val gameMode: String?,
    val selected: Boolean,
)

data class SkyblockProfilesResponse(
    val raw: JsonObject,
    val profiles: List<SkyblockProfile>,
) {
    fun selected(): SkyblockProfile? = profiles.firstOrNull { it.selected }
    fun byCuteName(name: String): SkyblockProfile? =
        profiles.firstOrNull { it.cuteName.equals(name, ignoreCase = true) }
}

data class SkyblockProfile(
    val raw: JsonObject,
    val profileId: String,
    val cuteName: String,
    val gameMode: String?,
    val selected: Boolean,
    val members: Map<String, JsonObject>,
) {
    fun summary() = ProfileSummary(profileId, cuteName, gameMode, selected)

    fun memberFor(uuidUndashed: String): JsonObject? = members[uuidUndashed]
}
