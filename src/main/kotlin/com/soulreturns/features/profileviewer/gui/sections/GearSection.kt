package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Armor, equipment, wardrobe and weapons (mock `gear`). */
object GearSection : ProfileSection {
    override val id = "gear"
    override val title = "Gear"
    override val group = ProfileSection.GROUP_ITEMS
    override val icon = "pv_helmet"

    override fun locked(ctx: ProfileContext): String? = if (ctx.member.apiAccessDisabled) "Inventory API off" else null

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
