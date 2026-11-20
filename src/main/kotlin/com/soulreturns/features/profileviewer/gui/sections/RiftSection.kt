package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Motes, Enigma Souls and the Rift's collectibles (mock `riftSec`). */
object RiftSection : ProfileSection {
    override val id = "rift"
    override val title = "Rift"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_swirl"

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
