package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.bestiaryMilestone
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Bestiary milestone and kills by mob (mock `bestiarySec`). */
object BestiarySection : ProfileSection {
    override val id = "bestiary"
    override val title = "Bestiary"
    override val group = ProfileSection.GROUP_PROGRESS
    override val icon = "pv_book"

    override fun badge(ctx: ProfileContext): String? = ctx.member.bestiaryMilestone?.let { Format.num(it) }

    override fun build(ctx: ProfileContext, ui: Ui) = ProfileSections.notYet(this, ui)
}
