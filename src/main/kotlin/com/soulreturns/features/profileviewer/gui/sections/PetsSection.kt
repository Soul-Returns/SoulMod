package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** The active pet and every pet (mock `petsSec`). */
object PetsSection : ProfileSection {
    override val id = "pets"
    override val title = "Pets"
    override val group = ProfileSection.GROUP_ITEMS
    override val icon = "pv_paw"

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
