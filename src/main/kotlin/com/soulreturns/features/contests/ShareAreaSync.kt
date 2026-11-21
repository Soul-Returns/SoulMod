package com.soulreturns.features.contests

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.features.contests.model.FriendsBody

/**
 * "Friends can see where I am": the local setting (`cfg.contests.shareArea`, default on) is what the
 * player chose; the server's copy (`FriendsBody.me.shareArea`, `POST /friends/settings`) follows it.
 * Pushed when changed here, and again whenever a friends load shows the server disagreeing.
 */
internal object ShareAreaSync {
    @Volatile private var pushing = false

    fun set(share: Boolean, onDone: (ActionResult) -> Unit) {
        if (cfg.contests.shareArea != share) ConfigManager.update { copy(contests = contests.copy(shareArea = share)) }
        push(share, onDone)
    }

    fun onFriendsLoaded(body: FriendsBody) {
        val want = cfg.contests.shareArea
        if (body.me.shareArea != want && !pushing) push(want) {}
    }

    private fun push(share: Boolean, onDone: (ActionResult) -> Unit) {
        pushing = true
        ContestsStore.friendCall({ result ->
            pushing = false
            onDone(result)
        }) { ContestsApi.setShareArea(share) }
    }
}
