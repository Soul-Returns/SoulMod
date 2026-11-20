package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.KUUDRA_TIERS
import com.soulreturns.data.hypixel.KUUDRA_TIER_NAMES
import com.soulreturns.data.hypixel.crimsonIsle
import com.soulreturns.data.hypixel.kuudraCompletions
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.BarRow
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui

/** Faction reputation, Kuudra and the Dojo (mock `crimsonSec`). */
object CrimsonIsleSection : ProfileSection {
    override val id = "crimson"
    override val title = "Crimson Isle"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_flame"

    /** Mock `rep`: the reputation bar is full at this many points. */
    private const val REPUTATION_BAR = 12_000

    /** Mock `dojo`: points at or above this are gold. */
    private const val DOJO_TOP_POINTS = 1000

    private val MAGES = Rich.mcColor('5') ?: KitStyle.TEXT
    private val BARBARIANS = Rich.mcColor('c') ?: KitStyle.TEXT

    override fun build(ctx: ProfileContext, ui: Ui) {
        val c = ctx.member.crimsonIsle
        if (c == null) {
            ui.empty("pv_flame", "No Crimson Isle progress", "${ctx.name} hasn't set foot on the Crimson Isle on ${ctx.profile.cuteName}.")
            return
        }
        val kuudra = ctx.member.kuudraCompletions
        val mages = (c.magesReputation ?: 0).coerceAtLeast(0)
        val barbarians = (c.barbariansReputation ?: 0).coerceAtLeast(0)
        ui.tiles {
            when (c.faction) {
                "mages" -> tile("Faction", "Mages", sub = "${Format.num(mages)} reputation", color = MAGES)
                "barbarians" -> tile("Faction", "Barbarians", sub = "${Format.num(barbarians)} reputation", color = BARBARIANS)
                else -> tile("Faction", "—", sub = "none chosen yet")
            }
            tile("Kuudra runs", Format.num(kuudra.values.sum()), sub = "all tiers")
            tile("Matriarch pearls", c.matriarchPearls?.let { Format.num(it) } ?: "—", sub = "Heavy Pearls collected")
            tile("Abiphone", c.abiphoneContacts?.let { Format.num(it) } ?: "—", sub = "contacts")
        }
        ui.grid(300) {
            card("Reputation") {
                levelRow("Mages", Format.num(mages), fraction(mages), maxed = false, selected = c.faction == "mages", chip = factionChip(c.faction == "mages"), valueColor = MAGES)
                levelRow("Barbarians", Format.num(barbarians), fraction(barbarians), maxed = false, selected = c.faction == "barbarians", chip = factionChip(c.faction == "barbarians"), valueColor = BARBARIANS)
            }
            card("Kuudra") {
                val most = maxOf(1, kuudra.values.maxOrNull() ?: 0)
                bars(KUUDRA_TIERS.map { tier -> (kuudra[tier] ?: 0).let { BarRow(KUUDRA_TIER_NAMES.getValue(tier), Format.num(it), it.toFloat() / most) } })
            }
            card("Dojo", sub = "best points per test") {
                kvs {
                    for (test in c.dojo) {
                        val p = test.points
                        kv("Test of ${test.name}", p?.let { Format.num(it) } ?: "—", color = if (p == null) KitStyle.TEXT_FAINT else if (p >= DOJO_TOP_POINTS) KitStyle.MAXED else KitStyle.TEXT)
                    }
                }
            }
        }
    }

    private fun fraction(points: Int): Float = (points.toFloat() / REPUTATION_BAR).coerceIn(0f, 1f)

    private fun factionChip(selected: Boolean): Chip? = if (selected) Chip.accent("FACTION") else null
}
