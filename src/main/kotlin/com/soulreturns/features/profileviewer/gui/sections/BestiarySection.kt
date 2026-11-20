package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.bestiaryDeaths
import com.soulreturns.data.hypixel.bestiaryMilestone
import com.soulreturns.data.hypixel.bestiaryMobs
import com.soulreturns.data.hypixel.hasBestiary
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.BarRow
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Ui
import java.util.Locale

/**
 * Bestiary milestone and kills by mob (mock `bestiarySec`). Kills are the response's raw counts per
 * mob, most first; the mod bundles no family tier table, so no tier is shown.
 */
object BestiarySection : ProfileSection {
    override val id = "bestiary"
    override val title = "Bestiary"
    override val group = ProfileSection.GROUP_PROGRESS
    override val icon = "pv_book"

    override fun badge(ctx: ProfileContext): String? = ctx.member.bestiaryMilestone?.let { Format.num(it) }

    override fun build(ctx: ProfileContext, ui: Ui) {
        val m = ctx.member
        if (!m.hasBestiary && m.bestiaryMilestone == null) {
            ui.empty("pv_book", "No Bestiary progress", "${ctx.name} has not killed a Bestiary mob on ${ctx.profile.cuteName}.")
            return
        }
        val mobs = m.bestiaryMobs
        val total = mobs.sumOf { it.kills }
        ui.tiles {
            tile("Milestone", m.bestiaryMilestone?.let { Format.num(it) } ?: "—", sub = "last claimed")
            tile("Kills", Format.short(total), sub = "${mobs.size} mob types")
            tile("Deaths", m.bestiaryDeaths?.let { Format.num(it) } ?: "—", sub = "to bestiary mobs")
        }
        val q = ui.query("query")
        val max = (mobs.firstOrNull()?.kills ?: 1L).coerceAtLeast(1L).toFloat()
        val shown = mobs.filter { q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) }
        ui.card("Kills by mob", span = Node.FULL, tools = { search("query", "Filter mobs") }) {
            if (shown.isEmpty()) {
                text(if (mobs.isEmpty()) "No Bestiary kills recorded." else "No mob is called \"$q\".", color = KitStyle.TEXT_DIM)
            } else {
                bars(shown.map { BarRow(it.name, Format.num(it.kills), it.kills / max) }, twoColumns = true)
            }
            foot("Family tiers need Hypixel's bestiary table, which Soul doesn't have yet; kills are exact.")
        }
    }
}
