package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Faction reputation, Kuudra and the Dojo (mock `crimsonSec`). */
object CrimsonIsleSection : ProfileSection {
    override val id = "crimson"
    override val title = "Crimson Isle"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_flame"

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
