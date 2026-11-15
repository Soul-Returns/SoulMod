package com.soulreturns.features.islandchests

internal class IslandChestSearchSession {
    data class Search(val query: String = "", val itemKey: String? = null)

    private var profile: String? = null
    private var search = Search()
    private var requested = false

    fun remember(profileId: String?, query: String, itemKey: String) {
        profile = profileId
        search = Search(query, itemKey)
    }

    fun request(enabled: Boolean, inWorld: Boolean, screenOpen: Boolean, focused: Boolean): Boolean {
        if (!canOpen(enabled, inWorld, screenOpen, focused)) return false
        requested = true
        return true
    }

    fun takeRequest(enabled: Boolean, inWorld: Boolean, screenOpen: Boolean, focused: Boolean): Boolean {
        val pending = requested
        requested = false
        return pending && canOpen(enabled, inWorld, screenOpen, focused)
    }

    fun restore(profileId: String?, locatingKey: String?): Search =
        if (profileId != null && profileId == profile) search.copy(itemKey = locatingKey ?: search.itemKey) else Search()

    private fun canOpen(enabled: Boolean, inWorld: Boolean, screenOpen: Boolean, focused: Boolean): Boolean =
        enabled && inWorld && !screenOpen && focused
}
