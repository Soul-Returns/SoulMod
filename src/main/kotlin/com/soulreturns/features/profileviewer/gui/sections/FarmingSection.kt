package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Jacob's Contests, medals and perks, and the Garden (mock `farmingSec`). */
object FarmingSection : ProfileSection {
    override val id = "farming"
    override val title = "Farming"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_sprout"
    override val subtitle = "Jacob's Contests and the Garden"

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
