package com.soulreturns.features.contests.gui

import com.soulreturns.features.contests.ContestsNav
import com.soulreturns.features.contests.hud.ContestEndReveal
import com.soulreturns.features.contests.hud.ContestHud
import com.soulreturns.features.contests.hud.ContestToasts
import com.soulreturns.ui.hud.SoulHud
import com.soulreturns.ui.hud.movable.MovableHudRegistry
import com.soulreturns.ui.hud.tracker.TrackerHudHost

/** The contests UI's one registration call (`Soul.kt`): screen entry points, HUD, toasts, end reveal. */
object ContestsUi {
    fun register() {
        ContestsNav.openHome = { ContestsScreen.openHome() }
        ContestsNav.openFriends = { ContestsScreen.openFriends() }
        ContestsNav.openHistory = { ContestsScreen.openHistory() }
        ContestsNav.openContest = { ContestsScreen.openContest(it) }
        ContestsNav.openResults = { ContestsScreen.openResults(it) }

        SoulHud.add(ContestHud)
        TrackerHudHost.add(ContestHud)
        MovableHudRegistry.register(ContestHud)
        SoulHud.add(ContestToasts)
        MovableHudRegistry.register(ContestToasts)
        SoulHud.add(ContestEndReveal)
        ContestToasts.register()
        ContestEndReveal.register()
    }
}
