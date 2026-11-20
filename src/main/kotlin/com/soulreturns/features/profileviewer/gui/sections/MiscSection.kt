package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Coins, essence, combat, auctions, experiments and everything else (mock `miscSec`). */
object MiscSection : ProfileSection {
    override val id = "misc"
    override val title = "Misc"
    override val group = ProfileSection.GROUP_OTHER
    override val icon = "pv_dots"
    override val subtitle = "Coins, essence and everything else"

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
