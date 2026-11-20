package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Ui

/**
 * Heart of the Mountain, powder, the Forge, Glacite and the mineshaft odds (mock `miningSec`). The
 * mineshaft card is `MineshaftStatsSection.card(ui, ctx, payload, full = false)`.
 */
object MiningSection : ProfileSection {
    override val id = "mining"
    override val title = "Mining"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_pick"
    override val subtitle = "Heart of the Mountain, powder, Forge and mineshafts"

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
