package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Fishing level, sea creatures, Trophy Fish (mock `fishingSec`). */
object FishingSection : ProfileSection {
    override val id = "fishing"
    override val title = "Fishing"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_fish"

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
