package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.magicalPower
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Magical Power and the accessory bag (mock `accessoriesSec`). */
object AccessoriesSection : ProfileSection {
    override val id = "accessories"
    override val title = "Accessories"
    override val group = ProfileSection.GROUP_ITEMS
    override val icon = "pv_ring"
    override val subtitle = "Magical Power and the accessory bag"

    override fun locked(ctx: ProfileContext): String? = if (ctx.member.apiAccessDisabled) "Inventory API off" else null

    override fun badge(ctx: ProfileContext): String? = ctx.member.magicalPower?.let { Format.num(it) }

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
