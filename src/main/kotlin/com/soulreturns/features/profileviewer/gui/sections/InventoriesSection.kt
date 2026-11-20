package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Every container, searchable at once, plus sacks (mock `inventories`). */
object InventoriesSection : ProfileSection {
    override val id = "inventories"
    override val title = "Inventories"
    override val group = ProfileSection.GROUP_ITEMS
    override val icon = "pv_chest"
    override val subtitle = "Every container, searchable at once"

    override fun locked(ctx: ProfileContext): String? = if (ctx.member.apiAccessDisabled) "Inventory API off: only sacks" else null

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
