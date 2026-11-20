package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Collections and minions (mock `collectionsSec`). */
object CollectionsSection : ProfileSection {
    override val id = "collections"
    override val title = "Collections"
    override val group = ProfileSection.GROUP_PROGRESS
    override val icon = "pv_boxes"
    override val subtitle = "Collections and minions"

    override fun locked(ctx: ProfileContext): String? = if (!ctx.member.raw.containsKey("collection")) "Collections API off" else null

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
